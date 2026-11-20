package com.mingliqiye.web.netty.servlet

import com.mingliqiye.web.netty.servlet.config.ServletServerConfig
import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.MultipartConfigElement
import jakarta.servlet.ServletContextAttributeEvent
import jakarta.servlet.ServletContextAttributeListener
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletRequestAttributeEvent
import jakarta.servlet.ServletRequestAttributeListener
import jakarta.servlet.ServletRequestEvent
import jakarta.servlet.ServletRequestListener
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import jakarta.servlet.http.HttpSessionAttributeListener
import jakarta.servlet.http.HttpSessionBindingEvent
import jakarta.servlet.http.HttpSessionEvent
import jakarta.servlet.http.HttpSessionIdListener
import jakarta.servlet.http.HttpSessionListener
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
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServletSpecIntegrationTest {
	private lateinit var server: NettyServletServer
	private lateinit var client: HttpClient
	private val base = "http://127.0.0.1:18096"

	private val reqFilterCount = AtomicInteger()
	private val errFilterCount = AtomicInteger()
	private val fwdFilterCount = AtomicInteger()
	private val requestInitialized = AtomicInteger()
	private val requestDestroyed = AtomicInteger()
	private val sessionCreated = AtomicInteger()
	private val sessionDestroyed = AtomicInteger()
	private val sessionIdChanged = AtomicInteger()
	private val requestAttrAdded = AtomicInteger()
	private val sessionAttrAdded = AtomicInteger()
	private val contextAttrAdded = AtomicInteger()
	private val initOrder = Collections.synchronizedList(mutableListOf<String>())
	private var lastOldSessionId: String? = null

	@BeforeAll
	fun setup() {
		server = NettyServletServer(ServletServerConfig(port = 18096))
		val context = server.container.context

		context.addListener(object : ServletRequestListener {
			override fun requestInitialized(sre: ServletRequestEvent) {
				requestInitialized.incrementAndGet()
			}

			override fun requestDestroyed(sre: ServletRequestEvent) {
				requestDestroyed.incrementAndGet()
			}
		})
		context.addListener(object : HttpSessionListener {
			override fun sessionCreated(se: HttpSessionEvent) {
				sessionCreated.incrementAndGet()
			}

			override fun sessionDestroyed(se: HttpSessionEvent) {
				sessionDestroyed.incrementAndGet()
			}
		})
		context.addListener(object : HttpSessionIdListener {
			override fun sessionIdChanged(se: HttpSessionEvent, oldSessionId: String) {
				sessionIdChanged.incrementAndGet()
				lastOldSessionId = oldSessionId
			}
		})
		context.addListener(object : ServletRequestAttributeListener {
			override fun attributeAdded(event: ServletRequestAttributeEvent) {
				requestAttrAdded.incrementAndGet()
			}

			override fun attributeRemoved(event: ServletRequestAttributeEvent) {}
			override fun attributeReplaced(event: ServletRequestAttributeEvent) {}
		})
		context.addListener(object : HttpSessionAttributeListener {
			override fun attributeAdded(event: HttpSessionBindingEvent) {
				sessionAttrAdded.incrementAndGet()
			}

			override fun attributeRemoved(event: HttpSessionBindingEvent) {}
			override fun attributeReplaced(event: HttpSessionBindingEvent) {}
		})
		context.addListener(object : ServletContextAttributeListener {
			override fun attributeAdded(event: ServletContextAttributeEvent) {
				contextAttrAdded.incrementAndGet()
			}

			override fun attributeRemoved(event: ServletContextAttributeEvent) {}
			override fun attributeReplaced(event: ServletContextAttributeEvent) {}
		})
		context.setAttribute("setup-attr", "v")

		server.addFilter("reqOnly", countingFilter(reqFilterCount), "/*")
		server.addFilter("errOnly", countingFilter(errFilterCount)).also {
			it.addMappingForUrlPatterns(EnumSet.of(DispatcherType.ERROR), false, "/*")
		}
		server.addFilter("fwdOnly", countingFilter(fwdFilterCount)).also {
			it.addMappingForUrlPatterns(EnumSet.of(DispatcherType.FORWARD), false, "/*")
		}

		server.addServlet("ok", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				req.setAttribute("ok-attr", "v")
				resp.writer.write("ok:${req.dispatcherType}")
			}
		}, "/ok")

		server.addServlet("boom", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.sendError(500, "boom")
			}
		}, "/boom")

		server.addServlet("err", object : HttpServlet() {
			override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.writer.write(
					"err:${req.dispatcherType}:code=${req.getAttribute("jakarta.servlet.error.status_code")}" +
						":uri=${req.getAttribute("jakarta.servlet.error.request_uri")}"
				)
			}
		}, "/err")
		server.container.addErrorPage(500, "/err")

		server.addServlet("fwd", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				req.getRequestDispatcher("/target").forward(req, resp)
			}
		}, "/fwd")

		server.addServlet("target", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.writer.write("target:${req.dispatcherType}")
			}
		}, "/target")

		server.addServlet("map", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				val m = req.httpServletMapping
				resp.writer.write("${m.mappingMatch}:${m.pattern}:${m.servletName}:${m.matchValue}")
			}
		}, "/map/*")

		server.addServlet("exact", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.writer.write("${req.httpServletMapping.mappingMatch}")
			}
		}, "/exact")

		val limited = server.addServlet("limited", object : HttpServlet() {
			override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
				val file = req.parts.first { it.submittedFileName != null }
				resp.writer.write("size=${file.size}")
			}
		}, "/limited")
		limited.setMultipartConfig(MultipartConfigElement(null, 1024, 8192, 0))

		val noasync = server.addServlet("noasync", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				val ise = try {
					req.startAsync()
					false
				} catch (e: IllegalStateException) {
					true
				}
				resp.writer.write("supported=${req.isAsyncSupported};ise=$ise")
			}
		}, "/noasync")
		noasync.setAsyncSupported(false)

		server.addServlet("newsess", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				val session = req.getSession()
				session.setAttribute("k", "v")
				resp.writer.write("new=${session.isNew};max=${session.maxInactiveInterval}")
			}
		}, "/newsess")

		server.addServlet("invalidate", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				req.getSession().invalidate()
				resp.writer.write("invalidated")
			}
		}, "/invalidate")

		server.addServlet("changeid", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				val session = req.getSession()
				val old = session.id
				val new = req.changeSessionId()
				session.setAttribute("kept", "yes")
				resp.writer.write("changed=${old != new};kept=${session.getAttribute("kept")}")
			}
		}, "/changeid")

		server.addServlet("late", object : HttpServlet() {
			override fun init() {
				initOrder.add("late")
			}
		}, "/late").setLoadOnStartup(2)
		server.addServlet("early", object : HttpServlet() {
			override fun init() {
				initOrder.add("early")
			}
		}, "/early").setLoadOnStartup(1)
		server.addServlet("lazy", object : HttpServlet() {
			override fun init() {
				initOrder.add("lazy")
			}
		}, "/lazy")

		server.startServer()
		client = HttpClient.newBuilder().cookieHandler(CookieManager()).build()
	}

	@AfterAll
	fun teardown() {
		server.stopServer()
	}

	private fun countingFilter(counter: AtomicInteger) = object : Filter {
		override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
			counter.incrementAndGet()
			chain.doFilter(request, response)
		}
	}

	private fun get(path: String): HttpResponse<String> =
		client.send(HttpRequest.newBuilder(URI.create("$base$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

	private fun postMultipart(path: String, fileBytes: ByteArray): HttpResponse<String> {
		val boundary = "----SpecBoundary"
		val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"f.bin\"\r\n" +
			"Content-Type: application/octet-stream\r\n\r\n"
		val tail = "\r\n--$boundary--\r\n"
		val body = head.toByteArray() + fileBytes + tail.toByteArray()
		val request = HttpRequest.newBuilder(URI.create("$base$path"))
			.header("Content-Type", "multipart/form-data; boundary=$boundary")
			.POST(HttpRequest.BodyPublishers.ofByteArray(body))
			.build()
		return client.send(request, HttpResponse.BodyHandlers.ofString())
	}

	@Test
	fun `error dispatch runs ERROR filters only and exposes error attributes`() {
		reqFilterCount.set(0)
		errFilterCount.set(0)
		val resp = get("/boom")
		assertEquals(500, resp.statusCode())
		assertEquals("err:ERROR:code=500:uri=/boom", resp.body())
		assertEquals(1, reqFilterCount.get())
		assertEquals(1, errFilterCount.get())
	}

	@Test
	fun `request dispatch skips ERROR and FORWARD filters`() {
		reqFilterCount.set(0)
		errFilterCount.set(0)
		fwdFilterCount.set(0)
		val resp = get("/ok")
		assertEquals(200, resp.statusCode())
		assertEquals("ok:REQUEST", resp.body())
		assertEquals(1, reqFilterCount.get())
		assertEquals(0, errFilterCount.get())
		assertEquals(0, fwdFilterCount.get())
	}

	@Test
	fun `forward dispatch runs FORWARD filters`() {
		fwdFilterCount.set(0)
		val resp = get("/fwd")
		assertEquals(200, resp.statusCode())
		assertEquals("target:FORWARD", resp.body())
		assertEquals(1, fwdFilterCount.get())
	}

	@Test
	fun `request and attribute listeners fire`() {
		val initBefore = requestInitialized.get()
		val destroyBefore = requestDestroyed.get()
		val attrBefore = requestAttrAdded.get()
		get("/ok")
		assertEquals(initBefore + 1, requestInitialized.get())
		assertEquals(destroyBefore + 1, requestDestroyed.get())
		assertTrue(requestAttrAdded.get() > attrBefore)
		assertTrue(contextAttrAdded.get() >= 1)
	}

	@Test
	fun `session lifecycle listeners fire on create invalidate and changeId`() {
		val createdBefore = sessionCreated.get()
		val destroyedBefore = sessionDestroyed.get()
		val fresh = HttpClient.newBuilder().cookieHandler(CookieManager()).build()
		fun freshGet(path: String): HttpResponse<String> =
			fresh.send(HttpRequest.newBuilder(URI.create("$base$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

		assertContains(freshGet("/newsess").body(), "new=true")
		assertEquals(createdBefore + 1, sessionCreated.get())
		assertTrue(sessionAttrAdded.get() >= 1)

		val changed = freshGet("/changeid")
		assertContains(changed.body(), "changed=true")
		assertContains(changed.body(), "kept=yes")
		assertEquals(1, sessionIdChanged.get())
		assertNotEquals(null, lastOldSessionId)

		freshGet("/invalidate")
		assertEquals(destroyedBefore + 1, sessionDestroyed.get())
		assertContains(freshGet("/newsess").body(), "new=true")
	}

	@Test
	fun `http servlet mapping reports match type pattern and name`() {
		assertEquals("PATH:/map/*:map:a/b", get("/map/a/b").body())
		assertEquals("EXACT", get("/exact").body())
	}

	@Test
	fun `per-servlet multipart config enforces its own file size limit`() {
		val small = postMultipart("/limited", ByteArray(100))
		assertEquals(200, small.statusCode())
		assertContains(small.body(), "size=100")
		val big = postMultipart("/limited", ByteArray(2048))
		assertEquals(500, big.statusCode())
	}

	@Test
	fun `asyncSupported false is enforced`() {
		assertEquals("supported=false;ise=true", get("/noasync").body())
	}

	@Test
	fun `loadOnStartup ordering is honored`() {
		assertEquals(listOf("early", "late", "lazy"), initOrder.toList())
	}

	@Test
	fun `session timeout setter uses minutes`() {
		val context = server.container.context
		val original = context.sessionTimeout
		context.sessionTimeout = 7
		assertEquals(7, context.sessionTimeout)
		val fresh = HttpClient.newBuilder().cookieHandler(CookieManager()).build()
		val resp = fresh.send(
			HttpRequest.newBuilder(URI.create("$base/newsess")).GET().build(),
			HttpResponse.BodyHandlers.ofString()
		)
		assertContains(resp.body(), "max=420")
		context.sessionTimeout = original
	}

	@Test
	fun `random port binds and serves`() {
		val random = NettyServletServer(ServletServerConfig(port = 0))
		random.addServlet("ping", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.writer.write("pong")
			}
		}, "/ping")
		random.startServer()
		try {
			assertTrue(random.port > 0)
			val resp = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://127.0.0.1:${random.port}/ping")).GET().build(),
				HttpResponse.BodyHandlers.ofString()
			)
			assertEquals("pong", resp.body())
		} finally {
			random.stopServer()
		}
	}
}