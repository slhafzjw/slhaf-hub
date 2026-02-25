package work.slhaf.hub

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.sync.Semaphore
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebHostApiTest {
    private val tempDirs = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { path ->
            runCatching { path.toFile().deleteRecursively() }
        }
        tempDirs.clear()
    }

    @Test
    fun healthAndUnauthorized() = withApp { _ ->
        val health = client.get("/health")
        assertEquals(HttpStatusCode.OK, health.status)
        assertEquals("OK", health.bodyAsText())

        val scripts = client.get("/scripts")
        assertEquals(HttpStatusCode.Unauthorized, scripts.status)
        assertTrue(scripts.bodyAsText().contains("unauthorized"))
    }

    @Test
    fun scriptCrudMetaRunAndValidation() = withApp { scriptsDir ->
        val create = client.post("/scripts/demo") {
            bearerRoot()
            setBody(
                """
                // @desc: demo api
                // @timeout: 10s
                // @param: name | required=false | default=world | desc=Name to greet
                val args: Array<String> = emptyArray()
                val kv = args.mapNotNull {
                    val i = it.indexOf('=')
                    if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
                }.toMap()
                println("hi " + (kv["name"] ?: "world"))
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Created, create.status)

        val list = client.get("/scripts") { bearerRoot() }
        assertEquals(HttpStatusCode.OK, list.status)
        assertTrue(list.bodyAsText().contains("demo"))

        val source = client.get("/scripts/demo") { bearerRoot() }
        assertEquals(HttpStatusCode.OK, source.status)
        assertTrue(source.bodyAsText().contains("@desc: demo api"))

        val meta = client.get("/meta/demo") { bearerRoot() }
        assertEquals(HttpStatusCode.OK, meta.status)
        val metaText = meta.bodyAsText()
        assertTrue(metaText.contains("\"script\":\"demo\""))
        assertTrue(metaText.contains("\"timeoutMs\":10000"))

        val run = client.get("/run/demo?name=Alice") { bearerRoot() }
        assertEquals(HttpStatusCode.OK, run.status)
        assertTrue(run.bodyAsText().contains("hi Alice"))

        val invalidUpdate = client.put("/scripts/demo") {
            bearerRoot()
            setBody(
                """
                // @desc: bad metadata
                // @param: user name | required=maybe | xxx=1
                val args: Array<String> = emptyArray()
                println("bad")
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.BadRequest, invalidUpdate.status)
        val invalidText = invalidUpdate.bodyAsText()
        assertTrue(invalidText.contains("metadata validation failed"))
        assertTrue(invalidText.contains("examples:"))
        assertTrue(invalidText.contains("@param:"))

        val remove = client.delete("/scripts/demo") { bearerRoot() }
        assertEquals(HttpStatusCode.OK, remove.status)

        assertFalse((scriptsDir.resolve("demo.hub.kts")).toFile().exists())
    }

    @Test
    fun subTokenAccessControlAndFiltering() = withApp { scriptsDir ->
        scriptsDir.resolve("allowed.hub.kts").writeText(
            """
            // @desc: allowed script
            val args: Array<String> = emptyArray()
            println("allowed")
            """.trimIndent()
        )
        scriptsDir.resolve("blocked.hub.kts").writeText(
            """
            // @desc: blocked script
            val args: Array<String> = emptyArray()
            println("blocked")
            """.trimIndent()
        )

        val createSub = client.post("/subtokens/demo-sub") {
            bearerRoot()
            setBody("allowed")
        }
        assertEquals(HttpStatusCode.Created, createSub.status)
        val token = extractJsonField(createSub.bodyAsText(), "token")
        assertNotNull(token)

        val type = client.get("/type") { bearer(token) }
        assertEquals(HttpStatusCode.OK, type.status)
        val typeText = type.bodyAsText()
        assertTrue(typeText.contains("\"tokenType\":\"sub\""))
        assertTrue(typeText.contains("\"subTokenName\":\"demo-sub\""))

        val scripts = client.get("/scripts") { bearer(token) }
        assertEquals(HttpStatusCode.OK, scripts.status)
        val scriptList = scripts.bodyAsText()
        assertTrue(scriptList.contains("allowed"))
        assertFalse(scriptList.contains("blocked"))

        val metaAllowed = client.get("/meta/allowed") { bearer(token) }
        assertEquals(HttpStatusCode.OK, metaAllowed.status)

        val metaBlocked = client.get("/meta/blocked") { bearer(token) }
        assertEquals(HttpStatusCode.Forbidden, metaBlocked.status)

        val runAllowed = client.get("/run/allowed") { bearer(token) }
        assertEquals(HttpStatusCode.OK, runAllowed.status)

        val runBlocked = client.get("/run/blocked") { bearer(token) }
        assertEquals(HttpStatusCode.Forbidden, runBlocked.status)

        val createScript = client.post("/scripts/not-allowed") {
            bearer(token)
            setBody("val args: Array<String> = emptyArray()\nprintln(\"x\")")
        }
        assertEquals(HttpStatusCode.Forbidden, createScript.status)

        val listSubTokens = client.get("/subtokens") { bearer(token) }
        assertEquals(HttpStatusCode.Forbidden, listSubTokens.status)

        val typeByPath = client.get("/u/demo-sub@$token/type")
        assertEquals(HttpStatusCode.OK, typeByPath.status)
        assertTrue(typeByPath.bodyAsText().contains("\"tokenType\":\"sub\""))

        val scriptsByPath = client.get("/u/demo-sub@$token/scripts")
        assertEquals(HttpStatusCode.OK, scriptsByPath.status)
        assertTrue(scriptsByPath.bodyAsText().contains("allowed"))
        assertFalse(scriptsByPath.bodyAsText().contains("blocked"))

        val metaByPathAllowed = client.get("/u/demo-sub@$token/meta/allowed")
        assertEquals(HttpStatusCode.OK, metaByPathAllowed.status)

        val metaByPathBlocked = client.get("/u/demo-sub@$token/meta/blocked")
        assertEquals(HttpStatusCode.Forbidden, metaByPathBlocked.status)

        val runByPathAllowed = client.get("/u/demo-sub@$token/run/allowed")
        assertEquals(HttpStatusCode.OK, runByPathAllowed.status)

        val runByPathBlocked = client.get("/u/demo-sub@$token/run/blocked")
        assertEquals(HttpStatusCode.Forbidden, runByPathBlocked.status)

        val invalidPathAuth = client.get("/u/demo-sub@invalid-token/scripts")
        assertEquals(HttpStatusCode.Unauthorized, invalidPathAuth.status)
    }

    @Test
    fun runTimeoutReturnsRequestTimeout() = withApp { _ ->
        val create = client.post("/scripts/slow") {
            bearerRoot()
            setBody(
                """
                // @desc: slow script
                // @timeout: 1ms
                val args: Array<String> = emptyArray()
                Thread.sleep(100)
                println("done")
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Created, create.status)

        val run = client.get("/run/slow") { bearerRoot() }
        assertEquals(HttpStatusCode.RequestTimeout, run.status)
        assertTrue(run.bodyAsText().contains("timed out"))
    }

    @Test
    fun metadataRequiresExplicitRequiredField() = withApp { _ ->
        val create = client.post("/scripts/badmeta") {
            bearerRoot()
            setBody(
                """
                // @desc: bad metadata
                // @param: name | default=world | desc=missing required
                val args: Array<String> = emptyArray()
                println("ok")
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.BadRequest, create.status)
        val body = create.bodyAsText()
        assertTrue(body.contains("metadata validation failed"))
        assertTrue(body.contains("missing required option"))
    }

    @Test
    fun runErrorResponseIncludesParamsAndRequiredCheck() = withApp { _ ->
        val create = client.post("/scripts/runner") {
            bearerRoot()
            setBody(
                """
                // @desc: run test
                // @param: must | required=true | default=world | desc=Must be provided explicitly
                // @param: boom | required=false | default=false | desc=Trigger runtime failure
                val args: Array<String> = emptyArray()
                val kv = args.mapNotNull {
                    val i = it.indexOf('=')
                    if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
                }.toMap()
                if ((kv["boom"] ?: "false").equals("true", ignoreCase = true)) {
                    error("boom")
                }
                println("must=" + (kv["must"] ?: "none"))
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Created, create.status)

        val missingRequired = client.get("/run/runner") { bearerRoot() }
        assertEquals(HttpStatusCode.BadRequest, missingRequired.status)
        val missingBody = missingRequired.bodyAsText()
        assertTrue(missingBody.contains("missing required params: must"))
        assertTrue(missingBody.contains("params:"))
        assertTrue(missingBody.contains("must (required=true"))

        val runtimeError = client.get("/run/runner?must=ok&boom=true") { bearerRoot() }
        assertEquals(HttpStatusCode.InternalServerError, runtimeError.status)
        val runtimeErrorBody = runtimeError.bodyAsText()
        assertTrue(runtimeErrorBody.contains("script execution failed"))
        assertTrue(runtimeErrorBody.contains("params:"))
        assertTrue(runtimeErrorBody.contains("boom (required=false"))
    }

    @Test
    fun defaultParamValueIsInjectedIntoHostArgs() = withApp { _ ->
        val create = client.post("/scripts/defaults") {
            bearerRoot()
            setBody(
                """
                // @desc: default args test
                // @param: name | required=false | default=world | desc=Name fallback
                val args: Array<String> = emptyArray()
                val kv = args.mapNotNull {
                    val i = it.indexOf('=')
                    if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
                }.toMap()
                println("name=" + (kv["name"] ?: "missing"))
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Created, create.status)

        val runWithoutArg = client.get("/run/defaults") { bearerRoot() }
        assertEquals(HttpStatusCode.OK, runWithoutArg.status)
        assertTrue(runWithoutArg.bodyAsText().contains("name=world"))

        val runWithArg = client.get("/run/defaults?name=alice") { bearerRoot() }
        assertEquals(HttpStatusCode.OK, runWithArg.status)
        assertTrue(runWithArg.bodyAsText().contains("name=alice"))
    }

    private fun withApp(testBlock: suspend io.ktor.server.testing.ApplicationTestBuilder.(java.nio.file.Path) -> Unit) {
        val scriptsDir = createTempDirectory("webhost-api-test-")
        tempDirs.add(scriptsDir)

        testApplication {
            val security = createHostSecurity(scriptsDir.toFile(), ROOT_TOKEN)
            application {
                webModule(scriptsDir.toFile(), security, Semaphore(4))
            }
            testBlock(scriptsDir)
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
        headers.append(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.bearerRoot() {
        bearer(ROOT_TOKEN)
    }

    private fun extractJsonField(json: String, field: String): String? {
        val regex = Regex("\"" + Regex.escape(field) + "\":\"([^\"]*)\"")
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    companion object {
        private const val ROOT_TOKEN = "root-test-token"
    }
}
