package com.mingliqiye.web.netty.servlet.websocket

import com.mingliqiye.web.netty.servlet.http.HttpServletRequestImpl
import io.netty.channel.Channel
import io.netty.handler.codec.http.QueryStringDecoder
import java.net.URI
import java.security.Principal

/** WebSocket 会话所需连接信息的抽象：服务端升级与客户端 connectToServer 共用。 */
internal interface WebSocketTransport {
	val channel: Channel
	val requestUri: URI
	val queryString: String?
	val parameterMap: Map<String, List<String>>
	val userPrincipal: Principal?
	val secure: Boolean
}

/** 服务端传输：信息取自原始 HTTP 升级请求。 */
internal class ServletWebSocketTransport(private val request: HttpServletRequestImpl) : WebSocketTransport {
	override val channel: Channel = request.ctx.channel()
	override val requestUri: URI = run {
		val host = request.getHeader("Host") ?: "localhost"
		val scheme = if (request.isSecure) "wss" else "ws"
		URI.create("$scheme://$host${request.nettyRequest.uri()}")
	}
	override val queryString: String? = request.queryString
	override val parameterMap: Map<String, List<String>> = QueryStringDecoder(request.nettyRequest.uri()).parameters()
	override val userPrincipal: Principal? = request.userPrincipal
	override val secure: Boolean = request.isSecure
}

/** 客户端传输：信息取自 connectToServer 的目标 URI。 */
internal class ClientWebSocketTransport(override val channel: Channel, private val uri: URI) : WebSocketTransport {
	override val requestUri: URI = uri
	override val queryString: String? = uri.rawQuery
	override val parameterMap: Map<String, List<String>> = QueryStringDecoder(uri.toString()).parameters()
	override val userPrincipal: Principal? = null
	override val secure: Boolean = uri.scheme.equals("wss", ignoreCase = true)
}