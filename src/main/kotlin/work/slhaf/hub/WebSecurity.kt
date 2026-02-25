package work.slhaf.hub

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import java.io.File
import java.security.SecureRandom

private const val ENV_API_TOKEN = "HOST_API_TOKEN"
private const val TOKEN_FILE_NAME = ".host-api-token"
private const val SUBTOKEN_FILE_NAME = ".host-subtokens.db"
private const val ALT_TOKEN_HEADER = "X-Host-Token"
private val SCRIPT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")

data class ApiTokenConfig(
    val token: String,
    val source: String,
    val tokenFile: File?,
)

enum class TokenType {
    ROOT,
    SUB,
}

data class SubTokenRecord(
    val name: String,
    val token: String,
    val scripts: Set<String>,
)

data class AuthContext(
    val type: TokenType,
    val subTokenName: String? = null,
    val allowedScripts: Set<String> = emptySet(),
)

class SubTokenStore(
    private val storageFile: File,
) {
    private val lock = Any()
    private var loaded = false
    private val byName = linkedMapOf<String, SubTokenRecord>()

    fun list(): List<SubTokenRecord> =
        synchronized(lock) {
            ensureLoaded()
            byName.values.sortedBy { it.name }
        }

    fun get(name: String): SubTokenRecord? =
        synchronized(lock) {
            ensureLoaded()
            byName[name]
        }

    fun findByToken(token: String): SubTokenRecord? =
        synchronized(lock) {
            ensureLoaded()
            byName.values.firstOrNull { it.token == token }
        }

    fun findByNameAndToken(name: String, token: String): SubTokenRecord? =
        synchronized(lock) {
            ensureLoaded()
            val record = byName[name] ?: return null
            if (record.token == token) record else null
        }

    fun create(name: String, scripts: Set<String>): SubTokenRecord {
        synchronized(lock) {
            ensureLoaded()
            require(name.matches(SCRIPT_NAME_REGEX)) { "invalid subtoken name" }
            if (byName.containsKey(name)) error("subtoken already exists: $name")

            val record = SubTokenRecord(name = name, token = randomTokenHex(), scripts = scripts.sorted().toSet())
            byName[name] = record
            persist()
            return record
        }
    }

    fun update(name: String, scripts: Set<String>): SubTokenRecord {
        synchronized(lock) {
            ensureLoaded()
            val existing = byName[name] ?: error("subtoken not found: $name")
            val updated = existing.copy(scripts = scripts.sorted().toSet())
            byName[name] = updated
            persist()
            return updated
        }
    }

    fun delete(name: String): Boolean =
        synchronized(lock) {
            ensureLoaded()
            val removed = byName.remove(name) ?: return false
            persist()
            removed.name.isNotBlank()
        }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true

        if (!storageFile.exists()) return
        storageFile.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEach

            val parts = trimmed.split('\t')
            if (parts.size < 2) return@forEach

            val name = parts[0].trim()
            val token = parts[1].trim()
            if (!name.matches(SCRIPT_NAME_REGEX) || token.isBlank()) return@forEach

            val scripts =
                parts.getOrNull(2)
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() && it.matches(SCRIPT_NAME_REGEX) }
                    ?.toSet()
                    ?: emptySet()

            byName[name] = SubTokenRecord(name, token, scripts)
        }
    }

    private fun persist() {
        storageFile.parentFile?.mkdirs()
        val text =
            buildString {
                appendLine("# name\\ttoken\\tscript1,script2,...")
                byName.values.sortedBy { it.name }.forEach { record ->
                    val scripts = record.scripts.sorted().joinToString(",")
                    append(record.name)
                    append('\t')
                    append(record.token)
                    append('\t')
                    append(scripts)
                    appendLine()
                }
            }

        storageFile.writeText(text)
        storageFile.setReadable(false, false)
        storageFile.setReadable(true, true)
        storageFile.setWritable(false, false)
        storageFile.setWritable(true, true)
    }
}

data class HostSecurity(
    val rootToken: String,
    val subTokens: SubTokenStore,
)

fun createHostSecurity(scriptsDir: File, rootToken: String): HostSecurity =
    HostSecurity(rootToken = rootToken, subTokens = SubTokenStore(File(scriptsDir, SUBTOKEN_FILE_NAME)))

fun randomTokenHex(bytes: Int = 32): String {
    val random = ByteArray(bytes)
    SecureRandom().nextBytes(random)
    return random.joinToString("") { "%02x".format(it) }
}

fun loadOrCreateApiToken(scriptsDir: File): ApiTokenConfig {
    val envToken = System.getenv(ENV_API_TOKEN)?.trim()
    if (!envToken.isNullOrBlank()) {
        return ApiTokenConfig(envToken, "env:$ENV_API_TOKEN", null)
    }

    val tokenFile = File(scriptsDir, TOKEN_FILE_NAME)
    if (tokenFile.exists()) {
        val saved = tokenFile.readText().trim()
        if (saved.isNotBlank()) return ApiTokenConfig(saved, "file:${tokenFile.absolutePath}", tokenFile)
    }

    val token = randomTokenHex()
    tokenFile.writeText(token)
    tokenFile.setReadable(false, false)
    tokenFile.setReadable(true, true)
    tokenFile.setWritable(false, false)
    tokenFile.setWritable(true, true)
    return ApiTokenConfig(token, "generated:file:${tokenFile.absolutePath}", tokenFile)
}

private fun extractProvidedToken(call: ApplicationCall): String? {
    val auth = call.request.headers[HttpHeaders.Authorization]
    if (!auth.isNullOrBlank() && auth.startsWith("Bearer ", ignoreCase = true)) {
        return auth.substringAfter("Bearer ").trim()
    }
    return call.request.headers[ALT_TOKEN_HEADER]?.trim()
}

private fun authenticateToken(call: ApplicationCall, security: HostSecurity): AuthContext? {
    val provided = extractProvidedToken(call)
    if (provided.isNullOrBlank()) return null
    if (provided == security.rootToken) return AuthContext(type = TokenType.ROOT)

    val sub = security.subTokens.findByToken(provided) ?: return null
    return AuthContext(type = TokenType.SUB, subTokenName = sub.name, allowedScripts = sub.scripts)
}

private fun parseSubTokenPathCredential(raw: String?): Pair<String, String>? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return null
    val at = value.indexOf('@')
    if (at <= 0 || at == value.lastIndex) return null
    val name = value.substring(0, at).trim()
    val token = value.substring(at + 1).trim()
    if (!name.matches(SCRIPT_NAME_REGEX) || token.isBlank()) return null
    return name to token
}

fun authenticateSubTokenPath(pathCredential: String?, security: HostSecurity): AuthContext? {
    val (name, token) = parseSubTokenPathCredential(pathCredential) ?: return null
    val sub = security.subTokens.findByNameAndToken(name, token) ?: return null
    return AuthContext(type = TokenType.SUB, subTokenName = sub.name, allowedScripts = sub.scripts)
}

suspend fun requireAuth(call: ApplicationCall, security: HostSecurity): AuthContext? {
    val context = authenticateToken(call, security)
    if (context != null) return context

    call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer realm=\"script-host\"")
    call.respondText("unauthorized", status = HttpStatusCode.Unauthorized, contentType = ContentType.Text.Plain)
    return null
}

suspend fun requireSubTokenPathAuth(
    call: ApplicationCall,
    security: HostSecurity,
    pathParamName: String = "subAuth",
): AuthContext? {
    val context = authenticateSubTokenPath(call.parameters[pathParamName], security)
    if (context != null) return context
    call.respondText(
        "unauthorized subtoken path, expected /u/<subtoken_name>@<subtoken>/...",
        status = HttpStatusCode.Unauthorized,
        contentType = ContentType.Text.Plain,
    )
    return null
}

suspend fun requireRoot(call: ApplicationCall, context: AuthContext): Boolean {
    if (context.type == TokenType.ROOT) return true
    call.respondText("forbidden: root token required", status = HttpStatusCode.Forbidden, contentType = ContentType.Text.Plain)
    return false
}

suspend fun requireScriptAccess(call: ApplicationCall, context: AuthContext, scriptName: String): Boolean {
    if (context.type == TokenType.ROOT) return true
    if (context.allowedScripts.contains(scriptName)) return true
    call.respondText("forbidden: subtoken has no access to script '$scriptName'", status = HttpStatusCode.Forbidden, contentType = ContentType.Text.Plain)
    return false
}

fun visibleScriptsFor(context: AuthContext): Set<String>? =
    when (context.type) {
        TokenType.ROOT -> null
        TokenType.SUB -> context.allowedScripts
    }

private fun String.jsonEscaped(): String = buildString(length) {
    for (ch in this@jsonEscaped) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
}

fun tokenTypeJson(context: AuthContext): String {
    val scripts = context.allowedScripts.sorted().joinToString(",") { "\"${it.jsonEscaped()}\"" }
    val name = context.subTokenName?.let { "\"${it.jsonEscaped()}\"" } ?: "null"
    return """{"tokenType":"${context.type.name.lowercase()}","subTokenName":$name,"scripts":[${scripts}]}"""
}

fun parseScriptNameSet(raw: String): Set<String> {
    val values =
        raw.split(Regex("[,\\n\\r\\t\\s]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

    require(values.all { it.matches(SCRIPT_NAME_REGEX) }) {
        "invalid script names, only [A-Za-z0-9._-] is allowed"
    }
    return values
}

fun subTokenListJson(records: List<SubTokenRecord>): String {
    val items =
        records.joinToString(",") { record ->
            val scripts = record.scripts.sorted().joinToString(",") { "\"${it.jsonEscaped()}\"" }
            """{"name":"${record.name.jsonEscaped()}","token":"${record.token.jsonEscaped()}","scripts":[${scripts}]}"""
        }
    return """{"items":[${items}]}"""
}

fun subTokenItemJson(record: SubTokenRecord, includeToken: Boolean): String {
    val scripts = record.scripts.sorted().joinToString(",") { "\"${it.jsonEscaped()}\"" }
    val tokenPart = if (includeToken) "\"${record.token.jsonEscaped()}\"" else "null"
    return """{"name":"${record.name.jsonEscaped()}","token":$tokenPart,"scripts":[${scripts}]}"""
}
