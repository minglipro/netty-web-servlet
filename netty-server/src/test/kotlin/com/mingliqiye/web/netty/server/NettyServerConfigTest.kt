package com.mingliqiye.web.netty.server

import com.mingliqiye.web.netty.server.config.NettyServerConfig
import com.mingliqiye.web.netty.server.io.NettyIO
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NettyServerConfigTest {
	@Test
	fun `config exposes groups, thread names and transport`() {
		val config = NettyServerConfig(
			port = 18095,
			bossThreads = 2,
			workerThreads = 3,
			bossThreadName = "CfgBoss",
			workerThreadName = "CfgWorker",
			sportIO = NettyIO.NIO,
		)
		val server = NettyWebServer.server(config).startServer()
		try {
			assertEquals(config, server.config)
			assertEquals(NettyIO.NIO, server.sportIO)
			assertEquals(2, server.bossGroup.executorCount())
			assertEquals(3, server.workerGroup.executorCount())

			val resp = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://127.0.0.1:18095/hello")).GET().build(),
				HttpResponse.BodyHandlers.ofString(),
			)
			assertEquals(200, resp.statusCode())
			assertEquals("/hello", resp.body())

			// 线程懒启动：单连接只会唤醒部分线程，验证命名前缀即可，数量以 executorCount 为准
			val liveNames = Thread.getAllStackTraces().keys.map { it.name }
			assertTrue(liveNames.any { it.startsWith("CfgWorker-") })
			assertTrue(liveNames.any { it.startsWith("CfgBoss-") })
		} finally {
			server.stopServer()
		}
	}
}