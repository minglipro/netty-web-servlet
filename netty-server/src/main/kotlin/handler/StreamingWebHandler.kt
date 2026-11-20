package com.mingliqiye.web.netty.server.handler

import com.mingliqiye.logger.Loggers
import com.mingliqiye.web.netty.server.data.BusinessTask
import com.mingliqiye.web.netty.server.data.StreamingTask
import com.mingliqiye.web.netty.server.io.RequestBody
import com.mingliqiye.web.netty.server.poll.CoroutineWorkerPool
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent

/**
 * 流式管线：请求头到达即提交业务处理，请求体分块喂给 [RequestBody]，不等待完整 body。
 * 队列已满（高压）时直接回 503 并关闭连接；请求体超限由 [RequestBody] 抛异常中断。
 */
class StreamingWebHandler(
	private val pool: CoroutineWorkerPool<StreamingTask>,
	private val maxRequestSize: Int,
) : SimpleChannelInboundHandler<Any>() {
	companion object {
		private val log = Loggers.getLogger<StreamingWebHandler>()
	}

	private var body: RequestBody? = null

	override fun channelRead0(ctx: ChannelHandlerContext, msg: Any) {
		when (msg) {
			is HttpRequest -> {
				if (HttpUtil.is100ContinueExpected(msg)) {
					ctx.writeAndFlush(DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE))
				}
				val newBody = RequestBody(maxRequestSize.toLong(), ctx)
				body = newBody
				log.debug { "Streaming dispatch ${msg.method()} ${msg.uri()} to worker pool" }
				if (!pool.submit(BusinessTask(ctx, StreamingTask(msg, newBody), msg))) {
					log.debug { "Reject ${msg.method()} ${msg.uri()} with 503, worker pool saturated" }
					newBody.abandon()
					val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SERVICE_UNAVAILABLE)
					response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
					response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
					ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
				}
			}

			is HttpContent -> {
				val current = body ?: return
				current.feed(msg)
				if (msg is LastHttpContent) {
					current.finish()
					body = null
				}
			}
		}
	}

	override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
		log.debug { "Streaming pipeline exception, closing channel ${ctx.channel()}: $cause" }
		ctx.close()
	}
}