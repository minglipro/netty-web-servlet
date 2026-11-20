package com.mingliqiye.web.netty.spring4

import com.mingliqiye.web.netty.servlet.session.DiskSessionStore
import com.mingliqiye.web.netty.servlet.session.SessionStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.File
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Boot `server.netty.session-store-path` 绑定磁盘会话存储，MVC 会话经其存取。 */
@SpringBootTest(
	classes = [TestApp::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class SpringBootSessionStoreIntegrationTest {
	companion object {
		private val storeFile: File = File.createTempFile("netty-boot-session", ".bin").apply { deleteOnExit() }

		@JvmStatic
		@DynamicPropertySource
		fun storeProperties(registry: DynamicPropertyRegistry) {
			registry.add("server.netty.session-store-path") { storeFile.absolutePath }
		}
	}

	@Autowired
	lateinit var environment: Environment

	@Autowired
	lateinit var sessionStore: SessionStore

	private val port: Int get() = environment.getProperty("local.server.port")!!.toInt()

	private val client: HttpClient = HttpClient.newBuilder().cookieHandler(CookieManager()).build()

	private fun get(path: String): String =
		client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()

	@Test
	fun `session store path binds disk store`() {
		assertIs<DiskSessionStore>(sessionStore)
	}

	@Test
	fun `mvc session counts through disk store`() {
		assertEquals("count=1", get("/sess"))
		assertEquals("count=2", get("/sess"))
	}
}