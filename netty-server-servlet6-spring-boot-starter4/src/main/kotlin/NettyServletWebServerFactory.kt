package com.mingliqiye.web.netty.spring4

import com.mingliqiye.logger.Loggers
import com.mingliqiye.web.netty.server.io.NettyIO
import com.mingliqiye.web.netty.servlet.NettyServletServer
import com.mingliqiye.web.netty.servlet.config.ServletServerConfig
import org.springframework.boot.web.server.AbstractConfigurableWebServerFactory
import org.springframework.boot.web.server.WebServer
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory
import org.springframework.boot.web.server.servlet.ServletContextInitializers
import org.springframework.boot.web.server.servlet.ServletWebServerSettings
import org.springframework.boot.web.servlet.ServletContextInitializer

/**
 * Spring Boot 4 嵌入式 Web 服务器工厂：以 Netty Servlet 容器替代 Tomcat/Jetty/Undertow。
 *
 * 通用服务器属性（端口/地址/上下文路径/会话/SSL/压缩等）由 Boot 的
 * ServletWebServerFactoryCustomizer 从 ServerProperties 注入；
 * Netty 专属旋钮经 [NettyServerProperties]（`server.netty.*`）绑定。
 * [getWebServer] 依次应用 ServletContextInitializer、web 监听器与错误页注册。
 *
 * @param properties Netty 专属配置
 */
class NettyServletWebServerFactory(
	private val properties: NettyServerProperties = NettyServerProperties(),
) : AbstractConfigurableWebServerFactory(), ConfigurableServletWebServerFactory {
	companion object {
		private val log = Loggers.getLogger<NettyServletWebServerFactory>()
	}

	private val settings = ServletWebServerSettings()

	override fun getSettings(): ServletWebServerSettings = settings

	override fun addWebListeners(vararg listeners: String) {
		settings.addWebListenerClassNames(*listeners)
	}

	override fun getWebServer(vararg initializers: ServletContextInitializer): WebServer {
		log.debug { "Building Netty servlet web server: port=$port contextPath=${settings.contextPath} initializers=${initializers.size} errorPages=${errorPages.size}" }
		val config = ServletServerConfig(
			serverName = "${settings.displayName}-NettyWebServer",
			host = address?.hostAddress ?: "",
			port = port,
			contextPath = settings.contextPath.toString(),
			queueCapacity = properties.queueCapacity,
			maxRequestSize = properties.maxRequestSize,
			responseBufferSize = properties.responseBufferSize,
			sessionTimeoutSeconds = settings.session.timeout?.toSeconds()?.toInt() ?: 1800,
			asyncTimeoutMillis = properties.asyncTimeoutMillis,
			multipartMaxFileSize = properties.multipartMaxFileSize,
			multipartMaxRequestSize = properties.multipartMaxRequestSize,
			bossThreads = properties.bossThreads,
			workerThreads = properties.workerThreads,
			bossThreadName = properties.bossThreadName,
			workerThreadName = properties.workerThreadName,
			sportIO = properties.transport ?: NettyIO.getSportIO(),
		)
		val server = NettyServletServer(config)
		val context = server.container.context
		ServletContextInitializers.from(settings, *initializers).forEach { it.onStartup(context) }
		settings.webListenerClassNames.forEach { context.addListener(it) }
		for (errorPage in errorPages) {
			val exception = errorPage.exception
			log.debug { "Registering error page ${errorPage.path} (global=${errorPage.isGlobal}, status=${errorPage.statusCode}, exception=${exception?.name})" }
			when {
				errorPage.isGlobal -> server.container.addErrorPage(errorPage.path)
				exception != null -> server.container.addErrorPage(exception, errorPage.path)
				else -> server.container.addErrorPage(errorPage.statusCode, errorPage.path)
			}
		}
		return NettyWebServerAdapter(server)
	}
}
