package com.mingliqiye.web.netty.servlet

import com.mingliqiye.web.netty.servlet.config.ServletServerConfig
import com.mingliqiye.web.netty.servlet.session.DiskSessionStore
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 磁盘会话存储端到端：停机落盘 → 新进程（同文件）启动恢复，会话 ID 与属性跨重启保留。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionDiskPersistenceIntegrationTest {
	private val port = 18112
	private val storeFile: File = File.createTempFile("netty-session-disk", ".bin").apply { deleteOnExit() }
	private val client: HttpClient = HttpClient.newBuilder().build()
	private var server: NettyServletServer? = null

	private fun startServer(): NettyServletServer {
		val s = NettyServletServer(ServletServerConfig(port = port, sessionStore = DiskSessionStore(storeFile)))
		s.addServlet("sess", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				val session = req.session
				val count = (session.getAttribute("count") as? Int ?: 0) + 1
				session.setAttribute("count", count)
				resp.contentType = "text/plain; charset=UTF-8"
				resp.writer.write("count=$count;id=${session.id}")
			}
		}, "/sess")
		s.startServer()
		server = s
		return s
	}

	private fun get(cookie: String?): String {
		val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/sess")).GET()
		if (cookie != null) builder.header("Cookie", "JSESSIONID=$cookie")
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body()
	}

	private fun idOf(body: String): String = body.substringAfter("id=")

	@AfterAll
	fun teardown() {
		server?.stopServer()
		storeFile.delete()
	}

	@Test
	fun `session survives restart via disk store`() {
		startServer()
		val first = get(null)
		assertContains(first, "count=1")
		val id = idOf(first)
		assertTrue(id.isNotEmpty())
		assertContains(get(id), "count=2;id=$id")
		server!!.stopServer()
		assertTrue(storeFile.length() > 0, "disk store should have been flushed on shutdown")

		// 新服务器实例读取同一文件，恢复会话
		startServer()
		assertEquals("count=3;id=$id", get(id))
		val fresh = get(null)
		assertContains(fresh, "count=1")
		assertNotEquals(id, idOf(fresh))
		server!!.stopServer()
	}

	@Test
	fun `invalidated session is not restored`() {
		val s = NettyServletServer(ServletServerConfig(port = port, sessionStore = DiskSessionStore(storeFile)))
		s.addServlet("invalidate", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				val session = req.session
				session.setAttribute("temp", "x")
				val id = session.id
				session.invalidate()
				resp.writer.write("id=$id")
			}
		}, "/invalidate")
		s.startServer()
		server = s
		val id = idOf(client.send(
			HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/invalidate")).GET().build(),
			HttpResponse.BodyHandlers.ofString(),
		).body())
		s.stopServer()

		startServer()
		// 已失效会话不应被恢复：携带旧 ID 访问会创建全新会话
		val body = get(id)
		assertContains(body, "count=1")
		assertNotEquals(id, idOf(body))
		server!!.stopServer()
	}
}