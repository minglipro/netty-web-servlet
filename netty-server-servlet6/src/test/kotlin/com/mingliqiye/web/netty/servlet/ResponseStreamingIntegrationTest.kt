package com.mingliqiye.web.netty.servlet

import com.mingliqiye.web.netty.servlet.config.ServletServerConfig
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponseStreamingIntegrationTest {
	private lateinit var server: NettyServletServer
	private lateinit var client: HttpClient
	private val port = 18094

	@BeforeAll
	fun setup() {
		server = NettyServletServer(ServletServerConfig(port = port))

		server.addServlet("sse", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.contentType = "text/plain;charset=UTF-8"
				resp.writer.write("part1")
				resp.flushBuffer()
				val latch = req.servletContext.getAttribute("sseLatch") as CountDownLatch
				check(latch.await(5, TimeUnit.SECONDS)) { "client never read first chunk" }
				resp.writer.write("part2")
				resp.flushBuffer()
			}
		}, "/sse")

		server.addServlet("big", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				val chunk = ByteArray(1024) { (it % 251).toByte() }
				repeat(200) { resp.outputStream.write(chunk) }
			}
		}, "/big")

		server.addServlet("streamError", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.writer.write("part1")
				resp.flushBuffer()
				resp.sendError(500)
			}
		}, "/stream-error")

		server.addServlet("asyncStream", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				val async = req.startAsync()
				async.start {
					val r = async.response as HttpServletResponse
					r.writer.write("a")
					r.flushBuffer()
					Thread.sleep(50)
					r.writer.write("b")
					async.complete()
				}
			}
		}, "/async-stream")

		server.addServlet("outFlush", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				val out = resp.outputStream
				out.write("x".toByteArray())
				out.flush()
				out.write("y".toByteArray())
			}
		}, "/out-flush")

		server.addServlet("ping", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.writer.write("pong")
			}
		}, "/ping")

		server.startServer()
		client = HttpClient.newHttpClient()
	}

	@AfterAll
	fun teardown() {
		server.stopServer()
	}

	@Test
	fun `flushBuffer delivers chunks incrementally and keeps connection alive`() {
		val latch = CountDownLatch(1)
		server.container.context.setAttribute("sseLatch", latch)
		Socket("127.0.0.1", port).use { socket ->
			socket.soTimeout = 5000
			val out = socket.getOutputStream()
			val input = BufferedInputStream(socket.getInputStream())
			out.write("GET /sse HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
			out.flush()

			val (status, headers) = readHead(input)
			assertEquals(200, status)
			assertEquals("chunked", headers["transfer-encoding"])
			assertNull(headers["content-length"])

			// 第一个 chunk 必须在 servlet 完成之前到达
			assertEquals("part1", readChunk(input))
			latch.countDown()
			assertEquals("part2", readChunk(input))
			assertEquals("", readChunk(input))

			// chunked 响应后连接必须可复用
			out.write("GET /ping HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
			out.flush()
			val (status2, headers2) = readHead(input)
			assertEquals(200, status2)
			assertEquals("4", headers2["content-length"])
			assertEquals("pong", input.readNBytes(4).toString(Charsets.UTF_8))
		}
	}

	@Test
	fun `buffer overflow auto-commits as chunked`() {
		val resp = client.send(
			HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/big")).GET().build(),
			HttpResponse.BodyHandlers.ofByteArray(),
		)
		assertEquals(200, resp.statusCode())
		assertEquals("chunked", resp.headers().firstValue("Transfer-Encoding").orElse(null))
		val expected = ByteArray(1024) { (it % 251).toByte() }
		val body = resp.body()
		assertEquals(200 * 1024, body.size)
		repeat(200) { i ->
			assertTrue(body.copyOfRange(i * 1024, (i + 1) * 1024).contentEquals(expected))
		}
	}

	@Test
	fun `sendError after commit cannot change the response`() {
		val resp = get("/stream-error")
		assertEquals(200, resp.statusCode())
		assertEquals("part1", resp.body())
	}

	@Test
	fun `async servlet can stream chunks`() {
		val resp = get("/async-stream")
		assertEquals(200, resp.statusCode())
		assertEquals("chunked", resp.headers().firstValue("Transfer-Encoding").orElse(null))
		assertEquals("ab", resp.body())
	}

	@Test
	fun `outputStream flush commits the response`() {
		val resp = get("/out-flush")
		assertEquals(200, resp.statusCode())
		assertEquals("chunked", resp.headers().firstValue("Transfer-Encoding").orElse(null))
		assertEquals("xy", resp.body())
	}

	private fun get(path: String): HttpResponse<String> =
		client.send(
			HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(),
			HttpResponse.BodyHandlers.ofString(),
		)

	private fun readHead(input: InputStream): Pair<Int, Map<String, String>> {
		val statusLine = readLine(input) ?: error("connection closed before status line")
		val status = statusLine.split(" ")[1].toInt()
		val headers = mutableMapOf<String, String>()
		while (true) {
			val line = readLine(input) ?: break
			if (line.isEmpty()) break
			headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
		}
		return status to headers
	}

	private fun readChunk(input: InputStream): String {
		val sizeLine = readLine(input) ?: error("connection closed before chunk size")
		val size = sizeLine.trim().toInt(16)
		if (size == 0) {
			readLine(input)
			return ""
		}
		val data = input.readNBytes(size).toString(Charsets.UTF_8)
		readLine(input)
		return data
	}

	private fun readLine(input: InputStream): String? {
		val sb = StringBuilder()
		while (true) {
			val c = input.read()
			if (c < 0) return if (sb.isEmpty()) null else sb.toString()
			if (c == '\n'.code) return sb.toString().trimEnd('\r')
			sb.append(c.toChar())
		}
	}
}