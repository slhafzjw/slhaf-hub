package work.slhaf.hub

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebAuthAndScriptApiTest : WebHostTestSupport() {
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
}
