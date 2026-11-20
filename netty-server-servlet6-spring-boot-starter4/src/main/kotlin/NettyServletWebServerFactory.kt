package com.mingliqiye.web.netty.spring4

import com.mingliqiye.logger.Loggers
import com.mingliqiye.web.netty.server.io.NettyIO
import com.mingliqiye.web.netty.servlet.NettyServletServer
import com.mingliqiye.web.netty.servlet.config.ServletServerConfig
import io.netty.handler.ssl.ClientAuth
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import org.springframework.boot.web.server.AbstractConfigurableWebServerFactory
import org.springframework.boot.web.server.Ssl
import org.springframework.boot.web.server.WebServer
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory
import org.springframework.boot.web.server.servlet.ServletContextInitializers
import org.springframework.boot.web.server.servlet.ServletWebServerSettings
import org.springframework.boot.web.servlet.ServletContextInitializer
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Spring Boot 4 嵌入式 Web 服务器工厂：以 Netty Servlet 容器替代 Tomcat/Jetty/Undertow。
 *
 * 通用服务器属性（端口/地址/上下文路径/会话/SSL/压缩等）由 Boot 的
 * ServletWebServerFactoryCustomizer 从 ServerProperties 注入；
 * Netty 专属旋钮经 [NettyServerProperties]（`server.netty.*`）绑定。
 * [getWebServer] 依次应用 ServletContextInitializer、web 监听器与错误页注册。
 *
 * @param properties Netty 专属配置
 * @param sessionStore 会话持久化存储，默认内存；可由自动配置按 `server.netty.session-store-path` 注入磁盘存储，
 * 或用户自定义 Bean（如 RedisSessionStore）覆盖
 */
class NettyServletWebServerFactory(
	private val properties: NettyServerProperties = NettyServerProperties(),
	private val sessionStore: com.mingliqiye.web.netty.servlet.session.SessionStore =
		com.mingliqiye.web.netty.servlet.session.MemorySessionStore,
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
			serverName = properties.serverName,
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
			sslContext = buildSslContext(),
			compression = buildCompressionConfig(),
			docRoot = settings.documentRoot?.absolutePath,
			http2Enabled = http2?.isEnabled == true,
			sessionStore = sessionStore,
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

	/** 从 Boot `server.compression.*` 构建压缩配置，未启用时返回 null。 */
	private fun buildCompressionConfig(): com.mingliqiye.web.netty.server.config.CompressionConfig? {
		val c = compression ?: return null
		if (!c.enabled) return null
		val defaults = com.mingliqiye.web.netty.server.config.CompressionConfig()
		return com.mingliqiye.web.netty.server.config.CompressionConfig(
			mimeTypes = c.mimeTypes?.toList() ?: defaults.mimeTypes,
			minResponseSize = c.minResponseSize?.toBytes()?.toInt() ?: defaults.minResponseSize,
			excludedUserAgents = c.excludedUserAgents?.map { Regex(it) } ?: emptyList(),
		)
	}

	/**
	 * 从 Boot `server.ssl.*`（含 SSL bundle）构建 Netty [SslContext]。
	 * 未启用 SSL 时返回 null；指定 key-alias 时从 keystore 提取对应私钥与证书链。
	 */
	private fun buildSslContext(): SslContext? {
		val ssl = ssl ?: return null
		if (!Ssl.isEnabled(ssl)) return null
		if (ssl.bundle != null && sslBundles == null) {
			throw IllegalStateException("server.ssl.bundle requires an SslBundles bean")
		}
		val bundle = getSslBundle()
		val alias = bundle.key.alias
		val builder = if (alias != null) {
			val keyStore = bundle.stores.keyStore
				?: throw IllegalStateException("SSL bundle has no key store for alias '$alias'")
			val password = (bundle.key.password ?: bundle.stores.keyStorePassword ?: "").toCharArray()
			val key = keyStore.getKey(alias, password) as? PrivateKey
				?: throw IllegalStateException("No private key for alias '$alias' in SSL key store")
			val chain = keyStore.getCertificateChain(alias)
				?.map { it as X509Certificate }?.toTypedArray()
				?: throw IllegalStateException("No certificate chain for alias '$alias' in SSL key store")
			SslContextBuilder.forServer(key, *chain)
		} else {
			SslContextBuilder.forServer(bundle.managers.keyManagerFactory)
		}
		when (ssl.clientAuth) {
			Ssl.ClientAuth.WANT -> builder.clientAuth(ClientAuth.OPTIONAL)
			Ssl.ClientAuth.NEED -> {
				builder.clientAuth(ClientAuth.REQUIRE)
				builder.trustManager(bundle.managers.trustManagerFactory)
			}
			else -> {}
		}
		bundle.options.enabledProtocols?.takeIf { it.isNotEmpty() }?.let { builder.protocols(it.toList()) }
		bundle.options.ciphers?.takeIf { it.isNotEmpty() }?.let { builder.ciphers(it.toList()) }
		if (http2?.isEnabled == true) {
			builder.applicationProtocolConfig(com.mingliqiye.web.netty.server.io.Http2Support.alpnConfig())
		}
		log.debug { "SSL enabled: protocol=${bundle.protocol} clientAuth=${ssl.clientAuth} alias=$alias http2=${http2?.isEnabled}" }
		return builder.build()
	}
}
