package work.slhaf.hub

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import java.io.File

private const val DEFAULT_PORT = 8080
private const val DEFAULT_SCRIPTS_DIR = "scripts"
private const val DEFAULT_HOST = "0.0.0.0"

private fun Application.module(scriptsDir: File, apiToken: String) {
    routing {
        get("/health") {
            call.respondText("OK")
        }
        get("/scripts") {
            if (!requireAuth(call, apiToken)) return@get
            call.respondText(renderScriptList(scriptsDir), ContentType.Text.Plain)
        }
        get("/scripts/{script}") {
            if (!requireAuth(call, apiToken)) return@get
            handleGetScriptContent(call, scriptsDir)
        }
        post("/scripts/{script}") {
            if (!requireAuth(call, apiToken)) return@post
            handleCreateScript(call, scriptsDir)
        }
        put("/scripts/{script}") {
            if (!requireAuth(call, apiToken)) return@put
            handleUpdateScript(call, scriptsDir)
        }
        delete("/scripts/{script}") {
            if (!requireAuth(call, apiToken)) return@delete
            handleDeleteScript(call, scriptsDir)
        }
        get("/meta/{script}") {
            if (!requireAuth(call, apiToken)) return@get
            val name = call.parameters["script"]
                ?: return@get call.respondText("missing route name", status = HttpStatusCode.BadRequest)
            val script = resolveScriptFile(scriptsDir, name)
                ?: return@get call.respondText("invalid script name", status = HttpStatusCode.BadRequest)
            if (!script.exists()) {
                return@get call.respondText("script not found: ${script.name}", status = HttpStatusCode.NotFound)
            }

            val (metadata, source) = loadMetadata(script)
            call.respondText(
                metadataJson(name, metadata, source),
                contentType = ContentType.Application.Json
            )
        }
        get("/run/{script}") {
            if (!requireAuth(call, apiToken)) return@get
            handleRunRequest(call, scriptsDir, consumeBody = false)
        }
        post("/run/{script}") {
            if (!requireAuth(call, apiToken)) return@post
            handleRunRequest(call, scriptsDir, consumeBody = true)
        }
    }
}

private fun usage() {
    println(
        """
Usage:
  ./gradlew runWeb --args='[--host=0.0.0.0] [--port=8080] [--scripts-dir=./scripts]'
Routes:
  GET  /health
  Authorization:
    Authorization: Bearer <token>
    or X-Host-Token: <token>
  GET  /scripts
  GET  /scripts/{script}
  POST /scripts/{script}
  PUT  /scripts/{script}
  DELETE /scripts/{script}
  GET  /meta/{script}
  GET  /run/{script}?k=v
  POST /run/{script}?k=v
        """.trimIndent()
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

    if (!scriptsDir.exists()) scriptsDir.mkdirs()
    val auth = loadOrCreateApiToken(scriptsDir)

    println("Starting script web host on http://$host:$port")
    println("Scripts directory: ${scriptsDir.absolutePath}")
    println("Auth token source: ${auth.source}")
    when {
        auth.source.startsWith("env:") -> println("Auth token loaded from environment variable.")
        auth.source.startsWith("generated:") ->
            println("Auth token generated and saved to: ${auth.tokenFile?.absolutePath}")
        else -> println("Auth token loaded from file: ${auth.tokenFile?.absolutePath}")
    }

    embeddedServer(Netty, port = port, host = host) {
        module(scriptsDir, auth.token)
    }.start(wait = true)
}
