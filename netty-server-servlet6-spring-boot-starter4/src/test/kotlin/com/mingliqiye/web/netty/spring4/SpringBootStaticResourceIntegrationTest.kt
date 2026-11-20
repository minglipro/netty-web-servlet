package com.mingliqiye.web.netty.spring4

import jakarta.servlet.ServletContext
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootApplication
open class StaticResourceTestApp {
	@Bean
	open fun docRootCustomizer(): WebServerFactoryCustomizer<NettyServletWebServerFactory> =
		WebServerFactoryCustomizer { factory ->
			val dir = Files.createTempDirectory("boot-docroot")
			dir.toFile().deleteOnExit()
			Files.writeString(dir.resolve("rooted.txt"), "doc-root-ok")
			factory.setDocumentRoot(dir.toFile())
		}
}

@RestController
class StaticResourceController(private val servletContext: ServletContext) {
	@GetMapping("/realpath")
	fun realpath(): String = "real=${servletContext.getRealPath("/rooted.txt") != null}"
}

@SpringBootTest(
	classes = [StaticResourceTestApp::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class SpringBootStaticResourceIntegrationTest {
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
	fun `classpath static resource served through Spring resource handler`() {
		val resp = get("/boot-static.txt")
		assertEquals(200, resp.statusCode())
		assertEquals("boot-static-ok", resp.body().trim())
	}

	@Test
	fun `document root enables getRealPath`() {
		val resp = get("/realpath")
		assertEquals(200, resp.statusCode())
		assertTrue(resp.body().contains("real=true"))
	}
}