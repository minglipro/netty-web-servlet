package com.mingliqiye.web.netty.servlet

import com.mingliqiye.web.netty.servlet.config.ServletServerConfig
import com.mingliqiye.web.netty.servlet.resources.DefaultServlet
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
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StaticResourceIntegrationTest {
	private lateinit var server: NettyServletServer
	private lateinit var listingServer: NettyServletServer
	private lateinit var docRoot: Path
	private val client = HttpClient.newHttpClient()
	private val base = "http://127.0.0.1:18104"
	private val listingBase = "http://127.0.0.1:18105"
	private val binData = ByteArray(100) { it.toByte() }

	@BeforeAll
	fun setup() {
		docRoot = Files.createTempDirectory("netty-docroot")
		Files.writeString(docRoot.resolve("index.html"), "<h1>welcome</h1>")
		Files.createDirectories(docRoot.resolve("css"))
		Files.writeString(docRoot.resolve("css/style.css"), "body{color:red}")
		Files.write(docRoot.resolve("data.bin"), binData)
		Files.writeString(docRoot.parent.resolve("secret.txt"), "top-secret")

		server = NettyServletServer(ServletServerConfig(port = 18104, docRoot = docRoot.toString()))
		server.addServlet("api", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.writer.write("api-ok")
			}
		}, "/api")
		server.addServlet("probe", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				val ctx = servletContext
				resp.writer.write(
					"real=${ctx.getRealPath("/index.html") != null}" +
						"|paths=${ctx.getResourcePaths("/")?.sorted()?.joinToString(",")}"
				)
			}
		}, "/probe")
		server.addServlet("cp", DefaultServlet(), "/cp/*")
		server.startServer()

		listingServer = NettyServletServer(ServletServerConfig(port = 18105, docRoot = docRoot.toString()))
		val registration = listingServer.addServlet("default", DefaultServlet(), "/")
		registration.setInitParameters(mapOf("listings" to "true"))
		listingServer.startServer()
	}

	@AfterAll
	fun teardown() {
		if (::server.isInitialized) server.stopServer()
		if (::listingServer.isInitialized) listingServer.stopServer()
		if (::docRoot.isInitialized) docRoot.toFile().deleteRecursively()
	}

	private fun get(base: String, path: String, vararg headers: String): HttpResponse<ByteArray> {
		val builder = HttpRequest.newBuilder(URI.create("$base$path")).GET()
		headers.toList().chunked(2).forEach { (k, v) -> builder.header(k, v) }
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
	}

	@Test
	fun `file served with content type length and last-modified`() {
		val resp = get(base, "/index.html")
		assertEquals(200, resp.statusCode())
		assertEquals("<h1>welcome</h1>", String(resp.body()))
		assertTrue(resp.headers().firstValue("Content-Type").orElse("").startsWith("text/html"))
		assertEquals("16", resp.headers().firstValue("Content-Length").orElse(null))
		assertTrue(resp.headers().firstValue("Last-Modified").isPresent)
		assertEquals("bytes", resp.headers().firstValue("Accept-Ranges").orElse(null))
	}

	@Test
	fun `directory root serves welcome file`() {
		val resp = get(base, "/")
		assertEquals(200, resp.statusCode())
		assertEquals("<h1>welcome</h1>", String(resp.body()))
	}

	@Test
	fun `css mime type resolved`() {
		val resp = get(base, "/css/style.css")
		assertEquals(200, resp.statusCode())
		assertTrue(resp.headers().firstValue("Content-Type").orElse("").startsWith("text/css"))
	}

	@Test
	fun `exact servlet wins over default servlet`() {
		assertEquals("api-ok", String(get(base, "/api").body()))
	}

	@Test
	fun `range request returns 206 with content-range`() {
		val resp = get(base, "/data.bin", "Range", "bytes=2-5")
		assertEquals(206, resp.statusCode())
		assertEquals("bytes 2-5/100", resp.headers().firstValue("Content-Range").orElse(null))
		assertEquals(listOf<Byte>(2, 3, 4, 5), resp.body().toList())
	}

	@Test
	fun `suffix range returns last bytes`() {
		val resp = get(base, "/data.bin", "Range", "bytes=-10")
		assertEquals(206, resp.statusCode())
		assertEquals("bytes 90-99/100", resp.headers().firstValue("Content-Range").orElse(null))
		assertEquals(binData.copyOfRange(90, 100).toList(), resp.body().toList())
	}

	@Test
	fun `unsatisfiable range returns 416`() {
		val resp = get(base, "/data.bin", "Range", "bytes=200-300")
		assertEquals(416, resp.statusCode())
		assertEquals("bytes */100", resp.headers().firstValue("Content-Range").orElse(null))
	}

	@Test
	fun `head returns headers without body`() {
		val resp = client.send(
			HttpRequest.newBuilder(URI.create("$base/data.bin")).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
			HttpResponse.BodyHandlers.ofByteArray()
		)
		assertEquals(200, resp.statusCode())
		assertEquals("100", resp.headers().firstValue("Content-Length").orElse(null))
		assertEquals(0, resp.body().size)
	}

	@Test
	fun `if-modified-since returns 304`() {
		val future = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusDays(1))
		val resp = get(base, "/index.html", "If-Modified-Since", future)
		assertEquals(304, resp.statusCode())
		assertEquals(0, resp.body().size)
	}

	@Test
	fun `path traversal is rejected`() {
		val resp = get(base, "/%2e%2e/secret.txt")
		assertTrue(resp.statusCode() == 400 || resp.statusCode() == 404)
	}

	@Test
	fun `missing file returns 404`() {
		assertEquals(404, get(base, "/nope.txt").statusCode())
	}

	@Test
	fun `getRealPath and getResourcePaths expose doc root`() {
		val resp = get(base, "/probe")
		assertEquals(200, resp.statusCode())
		val body = String(resp.body())
		assertContains(body, "real=true")
		assertContains(body, "index.html")
		assertContains(body, "css/")
		assertContains(body, "data.bin")
	}

	@Test
	fun `classpath resources served via manual default servlet`() {
		val resp = get(base, "/cp/cpres/hello.txt")
		assertEquals(200, resp.statusCode())
		assertEquals("classpath-resource", String(resp.body()).trim())
	}

	@Test
	fun `directory listing when enabled`() {
		val resp = get(listingBase, "/css/")
		assertEquals(200, resp.statusCode())
		val body = String(resp.body())
		assertContains(body, "Index of /css/")
		assertContains(body, "style.css")
	}

	@Test
	fun `directory without welcome file returns 404 when listings disabled`() {
		assertEquals(404, get(base, "/css/").statusCode())
	}
}