package com.mingliqiye.web.netty.servlet.async

import com.mingliqiye.web.netty.servlet.core.ServletContainer
import com.mingliqiye.web.netty.servlet.http.HttpServletRequestImpl
import com.mingliqiye.web.netty.servlet.http.HttpServletResponseImpl
import com.mingliqiye.web.netty.server.io.Responses
import jakarta.servlet.AsyncContext
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture

/**
 * AsyncContext 实现：startAsync 后请求脱离容器同步流程，由此对象接管响应完成时机。
 *
 * [complete] 写出响应并收尾（排水 body、requestDestroyed、在途计数递减）；
 * [dispatch] 以 ASYNC 分发类型重走 Filter 链；超时/错误先通知 AsyncListener，
 * 监听器未自行 complete 时兜底 sendError(500) 后 complete。
 */
internal class AsyncContextImpl(
	val request: HttpServletRequestImpl,
	val response: HttpServletResponseImpl,
	private val container: ServletContainer,
	private val originalRequest: ServletRequest,
	private val originalResponse: ServletResponse,
) : AsyncContext {
	companion object {
		private val log = com.mingliqiye.logger.Loggers.getLogger<AsyncContextImpl>()
	}

	private val listeners = CopyOnWriteArrayList<AsyncListener>()

	/** 是否已完成（complete 幂等守卫）。 */
	@Volatile
	var completed = false
		private set

	@Volatile
	private var timeout = container.config.asyncTimeoutMillis

	internal var timeoutFuture: ScheduledFuture<*>? = null

	override fun getRequest(): ServletRequest = originalRequest

	override fun getResponse(): ServletResponse = originalResponse

	override fun hasOriginalRequestAndResponse(): Boolean =
		originalRequest === request && originalResponse === response

	override fun dispatch() {
		dispatch(request.requestURI)
	}

	override fun dispatch(path: String) {
		container.scope.launch {
			container.dispatchAsync(this@AsyncContextImpl, path)
		}
	}

	override fun dispatch(context: jakarta.servlet.ServletContext, path: String) {
		dispatch(path)
	}

	override fun complete() {
		if (completed) return
		completed = true
		log.debug { "Async complete for ${request.requestURI}" }
		timeoutFuture?.cancel(false)
		try {
			if (response.isError) {
				container.performErrorDispatch(request, response)
			}
			if (response.isStreaming) {
				response.finishStream()
			} else {
				Responses.write(request.ctx, request.nettyRequest, response.toFullHttpResponse())
			}
		} finally {
			request.body.abandon()
			request.asyncCompleted()
			container.bus.requestDestroyed(request)
			container.requestFinished()
		}
		val event = AsyncEvent(this, request, response)
		listeners.forEach { it.onComplete(event) }
	}

	override fun start(run: Runnable) {
		container.scope.launch { run.run() }
	}

	override fun addListener(listener: AsyncListener) {
		listeners.add(listener)
	}

	override fun addListener(listener: AsyncListener, request: ServletRequest, response: ServletResponse) {
		listeners.add(listener)
	}

	override fun <T : AsyncListener> createListener(clazz: Class<T>): T =
		clazz.getDeclaredConstructor().newInstance()

	override fun setTimeout(timeout: Long) {
		this.timeout = timeout
	}

	override fun getTimeout(): Long = timeout

	internal fun fireTimeout() {
		if (completed) return
		log.debug { "Async timeout (${timeout}ms) for ${request.requestURI}" }
		val event = AsyncEvent(this, request, response)
		listeners.forEach { it.onTimeout(event) }
		if (!completed) {
			if (!response.isCommitted) {
				response.sendError(500, "Async timeout")
			}
			complete()
		}
	}

	internal fun fireError(throwable: Throwable) {
		if (completed) return
		log.debug { "Async error for ${request.requestURI}: $throwable" }
		val event = AsyncEvent(this, request, response, throwable)
		listeners.forEach { it.onError(event) }
		if (!completed) {
			if (!response.isCommitted) {
				response.sendError(500)
			}
			complete()
		}
	}
}