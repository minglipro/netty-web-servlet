package com.mingliqiye.web.netty.servlet

import com.mingliqiye.web.netty.servlet.config.ServletServerConfig
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NettyServletServerIntegrationTest {
	private lateinit var server: NettyServletServer
	private lateinit var client: HttpClient
	private val base = "http://127.0.0.1:18090"
	private val filterOrder = Collections.synchronizedList(mutableListOf<String>())
	private val timedOut = AtomicBoolean(false)

	@BeforeAll
	fun setup() {
		server = NettyServletServer(ServletServerConfig(port = 18090))

		server.addServlet("echo", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.contentType = "text/plain;charset=UTF-8"
				resp.writer.write(
					"uri=${req.requestURI};servletPath=${req.servletPath};pathInfo=${req.pathInfo}" +
						";q=${req.getParameter("q")};cn=${req.getParameter("cn")}" +
						";multi=${req.getParameterValues("multi")?.joinToString(",")}" +
						";xTest=${req.getHeader("X-Test")};order=${req.getAttribute("order")}"
				)
			}
		}, "/app/*")

		server.addServlet("form", object : HttpServlet() {
			override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.writer.write("name=${req.getParameter("name")};tags=${req.getParameterValues("tag")?.joinToString(",")}")
			}
		}, "/form")

		server.addServlet("session", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				val session = req.getSession()
				val count = (session.getAttribute("count") as? Int ?: 0) + 1
				session.setAttribute("count", count)
				resp.writer.write("count=$count;new=${session.isNew}")
			}
		}, "/session")

		server.addServlet("async", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				val async = req.startAsync()
				async.setTimeout(5000)
				async.start {
					Thread.sleep(100)
					(async.response as HttpServletResponse).writer.write("async-done")
					async.complete()
				}
			}
		}, "/async")

		server.addServlet("asyncDispatch", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				req.startAsync().dispatch("/target")
			}
		}, "/async-dispatch")

		server.addServlet("target", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.writer.write("dispatched:${req.dispatcherType}")
			}
		}, "/target")

		server.addServlet("asyncTimeout", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				val async = req.startAsync()
				async.setTimeout(300)
				async.addListener(object : jakarta.servlet.AsyncListener {
					override fun onComplete(event: jakarta.servlet.AsyncEvent) {}
					override fun onTimeout(event: jakarta.servlet.AsyncEvent) {
						timedOut.set(true)
					}
					override fun onError(event: jakarta.servlet.AsyncEvent) {}
					override fun onStartAsync(event: jakarta.servlet.AsyncEvent) {}
				})
			}
		}, "/async-timeout")

		server.addServlet("upload", object : HttpServlet() {
			override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
				val parts = req.parts
				val file = parts.first { it.submittedFileName != null }
				val content = file.inputStream.readBytes().toString(Charsets.UTF_8)
				resp.writer.write(
					"field1=${req.getParameter("field1")};name=${file.name}" +
						";file=${file.submittedFileName};size=${file.size};content=$content" +
						";type=${file.contentType}"
				)
			}
		}, "/upload")

		server.addServlet("redirect", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.sendRedirect("/app/landing")
			}
		}, "/redirect")

		server.addServlet("error", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.sendError(418, "teapot")
			}
		}, "/error")

		server.addServlet("forward", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				req.getRequestDispatcher("/target").forward(req, resp)
			}
		}, "/forward")

		server.addServlet("include", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.writer.write("before:")
				req.getRequestDispatcher("/target").include(req, resp)
				resp.writer.write(":after")
			}
		}, "/include")

		server.addServlet("boom", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				throw RuntimeException("boom")
			}
		}, "/boom")

		server.addFilter("filterA", object : Filter {
			override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
				filterOrder.add("A")
				request.setAttribute("order", "A")
				(response as HttpServletResponse).setHeader("X-Filter-A", "yes")
				chain.doFilter(request, response)
			}
		}, "/app/*")

		server.addFilter("filterB", object : Filter {
			override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
				filterOrder.add("B")
				request.setAttribute("order", "${request.getAttribute("order")}B")
				chain.doFilter(request, response)
			}
		}, "/app/*")

		server.startServer()
		client = HttpClient.newBuilder().cookieHandler(CookieManager()).build()
	}

	@AfterAll
	fun teardown() {
		server.stopServer()
	}

	private fun get(path: String, vararg headers: String): HttpResponse<String> {
		val builder = HttpRequest.newBuilder(URI.create("$base$path")).GET()
		for (i in headers.indices step 2) builder.header(headers[i], headers[i + 1])
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
	}

	private fun post(path: String, body: String, contentType: String): HttpResponse<String> =
		client.send(
			HttpRequest.newBuilder(URI.create("$base$path"))
				.header("Content-Type", contentType)
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build(),
			HttpResponse.BodyHandlers.ofString(),
		)

	@Test
	fun `GET routing with query params, path info and headers`() {
		val resp = get("/app/sub/path?q=hello&cn=%E4%B8%AD%E6%96%87&multi=a&multi=b", "X-Test", "abc")
		assertEquals(200, resp.statusCode())
		assertEquals(
			"uri=/app/sub/path;servletPath=/app;pathInfo=/sub/path;q=hello;cn=中文;multi=a,b;xTest=abc;order=AB",
			resp.body(),
		)
		assertEquals("yes", resp.headers().firstValue("X-Filter-A").orElse(null))
	}

	@Test
	fun `POST form parameters are parsed`() {
		val resp = post("/form", "name=zhang&tag=x&tag=y", "application/x-www-form-urlencoded")
		assertEquals(200, resp.statusCode())
		assertEquals("name=zhang;tags=x,y", resp.body())
	}

	@Test
	fun `filter chain runs in registration order`() {
		filterOrder.clear()
		get("/app/anything")
		assertEquals(listOf("A", "B"), filterOrder.toList())
	}

	@Test
	fun `session persists across requests via JSESSIONID cookie`() {
		val first = get("/session")
		assertEquals(200, first.statusCode())
		assertEquals("count=1;new=true", first.body())
		assertTrue(first.headers().allValues("Set-Cookie").any { it.startsWith("JSESSIONID=") })

		val second = get("/session")
		assertEquals("count=2;new=false", second.body())
	}

	@Test
	fun `async servlet completes from background task`() {
		val resp = get("/async")
		assertEquals(200, resp.statusCode())
		assertEquals("async-done", resp.body())
	}

	@Test
	fun `async dispatch re-enters container with ASYNC dispatcher type`() {
		val resp = get("/async-dispatch")
		assertEquals(200, resp.statusCode())
		assertEquals("dispatched:ASYNC", resp.body())
	}

	@Test
	fun `async timeout fires listener and returns 500`() {
		timedOut.set(false)
		val resp = get("/async-timeout")
		assertEquals(500, resp.statusCode())
		assertEquals("Async timeout", resp.body())
		assertTrue(timedOut.get())
	}

	@Test
	fun `multipart upload exposes parts and form fields`() {
		val boundary = "----QoderBoundary"
		val body = buildString {
			append("--$boundary\r\n")
			append("Content-Disposition: form-data; name=\"field1\"\r\n\r\n")
			append("value1\r\n")
			append("--$boundary\r\n")
			append("Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n")
			append("Content-Type: text/plain\r\n\r\n")
			append("hello multipart\r\n")
			append("--$boundary--\r\n")
		}
		val resp = post("/upload", body, "multipart/form-data; boundary=$boundary")
		assertEquals(200, resp.statusCode())
		assertEquals(
			"field1=value1;name=file;file=test.txt;size=15;content=hello multipart;type=text/plain",
			resp.body(),
		)
	}

	@Test
	fun `sendRedirect sets 302 and Location`() {
		val resp = get("/redirect")
		assertEquals(302, resp.statusCode())
		assertEquals("/app/landing", resp.headers().firstValue("Location").orElse(null))
	}

	@Test
	fun `sendError sets status and message body`() {
		val resp = get("/error")
		assertEquals(418, resp.statusCode())
		assertEquals("teapot", resp.body())
	}

	@Test
	fun `forward replaces output and sets FORWARD dispatcher type`() {
		val resp = get("/forward")
		assertEquals(200, resp.statusCode())
		assertEquals("dispatched:FORWARD", resp.body())
	}

	@Test
	fun `include appends target output`() {
		val resp = get("/include")
		assertEquals(200, resp.statusCode())
		assertEquals("before:dispatched:INCLUDE:after", resp.body())
	}

	@Test
	fun `unmapped path returns 404`() {
		assertEquals(404, get("/no-such-path").statusCode())
	}

	@Test
	fun `servlet exception returns 500`() {
		assertEquals(500, get("/boom").statusCode())
	}

	@Test
	fun `keep-alive connection serves many sequential requests`() {
		repeat(20) { i ->
			val resp = get("/app/seq?q=$i")
			assertEquals(200, resp.statusCode())
			assertContains(resp.body(), "q=$i")
		}
	}
}