package work.slhaf.hub

import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val metadataCache = ConcurrentHashMap<String, Pair<String, ScriptMetadata>>() // key -> stamp, metadata
private const val DEFAULT_SCRIPT_TIMEOUT_MS = 10_000L
private val metadataParamNameRegex = Regex("[A-Za-z0-9._-]+")

internal fun scriptStamp(file: File): String = "${file.length()}-${file.lastModified()}"

private fun parseMetadataFromComments(scriptContent: String): ScriptMetadata {
    var description: String? = null
    var timeoutMs = DEFAULT_SCRIPT_TIMEOUT_MS
    val params = mutableListOf<ScriptParamDefinition>()

    scriptContent.lines().forEach { raw ->
        val line = raw.trim()
        if (!line.startsWith("//")) return@forEach

        val comment = line.removePrefix("//").trim()
        if (comment.startsWith("@desc:", ignoreCase = true)) {
            description = comment.substringAfter(":").trim().takeIf { it.isNotBlank() }
            return@forEach
        }
        if (comment.startsWith("@timeout:", ignoreCase = true)) {
            val raw = comment.substringAfter(":").trim()
            parseTimeoutMs(raw)?.let { timeoutMs = it }
            return@forEach
        }
        if (comment.startsWith("@param:", ignoreCase = true)) {
            val payload = comment.substringAfter(":").trim()
            if (payload.isBlank()) return@forEach

            val parts = payload.split("|").map { it.trim() }.filter { it.isNotBlank() }
            if (parts.isEmpty()) return@forEach

            val name = parts.first()
            var required = false
            var defaultValue: String? = null
            var desc: String? = null

            parts.drop(1).forEach { part ->
                when {
                    part.equals("required", ignoreCase = true) -> required = true
                    part.startsWith("required=", ignoreCase = true) ->
                        required = part.substringAfter("=").trim().equals("true", ignoreCase = true)
                    part.startsWith("default=", ignoreCase = true) ->
                        defaultValue = part.substringAfter("=").trim().ifBlank { null }
                    part.startsWith("desc=", ignoreCase = true) ->
                        desc = part.substringAfter("=").trim().ifBlank { null }
                }
            }

            params += ScriptParamDefinition(
                name = name,
                required = required,
                defaultValue = defaultValue,
                description = desc
            )
        }
    }

    return ScriptMetadata(description = description, params = params, timeoutMs = timeoutMs)
}

fun validateScriptMetadata(scriptContent: String): List<String> {
    val errors = mutableListOf<String>()
    val seenParams = mutableSetOf<String>()

    scriptContent.lines().forEachIndexed { idx, raw ->
        val lineNo = idx + 1
        val line = raw.trim()
        if (!line.startsWith("//")) return@forEachIndexed

        val comment = line.removePrefix("//").trim()
        if (comment.startsWith("@timeout:", ignoreCase = true)) {
            val rawTimeout = comment.substringAfter(":").trim()
            if (parseTimeoutMs(rawTimeout) == null) {
                errors += "line $lineNo: invalid @timeout '$rawTimeout'. expected format: '@timeout: 10s' or '500ms' or '1m'."
            }
            return@forEachIndexed
        }

        if (comment.startsWith("@param:", ignoreCase = true)) {
            val payload = comment.substringAfter(":").trim()
            if (payload.isBlank()) {
                errors += "line $lineNo: empty @param. expected format: '@param: name | required=true|false | default=value | desc=text'."
                return@forEachIndexed
            }

            val parts = payload.split("|").map { it.trim() }.filter { it.isNotBlank() }
            if (parts.isEmpty()) {
                errors += "line $lineNo: invalid @param. expected format: '@param: name | required=true|false | default=value | desc=text'."
                return@forEachIndexed
            }

            val name = parts.first()
            if (!metadataParamNameRegex.matches(name)) {
                errors += "line $lineNo: invalid param name '$name'. allowed pattern: [A-Za-z0-9._-]+."
            }
            if (!seenParams.add(name)) {
                errors += "line $lineNo: duplicate @param name '$name'. param names must be unique."
            }

            var hasRequiredOption = false
            parts.drop(1).forEach { part ->
                when {
                    part.equals("required", ignoreCase = true) -> {
                        hasRequiredOption = true
                    }
                    part.startsWith("required=", ignoreCase = true) -> {
                        hasRequiredOption = true
                        val v = part.substringAfter("=").trim()
                        if (!v.equals("true", ignoreCase = true) && !v.equals("false", ignoreCase = true)) {
                            errors += "line $lineNo: invalid required value '$v'. expected true/false."
                        }
                    }
                    part.startsWith("default=", ignoreCase = true) -> Unit
                    part.startsWith("desc=", ignoreCase = true) -> Unit
                    else -> {
                        errors += "line $lineNo: unsupported @param option '$part'. supported: required=, default=, desc=."
                    }
                }
            }
            if (!hasRequiredOption) {
                errors += "line $lineNo: missing required option. expected '@param: name | required=true|false | default=value | desc=text'."
            }
        }
    }

    return errors
}

private fun parseTimeoutMs(raw: String): Long? {
    if (raw.isBlank()) return null
    val v = raw.trim().lowercase()
    return when {
        v.endsWith("ms") -> v.removeSuffix("ms").trim().toLongOrNull()
        v.endsWith("s") -> v.removeSuffix("s").trim().toLongOrNull()?.times(1000)
        v.endsWith("m") -> v.removeSuffix("m").trim().toLongOrNull()?.times(60_000)
        else -> v.toLongOrNull()?.times(1000)
    }?.takeIf { it > 0 }
}

internal fun metadataForFile(scriptFile: File, scriptContent: String): ScriptMetadata {
    val key = scriptFile.canonicalPath
    val stamp = scriptStamp(scriptFile)
    val cached = metadataCache[key]
    if (cached != null && cached.first == stamp) return cached.second

    val parsed = parseMetadataFromComments(scriptContent)
    metadataCache[key] = stamp to parsed
    return parsed
}

fun cachedMetadata(scriptFile: File): ScriptMetadata? {
    val key = scriptFile.canonicalPath
    val cached = metadataCache[key] ?: return null
    val currentStamp = scriptStamp(scriptFile)
    return if (cached.first == currentStamp) cached.second else null
}

internal fun clearMetadataCache(scriptFile: File) {
    metadataCache.remove(scriptFile.canonicalPath)
}

fun loadMetadataFromComments(scriptFile: File): ScriptMetadata {
    val content = scriptFile.readText()
    return metadataForFile(scriptFile, content)
}
