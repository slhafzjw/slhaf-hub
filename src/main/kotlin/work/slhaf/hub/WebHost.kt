package work.slhaf.hub

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.sync.Semaphore
import java.io.File

private const val DEFAULT_PORT = 8080
private const val DEFAULT_SCRIPTS_DIR = "scripts"
private const val DEFAULT_HOST = "0.0.0.0"
private val DEFAULT_MAX_RUN_CONCURRENCY = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

private fun usage() {
    println(
        """
Usage:
  ./gradlew runWeb --args='[--host=0.0.0.0] [--port=8080] [--scripts-dir=./scripts] [--max-run-concurrency=N]'
Routes:
  GET  /health
  GET  /type
  GET  /u/{subtoken_name}@{subtoken}/type      (subtoken path auth)
  Authorization:
    Authorization: Bearer <token>
    or X-Host-Token: <token>
  GET  /scripts
  GET  /u/{subtoken_name}@{subtoken}/scripts   (subtoken path auth)
  GET  /scripts/{script}                      (root only)
  POST /scripts/{script}                      (root only)
  PUT  /scripts/{script}                      (root only)
  DELETE /scripts/{script}                    (root only)
  GET  /meta/{script}                         (root or allowed subtoken)
  GET  /u/{subtoken_name}@{subtoken}/meta/{script}
  GET  /run/{script}?k=v                      (root or allowed subtoken)
  GET  /u/{subtoken_name}@{subtoken}/run/{script}?k=v
  POST /run/{script}?k=v                      (root or allowed subtoken)
  POST /u/{subtoken_name}@{subtoken}/run/{script}?k=v
  GET  /subtokens                             (root only)
  GET  /subtokens/{name}                      (root only)
  POST /subtokens/{name}                      (root only, body: script names list)
  PUT  /subtokens/{name}                      (root only, body: script names list)
  DELETE /subtokens/{name}                    (root only)
        """.trimIndent(),
    )
}

private fun List<String>.optionValue(prefix: String): String? =
    firstOrNull { it.startsWith(prefix) }?.substringAfter("=")

fun main(args: Array<String>) {
    val cli = args.toList()

    if ("--help" in cli || "-h" in cli) {
        usage()
        return
    }

    val port = cli.optionValue("--port=")?.toIntOrNull() ?: DEFAULT_PORT
    val host = cli.optionValue("--host=")?.ifBlank { DEFAULT_HOST } ?: DEFAULT_HOST
    val scriptsDir = File(cli.optionValue("--scripts-dir=") ?: DEFAULT_SCRIPTS_DIR).absoluteFile
    val maxRunConcurrency = cli.optionValue("--max-run-concurrency=")?.toIntOrNull() ?: DEFAULT_MAX_RUN_CONCURRENCY
    require(maxRunConcurrency > 0) { "--max-run-concurrency must be > 0" }

    if (!scriptsDir.exists()) scriptsDir.mkdirs()
    val auth = loadOrCreateApiToken(scriptsDir)
    val security = createHostSecurity(scriptsDir, auth.token)
    val runConcurrencyLimiter = Semaphore(maxRunConcurrency)

    println("Starting script web host on http://$host:$port")
    println("Scripts directory: ${scriptsDir.absolutePath}")
    println("Run concurrency limit: $maxRunConcurrency")
    println("Auth token source: ${auth.source}")
    when {
        auth.source.startsWith("env:") -> println("Auth token loaded from environment variable.")
        auth.source.startsWith("generated:") ->
            println("Auth token generated and saved to: ${auth.tokenFile?.absolutePath}")

        else -> println("Auth token loaded from file: ${auth.tokenFile?.absolutePath}")
    }

    embeddedServer(Netty, port = port, host = host) {
        webModule(scriptsDir, security, runConcurrencyLimiter)
    }.start(wait = true)
}
