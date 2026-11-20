package com.mingliqiye.web.netty.servlet

import com.mingliqiye.web.netty.servlet.config.ServletServerConfig
import jakarta.servlet.SessionTrackingMode
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionUrlRewriteIntegrationTest {
	private lateinit var server: NettyServletServer
	private lateinit var urlOnlyServer: NettyServletServer
	private val client = HttpClient.newHttpClient()
	private val base = "http://127.0.0.1:18102"
	private val urlOnlyBase = "http://127.0.0.1:18103"

	private fun registerServlets(server: NettyServletServer) {
		server.addServlet("start", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				req.getSession().setAttribute("user", "alice")
				resp.contentType = "text/plain"
				resp.writer.write(
					"encoded=${resp.encodeURL("/next")}|redirect=${resp.encodeRedirectURL("/redir?x=1")}" +
						"|fromCookie=${req.isRequestedSessionIdFromCookie}|fromURL=${req.isRequestedSessionIdFromURL}"
				)
			}
		}, "/start")
		server.addServlet("next", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				val session = req.getSession()
				val count = (session.getAttribute("count") as? Int ?: 0) + 1
				session.setAttribute("count", count)
				resp.contentType = "text/plain"
				resp.writer.write(
					"count=$count|uri=${req.requestURI}|sid=${req.requestedSessionId}" +
						"|fromCookie=${req.isRequestedSessionIdFromCookie}|fromURL=${req.isRequestedSessionIdFromURL}" +
						"|valid=${req.isRequestedSessionIdValid}"
				)
			}
		}, "/next")
	}

	@BeforeAll
	fun setup() {
		server = NettyServletServer(ServletServerConfig(port = 18102))
		registerServlets(server)
		server.startServer()

		urlOnlyServer = NettyServletServer(ServletServerConfig(port = 18103))
		registerServlets(urlOnlyServer)
		urlOnlyServer.container.context.setSessionTrackingModes(setOf(SessionTrackingMode.URL))
		urlOnlyServer.startServer()
	}

	@AfterAll
	fun teardown() {
		if (::server.isInitialized) server.stopServer()
		if (::urlOnlyServer.isInitialized) urlOnlyServer.stopServer()
	}

	private fun get(base: String, path: String, vararg headers: String): HttpResponse<String> {
		val builder = HttpRequest.newBuilder(URI.create("$base$path")).GET()
		headers.toList().chunked(2).forEach { (k, v) -> builder.header(k, v) }
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
	}

	private fun sessionId(encodedUrl: String): String =
		Regex(";jsessionid=([0-9a-f]+)").find(encodedUrl)!!.groupValues[1]

	private fun field(body: String, name: String): String =
		body.split("|").first { it.startsWith("$name=") }.substringAfter("=")

	@Test
	fun `url rewriting carries session without cookies`() {
		val start = get(base, "/start")
		assertEquals(200, start.statusCode())
		val encoded = field(start.body(), "encoded")
		assertContains(encoded, ";jsessionid=")
		assertTrue(start.headers().allValues("Set-Cookie").any { it.startsWith("JSESSIONID=") })
		val sid = sessionId(encoded)

		val first = get(base, "/next;jsessionid=$sid")
		assertEquals(200, first.statusCode())
		assertEquals("1", field(first.body(), "count"))
		assertEquals("/next", field(first.body(), "uri"))
		assertEquals(sid, field(first.body(), "sid"))
		assertEquals("true", field(first.body(), "fromURL"))
		assertEquals("false", field(first.body(), "fromCookie"))

		val second = get(base, "/next;jsessionid=$sid")
		assertEquals("2", field(second.body(), "count"))
	}

	@Test
	fun `path parameters are stripped from routing and requestURI`() {
		val start = get(base, "/start")
		val sid = sessionId(field(start.body(), "encoded"))
		val resp = get(base, "/next;foo=bar;jsessionid=$sid")
		assertEquals(200, resp.statusCode())
		assertEquals("/next", field(resp.body(), "uri"))
		assertEquals(sid, field(resp.body(), "sid"))
	}

	@Test
	fun `encodeURL skips rewriting when session cookie present`() {
		val start = get(base, "/start")
		val sid = sessionId(field(start.body(), "encoded"))
		val resp = get(base, "/start", "Cookie", "JSESSIONID=$sid")
		assertEquals("/next", field(resp.body(), "encoded"))
		assertEquals("/redir?x=1", field(resp.body(), "redirect"))
		assertEquals("true", field(resp.body(), "fromCookie"))
		assertEquals("false", field(resp.body(), "fromURL"))
	}

	@Test
	fun `encodeRedirectURL keeps query string after jsessionid`() {
		val start = get(base, "/start")
		val redirect = field(start.body(), "redirect")
		assertTrue(Regex("^/redir;jsessionid=[0-9a-f]+\\?x=1$").matches(redirect))
	}

	@Test
	fun `unknown jsessionid falls back to a new session`() {
		val resp = get(base, "/next;jsessionid=deadbeef")
		assertEquals(200, resp.statusCode())
		assertEquals("1", field(resp.body(), "count"))
		assertEquals("false", field(resp.body(), "valid"))
	}

	@Test
	fun `url only mode never sets cookie and ignores incoming cookies`() {
		val start = get(urlOnlyBase, "/start")
		assertNull(start.headers().firstValue("Set-Cookie").orElse(null))
		val encoded = field(start.body(), "encoded")
		assertContains(encoded, ";jsessionid=")
		val sid = sessionId(encoded)

		val viaUrl = get(urlOnlyBase, "/next;jsessionid=$sid")
		assertEquals("1", field(viaUrl.body(), "count"))
		assertEquals("true", field(viaUrl.body(), "fromURL"))

		val viaCookie = get(urlOnlyBase, "/next", "Cookie", "JSESSIONID=$sid")
		assertEquals("1", field(viaCookie.body(), "count"))
		assertEquals("false", field(viaCookie.body(), "fromCookie"))
		assertFalse(viaCookie.headers().firstValue("Set-Cookie").isPresent)
	}
}