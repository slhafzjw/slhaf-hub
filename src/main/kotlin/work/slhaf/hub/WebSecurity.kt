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
private const val ALT_TOKEN_HEADER = "X-Host-Token"

data class ApiTokenConfig(
    val token: String,
    val source: String,
    val tokenFile: File?
)

private fun randomTokenHex(bytes: Int = 32): String {
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

suspend fun requireAuth(call: ApplicationCall, expectedToken: String): Boolean {
    val provided = extractProvidedToken(call)
    if (provided == expectedToken) return true

    call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer realm=\"script-host\"")
    call.respondText("unauthorized", status = HttpStatusCode.Unauthorized, contentType = ContentType.Text.Plain)
    return false
}
