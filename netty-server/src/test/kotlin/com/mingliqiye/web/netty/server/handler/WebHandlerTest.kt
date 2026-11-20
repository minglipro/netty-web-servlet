package com.mingliqiye.web.netty.server.handler

import com.mingliqiye.web.netty.server.io.Responses
import com.mingliqiye.web.netty.server.poll.CoroutineWorkerPool
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebHandlerTest {
	@Test
	fun `queue full responds 503`() {
		val started = CountDownLatch(1)
		val block = CountDownLatch(1)
		val pool = CoroutineWorkerPool<FullHttpRequest>(capacity = 1, { it.release() }) { request, ctx ->
			started.countDown()
			block.await()
			Responses.write(ctx, request, ok())
		}
		val channel = EmbeddedChannel(WebHandler(pool))
		try {
			channel.writeInbound(request())
			check(started.await(5, TimeUnit.SECONDS)) { "worker did not pick up first task" }
			channel.writeInbound(request())
			channel.writeInbound(request())

			val response = channel.readOutbound<FullHttpResponse>()
			assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status())
			response.release()
		} finally {
			block.countDown()
			pool.shutdown()
			Thread.sleep(100)
			channel.runPendingTasks()
			channel.finishAndReleaseAll()
		}
	}

	@Test
	fun `business exception responds 500`() {
		val pool = CoroutineWorkerPool<FullHttpRequest>(releaseTask = { it.release() }) { _, _ ->
			throw RuntimeException("boom")
		}
		val channel = EmbeddedChannel(WebHandler(pool))
		try {
			channel.writeInbound(request())
			val response = awaitOutbound(channel)
			assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status())
			response.release()
		} finally {
			pool.shutdown()
			channel.finishAndReleaseAll()
		}
	}

	@Test
	fun `keep-alive follows request`() {
		val pool = CoroutineWorkerPool<FullHttpRequest>(releaseTask = { it.release() }) { request, ctx ->
			Responses.write(ctx, request, ok())
		}
		val channel = EmbeddedChannel(WebHandler(pool))
		try {
			channel.writeInbound(request())
			val keepAliveResponse = awaitOutbound(channel)
			assertTrue(HttpUtil.isKeepAlive(keepAliveResponse))
			keepAliveResponse.release()

			val closeRequest = request()
			closeRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
			channel.writeInbound(closeRequest)
			val closeResponse = awaitOutbound(channel)
			assertFalse(HttpUtil.isKeepAlive(closeResponse))
			closeResponse.release()
		} finally {
			pool.shutdown()
			channel.finishAndReleaseAll()
		}
	}

	private fun ok() = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK).apply {
		headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
	}

	private fun awaitOutbound(channel: EmbeddedChannel): FullHttpResponse {
		val deadline = System.currentTimeMillis() + 5000
		while (System.currentTimeMillis() < deadline) {
			// Netty 4.2 的 EmbeddedEventLoop 将 execute() 任务入队，需手动驱动
			channel.runPendingTasks()
			val msg = channel.readOutbound<FullHttpResponse>()
			if (msg != null) return msg
			Thread.sleep(10)
		}
		error("no outbound response within 5s")
	}

	private fun request() = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")
}