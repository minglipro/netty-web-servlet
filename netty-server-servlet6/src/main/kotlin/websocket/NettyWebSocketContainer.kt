package com.mingliqiye.web.netty.servlet.websocket

import com.mingliqiye.logger.Loggers
import com.mingliqiye.web.netty.server.io.WebSockets
import com.mingliqiye.web.netty.servlet.core.ServletContainer
import com.mingliqiye.web.netty.servlet.http.HttpServletRequestImpl
import com.mingliqiye.web.netty.servlet.http.HttpServletResponseImpl
import io.netty.handler.codec.http.QueryStringDecoder
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
import java.util.concurrent.ConcurrentHashMap
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
 * 仅支持服务端，connectToServer 抛 [UnsupportedOperationException]。
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

	override fun getInstalledExtensions(): Set<Extension> = emptySet()

	override fun connectToServer(annotatedEndpoint: Any, path: URI): Session =
		throw UnsupportedOperationException("Client WebSocket connections are not supported")

	override fun connectToServer(annotatedEndpointClass: Class<*>, path: URI): Session =
		throw UnsupportedOperationException("Client WebSocket connections are not supported")

	override fun connectToServer(endpoint: Endpoint, config: jakarta.websocket.ClientEndpointConfig, path: URI): Session =
		throw UnsupportedOperationException("Client WebSocket connections are not supported")

	override fun connectToServer(endpointClass: Class<out Endpoint>, config: jakarta.websocket.ClientEndpointConfig, path: URI): Session =
		throw UnsupportedOperationException("Client WebSocket connections are not supported")

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
		val instance = configurator.getEndpointInstance(config.endpointClass)
		val endpoint = instance as? Endpoint ?: AnnotatedEndpointAdapter(instance)
		val session = WebSocketSessionImpl(this, req, config, endpoint, pathParams, subprotocol)
		req._upgraded = true
		resp.markCommitted()
		log.debug { "Upgrading ${req.requestURI} to WebSocket, endpoint=${config.endpointClass.name} subprotocol='$subprotocol'" }
		WebSockets.upgrade(req.ctx, req.nettyRequest, subprotocol.ifEmpty { null }, MAX_FRAME_PAYLOAD_LENGTH, WebSocketFrameHandler(session))
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

	/** 容器停机：关闭全部会话（GOING_AWAY）并停止空闲检查器。 */
	internal fun closeAll() {
		idleChecker.shutdownNow()
		val reason = CloseReason(CloseReason.CloseCodes.GOING_AWAY, "Server shutdown")
		openSessions.toList().forEach { runCatching { it.close(reason) } }
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