package com.mingliqiye.web.netty.servlet

import com.mingliqiye.web.netty.server.NettyWebServer
import com.mingliqiye.web.netty.server.config.NettyServerConfig
import com.mingliqiye.web.netty.server.io.NettyIO
import com.mingliqiye.web.netty.servlet.config.ServletServerConfig
import com.mingliqiye.web.netty.servlet.core.ServletContainer
import jakarta.servlet.Filter
import jakarta.servlet.FilterRegistration
import jakarta.servlet.Servlet
import jakarta.servlet.ServletRegistration

/**
 * Jakarta Servlet 6.1 容器服务器：在 [NettyWebServer] 流式管线之上实现 Servlet 语义
 * （路由、Filter 链、HttpSession、异步、multipart、ERROR/FORWARD/INCLUDE 分发）。
 *
 * 典型用法：
 * ```
 * val server = NettyServletServer(ServletServerConfig(port = 8080))
 * server.addServlet("app", myServlet, "/app")
 * server.addFilter("auth", authFilter, "/")
 * server.startServer()
 * ```
 *
 * @param config 容器全量配置，见 [ServletServerConfig]
 */

class NettyServletServer(config: ServletServerConfig = ServletServerConfig()) {
	/** 底层 Servlet 容器，可直接访问以注册错误页、监听器等高级能力。 */
	val container = ServletContainer(config)

	/** 实际绑定端口（port=0 随机端口场景下启动后从这里读取）。 */
	val port: Int get() = server.actualPort


	private var server: NettyWebServer = NettyWebServer
		.server(
			NettyServerConfig(
				serverName = config.serverName,
				host = config.host,
				port = config.port,
				queueCapacity = config.queueCapacity,
				maxRequestSize = config.maxRequestSize,
				bossThreads = config.bossThreads,
				workerThreads = config.workerThreads,
				bossThreadName = config.bossThreadName,
				workerThreadName = config.workerThreadName,
				sportIO = config.sportIO,
				sslContext = config.sslContext,
				compression = config.compression,
				http2Enabled = config.http2Enabled,
				streamingHandler = container::handle,
			)
		)


	/**
	 * 注册 Servlet 并映射 URL 模式（精确、路径前缀、扩展名、默认 `/`）。
	 *
	 * @return 动态注册句柄，可继续设置 loadOnStartup/asyncSupported/multipartConfig
	 */
	fun addServlet(name: String, servlet: Servlet, vararg urlPatterns: String): ServletRegistration.Dynamic =
		container.addServlet(name, servlet, *urlPatterns)

	/**
	 * 注册 Filter 并映射 URL 模式，默认仅拦截 REQUEST 分发。
	 *
	 * @return 动态注册句柄，可继续设置 dispatcherTypes/isMatchAfter/asyncSupported
	 */
	fun addFilter(name: String, filter: Filter, vararg urlPatterns: String): FilterRegistration.Dynamic =
		container.addFilter(name, filter, *urlPatterns)

	/** 初始化容器（监听器 contextInitialized → loadOnStartup Servlet → Filter）并绑定端口。 */
	fun startServer(): NettyServletServer {
		container.init()
		server.startServer()
		return this
	}

	/** 仅停止接受新连接（优雅停机第一步），在途请求继续处理。 */
	fun stopListening() {
		server.stopListening()
	}

	/** 完整停机：关闭网络层后销毁容器（会话清理、监听器 contextDestroyed、Servlet/Filter destroy）。 */
	fun stopServer() {
		server.stopServer()
		container.shutdown()
	}

	/** 阻塞当前线程直到服务器监听通道关闭。 */
	fun sync() {
		server.sync()
	}
}
