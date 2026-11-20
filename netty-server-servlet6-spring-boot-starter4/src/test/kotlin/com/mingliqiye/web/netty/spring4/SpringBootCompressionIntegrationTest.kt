package com.mingliqiye.web.netty.spring4

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.GZIPInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@SpringBootTest(
	classes = [TestApp::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [
		"server.compression.enabled=true",
		"server.compression.min-response-size=64B",
		"server.compression.excluded-user-agents=BadBot.*",
	],
)
class SpringBootCompressionIntegrationTest {
	@Autowired
	lateinit var environment: Environment

	private val port: Int get() = environment.getProperty("local.server.port")!!.toInt()
	private val client = HttpClient.newHttpClient()

	private fun get(vararg headers: String): HttpResponse<ByteArray> {
		val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/big")).GET()
		headers.toList().chunked(2).forEach { (k, v) -> builder.header(k, v) }
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
	}

	@Test
	fun `server compression properties enable gzip`() {
		val resp = get("Accept-Encoding", "gzip")
		assertEquals("gzip", resp.headers().firstValue("Content-Encoding").orElse(null))
		assertContentEquals("x".repeat(5000).toByteArray(), GZIPInputStream(resp.body().inputStream()).readBytes())
	}

	@Test
	fun `excluded user agent bypasses compression`() {
		val resp = get("Accept-Encoding", "gzip", "User-Agent", "BadBot/2.0")
		assertEquals(null, resp.headers().firstValue("Content-Encoding").orElse(null))
		assertEquals("x".repeat(5000), String(resp.body()))
	}
}