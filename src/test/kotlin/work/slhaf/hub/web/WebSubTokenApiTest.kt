package work.slhaf.hub

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebSubTokenApiTest : WebHostTestSupport() {
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
}
