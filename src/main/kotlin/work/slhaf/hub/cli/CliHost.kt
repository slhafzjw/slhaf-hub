package work.slhaf.hub

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds

private fun usage() {
    println(
        """
Usage:
  ./gradlew runCli --args='<script.hub.kts> [--arg=key=value ...] [--body=text] [--watch] [--debounce-ms=300]'

Examples:
  ./gradlew runCli --args='scripts/hello.hub.kts'
  ./gradlew runCli --args='scripts/hello.hub.kts --arg=name=Codex'
  ./gradlew runCli --args='scripts/hello.hub.kts --watch --debounce-ms=200'
        """.trimIndent()
    )
}

private fun parseDebounce(cliArgs: List<String>): Long {
    val token = cliArgs.firstOrNull { it.startsWith("--debounce-ms=") } ?: return 300L
    return token.substringAfter("=").toLongOrNull()?.coerceAtLeast(50L) ?: 300L
}

private fun parseScriptArgs(cliArgs: List<String>): List<String> =
    cliArgs.asSequence()
        .filter { it.startsWith("--arg=") }
        .map {
            val token = it.substringAfter("--arg=")
            token
        }
        .toList()

private fun parseBody(cliArgs: List<String>): String? =
    cliArgs.firstOrNull { it.startsWith("--body=") }?.substringAfter("=")

private fun runOnce(scriptFile: File, requestContext: ScriptRequestContext) {
    println("\\n=== Evaluating ${scriptFile.absolutePath} @ ${java.time.LocalTime.now()} ===")
    val result = evalAndCapture(scriptFile, requestContext)

    if (result.output.isNotBlank()) {
        println(result.output)
    }
    if (result.metadata.description != null) {
        println("[META] description: ${result.metadata.description}")
    }
    if (result.metadata.params.isNotEmpty()) {
        println(
            "[META] params: " + result.metadata.params.joinToString(", ") { p ->
                "${p.name}(required=${p.required}, default=${p.defaultValue ?: "null"})"
            }
        )
    }

    if (result.ok) {
        println("[OK] Script evaluation finished")
    } else {
        println("[FAIL] Script evaluation failed")
    }
}

fun main(args: Array<String>) {
    val rawArgs = args.toList()
    if (rawArgs.isEmpty() || rawArgs.contains("--help") || rawArgs.contains("-h")) {
        usage()
        kotlin.system.exitProcess(if (rawArgs.isEmpty()) 1 else 0)
    }

    val scriptPath = rawArgs.firstOrNull { !it.startsWith("--") }
    if (scriptPath == null) {
        usage()
        kotlin.system.exitProcess(1)
    }

    val scriptFile = File(scriptPath).absoluteFile
    if (!scriptFile.exists()) {
        println("Script file not found: ${scriptFile.absolutePath}")
        kotlin.system.exitProcess(2)
    }

    val watch = rawArgs.contains("--watch")
    val debounceMs = parseDebounce(rawArgs)
    val requestContext = ScriptRequestContext(
        args = parseScriptArgs(rawArgs),
        body = parseBody(rawArgs)
    )

    runOnce(scriptFile, requestContext)
    if (!watch) return

    val watcher = FileSystems.getDefault().newWatchService()
    val dir: Path = scriptFile.parentFile.toPath()
    dir.register(
        watcher,
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_MODIFY,
        StandardWatchEventKinds.ENTRY_DELETE
    )

    println("[WATCH] Watching ${scriptFile.absolutePath}, debounce=${debounceMs}ms")
    println("[WATCH] Press Ctrl+C to stop")

    var lastStamp = scriptFile.takeIf { it.exists() }?.let { "${it.length()}-${it.lastModified()}" } ?: "MISSING"

    while (true) {
        val key = watcher.take()
        var shouldReload = false

        for (event in key.pollEvents()) {
            val changed = event.context()?.toString() ?: continue
            if (changed == scriptFile.name) {
                shouldReload = true
            }
        }

        key.reset()

        if (!shouldReload) continue

        Thread.sleep(debounceMs)
        val currentStamp = scriptFile.takeIf { it.exists() }?.let { "${it.length()}-${it.lastModified()}" } ?: "MISSING"
        if (currentStamp == lastStamp) continue

        lastStamp = currentStamp
        if (!scriptFile.exists()) {
            println("[WATCH] Script deleted: ${scriptFile.absolutePath}")
            continue
        }

        runOnce(scriptFile, requestContext)
    }
}
