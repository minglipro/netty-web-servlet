package com.mingliqiye.web.netty.spring4

import org.springframework.boot.web.servlet.ServletContextInitializer
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.springframework.boot.web.error.ErrorPage
import org.springframework.boot.web.server.GracefulShutdownResult
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NettyServletWebServerFactoryTest {
	private fun get(port: Int, path: String): HttpResponse<String> =
		HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(),
			HttpResponse.BodyHandlers.ofString()
		)

	@Test
	fun `factory serves registered servlets on random port`() {
		val factory = NettyServletWebServerFactory()
		factory.port = 0
		val initializer = ServletContextInitializer { context ->
			context.addServlet("hello", object : HttpServlet() {
				override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
					resp.writer.write("hello-factory")
				}
			}).addMapping("/hello")
		}
		val webServer = factory.getWebServer(initializer)
		webServer.start()
		try {
			assertTrue(webServer.port > 0)
			assertEquals("hello-factory", get(webServer.port, "/hello").body())
			assertEquals(404, get(webServer.port, "/missing").statusCode())
		} finally {
			webServer.stop()
		}
	}

	@Test
	fun `factory error pages route sendError through ERROR dispatch`() {
		val factory = NettyServletWebServerFactory()
		factory.port = 0
		factory.addErrorPages(ErrorPage("/error"))
		val initializer = ServletContextInitializer { context ->
			context.addServlet("boom", object : HttpServlet() {
				override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
					resp.sendError(503, "down")
				}
			}).addMapping("/boom")
			context.addServlet("error", object : HttpServlet() {
				override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
					resp.writer.write("error-page:${req.getAttribute("jakarta.servlet.error.status_code")}")
				}
			}).addMapping("/error")
		}
		val webServer = factory.getWebServer(initializer)
		webServer.start()
		try {
			val resp = get(webServer.port, "/boom")
			assertEquals(503, resp.statusCode())
			assertEquals("error-page:503", resp.body())
		} finally {
			webServer.stop()
		}
	}

	@Test
	fun `graceful shutdown drains and reports idle`() {
		val factory = NettyServletWebServerFactory()
		factory.port = 0
		val webServer = factory.getWebServer(ServletContextInitializer { context ->
			context.addServlet("ping", object : HttpServlet() {
				override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
					resp.writer.write("pong")
				}
			}).addMapping("/ping")
		})
		webServer.start()
		assertEquals("pong", get(webServer.port, "/ping").body())
		val latch = CountDownLatch(1)
		val result = AtomicReference<GracefulShutdownResult>()
		webServer.shutDownGracefully {
			result.set(it)
			latch.countDown()
		}
		assertTrue(latch.await(15, TimeUnit.SECONDS))
		assertEquals(GracefulShutdownResult.IDLE, result.get())
	}
}