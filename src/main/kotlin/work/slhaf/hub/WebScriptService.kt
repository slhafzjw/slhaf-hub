package work.slhaf.hub

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import java.io.File

private const val SCRIPT_EXTENSION = ".hub.kts"
private val ROUTE_NAME_REGEX = Regex("[A-Za-z0-9._-]+")

fun resolveScriptFile(baseDir: File, routeName: String): File? {
    if (!routeName.matches(ROUTE_NAME_REGEX)) return null

    val canonicalBase = baseDir.canonicalFile
    val candidate = File(baseDir, "$routeName$SCRIPT_EXTENSION").canonicalFile
    val insideBase = candidate.path.startsWith(canonicalBase.path + File.separator)

    return if (insideBase || candidate == canonicalBase) candidate else null
}

private fun listScriptNames(scriptsDir: File): List<String> =
    scriptsDir.listFiles()
        ?.asSequence()
        ?.filter { it.isFile && it.name.endsWith(SCRIPT_EXTENSION) }
        ?.map { it.name.removeSuffix(SCRIPT_EXTENSION) }
        ?.sorted()
        ?.toList()
        ?: emptyList()

fun renderScriptList(scriptsDir: File): String =
    listScriptNames(scriptsDir).joinToString("\n") { name ->
        val file = resolveScriptFile(scriptsDir, name)
        val description = file?.let(::cachedMetadata)?.description
        if (description.isNullOrBlank()) name else "$name\t$description"
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

fun metadataJson(scriptName: String, metadata: ScriptMetadata, source: String): String {
    val description = metadata.description?.let { "\"${it.jsonEscaped()}\"" } ?: "null"
    val params = metadata.params.joinToString(",") { param ->
        val defaultValue = param.defaultValue?.let { "\"${it.jsonEscaped()}\"" } ?: "null"
        val desc = param.description?.let { "\"${it.jsonEscaped()}\"" } ?: "null"
        """{"name":"${param.name.jsonEscaped()}","required":${param.required},"defaultValue":$defaultValue,"description":$desc}"""
    }
    return """{"script":"${scriptName.jsonEscaped()}","source":"${source.jsonEscaped()}","description":$description,"params":[$params]}"""
}

fun loadMetadata(script: File): Pair<ScriptMetadata, String> {
    val cached = cachedMetadata(script)
    if (cached != null) return cached to "cache"

    val executed = evalAndCapture(script, ScriptRequestContext(args = emptyList())).metadata
    return executed to "parsed"
}

suspend fun handleCreateScript(call: ApplicationCall, scriptsDir: File) {
    val name = call.parameters["script"]
        ?: return call.respondText("missing route name", status = HttpStatusCode.BadRequest)
    val script = resolveScriptFile(scriptsDir, name)
        ?: return call.respondText("invalid script name", status = HttpStatusCode.BadRequest)
    if (script.exists()) {
        return call.respondText("script already exists: ${script.name}", status = HttpStatusCode.Conflict)
    }

    val content = call.receiveText()
    if (content.isBlank()) {
        return call.respondText("script content is empty", status = HttpStatusCode.BadRequest)
    }

    script.parentFile?.mkdirs()
    script.writeText(content)
    removeCachedMetadata(script)

    val result = evalAndCapture(script, ScriptRequestContext())
    if (!result.ok) {
        script.delete()
        removeCachedMetadata(script)
        return call.respondText(
            "script validation failed:\n${result.output.ifBlank { "unknown error" }}",
            status = HttpStatusCode.BadRequest,
            contentType = ContentType.Text.Plain
        )
    }

    call.respondText(
        "created ${script.name}\n${result.output}".trim(),
        status = HttpStatusCode.Created,
        contentType = ContentType.Text.Plain
    )
}

suspend fun handleDeleteScript(call: ApplicationCall, scriptsDir: File) {
    val name = call.parameters["script"]
        ?: return call.respondText("missing route name", status = HttpStatusCode.BadRequest)
    val script = resolveScriptFile(scriptsDir, name)
        ?: return call.respondText("invalid script name", status = HttpStatusCode.BadRequest)
    if (!script.exists()) {
        return call.respondText("script not found: ${script.name}", status = HttpStatusCode.NotFound)
    }

    val deleted = script.delete()
    removeCachedMetadata(script)
    if (!deleted) {
        return call.respondText(
            "failed to delete script: ${script.name}",
            status = HttpStatusCode.InternalServerError
        )
    }
    call.respondText("deleted ${script.name}", status = HttpStatusCode.OK)
}

suspend fun handleUpdateScript(call: ApplicationCall, scriptsDir: File) {
    val name = call.parameters["script"]
        ?: return call.respondText("missing route name", status = HttpStatusCode.BadRequest)
    val script = resolveScriptFile(scriptsDir, name)
        ?: return call.respondText("invalid script name", status = HttpStatusCode.BadRequest)
    if (!script.exists()) {
        return call.respondText("script not found: ${script.name}", status = HttpStatusCode.NotFound)
    }

    val newContent = call.receiveText()
    if (newContent.isBlank()) {
        return call.respondText("script content is empty", status = HttpStatusCode.BadRequest)
    }

    val previousContent = script.readText()
    script.writeText(newContent)
    removeCachedMetadata(script)

    val result = evalAndCapture(script, ScriptRequestContext())
    if (!result.ok) {
        script.writeText(previousContent)
        removeCachedMetadata(script)
        return call.respondText(
            "script validation failed, rolled back:\n${result.output.ifBlank { "unknown error" }}",
            status = HttpStatusCode.BadRequest,
            contentType = ContentType.Text.Plain
        )
    }

    call.respondText(
        "updated ${script.name}\n${result.output}".trim(),
        status = HttpStatusCode.OK,
        contentType = ContentType.Text.Plain
    )
}

suspend fun handleRunRequest(call: ApplicationCall, scriptsDir: File, consumeBody: Boolean) {
    val name = call.parameters["script"]
        ?: return call.respondText("missing route name", status = HttpStatusCode.BadRequest)

    val script = resolveScriptFile(scriptsDir, name)
        ?: return call.respondText("invalid script name", status = HttpStatusCode.BadRequest)

    if (!script.exists()) {
        return call.respondText("script not found: ${script.name}", status = HttpStatusCode.NotFound)
    }

    val requestArgs = call.request.queryParameters.entries()
        .mapNotNull { entry ->
            val value = entry.value.lastOrNull() ?: return@mapNotNull null
            "${entry.key}=$value"
        }
        .toList()

    val requestBody = if (consumeBody) call.receiveText() else null
    val result = evalAndCapture(script, ScriptRequestContext(args = requestArgs, body = requestBody))

    val status = when {
        result.ok -> HttpStatusCode.OK
        result.missingRequiredParams.isNotEmpty() -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.InternalServerError
    }

    call.respondText(
        result.output.ifBlank { if (result.ok) "OK" else "FAILED" },
        status = status,
        contentType = ContentType.Text.Plain
    )
}

suspend fun handleGetScriptContent(call: ApplicationCall, scriptsDir: File) {
    val name = call.parameters["script"]
        ?: return call.respondText("missing route name", status = HttpStatusCode.BadRequest)
    val script = resolveScriptFile(scriptsDir, name)
        ?: return call.respondText("invalid script name", status = HttpStatusCode.BadRequest)
    if (!script.exists()) {
        return call.respondText("script not found: ${script.name}", status = HttpStatusCode.NotFound)
    }

    call.respondText(script.readText(), contentType = ContentType.Text.Plain)
}
