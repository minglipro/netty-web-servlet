package com.mingliqiye.web.netty.session.redis

import com.mingliqiye.web.netty.servlet.NettyServletServer
import com.mingliqiye.web.netty.servlet.config.ServletServerConfig
import com.mingliqiye.web.netty.servlet.session.StoredSession
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import redis.embedded.RedisServer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Redis 会话存储：存储级读写/TTL/删除 + 跨服务器实例（共享 Redis）会话恢复。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisSessionStoreIntegrationTest {
	private val redisPort = 16379
	private val httpPort = 18113
	private lateinit var redis: RedisServer
	private val client: HttpClient = HttpClient.newBuilder().build()
	private var server: NettyServletServer? = null

	@BeforeAll
	fun setup() {
		redis = RedisServer.newRedisServer().port(redisPort).setting("maxmemory 128M").build()
		redis.start()
	}

	@AfterAll
	fun teardown() {
		server?.stopServer()
		if (::redis.isInitialized) redis.stop()
	}

	private fun store() = RedisSessionStore("redis://127.0.0.1:$redisPort")

	private fun startServer(): NettyServletServer {
		val s = NettyServletServer(ServletServerConfig(port = httpPort, sessionStore = store()))
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
		val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$httpPort/sess")).GET()
		if (cookie != null) builder.header("Cookie", "JSESSIONID=$cookie")
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body()
	}

	private fun idOf(body: String): String = body.substringAfter("id=")

	@Test
	fun `store round trips snapshots and removes`() {
		store().use { s ->
			val snapshot = StoredSession("abc", 1L, 2L, 1800, mapOf("k" to "v", "n" to 42))
			s.save(snapshot)
			assertEquals(snapshot, s.load("abc"))
			assertContains(s.loadAll().map { it.id }, "abc")
			s.remove("abc")
			assertNull(s.load("abc"))
		}
	}

	@Test
	fun `session shared across server instances via redis`() {
		startServer()
		val first = get(null)
		assertContains(first, "count=1")
		val id = idOf(first)
		assertContains(get(id), "count=2;id=$id")
		server!!.stopServer()

		// 新服务器实例连接同一 Redis，恢复会话
		startServer()
		assertEquals("count=3;id=$id", get(id))
		val fresh = get(null)
		assertContains(fresh, "count=1")
		assertNotEquals(id, idOf(fresh))
		server!!.stopServer()
	}
}