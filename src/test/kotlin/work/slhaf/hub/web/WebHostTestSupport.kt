package work.slhaf.hub

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.sync.Semaphore
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest

abstract class WebHostTestSupport {
    private val tempDirs = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { path ->
            runCatching { path.toFile().deleteRecursively() }
        }
        tempDirs.clear()
    }

    protected fun withApp(testBlock: suspend ApplicationTestBuilder.(java.nio.file.Path) -> Unit) {
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

    protected fun HttpRequestBuilder.bearer(token: String) {
        headers.append(HttpHeaders.Authorization, "Bearer $token")
    }

    protected fun HttpRequestBuilder.bearerRoot() {
        bearer(ROOT_TOKEN)
    }

    protected fun extractJsonField(json: String, field: String): String? {
        val regex = Regex("\\\"" + Regex.escape(field) + "\\\":\\\"([^\\\"]*)\\\"")
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    companion object {
        private const val ROOT_TOKEN = "root-test-token"
    }
}
