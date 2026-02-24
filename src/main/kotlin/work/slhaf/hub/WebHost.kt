package work.slhaf.hub

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

private const val DEFAULT_PORT = 8080
private const val DEFAULT_SCRIPTS_DIR = "scripts"
private const val DEFAULT_HOST = "0.0.0.0"
private val DEFAULT_MAX_RUN_CONCURRENCY = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

private suspend fun handleSubTokenCreate(call: io.ktor.server.application.ApplicationCall, security: HostSecurity) {
    val name = call.parameters["name"]
        ?: return call.respondText("missing subtoken name", status = HttpStatusCode.BadRequest)

    if (!name.matches(Regex("[A-Za-z0-9._-]+"))) {
        return call.respondText("invalid subtoken name", status = HttpStatusCode.BadRequest)
    }

    val scriptsRaw = call.receiveText()
    val scripts =
        try {
            parseScriptNameSet(scriptsRaw)
        } catch (t: Throwable) {
            return call.respondText(t.message ?: "invalid script names", status = HttpStatusCode.BadRequest)
        }

    val created =
        try {
            security.subTokens.create(name, scripts)
        } catch (t: Throwable) {
            return call.respondText(t.message ?: "failed to create subtoken", status = HttpStatusCode.Conflict)
        }

    call.respondText(subTokenItemJson(created, includeToken = true), contentType = ContentType.Application.Json, status = HttpStatusCode.Created)
}

private suspend fun handleSubTokenUpdate(call: io.ktor.server.application.ApplicationCall, security: HostSecurity) {
    val name = call.parameters["name"]
        ?: return call.respondText("missing subtoken name", status = HttpStatusCode.BadRequest)

    val scriptsRaw = call.receiveText()
    val scripts =
        try {
            parseScriptNameSet(scriptsRaw)
        } catch (t: Throwable) {
            return call.respondText(t.message ?: "invalid script names", status = HttpStatusCode.BadRequest)
        }

    val updated =
        try {
            security.subTokens.update(name, scripts)
        } catch (t: Throwable) {
            return call.respondText(t.message ?: "failed to update subtoken", status = HttpStatusCode.NotFound)
        }

    call.respondText(subTokenItemJson(updated, includeToken = true), contentType = ContentType.Application.Json)
}

private suspend fun handleSubTokenGet(call: io.ktor.server.application.ApplicationCall, security: HostSecurity) {
    val name = call.parameters["name"]
        ?: return call.respondText("missing subtoken name", status = HttpStatusCode.BadRequest)

    val item = security.subTokens.get(name)
        ?: return call.respondText("subtoken not found: $name", status = HttpStatusCode.NotFound)

    call.respondText(subTokenItemJson(item, includeToken = true), contentType = ContentType.Application.Json)
}

private suspend fun handleSubTokenDelete(call: io.ktor.server.application.ApplicationCall, security: HostSecurity) {
    val name = call.parameters["name"]
        ?: return call.respondText("missing subtoken name", status = HttpStatusCode.BadRequest)

    val deleted = security.subTokens.delete(name)
    if (!deleted) return call.respondText("subtoken not found: $name", status = HttpStatusCode.NotFound)

    call.respondText("deleted subtoken: $name")
}

fun Application.webModule(scriptsDir: File, security: HostSecurity, runConcurrencyLimiter: Semaphore) {
    routing {
        get("/health") {
            call.respondText("OK")
        }

        get("/type") {
            val auth = requireAuth(call, security) ?: return@get
            call.respondText(tokenTypeJson(auth), contentType = ContentType.Application.Json)
        }

        get("/scripts") {
            val auth = requireAuth(call, security) ?: return@get
            val allow = visibleScriptsFor(auth)
            call.respondText(renderScriptList(scriptsDir, allow), ContentType.Text.Plain)
        }

        get("/scripts/{script}") {
            val auth = requireAuth(call, security) ?: return@get
            if (!requireRoot(call, auth)) return@get
            handleGetScriptContent(call, scriptsDir)
        }

        post("/scripts/{script}") {
            val auth = requireAuth(call, security) ?: return@post
            if (!requireRoot(call, auth)) return@post
            handleCreateScript(call, scriptsDir)
        }

        put("/scripts/{script}") {
            val auth = requireAuth(call, security) ?: return@put
            if (!requireRoot(call, auth)) return@put
            handleUpdateScript(call, scriptsDir)
        }

        delete("/scripts/{script}") {
            val auth = requireAuth(call, security) ?: return@delete
            if (!requireRoot(call, auth)) return@delete
            handleDeleteScript(call, scriptsDir)
        }

        get("/meta/{script}") {
            val auth = requireAuth(call, security) ?: return@get
            val name = call.parameters["script"]
                ?: return@get call.respondText("missing route name", status = HttpStatusCode.BadRequest)

            if (!requireScriptAccess(call, auth, name)) return@get

            val script = resolveScriptFile(scriptsDir, name)
                ?: return@get call.respondText("invalid script name", status = HttpStatusCode.BadRequest)
            if (!script.exists()) {
                return@get call.respondText("script not found: ${script.name}", status = HttpStatusCode.NotFound)
            }

            val (metadata, source) = loadMetadata(script)
            call.respondText(
                metadataJson(name, metadata, source),
                contentType = ContentType.Application.Json,
            )
        }

        get("/run/{script}") {
            val auth = requireAuth(call, security) ?: return@get
            val name = call.parameters["script"]
                ?: return@get call.respondText("missing route name", status = HttpStatusCode.BadRequest)
            if (!requireScriptAccess(call, auth, name)) return@get
            runConcurrencyLimiter.withPermit {
                handleRunRequest(call, scriptsDir, consumeBody = false)
            }
        }

        post("/run/{script}") {
            val auth = requireAuth(call, security) ?: return@post
            val name = call.parameters["script"]
                ?: return@post call.respondText("missing route name", status = HttpStatusCode.BadRequest)
            if (!requireScriptAccess(call, auth, name)) return@post
            runConcurrencyLimiter.withPermit {
                handleRunRequest(call, scriptsDir, consumeBody = true)
            }
        }

        get("/subtokens") {
            val auth = requireAuth(call, security) ?: return@get
            if (!requireRoot(call, auth)) return@get
            call.respondText(subTokenListJson(security.subTokens.list()), contentType = ContentType.Application.Json)
        }

        get("/subtokens/{name}") {
            val auth = requireAuth(call, security) ?: return@get
            if (!requireRoot(call, auth)) return@get
            handleSubTokenGet(call, security)
        }

        post("/subtokens/{name}") {
            val auth = requireAuth(call, security) ?: return@post
            if (!requireRoot(call, auth)) return@post
            handleSubTokenCreate(call, security)
        }

        put("/subtokens/{name}") {
            val auth = requireAuth(call, security) ?: return@put
            if (!requireRoot(call, auth)) return@put
            handleSubTokenUpdate(call, security)
        }

        delete("/subtokens/{name}") {
            val auth = requireAuth(call, security) ?: return@delete
            if (!requireRoot(call, auth)) return@delete
            handleSubTokenDelete(call, security)
        }
    }
}

private fun usage() {
    println(
        """
Usage:
  ./gradlew runWeb --args='[--host=0.0.0.0] [--port=8080] [--scripts-dir=./scripts] [--max-run-concurrency=N]'
Routes:
  GET  /health
  GET  /type
  Authorization:
    Authorization: Bearer <token>
    or X-Host-Token: <token>
  GET  /scripts
  GET  /scripts/{script}                      (root only)
  POST /scripts/{script}                      (root only)
  PUT  /scripts/{script}                      (root only)
  DELETE /scripts/{script}                    (root only)
  GET  /meta/{script}                         (root or allowed subtoken)
  GET  /run/{script}?k=v                      (root or allowed subtoken)
  POST /run/{script}?k=v                      (root or allowed subtoken)
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
