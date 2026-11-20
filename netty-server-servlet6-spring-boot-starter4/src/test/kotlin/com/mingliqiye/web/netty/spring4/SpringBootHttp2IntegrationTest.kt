package com.mingliqiye.web.netty.spring4

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

/** Boot `server.http2.enabled=true` 明文 h2c：JDK HttpClient 经升级协商 HTTP/2 访问 MVC 端点。 */
@SpringBootTest(
	classes = [TestApp::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["server.http2.enabled=true"],
)
class SpringBootHttp2IntegrationTest {
	@Autowired
	lateinit var environment: Environment

	private val port: Int get() = environment.getProperty("local.server.port")!!.toInt()

	private val client: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build()

	@Test
	fun `mvc endpoint negotiated over h2c upgrade`() {
		val resp = client.send(
			HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/hello")).GET().build(),
			HttpResponse.BodyHandlers.ofString(),
		)
		assertEquals(HttpClient.Version.HTTP_2, resp.version())
		assertEquals(200, resp.statusCode())
		assertEquals("hello-mvc", resp.body())
	}
}