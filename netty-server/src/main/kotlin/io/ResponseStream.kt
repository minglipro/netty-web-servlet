package com.mingliqiye.web.netty.server.io

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.LastHttpContent

/**
 * 流式响应：start 发送响应头（chunked），writeChunk 逐块发送，finish 发送结束块。
 * 所有写操作序列化到事件循环，可在任意业务线程调用。
 */
class ResponseStream(
	private val ctx: ChannelHandlerContext,
	private val request: HttpRequest,
) {
	private val keepAlive = HttpUtil.isKeepAlive(request) && ctx.channel().attr(Responses.MUST_CLOSE).get() != true

	/** 发送响应头，开始 chunked 输出；连接不可用时静默丢弃。 */
	fun start(response: HttpResponse) {
		HttpUtil.setKeepAlive(response, keepAlive)
		ctx.executor().execute {
			if (ctx.channel().isActive) {
				ctx.writeAndFlush(response)
			}
		}
	}

	/** 发送一个数据块；所有权移交本方法，连接已断开时自动释放 [content]。 */
	fun writeChunk(content: ByteBuf) {
		ctx.executor().execute {
			if (ctx.channel().isActive) {
				ctx.writeAndFlush(DefaultHttpContent(content))
			} else {
				content.release()
			}
		}
	}

	/** 发送 chunked 结束块；非 keep-alive 连接随后关闭。 */
	fun finish() {
		ctx.executor().execute {
			if (ctx.channel().isActive) {
				val future = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
				if (!keepAlive) {
					future.addListener(ChannelFutureListener.CLOSE)
				}
			}
		}
	}
}