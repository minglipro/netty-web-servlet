package com.mingliqiye.web.netty.jsp

import com.mingliqiye.web.netty.servlet.NettyServletServer
import com.mingliqiye.web.netty.servlet.config.ServletServerConfig
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Jasper JSP 端到端：真实编译（ECJ）+ 执行 scriptlet/表达式/EL，经 *.jsp 自动映射与 addJspFile 两条路径。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JspIntegrationTest {
	private lateinit var server: NettyServletServer
	private lateinit var docRoot: Path
	private val client = HttpClient.newHttpClient()
	private val base = "http://127.0.0.1:18114"

	@BeforeAll
	fun setup() {
		docRoot = Files.createTempDirectory("netty-jsp-docroot")
		Files.writeString(
			docRoot.resolve("hello.jsp"),
			"<%@ page contentType=\"text/html; charset=UTF-8\" %>" +
				"<% String greeting = \"Hello\"; int a = 2; int b = 3; %>" +
				"<%= greeting %>-JSP|sum=<%= a + b %>|el=\${1 + 2}|who=\${param.who}",
		)
		Files.writeString(
			docRoot.resolve("expr.jsp"),
			"<%@ page contentType=\"text/plain\" %>" +
				"method=\${pageContext.request.method}|ctx=\${pageContext.servletContext.serverInfo}|arith=\${10 * 5}",
		)
		Files.writeString(docRoot.resolve("fixed.jsp"), "<%@ page contentType=\"text/plain\" %>fixed-jsp-ok")

		server = NettyServletServer(ServletServerConfig(port = 18114, docRoot = docRoot.toString()))
		server.container.context.addJspFile("fixed", "/fixed.jsp")
		server.startServer()
	}

	@AfterAll
	fun teardown() {
		if (::server.isInitialized) server.stopServer()
		if (::docRoot.isInitialized) docRoot.toFile().deleteRecursively()
	}

	private fun get(path: String): HttpResponse<String> =
		client.send(HttpRequest.newBuilder(URI.create("$base$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

	@Test
	fun `scriptlet expression and EL are compiled and executed`() {
		val resp = get("/hello.jsp?who=Netty")
		assertEquals(200, resp.statusCode())
		assertEquals("Hello-JSP|sum=5|el=3|who=Netty", resp.body())
		assertTrue(resp.headers().firstValue("Content-Type").orElse("").startsWith("text/html"))
	}

	@Test
	fun `EL implicit objects resolve request and servlet context`() {
		val resp = get("/expr.jsp")
		assertEquals(200, resp.statusCode())
		assertEquals("method=GET|ctx=NettyServlet/1.0|arith=50", resp.body())
	}

	@Test
	fun `addJspFile registers named servlet with jspFile init parameter`() {
		val registration = server.container.context.getServletRegistration("fixed")
		assertTrue(registration != null)
		assertEquals("/fixed.jsp", registration!!.getInitParameter("jspFile"))
		assertContains(registration.mappings, "/fixed.jsp")
		val resp = get("/fixed.jsp")
		assertEquals(200, resp.statusCode())
		assertEquals("fixed-jsp-ok", resp.body())
	}

	@Test
	fun `compiled jsp is reused across requests`() {
		repeat(3) {
			val resp = get("/hello.jsp?who=Again")
			assertEquals(200, resp.statusCode())
			assertEquals("Hello-JSP|sum=5|el=3|who=Again", resp.body())
		}
	}

	@Test
	fun `missing jsp returns 404`() {
		assertEquals(404, get("/nope.jsp").statusCode())
	}
}