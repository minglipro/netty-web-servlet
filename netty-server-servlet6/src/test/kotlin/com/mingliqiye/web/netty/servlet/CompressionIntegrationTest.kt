package com.mingliqiye.web.netty.servlet

import com.mingliqiye.web.netty.server.config.CompressionConfig
import com.mingliqiye.web.netty.servlet.config.ServletServerConfig
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.GZIPInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompressionIntegrationTest {
	private lateinit var server: NettyServletServer
	private val client = HttpClient.newHttpClient()
	private val base = "http://127.0.0.1:18101"
	private val bigText = "a".repeat(5000)

	@BeforeAll
	fun setup() {
		server = NettyServletServer(
			ServletServerConfig(
				port = 18101,
				compression = CompressionConfig(minResponseSize = 64, excludedUserAgents = listOf(Regex("BadBot"))),
			)
		)
		server.addServlet("text", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.contentType = "text/plain;charset=UTF-8"
				resp.writer.write(bigText)
			}
		}, "/text")
		server.addServlet("small", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.contentType = "text/plain"
				resp.writer.write("hi")
			}
		}, "/small")
		server.addServlet("bin", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.contentType = "application/octet-stream"
				resp.outputStream.write(ByteArray(5000) { 7 })
			}
		}, "/bin")
		server.addServlet("stream", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.contentType = "text/plain"
				resp.flushBuffer()
				resp.writer.write(bigText)
				resp.flushBuffer()
			}
		}, "/stream")
		server.startServer()
	}

	@AfterAll
	fun teardown() {
		server.stopServer()
	}

	private fun get(path: String, vararg headers: String): HttpResponse<ByteArray> {
		val builder = HttpRequest.newBuilder(URI.create("$base$path")).GET()
		headers.toList().chunked(2).forEach { (k, v) -> builder.header(k, v) }
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
	}

	private fun gunzip(body: ByteArray): ByteArray = GZIPInputStream(body.inputStream()).readBytes()

	@Test
	fun `large text response is gzipped when client accepts`() {
		val resp = get("/text", "Accept-Encoding", "gzip")
		assertEquals(200, resp.statusCode())
		assertEquals("gzip", resp.headers().firstValue("Content-Encoding").orElse(null))
		assertContentEquals(bigText.toByteArray(), gunzip(resp.body()))
	}

	@Test
	fun `small response below threshold is not compressed`() {
		val resp = get("/small", "Accept-Encoding", "gzip")
		assertNull(resp.headers().firstValue("Content-Encoding").orElse(null))
		assertEquals("hi", String(resp.body()))
	}

	@Test
	fun `non-whitelisted mime type is not compressed`() {
		val resp = get("/bin", "Accept-Encoding", "gzip")
		assertNull(resp.headers().firstValue("Content-Encoding").orElse(null))
		assertEquals(5000, resp.body().size)
	}

	@Test
	fun `excluded user agent is not compressed`() {
		val resp = get("/text", "Accept-Encoding", "gzip", "User-Agent", "BadBot/1.0")
		assertNull(resp.headers().firstValue("Content-Encoding").orElse(null))
		assertEquals(bigText, String(resp.body()))
	}

	@Test
	fun `streaming chunked response is compressed`() {
		val resp = get("/stream", "Accept-Encoding", "gzip")
		assertEquals("gzip", resp.headers().firstValue("Content-Encoding").orElse(null))
		assertContentEquals(bigText.toByteArray(), gunzip(resp.body()))
	}

	@Test
	fun `identity encoding is not compressed`() {
		val resp = get("/text", "Accept-Encoding", "identity")
		assertNull(resp.headers().firstValue("Content-Encoding").orElse(null))
		assertEquals(bigText, String(resp.body()))
	}
}