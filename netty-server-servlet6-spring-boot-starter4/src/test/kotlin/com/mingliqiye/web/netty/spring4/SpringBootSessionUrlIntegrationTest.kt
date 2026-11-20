package com.mingliqiye.web.netty.spring4

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootApplication
open class UrlSessionTestApp

@RestController
class UrlSessionController {
	@GetMapping("/enc")
	fun enc(req: HttpServletRequest, resp: HttpServletResponse): String {
		req.session.setAttribute("user", "alice")
		return resp.encodeURL("/next")
	}

	@GetMapping("/next")
	fun next(req: HttpServletRequest): String {
		val count = (req.session.getAttribute("count") as? Int ?: 0) + 1
		req.session.setAttribute("count", count)
		return "count=$count|fromURL=${req.isRequestedSessionIdFromURL}"
	}
}

@SpringBootTest(
	classes = [UrlSessionTestApp::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["server.servlet.session.tracking-modes=url"],
)
class SpringBootSessionUrlIntegrationTest {
	@org.springframework.beans.factory.annotation.Autowired
	lateinit var environment: Environment

	private val port: Int get() = environment.getProperty("local.server.port")!!.toInt()
	private val client: HttpClient = HttpClient.newHttpClient()

	private fun get(path: String): HttpResponse<String> =
		client.send(
			HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(),
			HttpResponse.BodyHandlers.ofString()
		)

	@Test
	fun `boot tracking-modes url enables jsessionid rewriting without cookies`() {
		val enc = get("/enc")
		assertEquals(200, enc.statusCode())
		assertNull(enc.headers().firstValue("Set-Cookie").orElse(null))
		assertContains(enc.body(), "/next;jsessionid=")

		val next = get(enc.body())
		assertEquals(200, next.statusCode())
		assertEquals("count=1|fromURL=true", next.body())

		val again = get(enc.body())
		assertEquals("count=2|fromURL=true", again.body())
	}
}