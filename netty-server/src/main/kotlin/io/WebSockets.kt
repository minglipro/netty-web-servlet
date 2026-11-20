package com.mingliqiye.web.netty.server.io

import com.mingliqiye.web.netty.server.handler.StreamingWebHandler
import com.mingliqiye.web.netty.server.handler.WebHandler
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder
import java.security.MessageDigest
import java.util.Base64

/**
 * WebSocket 升级工具：校验升级请求、计算握手密钥、写出 101 响应并把 HTTP 管线切换为 WebSocket 帧管线。
 *
 * 切换后管线为 WebSocket13FrameDecoder → WebSocket13FrameEncoder → 业务帧处理器，
 * 原 HttpServerCodec 与聚合/流式业务处理器全部移除，连接不再走 HTTP 语义。
 */
object WebSockets {
	private const val ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

	/** 判断请求是否为合法的 WebSocket 升级请求（GET + Upgrade/Connection 头 + Sec-WebSocket-Key + 版本 13）。 */
	fun isUpgradeRequest(request: HttpRequest): Boolean {
		if (!request.method().name().equals("GET", true)) return false
		val upgrade = request.headers().get(HttpHeaderNames.UPGRADE) ?: return false
		if (!upgrade.equals("websocket", true)) return false
		val connection = request.headers().get(HttpHeaderNames.CONNECTION) ?: return false
		if (!connection.split(',').any { it.trim().equals("upgrade", true) }) return false
		if (request.headers().get(HttpHeaderNames.SEC_WEBSOCKET_KEY) == null) return false
		return request.headers().get(HttpHeaderNames.SEC_WEBSOCKET_VERSION) == "13"
	}

	/** 按 RFC 6455 计算 Sec-WebSocket-Accept：Base64(SHA-1(key + GUID))。 */
	fun acceptKey(key: String): String {
		val digest = MessageDigest.getInstance("SHA-1").digest((key + ACCEPT_GUID).toByteArray(Charsets.US_ASCII))
		return Base64.getEncoder().encodeToString(digest)
	}

	/**
	 * 执行 101 握手并切换管线（全部操作序列化到事件循环线程）。
	 *
	 * @param subprotocol 协商出的子协议，null 表示不响应 Sec-WebSocket-Protocol
	 * @param maxFramePayloadLength 单帧负载上限（字节）
	 * @param frameHandler 接管 WebSocket 帧的业务处理器
	 * @param extensions 已协商的服务端扩展（如 permessage-deflate），按序插入编解码器并允许 RSV 位
	 */
	fun upgrade(
		ctx: ChannelHandlerContext,
		request: HttpRequest,
		subprotocol: String?,
		maxFramePayloadLength: Int,
		frameHandler: ChannelHandler,
		extensions: List<io.netty.handler.codec.http.websocketx.extensions.WebSocketServerExtension> = emptyList(),
	) {
		val key = request.headers().get(HttpHeaderNames.SEC_WEBSOCKET_KEY)
			?: throw IllegalArgumentException("Missing Sec-WebSocket-Key")
		ctx.executor().execute {
			if (!ctx.channel().isActive) return@execute
			val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS)
			response.headers().set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET)
			response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE)
			response.headers().set(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT, acceptKey(key))
			if (subprotocol != null) {
				response.headers().set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, subprotocol)
			}
			if (extensions.isNotEmpty()) {
				response.headers().set(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, extensionsHeaderValue(extensions))
			}
			ctx.writeAndFlush(response)
			val pipeline = ctx.pipeline()
			listOf(
				HttpServerCodec::class.java, HttpObjectAggregator::class.java,
				WebHandler::class.java, StreamingWebHandler::class.java,
				io.netty.handler.codec.http.HttpContentCompressor::class.java,
			).forEach { if (pipeline.get(it) != null) pipeline.remove(it) }
			pipeline.addLast(WebSocket13FrameDecoder(true, extensions.isNotEmpty(), maxFramePayloadLength, false))
			extensions.forEach { pipeline.addLast(it.newExtensionDecoder()) }
			extensions.forEach { pipeline.addLast(it.newExtensionEncoder()) }
			pipeline.addLast(WebSocket13FrameEncoder(false))
			pipeline.addLast(frameHandler)
		}
	}

	/** 按 RFC 6455 扩展头格式拼接协商结果（name; param=value; flag）。 */
	private fun extensionsHeaderValue(
		extensions: List<io.netty.handler.codec.http.websocketx.extensions.WebSocketServerExtension>,
	): String = extensions.joinToString(", ") { ext ->
		val data = ext.newReponseData()
		buildString {
			append(data.name())
			for ((name, value) in data.parameters()) {
				append("; ").append(name)
				if (!value.isNullOrEmpty()) {
					append('=').append(value)
				}
			}
		}
	}
}
