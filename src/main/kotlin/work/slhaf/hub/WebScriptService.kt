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

fun renderScriptList(scriptsDir: File, allowNames: Set<String>? = null): String =
    listScriptNames(scriptsDir)
        .asSequence()
        .filter { allowNames == null || allowNames.contains(it) }
        .joinToString("\n") { name ->
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

private fun metadataValidationMessage(errors: List<String>): String =
    buildString {
        appendLine("metadata validation failed:")
        errors.forEach { appendLine("- $it") }
        appendLine("examples:")
        appendLine("// @desc: Demo greeting API")
        appendLine("// @timeout: 10s")
        appendLine("// @param: name | required=false | default=world | desc=Name to greet")
    }.trim()

private fun formatParamsHint(metadata: ScriptMetadata): String =
    buildString {
        appendLine("params:")
        if (metadata.params.isEmpty()) {
            appendLine("- (none)")
            return@buildString
        }
        metadata.params.forEach { p ->
            val defaultPart = p.defaultValue?.let { ", default=$it" } ?: ""
            val descPart = p.description?.let { ", desc=$it" } ?: ""
            appendLine("- ${p.name} (required=${p.required}$defaultPart$descPart)")
        }
    }.trimEnd()

private fun runFailureMessage(result: ScriptExecutionResult): String {
    val title = when {
        result.timedOut -> "[ERROR] script execution timed out"
        result.missingRequiredParams.isNotEmpty() ->
            "[ERROR] script execution failed: missing required params: ${result.missingRequiredParams.joinToString(", ")}"
        else -> "[ERROR] script execution failed"
    }
    return buildString {
        appendLine(title)
        if (result.output.isNotBlank()) appendLine(result.output)
        append(formatParamsHint(result.metadata))
    }.trim()
}

fun metadataJson(scriptName: String, metadata: ScriptMetadata, source: String): String {
    val description = metadata.description?.let { "\"${it.jsonEscaped()}\"" } ?: "null"
    val params = metadata.params.joinToString(",") { param ->
        val defaultValue = param.defaultValue?.let { "\"${it.jsonEscaped()}\"" } ?: "null"
        val desc = param.description?.let { "\"${it.jsonEscaped()}\"" } ?: "null"
        """{"name":"${param.name.jsonEscaped()}","required":${param.required},"defaultValue":$defaultValue,"description":$desc}"""
    }
    return """{"script":"${scriptName.jsonEscaped()}","source":"${source.jsonEscaped()}","description":$description,"timeoutMs":${metadata.timeoutMs},"params":[$params]}"""
}

fun loadMetadata(script: File): Pair<ScriptMetadata, String> {
    val cached = cachedMetadata(script)
    if (cached != null) return cached to "cache"
    val parsed = loadMetadataFromComments(script)
    return parsed to "comments"
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
    val metadataErrors = validateScriptMetadata(content)
    if (metadataErrors.isNotEmpty()) {
        return call.respondText(
            metadataValidationMessage(metadataErrors),
            status = HttpStatusCode.BadRequest,
            contentType = ContentType.Text.Plain
        )
    }

    script.parentFile?.mkdirs()
    script.writeText(content)
    removeCachedMetadata(script)

    val result = evalAndCapture(script, ScriptRequestContext(), enforceRequiredParams = false)
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
    val metadataErrors = validateScriptMetadata(newContent)
    if (metadataErrors.isNotEmpty()) {
        return call.respondText(
            metadataValidationMessage(metadataErrors),
            status = HttpStatusCode.BadRequest,
            contentType = ContentType.Text.Plain
        )
    }

    val previousContent = script.readText()
    script.writeText(newContent)
    removeCachedMetadata(script)

    val result = evalAndCapture(script, ScriptRequestContext(), enforceRequiredParams = false)
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
    val metadata = loadMetadataFromComments(script)
    val result = evalAndCaptureWithTimeout(
        script,
        ScriptRequestContext(args = requestArgs, body = requestBody),
        timeoutMs = metadata.timeoutMs,
    )

    val status = when {
        result.ok -> HttpStatusCode.OK
        result.timedOut -> HttpStatusCode.RequestTimeout
        result.missingRequiredParams.isNotEmpty() -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.InternalServerError
    }

    call.respondText(
        if (result.ok) {
            result.output.ifBlank { "OK" }
        } else {
            runFailureMessage(result)
        },
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
