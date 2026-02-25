package work.slhaf.hub

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebRunApiTest : WebHostTestSupport() {
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

    @Test
    fun lateinitArgsDeclarationIsSupported() = withApp { _ ->
        val create = client.post("/scripts/lateinit-args") {
            bearerRoot()
            setBody(
                """
                // @desc: lateinit args
                // @param: name | required=false | default=world | desc=Name fallback
                lateinit var args: Array<String>
                val kv = args.mapNotNull {
                    val i = it.indexOf('=')
                    if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
                }.toMap()
                println("name=" + (kv["name"] ?: "missing"))
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Created, create.status)

        val run = client.get("/run/lateinit-args") { bearerRoot() }
        assertEquals(HttpStatusCode.OK, run.status)
        assertTrue(run.bodyAsText().contains("name=world"))
    }
}
