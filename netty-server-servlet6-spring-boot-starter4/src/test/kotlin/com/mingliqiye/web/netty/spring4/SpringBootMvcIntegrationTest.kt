package com.mingliqiye.web.netty.spring4

import jakarta.servlet.http.HttpSession
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootApplication
open class TestApp

@RestController
class TestController {
	@GetMapping("/hello")
	fun hello(): String = "hello-mvc"

	@GetMapping("/big")
	fun big(): String = "x".repeat(5000)

	@GetMapping("/fail")
	fun fail(): String = throw RuntimeException("kaboom")

	@GetMapping("/sess")
	fun sess(session: HttpSession): String {
		val count = (session.getAttribute("count") as? Int ?: 0) + 1
		session.setAttribute("count", count)
		return "count=$count"
	}

	@PostMapping("/upload")
	fun upload(@RequestParam("file") file: MultipartFile): String = "size=${file.size}"
}

@SpringBootTest(
	classes = [TestApp::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["server.netty.queue-capacity=123", "server.netty.worker-thread-name=BootTestWorker"],
)
class SpringBootMvcIntegrationTest {
	@Autowired
	lateinit var environment: Environment

	@Autowired
	lateinit var properties: NettyServerProperties

	private val port: Int get() = environment.getProperty("local.server.port")!!.toInt()

	private val client: HttpClient = HttpClient.newBuilder().cookieHandler(CookieManager()).build()

	private fun get(path: String): HttpResponse<String> =
		client.send(
			HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(),
			HttpResponse.BodyHandlers.ofString()
		)

	@Test
	fun `dispatcher servlet serves MVC endpoint`() {
		val resp = get("/hello")
		assertEquals(200, resp.statusCode())
		assertEquals("hello-mvc", resp.body())
	}

	@Test
	fun `exception renders Boot error JSON via ERROR dispatch`() {
		val resp = get("/fail")
		assertEquals(500, resp.statusCode())
		assertContains(resp.body(), "\"status\":500")
		assertContains(resp.body(), "\"path\":\"/fail\"")
	}

	@Test
	fun `unknown path renders Boot 404 error JSON`() {
		val resp = get("/missing")
		assertEquals(404, resp.statusCode())
		assertContains(resp.body(), "\"status\":404")
	}

	@Test
	fun `http session works through Spring MVC`() {
		assertEquals("count=1", get("/sess").body())
		assertEquals("count=2", get("/sess").body())
	}

	@Test
	fun `multipart upload goes through per-servlet MultipartConfig`() {
		val boundary = "----BootBoundary"
		val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"f.txt\"\r\n" +
			"Content-Type: text/plain\r\n\r\n"
		val body = head.toByteArray() + "hello boot".toByteArray() + "\r\n--$boundary--\r\n".toByteArray()
		val resp = client.send(
			HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/upload"))
				.header("Content-Type", "multipart/form-data; boundary=$boundary")
				.POST(HttpRequest.BodyPublishers.ofByteArray(body))
				.build(),
			HttpResponse.BodyHandlers.ofString()
		)
		assertEquals(200, resp.statusCode())
		assertEquals("size=10", resp.body())
	}

	@Test
	fun `netty properties bind from server netty prefix`() {
		assertEquals(123, properties.queueCapacity)
		assertEquals("BootTestWorker", properties.workerThreadName)
		assertTrue(port > 0)
	}
}
