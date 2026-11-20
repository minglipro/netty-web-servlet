package com.mingliqiye.web.netty.server.handler

import com.mingliqiye.logger.Loggers
import com.mingliqiye.web.netty.server.data.BusinessTask
import com.mingliqiye.web.netty.server.poll.CoroutineWorkerPool
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion

/**
 * 聚合管线入口：把聚合完成的完整请求投递到协程工作池。
 * 队列已满（高压）时直接回 503 并关闭连接，实现快速降级。
 */
class WebHandler(val pool: CoroutineWorkerPool<FullHttpRequest>) : SimpleChannelInboundHandler<FullHttpRequest>() {
	companion object {
		private val log = Loggers.getLogger<WebHandler>()
	}

	override fun channelRead0(
		ctx: ChannelHandlerContext,
		msg: FullHttpRequest,
	) {
		// SimpleChannelInboundHandler 会在 channelRead0 返回后释放 msg，异步处理前必须 retain
		msg.retain()
		log.debug { "Dispatch ${msg.method()} ${msg.uri()} to worker pool" }
		if (!pool.submit(BusinessTask(ctx, msg, msg))) {
			log.debug { "Reject ${msg.method()} ${msg.uri()} with 503, worker pool saturated" }
			msg.release()
			val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SERVICE_UNAVAILABLE)
			response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
			response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
			ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
		}
	}
}
