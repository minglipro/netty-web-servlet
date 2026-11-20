package com.mingliqiye.web.netty.spring4

import com.mingliqiye.logger.Loggers
import com.mingliqiye.web.netty.servlet.NettyServletServer
import org.springframework.boot.web.server.GracefulShutdownCallback
import org.springframework.boot.web.server.GracefulShutdownResult
import org.springframework.boot.web.server.WebServer
import org.springframework.boot.web.server.WebServerException

/**
 * Boot [WebServer] 适配器：把 NettyServletServer 的生命周期映射到 Boot 契约。
 *
 * 优雅停机：后台线程先停止监听，再轮询排空在途请求（最长 30 秒），
 * 超时仍有活跃请求时报告 REQUESTS_ACTIVE，否则 IDLE，最后完整停机并回调。
 */
class NettyWebServerAdapter(private val server: NettyServletServer) : WebServer {
	companion object {
		private val log = Loggers.getLogger<NettyWebServerAdapter>()
	}

	override fun start() {
		try {
			server.startServer()
		} catch (e: Exception) {
			throw WebServerException("Unable to start Netty servlet web server", e)
		}
	}

	override fun stop() {
		try {
			server.stopServer()
		} catch (e: Exception) {
			throw WebServerException("Unable to stop Netty servlet web server", e)
		}
	}

	override fun getPort(): Int = server.port

	override fun shutDownGracefully(callback: GracefulShutdownCallback) {
		val thread = Thread({
			log.debug { "Graceful shutdown: stop listening, activeRequests=${server.container.activeRequests}" }
			server.stopListening()
			val deadline = System.currentTimeMillis() + 30_000
			while (server.container.activeRequests > 0 && System.currentTimeMillis() < deadline) {
				Thread.sleep(50)
			}
			val result = if (server.container.activeRequests > 0) {
				GracefulShutdownResult.REQUESTS_ACTIVE
			} else {
				GracefulShutdownResult.IDLE
			}
			log.debug { "Graceful shutdown drain finished: $result (activeRequests=${server.container.activeRequests})" }
			runCatching { server.stopServer() }
			callback.shutdownComplete(result)
		}, "netty-graceful-shutdown")
		thread.isDaemon = true
		thread.start()
	}
}