package com.mingliqiye.web.netty.server.io

import com.mingliqiye.web.netty.server.io.HeaderUtils.addSystemHeaders
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpUtil
import io.netty.util.AttributeKey

/** 完整响应写出工具：统一处理 keep-alive 语义与事件循环线程序列化。 */
object Responses {
	/** 通道属性：置 true 时强制关闭连接（如请求体读取失败后无法安全复用）。 */
	val MUST_CLOSE: AttributeKey<Boolean> = AttributeKey.valueOf("webMustClose")

	/**
	 * 在事件循环线程写出完整响应并按需关闭连接。
	 * 响应所有权移交本方法；连接已断开时自动释放。
	 */
	fun write(ctx: ChannelHandlerContext, request: HttpRequest, response: FullHttpResponse) {
		val keepAlive = HttpUtil.isKeepAlive(request) && ctx.channel().attr(MUST_CLOSE).get() != true
		HttpUtil.setKeepAlive(response, keepAlive)
		response.addSystemHeaders()
		ctx.executor().execute {
			if (ctx.channel().isActive) {
				val future = ctx.writeAndFlush(response)
				if (!keepAlive) {
					future.addListener(ChannelFutureListener.CLOSE)
				}
			} else {
				response.release()
			}
		}
	}
}
