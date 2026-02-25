package work.slhaf.hub

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.io.File

private val requestLogger = LoggerFactory.getLogger("work.slhaf.hub.RequestAudit")
private val subTokenPathRegex = Regex("^/u/([^/]+)/")

private fun sanitizeRequestPath(path: String): String {
    val match = subTokenPathRegex.find(path) ?: return path
    val credential = match.groupValues[1]
    val at = credential.indexOf('@')
    if (at <= 0 || at == credential.lastIndex) return path
    val nameOnly = credential.substring(0, at)
    return path.replaceFirst("/u/$credential/", "/u/$nameOnly@***/")
}

private suspend inline fun withRequestAudit(
    call: ApplicationCall,
    endpoint: String,
    authProvider: () -> AuthContext? = { null },
    crossinline block: suspend () -> Unit,
) {
    val startNs = System.nanoTime()
    var thrown: Throwable? = null
    try {
        block()
    } catch (t: Throwable) {
        thrown = t
        throw t
    } finally {
        val durationMs = (System.nanoTime() - startNs) / 1_000_000
        val auth = authProvider()
        val tokenType = auth?.type?.name?.lowercase() ?: "none"
        val subToken = auth?.subTokenName ?: "-"
        val script = call.parameters["script"] ?: "-"
        val sanitizedPath = sanitizeRequestPath(call.request.path())
        val status = call.response.status()?.value ?: if (thrown == null) 200 else 500
        if (thrown == null) {
            requestLogger.info(
                "endpoint={} method={} path={} status={} durationMs={} tokenType={} subToken={} script={}",
                endpoint,
                call.request.httpMethod.value,
                sanitizedPath,
                status,
                durationMs,
                tokenType,
                subToken,
                script,
            )
        } else {
            requestLogger.warn(
                "endpoint={} method={} path={} status={} durationMs={} tokenType={} subToken={} script={} error={}",
                endpoint,
                call.request.httpMethod.value,
                sanitizedPath,
                status,
                durationMs,
                tokenType,
                subToken,
                script,
                "${thrown::class.simpleName}: ${thrown.message}",
            )
        }
    }
}

private suspend fun handleSubTokenCreate(call: ApplicationCall, security: HostSecurity) {
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

private suspend fun handleSubTokenUpdate(call: ApplicationCall, security: HostSecurity) {
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

private suspend fun handleSubTokenGet(call: ApplicationCall, security: HostSecurity) {
    val name = call.parameters["name"]
        ?: return call.respondText("missing subtoken name", status = HttpStatusCode.BadRequest)

    val item = security.subTokens.get(name)
        ?: return call.respondText("subtoken not found: $name", status = HttpStatusCode.NotFound)

    call.respondText(subTokenItemJson(item, includeToken = true), contentType = ContentType.Application.Json)
}

private suspend fun handleSubTokenDelete(call: ApplicationCall, security: HostSecurity) {
    val name = call.parameters["name"]
        ?: return call.respondText("missing subtoken name", status = HttpStatusCode.BadRequest)

    val deleted = security.subTokens.delete(name)
    if (!deleted) return call.respondText("subtoken not found: $name", status = HttpStatusCode.NotFound)

    call.respondText("deleted subtoken: $name")
}

private suspend fun handleTypeForAuth(call: ApplicationCall, auth: AuthContext) {
    call.respondText(tokenTypeJson(auth), contentType = ContentType.Application.Json)
}

private suspend fun handleScriptsForAuth(call: ApplicationCall, scriptsDir: File, auth: AuthContext) {
    val allow = visibleScriptsFor(auth)
    call.respondText(renderScriptList(scriptsDir, allow), ContentType.Text.Plain)
}

private suspend fun handleMetaForAuth(call: ApplicationCall, scriptsDir: File, auth: AuthContext) {
    val name = call.parameters["script"]
        ?: return call.respondText("missing route name", status = HttpStatusCode.BadRequest)
    if (!requireScriptAccess(call, auth, name)) return

    val script = resolveScriptFile(scriptsDir, name)
        ?: return call.respondText("invalid script name", status = HttpStatusCode.BadRequest)
    if (!script.exists()) {
        return call.respondText("script not found: ${script.name}", status = HttpStatusCode.NotFound)
    }

    val (metadata, source) = loadMetadata(script)
    call.respondText(
        metadataJson(name, metadata, source),
        contentType = ContentType.Application.Json,
    )
}

private suspend fun handleRunForAuth(
    call: ApplicationCall,
    scriptsDir: File,
    auth: AuthContext,
    runConcurrencyLimiter: Semaphore,
    consumeBody: Boolean,
) {
    val name = call.parameters["script"]
        ?: return call.respondText("missing route name", status = HttpStatusCode.BadRequest)
    if (!requireScriptAccess(call, auth, name)) return
    runConcurrencyLimiter.withPermit {
        handleRunRequest(call, scriptsDir, consumeBody = consumeBody)
    }
}

private fun Routing.registerHeaderAuthenticatedRoutes(
    scriptsDir: File,
    security: HostSecurity,
    runConcurrencyLimiter: Semaphore,
) {
    get("/type") {
        var authForLog: AuthContext? = null
        withRequestAudit(call, "type", { authForLog }) {
            val auth = requireAuth(call, security) ?: return@withRequestAudit
            authForLog = auth
            handleTypeForAuth(call, auth)
        }
    }

    get("/scripts") {
        var authForLog: AuthContext? = null
        withRequestAudit(call, "scripts.list", { authForLog }) {
            val auth = requireAuth(call, security) ?: return@withRequestAudit
            authForLog = auth
            handleScriptsForAuth(call, scriptsDir, auth)
        }
    }

    get("/meta/{script}") {
        var authForLog: AuthContext? = null
        withRequestAudit(call, "meta.get", { authForLog }) {
            val auth = requireAuth(call, security) ?: return@withRequestAudit
            authForLog = auth
            handleMetaForAuth(call, scriptsDir, auth)
        }
    }

    get("/run/{script}") {
        var authForLog: AuthContext? = null
        withRequestAudit(call, "run.get", { authForLog }) {
            val auth = requireAuth(call, security) ?: return@withRequestAudit
            authForLog = auth
            handleRunForAuth(call, scriptsDir, auth, runConcurrencyLimiter, consumeBody = false)
        }
    }

    post("/run/{script}") {
        var authForLog: AuthContext? = null
        withRequestAudit(call, "run.post", { authForLog }) {
            val auth = requireAuth(call, security) ?: return@withRequestAudit
            authForLog = auth
            handleRunForAuth(call, scriptsDir, auth, runConcurrencyLimiter, consumeBody = true)
        }
    }
}

private fun Routing.registerSubTokenPathRoutes(
    scriptsDir: File,
    security: HostSecurity,
    runConcurrencyLimiter: Semaphore,
) {
    get("/u/{subAuth}/type") {
        var authForLog: AuthContext? = null
        withRequestAudit(call, "u.type", { authForLog }) {
            val auth = requireSubTokenPathAuth(call, security) ?: return@withRequestAudit
            authForLog = auth
            handleTypeForAuth(call, auth)
        }
    }

    get("/u/{subAuth}/scripts") {
        var authForLog: AuthContext? = null
        withRequestAudit(call, "u.scripts.list", { authForLog }) {
            val auth = requireSubTokenPathAuth(call, security) ?: return@withRequestAudit
            authForLog = auth
            handleScriptsForAuth(call, scriptsDir, auth)
        }
    }

    get("/u/{subAuth}/meta/{script}") {
        var authForLog: AuthContext? = null
        withRequestAudit(call, "u.meta.get", { authForLog }) {
            val auth = requireSubTokenPathAuth(call, security) ?: return@withRequestAudit
            authForLog = auth
            handleMetaForAuth(call, scriptsDir, auth)
        }
    }

    get("/u/{subAuth}/run/{script}") {
        var authForLog: AuthContext? = null
        withRequestAudit(call, "u.run.get", { authForLog }) {
            val auth = requireSubTokenPathAuth(call, security) ?: return@withRequestAudit
            authForLog = auth
            handleRunForAuth(call, scriptsDir, auth, runConcurrencyLimiter, consumeBody = false)
        }
    }

    post("/u/{subAuth}/run/{script}") {
        var authForLog: AuthContext? = null
        withRequestAudit(call, "u.run.post", { authForLog }) {
            val auth = requireSubTokenPathAuth(call, security) ?: return@withRequestAudit
            authForLog = auth
            handleRunForAuth(call, scriptsDir, auth, runConcurrencyLimiter, consumeBody = true)
        }
    }
}

fun Application.webModule(scriptsDir: File, security: HostSecurity, runConcurrencyLimiter: Semaphore) {
    routing {
        get("/health") {
            withRequestAudit(call, "health") {
                call.respondText("OK")
            }
        }

        registerHeaderAuthenticatedRoutes(scriptsDir, security, runConcurrencyLimiter)
        registerSubTokenPathRoutes(scriptsDir, security, runConcurrencyLimiter)

        get("/scripts/{script}") {
            var authForLog: AuthContext? = null
            withRequestAudit(call, "scripts.get", { authForLog }) {
                val auth = requireAuth(call, security) ?: return@withRequestAudit
                authForLog = auth
                if (!requireRoot(call, auth)) return@withRequestAudit
                handleGetScriptContent(call, scriptsDir)
            }
        }

        post("/scripts/{script}") {
            var authForLog: AuthContext? = null
            withRequestAudit(call, "scripts.create", { authForLog }) {
                val auth = requireAuth(call, security) ?: return@withRequestAudit
                authForLog = auth
                if (!requireRoot(call, auth)) return@withRequestAudit
                handleCreateScript(call, scriptsDir)
            }
        }

        put("/scripts/{script}") {
            var authForLog: AuthContext? = null
            withRequestAudit(call, "scripts.update", { authForLog }) {
                val auth = requireAuth(call, security) ?: return@withRequestAudit
                authForLog = auth
                if (!requireRoot(call, auth)) return@withRequestAudit
                handleUpdateScript(call, scriptsDir)
            }
        }

        delete("/scripts/{script}") {
            var authForLog: AuthContext? = null
            withRequestAudit(call, "scripts.delete", { authForLog }) {
                val auth = requireAuth(call, security) ?: return@withRequestAudit
                authForLog = auth
                if (!requireRoot(call, auth)) return@withRequestAudit
                handleDeleteScript(call, scriptsDir)
            }
        }

        get("/subtokens") {
            var authForLog: AuthContext? = null
            withRequestAudit(call, "subtokens.list", { authForLog }) {
                val auth = requireAuth(call, security) ?: return@withRequestAudit
                authForLog = auth
                if (!requireRoot(call, auth)) return@withRequestAudit
                call.respondText(subTokenListJson(security.subTokens.list()), contentType = ContentType.Application.Json)
            }
        }

        get("/subtokens/{name}") {
            var authForLog: AuthContext? = null
            withRequestAudit(call, "subtokens.get", { authForLog }) {
                val auth = requireAuth(call, security) ?: return@withRequestAudit
                authForLog = auth
                if (!requireRoot(call, auth)) return@withRequestAudit
                handleSubTokenGet(call, security)
            }
        }

        post("/subtokens/{name}") {
            var authForLog: AuthContext? = null
            withRequestAudit(call, "subtokens.create", { authForLog }) {
                val auth = requireAuth(call, security) ?: return@withRequestAudit
                authForLog = auth
                if (!requireRoot(call, auth)) return@withRequestAudit
                handleSubTokenCreate(call, security)
            }
        }

        put("/subtokens/{name}") {
            var authForLog: AuthContext? = null
            withRequestAudit(call, "subtokens.update", { authForLog }) {
                val auth = requireAuth(call, security) ?: return@withRequestAudit
                authForLog = auth
                if (!requireRoot(call, auth)) return@withRequestAudit
                handleSubTokenUpdate(call, security)
            }
        }

        delete("/subtokens/{name}") {
            var authForLog: AuthContext? = null
            withRequestAudit(call, "subtokens.delete", { authForLog }) {
                val auth = requireAuth(call, security) ?: return@withRequestAudit
                authForLog = auth
                if (!requireRoot(call, auth)) return@withRequestAudit
                handleSubTokenDelete(call, security)
            }
        }
    }
}
