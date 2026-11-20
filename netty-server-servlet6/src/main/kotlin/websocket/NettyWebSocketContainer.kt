package com.mingliqiye.web.netty.servlet.websocket

import com.mingliqiye.logger.Loggers
import com.mingliqiye.web.netty.server.io.WebSockets
import com.mingliqiye.web.netty.servlet.core.ServletContainer
import com.mingliqiye.web.netty.servlet.http.HttpServletRequestImpl
import com.mingliqiye.web.netty.servlet.http.HttpServletResponseImpl
import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker13
import io.netty.handler.codec.http.websocketx.WebSocketVersion
import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ClientAuth
import io.netty.handler.ssl.IdentityCipherSuiteFilter
import io.netty.handler.ssl.JdkSslContext
import io.netty.handler.ssl.SslContextBuilder
import jakarta.websocket.ClientEndpoint
import jakarta.websocket.ClientEndpointConfig
import jakarta.websocket.CloseReason
import jakarta.websocket.DeploymentException
import jakarta.websocket.Endpoint
import jakarta.websocket.Extension
import jakarta.websocket.HandshakeResponse
import jakarta.websocket.Session
import jakarta.websocket.server.HandshakeRequest
import jakarta.websocket.server.ServerContainer
import jakarta.websocket.server.ServerEndpoint
import jakarta.websocket.server.ServerEndpointConfig
import java.io.IOException
import java.net.URI
import java.security.Principal
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * jakarta.websocket [ServerContainer] 实现：端点注册、HTTP 升级握手与会话生命周期管理。
 *
 * 两条升级路径：
 * - 原生端点（@ServerEndpoint / [ServerEndpointConfig]）由 [tryUpgrade] 在 Servlet 链之前按路径匹配；
 * - Spring 等框架经 ServletContext 属性取得本容器后调用 [upgradeHttpToWebSocket]。
 *
 * 空闲会话由后台检查器按 [Session.getMaxIdleTimeout] 关闭；容器停机时 [closeAll] 以 GOING_AWAY 关闭全部会话。
 * 同时支持客户端：connectToServer 四形态（注解实例/类、[Endpoint] 实例/类 + [ClientEndpointConfig]），
 * 客户端帧强制掩码，wss 走 [ClientEndpointConfig.getSSLContext] 或默认信任库。
 */
class NettyWebSocketContainer internal constructor(private val container: ServletContainer) : ServerContainer {
	companion object {
		private val log = Loggers.getLogger<NettyWebSocketContainer>()
		private const val MAX_FRAME_PAYLOAD_LENGTH = 65536
	}

	private val endpoints = ConcurrentHashMap<String, ServerEndpointConfig>()

	internal val openSessions = ConcurrentHashMap.newKeySet<WebSocketSessionImpl>()

	@Volatile
	private var asyncSendTimeout = 30_000L

	@Volatile
	private var maxIdleTimeout = 0L

	@Volatile
	private var maxBinaryBufferSize = 8192

	@Volatile
	private var maxTextBufferSize = 8192

	private val idleChecker = Executors.newSingleThreadScheduledExecutor { r ->
		Thread(r, "websocket-idle-checker").apply { isDaemon = true }
	}.also {
		it.scheduleAtFixedRate(::checkIdleSessions, 1, 1, TimeUnit.SECONDS)
	}

	private val clientGroupLazy = lazy {
		MultiThreadIoEventLoopGroup(1, java.util.concurrent.ThreadFactory { r ->
			Thread(r, "websocket-client").apply { isDaemon = true }
		}, NioIoHandler.newFactory())
	}
	private val clientGroup: MultiThreadIoEventLoopGroup by clientGroupLazy

	private fun checkIdleSessions() {
		val now = System.currentTimeMillis()
		for (session in openSessions) {
			val timeout = session.maxIdleTimeout
			if (timeout > 0 && now - session.lastActivity > timeout) {
				log.debug { "WebSocket session ${session.id} idle timeout (${timeout}ms)" }
				runCatching { session.close(CloseReason(CloseReason.CloseCodes.GOING_AWAY, "Idle timeout")) }
			}
		}
	}

	// ---- WebSocketContainer 默认值 ----

	override fun getDefaultAsyncSendTimeout(): Long = asyncSendTimeout

	override fun setAsyncSendTimeout(timeout: Long) {
		asyncSendTimeout = timeout
	}

	override fun getDefaultMaxSessionIdleTimeout(): Long = maxIdleTimeout

	override fun setDefaultMaxSessionIdleTimeout(timeout: Long) {
		maxIdleTimeout = timeout
	}

	override fun getDefaultMaxBinaryMessageBufferSize(): Int = maxBinaryBufferSize

	override fun setDefaultMaxBinaryMessageBufferSize(length: Int) {
		maxBinaryBufferSize = length
	}

	override fun getDefaultMaxTextMessageBufferSize(): Int = maxTextBufferSize

	override fun setDefaultMaxTextMessageBufferSize(length: Int) {
		maxTextBufferSize = length
	}

	override fun getInstalledExtensions(): Set<Extension> =
		setOf(WebSocketExtensionImpl("permessage-deflate", emptyList()))

	override fun connectToServer(annotatedEndpoint: Any, path: URI): Session =
		doConnect(AnnotatedEndpointAdapter(annotatedEndpoint), clientConfigFor(annotatedEndpoint.javaClass), path)

	override fun connectToServer(annotatedEndpointClass: Class<*>, path: URI): Session {
		val instance = annotatedEndpointClass.getDeclaredConstructor().newInstance()
		return doConnect(AnnotatedEndpointAdapter(instance), clientConfigFor(annotatedEndpointClass), path)
	}

	override fun connectToServer(endpoint: Endpoint, config: ClientEndpointConfig, path: URI): Session =
		doConnect(endpoint, config, path)

	override fun connectToServer(endpointClass: Class<out Endpoint>, config: ClientEndpointConfig, path: URI): Session =
		doConnect(endpointClass.getDeclaredConstructor().newInstance(), config, path)

	/** 从 @ClientEndpoint 注解读取子协议/编解码器/Configurator；无注解时用默认配置。 */
	private fun clientConfigFor(clazz: Class<*>): ClientEndpointConfig {
		val annotation = clazz.getAnnotation(ClientEndpoint::class.java)
			?: return ClientEndpointConfig.Builder.create().build()
		return ClientEndpointConfig.Builder.create()
			.preferredSubprotocols(annotation.subprotocols.toList())
			.encoders(annotation.encoders.map { it.java })
			.decoders(annotation.decoders.map { it.java })
			.configurator(annotation.configurator.java.getDeclaredConstructor().newInstance())
			.build()
	}

	/**
	 * 客户端连接：TCP（wss 加 TLS）→ HTTP 101 升级握手 → 帧管线。
	 * [WebSocketClientHandshaker13.finishHandshake] 负责校验响应并就地替换 HTTP 解码器为帧解码器；
	 * 握手完成后登记会话、回调 Configurator.afterResponse 与 endpoint.onOpen，再向调用方返回。
	 */
	private fun doConnect(endpoint: Endpoint, config: ClientEndpointConfig, path: URI): Session {
		val scheme = path.scheme?.lowercase()
		if (scheme != "ws" && scheme != "wss") {
			throw DeploymentException("Unsupported WebSocket URI scheme: ${path.scheme}")
		}
		val host = path.host ?: throw DeploymentException("WebSocket URI has no host: $path")
		val port = if (path.port != -1) path.port else if (scheme == "wss") 443 else 80

		val headerMap = mutableMapOf<String, List<String>>()
		config.configurator.beforeRequest(headerMap)
		val headers = DefaultHttpHeaders()
		headerMap.forEach { (name, values) -> headers.add(name, values) }

		val handshaker = WebSocketClientHandshaker13(
			path, WebSocketVersion.V13,
			config.preferredSubprotocols.joinToString(",").ifEmpty { null },
			false, headers, MAX_FRAME_PAYLOAD_LENGTH,
		)
		val ready = CompletableFuture<Session>()
		val bootstrap = Bootstrap()
			.group(clientGroup)
			.channel(NioSocketChannel::class.java)
			.handler(object : ChannelInitializer<SocketChannel>() {
				override fun initChannel(ch: SocketChannel) {
					val pipeline = ch.pipeline()
					if (scheme == "wss") {
						val sslContext = config.getSSLContext()?.let {
							JdkSslContext(it, true, null, IdentityCipherSuiteFilter.INSTANCE, ApplicationProtocolConfig.DISABLED, ClientAuth.NONE, null, false)
						} ?: SslContextBuilder.forClient().build()
						pipeline.addLast(sslContext.newHandler(ch.alloc(), host, port))
					}
					pipeline.addLast(HttpClientCodec())
					pipeline.addLast(HttpObjectAggregator(MAX_FRAME_PAYLOAD_LENGTH))
					pipeline.addLast(object : SimpleChannelInboundHandler<FullHttpResponse>() {
						private var session: WebSocketSessionImpl? = null

						override fun channelRead0(ctx: ChannelHandlerContext, response: FullHttpResponse) {
							try {
								handshaker.finishHandshake(ch, response)
							} catch (e: Exception) {
								ready.completeExceptionally(e)
								ch.close()
								return
							}
							config.configurator.afterResponse(object : HandshakeResponse {
								override fun getHeaders(): MutableMap<String, MutableList<String>> {
									val map = linkedMapOf<String, MutableList<String>>()
									for (entry in response.headers().entries()) {
										map.getOrPut(entry.key) { mutableListOf() }.add(entry.value)
									}
									return map
								}
							})
							val newSession = WebSocketSessionImpl(
								this@NettyWebSocketContainer, ClientWebSocketTransport(ch, path), config,
								endpoint, emptyMap(), handshaker.actualSubprotocol() ?: "",
							)
							session = newSession
							openSessions.add(newSession)
							ctx.pipeline().remove(this)
							ctx.pipeline().addLast(WebSocket13FrameEncoder(true))
							ctx.pipeline().addLast(WebSocketFrameHandler(newSession))
							try {
								endpoint.onOpen(newSession, config)
							} catch (e: Exception) {
								log.error("WebSocket client onOpen failed for session ${newSession.id}", e)
								newSession.fireError(e)
								newSession.close(CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "onOpen failed"))
							}
							ready.complete(newSession)
						}

						override fun channelInactive(ctx: ChannelHandlerContext) {
							ready.completeExceptionally(IOException("Connection closed before WebSocket handshake completed"))
							super.channelInactive(ctx)
						}

						override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
							if (!ready.completeExceptionally(cause)) {
								session?.fireError(cause)
							}
							ctx.close()
						}
					})
				}
			})
		try {
			val channel = bootstrap.connect(host, port).sync().channel()
			handshaker.handshake(channel).sync()
			return ready.get(10, TimeUnit.SECONDS)
		} catch (e: Exception) {
			val cause = if (e is ExecutionException) e.cause ?: e else e
			throw DeploymentException("WebSocket connect to $path failed: ${cause.message}", cause)
		}
	}

	// ---- 端点注册 ----

	override fun addEndpoint(endpointClass: Class<*>) {
		val annotation = endpointClass.getAnnotation(ServerEndpoint::class.java)
			?: throw DeploymentException("${endpointClass.name} is not annotated with @ServerEndpoint")
		val config = ServerEndpointConfig.Builder.create(endpointClass, annotation.value)
			.subprotocols(annotation.subprotocols.toList())
			.decoders(annotation.decoders.map { it.java })
			.encoders(annotation.encoders.map { it.java })
			.configurator(annotation.configurator.java.getDeclaredConstructor().newInstance())
			.build()
		addEndpoint(config)
	}

	override fun addEndpoint(config: ServerEndpointConfig) {
		if (!config.path.startsWith("/")) {
			throw DeploymentException("Endpoint path must start with '/': ${config.path}")
		}
		endpoints[config.path] = config
		log.debug { "Registered WebSocket endpoint ${config.endpointClass.name} at '${config.path}'" }
	}

	/**
	 * 原生端点升级入口：按请求路径（含 /{param} 模板）匹配已注册端点并执行握手。
	 * 返回 false 表示无匹配端点，请求继续走普通 Servlet 链。
	 */
	internal fun tryUpgrade(request: HttpServletRequestImpl, response: HttpServletResponseImpl): Boolean {
		if (endpoints.isEmpty()) return false
		val path = request.requestURI.removePrefix(container.config.contextPath).ifEmpty { "/" }
		endpoints[path]?.let {
			upgradeHttpToWebSocket(request, response, it, emptyMap())
			return true
		}
		for ((pattern, config) in endpoints) {
			if (!pattern.contains('{')) continue
			val params = matchTemplate(pattern, path) ?: continue
			upgradeHttpToWebSocket(request, response, config, params)
			return true
		}
		return false
	}

	private fun matchTemplate(pattern: String, path: String): Map<String, String>? {
		val patternSegments = pattern.split('/')
		val pathSegments = path.split('/')
		if (patternSegments.size != pathSegments.size) return null
		val params = mutableMapOf<String, String>()
		for (i in patternSegments.indices) {
			val p = patternSegments[i]
			val s = pathSegments[i]
			if (p.startsWith("{") && p.endsWith("}")) {
				params[p.substring(1, p.length - 1)] = s
			} else if (p != s) {
				return null
			}
		}
		return params
	}

	// ---- 升级握手 ----

	/** 解析客户端扩展提议并逐个协商（当前支持 permessage-deflate），每次握手独立实例。 */
	private fun negotiateExtensions(header: String?): List<io.netty.handler.codec.http.websocketx.extensions.WebSocketServerExtension> {
		if (header.isNullOrBlank()) return emptyList()
		// 0 = 默认窗口上限，等价于已弃用的无参构造
		val handshaker = io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker(0)
		return io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionUtil.extractExtensions(header)
			.mapNotNull { handshaker.handshakeExtension(it) }
	}

	override fun upgradeHttpToWebSocket(
		request: Any,
		response: Any,
		config: ServerEndpointConfig,
		pathParams: Map<String, String>,
	) {
		var reqObj = request
		while (reqObj is jakarta.servlet.ServletRequestWrapper) reqObj = reqObj.request
		var respObj = response
		while (respObj is jakarta.servlet.ServletResponseWrapper) respObj = respObj.response
		val req = reqObj as? HttpServletRequestImpl
			?: throw IllegalArgumentException("request must be this container's HttpServletRequest implementation")
		val resp = respObj as? HttpServletResponseImpl
			?: throw IllegalArgumentException("response must be this container's HttpServletResponse implementation")
		if (!WebSockets.isUpgradeRequest(req.nettyRequest)) {
			throw IOException("Not a valid WebSocket upgrade request")
		}
		if (resp.isCommitted) {
			throw IOException("Response already committed")
		}
		val configurator = config.configurator
		val handshakeRequest = HandshakeRequestImpl(req)
		val handshakeResponse = HandshakeResponseImpl()
		configurator.modifyHandshake(config, handshakeRequest, handshakeResponse)
		val origin = req.getHeader("Origin")
		if (origin != null && !configurator.checkOrigin(origin)) {
			throw IOException("Origin check failed for '$origin'")
		}
		val requested = req.getHeader("Sec-WebSocket-Protocol")
			?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
		val subprotocol = configurator.getNegotiatedSubprotocol(config.subprotocols, requested) ?: ""
		val extensions = negotiateExtensions(req.getHeader("Sec-WebSocket-Extensions"))
		val instance = configurator.getEndpointInstance(config.endpointClass)
		val endpoint = instance as? Endpoint ?: AnnotatedEndpointAdapter(instance)
		val session = WebSocketSessionImpl(this, ServletWebSocketTransport(req), config, endpoint, pathParams, subprotocol, extensions)
		req._upgraded = true
		resp.markCommitted()
		log.debug { "Upgrading ${req.requestURI} to WebSocket, endpoint=${config.endpointClass.name} subprotocol='$subprotocol' extensions=${extensions.map { it.newReponseData().name() }}" }
		WebSockets.upgrade(req.ctx, req.nettyRequest, subprotocol.ifEmpty { null }, MAX_FRAME_PAYLOAD_LENGTH, WebSocketFrameHandler(session), extensions)
		req.ctx.executor().execute {
			openSessions.add(session)
			try {
				endpoint.onOpen(session, config)
			} catch (e: Exception) {
				log.error("WebSocket onOpen failed for session ${session.id}", e)
				session.fireError(e)
				session.close(CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "onOpen failed"))
			}
		}
	}

	/** 容器停机：关闭全部会话（GOING_AWAY）、停止空闲检查器与客户端事件循环。 */
	internal fun closeAll() {
		idleChecker.shutdownNow()
		val reason = CloseReason(CloseReason.CloseCodes.GOING_AWAY, "Server shutdown")
		openSessions.toList().forEach { runCatching { it.close(reason) } }
		if (clientGroupLazy.isInitialized()) {
			clientGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS)
		}
	}
}

/** 升级请求的 [HandshakeRequest] 视图：头部/参数/URI/会话均取自原始 HTTP 请求。 */
internal class HandshakeRequestImpl(private val request: HttpServletRequestImpl) : HandshakeRequest {
	override fun getHeaders(): Map<String, List<String>> {
		val headers = linkedMapOf<String, List<String>>()
		for (name in request.nettyRequest.headers().names()) {
			headers[name] = request.getHeaders(name).toList()
		}
		return headers
	}

	override fun getUserPrincipal(): Principal? = request.userPrincipal

	override fun getRequestURI(): URI {
		val host = request.getHeader("Host") ?: "localhost"
		return URI.create("ws://$host${request.nettyRequest.uri()}")
	}

	override fun isUserInRole(role: String): Boolean = request.isUserInRole(role)

	override fun getHttpSession(): Any? = request.getSession(false)

	override fun getParameterMap(): Map<String, List<String>> =
		QueryStringDecoder(request.nettyRequest.uri()).parameters()

	override fun getQueryString(): String? = request.queryString
}

/** 升级响应头部收集器：供 Configurator.modifyHandshake 写入自定义响应头。 */
internal class HandshakeResponseImpl : HandshakeResponse {
	private val headers = linkedMapOf<String, MutableList<String>>()

	override fun getHeaders(): MutableMap<String, MutableList<String>> = headers
}
