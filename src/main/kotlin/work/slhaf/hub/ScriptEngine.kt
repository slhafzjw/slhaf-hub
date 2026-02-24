package work.slhaf.hub

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.script.experimental.api.*
import kotlin.script.experimental.dependencies.*
import kotlin.script.experimental.dependencies.maven.MavenDependenciesResolver
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

private val scriptingHost = BasicJvmScriptingHost()
private val evalLock = Any()
private val metadataCache = ConcurrentHashMap<String, Pair<String, ScriptMetadata>>() // key -> stamp, metadata

private val resolver = CompoundDependenciesResolver(FileSystemDependenciesResolver(), MavenDependenciesResolver())
private val argsDeclarationRegex = Regex("""^\s*val\s+args\s*:\s*Array<String>\s*=\s*emptyArray\(\)\s*$""")
private const val DEFAULT_SCRIPT_TIMEOUT_MS = 10_000L
private val metadataParamNameRegex = Regex("[A-Za-z0-9._-]+")
private val evalExecutor = Executors.newCachedThreadPool { r ->
    Thread(r, "script-eval-worker").apply { isDaemon = true }
}

fun explicitClasspathFromEnv(): List<File>? {
    val value = System.getenv("HOST_SCRIPT_CLASSPATH") ?: return null
    if (value.isBlank()) return null
    return value.split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)
}

private fun runtimeClasspathFromJavaProperty(): List<File> {
    val raw = System.getProperty("java.class.path").orEmpty()
    if (raw.isBlank()) return emptyList()
    return raw
        .split(File.pathSeparator)
        .asSequence()
        .filter { it.isNotBlank() }
        .map(::File)
        .filter { it.exists() }
        .distinctBy { it.absolutePath }
        .toList()
}

private fun configureMavenDepsOnAnnotations(
    context: ScriptConfigurationRefinementContext
): ResultWithDiagnostics<ScriptCompilationConfiguration> {
    val annotations = context.collectedData
        ?.get(ScriptCollectedData.collectedAnnotations)
        ?.takeIf { it.isNotEmpty() }
        ?: return context.compilationConfiguration.asSuccess()

    return runBlocking {
        resolver.resolveFromScriptSourceAnnotations(annotations)
    }.onSuccess { files ->
        context.compilationConfiguration.with {
            dependencies.append(JvmDependency(files))
        }.asSuccess()
    }
}

private fun compilationConfiguration(explicitCp: List<File>?): ScriptCompilationConfiguration {
    return ScriptCompilationConfiguration {
        baseClass(SimpleScript::class)
        defaultImports(DependsOn::class, Repository::class)

        jvm {
            val runtimeCp = runtimeClasspathFromJavaProperty()
            updateClasspath((explicitCp ?: emptyList()) + runtimeCp)
        }

        refineConfiguration {
            onAnnotations(DependsOn::class, Repository::class, handler = ::configureMavenDepsOnAnnotations)
        }
    }
}

private fun escapeKotlinString(value: String): String = buildString(value.length) {
    value.forEach { ch ->
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

private fun argsInitializer(args: List<String>): String =
    "val args: Array<String> = arrayOf(${args.joinToString(",") { "\"${escapeKotlinString(it)}\"" }})"

private fun scriptStamp(file: File): String = "${file.length()}-${file.lastModified()}"

private fun injectArgsDeclaration(scriptContent: String, args: List<String>): String {
    val lines = scriptContent.lines()
    val injected = argsInitializer(args)
    var replaced = false
    val result = lines.map { line ->
        if (!replaced && argsDeclarationRegex.matches(line)) {
            replaced = true
            injected
        } else {
            line
        }
    }
    return result.joinToString("\n")
}

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

            parts.drop(1).forEach { part ->
                when {
                    part.equals("required", ignoreCase = true) -> Unit
                    part.startsWith("required=", ignoreCase = true) -> {
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

private fun metadataForFile(scriptFile: File, scriptContent: String): ScriptMetadata {
    val key = scriptFile.canonicalPath
    val stamp = scriptStamp(scriptFile)
    val cached = metadataCache[key]
    if (cached != null && cached.first == stamp) return cached.second

    val parsed = parseMetadataFromComments(scriptContent)
    metadataCache[key] = stamp to parsed
    return parsed
}

private fun evalSource(source: SourceCode): ResultWithDiagnostics<EvaluationResult> {
    val explicitCp = explicitClasspathFromEnv()
    return scriptingHost.eval(source, compilationConfiguration(explicitCp), null)
}

data class ScriptExecutionResult(
    val ok: Boolean,
    val output: String,
    val metadata: ScriptMetadata,
    val missingRequiredParams: List<String>,
    val timedOut: Boolean = false,
)

fun cachedMetadata(scriptFile: File): ScriptMetadata? {
    val key = scriptFile.canonicalPath
    val cached = metadataCache[key] ?: return null
    val currentStamp = scriptStamp(scriptFile)
    return if (cached.first == currentStamp) cached.second else null
}

fun removeCachedMetadata(scriptFile: File) {
    metadataCache.remove(scriptFile.canonicalPath)
}

fun loadMetadataFromComments(scriptFile: File): ScriptMetadata {
    val content = scriptFile.readText()
    return metadataForFile(scriptFile, content)
}

fun evalAndCapture(scriptFile: File, requestContext: ScriptRequestContext = ScriptRequestContext()): ScriptExecutionResult {
    synchronized(evalLock) {
        val oldOut = System.out
        val oldErr = System.err
        val buffer = ByteArrayOutputStream()
        val ps = PrintStream(buffer, true, Charsets.UTF_8.name())

        return try {
            System.setOut(ps)
            System.setErr(ps)

            val original = scriptFile.readText()
            val metadata = metadataForFile(scriptFile, original)
            val missingRequired = metadata.params
                .filter { p ->
                    p.required && requestContext.args.none { token ->
                        token.substringBefore("=", missingDelimiterValue = "") == p.name
                    } && p.defaultValue == null
                }
                .map { it.name }

            val injected = injectArgsDeclaration(original, requestContext.args)
            val result = evalSource(injected.toScriptSource(scriptFile.name))
            val diagnostics = result.reports
                .filter { it.severity > ScriptDiagnostic.Severity.DEBUG }
                .joinToString("\n") {
                    val ex = it.exception?.let { e -> ": ${e::class.simpleName}: ${e.message}" } ?: ""
                    "[${it.severity}] ${it.message}$ex"
                }
            val missingMessage = if (missingRequired.isEmpty()) "" else
                "[ERROR] Missing required parameters: ${missingRequired.joinToString(", ")}"

            val output = buffer.toString(Charsets.UTF_8.name()).trim()
            val finalText = buildString {
                if (output.isNotEmpty()) appendLine(output)
                if (diagnostics.isNotEmpty()) appendLine(diagnostics)
                if (missingMessage.isNotEmpty()) appendLine(missingMessage)
            }.trim()

            ScriptExecutionResult(
                ok = result is ResultWithDiagnostics.Success && missingRequired.isEmpty(),
                output = finalText,
                metadata = metadata,
                missingRequiredParams = missingRequired,
                timedOut = false,
            )
        } finally {
            ps.flush()
            ps.close()
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
    }
}

fun evalAndCaptureWithTimeout(
    scriptFile: File,
    requestContext: ScriptRequestContext = ScriptRequestContext(),
    timeoutMs: Long,
): ScriptExecutionResult {
    val boundedTimeout = timeoutMs.coerceAtLeast(1)
    val future = evalExecutor.submit<ScriptExecutionResult> {
        evalAndCapture(scriptFile, requestContext)
    }
    return try {
        future.get(boundedTimeout, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        future.cancel(true)
        val metadata = loadMetadataFromComments(scriptFile)
        ScriptExecutionResult(
            ok = false,
            output = "[ERROR] Script execution timed out after ${boundedTimeout}ms",
            metadata = metadata,
            missingRequiredParams = emptyList(),
            timedOut = true,
        )
    }
}
