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
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamingBodyIntegrationTest {
	private lateinit var server: NettyServletServer
	private lateinit var limitedServer: NettyServletServer
	private lateinit var client: HttpClient
	private val port = 18092
	private val limitedPort = 18093

	@BeforeAll
	fun setup() {
		server = NettyServletServer(
			ServletServerConfig(port = port, maxRequestSize = 8 * 1024 * 1024, multipartMaxFileSize = 4 * 1024 * 1024, serverName = "testServer-$port")
		)
		limitedServer = NettyServletServer(ServletServerConfig(port = limitedPort, maxRequestSize = 1024, serverName = "testServer-$limitedPort"))

		val sink = object : HttpServlet() {
			override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
				// 请求头在 body 读取之前即可用
				resp.setHeader("X-Seen-Content-Length", req.contentLengthLong.toString())
				var total = 0L
				val buffer = ByteArray(64 * 1024)
				val input = req.inputStream
				while (true) {
					val n = input.read(buffer)
					if (n < 0) break
					total += n
					Thread.sleep(1)
				}
				resp.writer.write("bytes=$total")
			}

			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.writer.write("pong")
			}
		}
		server.addServlet("sink", sink, "/sink")
		limitedServer.addServlet("sink", sink, "/sink")

		server.addServlet("early", object : HttpServlet() {
			override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.writer.write("early")
			}
		}, "/early")

		server.addServlet("bigUpload", object : HttpServlet() {
			override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
				val file = req.parts.first { it.submittedFileName != null }
				val bytes = file.inputStream.readBytes()
				resp.writer.write("size=${bytes.size};head=${bytes.take(4).joinToString(",")}")
			}
		}, "/big-upload")

		server.startServer()
		limitedServer.startServer()
		client = HttpClient.newHttpClient()
	}

	@AfterAll
	fun teardown() {
		server.stopServer()
		limitedServer.stopServer()
	}

	@Test
	fun `large body streams through with backpressure`() {
		val payload = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
		val resp = client.send(
			HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/sink"))
				.POST(HttpRequest.BodyPublishers.ofByteArray(payload))
				.build(),
			HttpResponse.BodyHandlers.ofString(),
		)
		assertEquals(200, resp.statusCode())
		assertEquals("bytes=${payload.size}", resp.body())
		assertEquals(payload.size.toString(), resp.headers().firstValue("X-Seen-Content-Length").orElse(null))
	}

	@Test
	fun `headers arrive first and body is read later from a slow client`() {
		Socket("127.0.0.1", port).use { socket ->
			socket.soTimeout = 5000
			val out = socket.getOutputStream()
			val input = BufferedInputStream(socket.getInputStream())
			out.write("POST /sink HTTP/1.1\r\nHost: localhost\r\nContent-Length: 11\r\n\r\n".toByteArray())
			out.flush()
			Thread.sleep(300)
			out.write("hello world".toByteArray())
			out.flush()
			val (status, body) = readResponse(input)
			assertEquals(200, status)
			assertEquals("bytes=11", body)
		}
	}

	@Test
	fun `early response without reading body keeps connection alive`() {
		Socket("127.0.0.1", port).use { socket ->
			socket.soTimeout = 5000
			val out = socket.getOutputStream()
			val input = BufferedInputStream(socket.getInputStream())
			out.write("POST /early HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1000\r\n\r\n".toByteArray())
			out.write(ByteArray(500))
			out.flush()
			val (status, body) = readResponse(input)
			assertEquals(200, status)
			assertEquals("early", body)
			// 剩余 body 在响应之后才发完，容器必须排水而不是断连
			out.write(ByteArray(500))
			out.flush()
			out.write("GET /sink HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
			out.flush()
			val (status2, body2) = readResponse(input)
			assertEquals(200, status2)
			assertEquals("pong", body2)
		}
	}

	@Test
	fun `expect 100-continue gets interim response before body`() {
		Socket("127.0.0.1", port).use { socket ->
			socket.soTimeout = 5000
			val out = socket.getOutputStream()
			val input = BufferedInputStream(socket.getInputStream())
			out.write(
				("POST /sink HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\nExpect: 100-continue\r\n\r\n").toByteArray()
			)
			out.flush()
			val (interim, _) = readResponse(input)
			assertEquals(100, interim)
			out.write("abcde".toByteArray())
			out.flush()
			val (status, body) = readResponse(input)
			assertEquals(200, status)
			assertEquals("bytes=5", body)
		}
	}

	@Test
	fun `oversized body is rejected with 413`() {
		Socket("127.0.0.1", limitedPort).use { socket ->
			socket.soTimeout = 5000
			val out = socket.getOutputStream()
			val input = BufferedInputStream(socket.getInputStream())
			out.write("POST /sink HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5000\r\n\r\n".toByteArray())
			out.write(ByteArray(2048))
			out.flush()
			val (status, _) = readResponse(input)
			assertEquals(413, status)
		}
	}

	@Test
	fun `large multipart file streams to part input`() {
		val payload = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
		val boundary = "----StreamingBoundary"
		val prefix = (
			"--$boundary\r\n" +
				"Content-Disposition: form-data; name=\"file\"; filename=\"big.bin\"\r\n" +
				"Content-Type: application/octet-stream\r\n\r\n"
			).toByteArray()
		val suffix = "\r\n--$boundary--\r\n".toByteArray()
		val body = prefix + payload + suffix
		val resp = client.send(
			HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/big-upload"))
				.header("Content-Type", "multipart/form-data; boundary=$boundary")
				.POST(HttpRequest.BodyPublishers.ofByteArray(body))
				.build(),
			HttpResponse.BodyHandlers.ofString(),
		)
		assertEquals(200, resp.statusCode())
		assertEquals("size=${payload.size};head=${payload.take(4).joinToString(",")}", resp.body())
	}

	@Test
	fun `sequential streamed posts reuse the connection pool`() {
		repeat(5) { i ->
			val payload = ByteArray(64 * 1024) { i.toByte() }
			val resp = client.send(
				HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/sink"))
					.POST(HttpRequest.BodyPublishers.ofByteArray(payload))
					.build(),
				HttpResponse.BodyHandlers.ofString(),
			)
			assertEquals(200, resp.statusCode())
			assertEquals("bytes=${payload.size}", resp.body())
		}
	}

	private fun readResponse(input: InputStream): Pair<Int, String> {
		val statusLine = readLine(input) ?: error("connection closed before status line")
		val status = statusLine.split(" ")[1].toInt()
		var contentLength = 0
		while (true) {
			val line = readLine(input) ?: break
			if (line.isEmpty()) break
			val name = line.substringBefore(':').trim()
			if (name.equals("Content-Length", true)) {
				contentLength = line.substringAfter(':').trim().toInt()
			}
		}
		val body = if (contentLength > 0) input.readNBytes(contentLength).toString(Charsets.UTF_8) else ""
		return status to body
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
