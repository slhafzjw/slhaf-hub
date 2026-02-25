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
private val compiledScriptCache = ConcurrentHashMap<String, Pair<String, CompiledScript>>() // key -> stamp, compiled script

private val resolver = CompoundDependenciesResolver(FileSystemDependenciesResolver(), MavenDependenciesResolver())
private val argsDeclarationRegexes = listOf(
    Regex("""^\s*val\s+args\s*:\s*Array<String>\s*=\s*emptyArray\(\)\s*$"""),
    Regex("""^\s*lateinit\s+var\s+args\s*:\s*Array<String>\s*$"""),
)
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

private fun injectArgsBridgeDeclaration(scriptContent: String): String {
    val lines = scriptContent.lines()
    val injected = "val args: Array<String> = hostArgs"
    var replaced = false
    val result = lines.map { line ->
        if (!replaced && argsDeclarationRegexes.any { it.matches(line) }) {
            replaced = true
            injected
        } else {
            line
        }
    }
    return result.joinToString("\n")
}

private fun parseArgEntry(raw: String): Pair<String, String>? {
    val idx = raw.indexOf('=')
    if (idx <= 0) return null
    return raw.substring(0, idx) to raw.substring(idx + 1)
}

private fun applyDefaultArgs(metadata: ScriptMetadata, requestArgs: List<String>): List<String> {
    val existingKeys = requestArgs.mapNotNull { parseArgEntry(it)?.first }.toMutableSet()
    val merged = requestArgs.toMutableList()
    metadata.params.forEach { param ->
        if (!existingKeys.contains(param.name) && param.defaultValue != null) {
            merged += "${param.name}=${param.defaultValue}"
            existingKeys += param.name
        }
    }
    return merged
}

private fun compileSource(source: SourceCode): ResultWithDiagnostics<CompiledScript> {
    val explicitCp = explicitClasspathFromEnv()
    return runBlocking {
        scriptingHost.compiler(source, compilationConfiguration(explicitCp))
    }
}

private fun evalCompiled(compiledScript: CompiledScript, requestContext: ScriptRequestContext): ResultWithDiagnostics<EvaluationResult> {
    val evaluationConfiguration = ScriptEvaluationConfiguration {
        constructorArgs(requestContext.args.toTypedArray())
    }
    return runBlocking {
        scriptingHost.evaluator(compiledScript, evaluationConfiguration)
    }
}

private fun compiledScriptFor(scriptFile: File, preparedContent: String): ResultWithDiagnostics<CompiledScript> {
    val key = scriptFile.canonicalPath
    val stamp = scriptStamp(scriptFile)
    val cached = compiledScriptCache[key]
    if (cached != null && cached.first == stamp) {
        return cached.second.asSuccess()
    }

    val compiled = compileSource(preparedContent.toScriptSource(scriptFile.name))
    if (compiled is ResultWithDiagnostics.Success) {
        compiledScriptCache[key] = stamp to compiled.value
    }
    return compiled
}

data class ScriptExecutionResult(
    val ok: Boolean,
    val output: String,
    val metadata: ScriptMetadata,
    val missingRequiredParams: List<String>,
    val timedOut: Boolean = false,
)

fun removeCachedMetadata(scriptFile: File) {
    clearMetadataCache(scriptFile)
    compiledScriptCache.remove(scriptFile.canonicalPath)
}

fun evalAndCapture(scriptFile: File, requestContext: ScriptRequestContext = ScriptRequestContext()): ScriptExecutionResult {
    return evalAndCapture(scriptFile, requestContext, enforceRequiredParams = true)
}

fun evalAndCapture(
    scriptFile: File,
    requestContext: ScriptRequestContext = ScriptRequestContext(),
    enforceRequiredParams: Boolean,
): ScriptExecutionResult {
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
                    }
                }
                .map { it.name }
            val effectiveArgs = applyDefaultArgs(metadata, requestContext.args)

            val injected = injectArgsBridgeDeclaration(original)
            val compilationResult = compiledScriptFor(scriptFile, injected)
            val evaluationResult = if (compilationResult is ResultWithDiagnostics.Success) {
                evalCompiled(compilationResult.value, requestContext.copy(args = effectiveArgs))
            } else {
                null
            }
            val reports = evaluationResult?.reports ?: compilationResult.reports
            val returnValueError = (evaluationResult as? ResultWithDiagnostics.Success)
                ?.value
                ?.returnValue as? ResultValue.Error
            val hasErrorDiagnostics = reports.any {
                it.severity == ScriptDiagnostic.Severity.ERROR || it.severity == ScriptDiagnostic.Severity.FATAL
            }
            val diagnostics = reports
                .filter { it.severity > ScriptDiagnostic.Severity.DEBUG }
                .joinToString("\n") {
                    val ex = it.exception?.let { e -> ": ${e::class.simpleName}: ${e.message}" } ?: ""
                    "[${it.severity}] ${it.message}$ex"
                }
            val missingMessage = if (!enforceRequiredParams || missingRequired.isEmpty()) "" else
                "[ERROR] Missing required parameters: ${missingRequired.joinToString(", ")}"

            val output = buffer.toString(Charsets.UTF_8.name()).trim()
            val finalText = buildString {
                if (output.isNotEmpty()) appendLine(output)
                if (returnValueError != null) appendLine("[ERROR] ${returnValueError.error::class.simpleName}: ${returnValueError.error.message}")
                if (diagnostics.isNotEmpty()) appendLine(diagnostics)
                if (missingMessage.isNotEmpty()) appendLine(missingMessage)
            }.trim()

            ScriptExecutionResult(
                ok = compilationResult is ResultWithDiagnostics.Success &&
                    evaluationResult is ResultWithDiagnostics.Success &&
                    !hasErrorDiagnostics &&
                    returnValueError == null &&
                    (!enforceRequiredParams || missingRequired.isEmpty()),
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
    enforceRequiredParams: Boolean = true,
): ScriptExecutionResult {
    val boundedTimeout = timeoutMs.coerceAtLeast(1)
    val future = evalExecutor.submit<ScriptExecutionResult> {
        evalAndCapture(scriptFile, requestContext, enforceRequiredParams = enforceRequiredParams)
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
