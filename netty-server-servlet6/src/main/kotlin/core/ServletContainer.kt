package com.mingliqiye.web.netty.servlet.core

import com.mingliqiye.logger.Loggers
import com.mingliqiye.web.netty.server.io.BodyTooLargeException
import com.mingliqiye.web.netty.server.io.RequestBody
import com.mingliqiye.web.netty.server.io.Responses
import com.mingliqiye.web.netty.servlet.async.AsyncContextImpl
import com.mingliqiye.web.netty.servlet.config.ServletServerConfig
import com.mingliqiye.web.netty.servlet.http.HttpServletRequestImpl
import com.mingliqiye.web.netty.servlet.http.HttpServletResponseImpl
import com.mingliqiye.web.netty.servlet.session.SessionManager
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.HttpRequest
import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.Servlet
import jakarta.servlet.ServletRegistration
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Servlet 容器核心：请求分发、Filter 链、错误页、异步调度与生命周期管理。
 *
 * 每个请求经 [handle] 进入：REQUEST 分发 → 匹配 Servlet 与 Filter → 执行；
 * sendError/未捕获异常触发 ERROR 分发（[performErrorDispatch]），
 * forward/include/async dispatch 分别以对应 [DispatcherType] 重走 [runChain]。
 *
 * @param config 容器全量配置
 */
class ServletContainer(val config: ServletServerConfig = ServletServerConfig()) {
	companion object {
		private val log = Loggers.getLogger<ServletContainer>()

		internal const val ERROR_STATUS_CODE = "jakarta.servlet.error.status_code"
		internal const val ERROR_MESSAGE = "jakarta.servlet.error.message"
		internal const val ERROR_EXCEPTION = "jakarta.servlet.error.exception"
		internal const val ERROR_EXCEPTION_TYPE = "jakarta.servlet.error.exception_type"
		internal const val ERROR_REQUEST_URI = "jakarta.servlet.error.request_uri"
		internal const val ERROR_SERVLET_NAME = "jakarta.servlet.error.servlet_name"
	}

	private val contextImpl = ServletContextImpl(this)

	/** 应用级 ServletContext。 */
	val context: jakarta.servlet.ServletContext = contextImpl
	internal val bus = ListenerBus(contextImpl)

	/** 会话管理器。 */
	val sessions = SessionManager(context).also {
		it.bus = bus
		it.defaultTimeoutSeconds = config.sessionTimeoutSeconds
	}

	/** WebSocket 服务端容器（jakarta.websocket ServerContainer）。 */
	val webSockets = com.mingliqiye.web.netty.servlet.websocket.NettyWebSocketContainer(this)

	/** 容器协程作用域：async dispatch 与 AsyncContext.start 在此执行。 */
	val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	internal var sessionTimeoutSeconds: Int = config.sessionTimeoutSeconds
		set(value) {
			field = value
			sessions.defaultTimeoutSeconds = value
		}

	internal var defaultRequestEncoding: String? = null
	internal var defaultResponseEncoding: String? = null

	private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
		Thread(r, "servlet-async-timeout").apply { isDaemon = true }
	}

	internal val servlets = linkedMapOf<String, ServletRegistrationImpl>()
	internal val filters = mutableListOf<FilterRegistrationImpl>()

	private val active = java.util.concurrent.atomic.AtomicInteger()

	private val shutdownOnce = java.util.concurrent.atomic.AtomicBoolean(false)

	/** 在途请求数（含异步未完成的），优雅停机排空依据。 */
	val activeRequests: Int get() = active.get()

	internal fun requestFinished() {
		active.decrementAndGet()
	}

	private class ErrorPageEntry(val statusCode: Int?, val exceptionType: Class<out Throwable>?, val location: String)

	private val errorPages = mutableListOf<ErrorPageEntry>()

	/** 注册全局默认错误页（无状态码/异常匹配时兜底）。 */
	fun addErrorPage(location: String) {
		errorPages.add(ErrorPageEntry(null, null, location))
	}

	/** 注册按 HTTP 状态码匹配的错误页。 */
	fun addErrorPage(statusCode: Int, location: String) {
		errorPages.add(ErrorPageEntry(statusCode, null, location))
	}

	/** 注册按异常类型（含父类链）匹配的错误页。 */
	fun addErrorPage(exceptionType: Class<out Throwable>, location: String) {
		errorPages.add(ErrorPageEntry(null, exceptionType, location))
	}

	/** 注册 Servlet 并映射 URL 模式，返回动态注册句柄。 */
	fun addServlet(name: String, servlet: Servlet, vararg urlPatterns: String): ServletRegistration.Dynamic {
		val registration = ServletRegistrationImpl(name, servlet, context)
		registration.addMapping(urlPatterns.toList())
		servlets[name] = registration
		log.debug { "Registered servlet '$name' ${servlet.javaClass.name} mappings=${urlPatterns.toList()}" }
		return registration
	}

	/** 注册 Filter 并映射 URL 模式（默认仅 REQUEST 分发），返回动态注册句柄。 */
	fun addFilter(name: String, filter: Filter, vararg urlPatterns: String): jakarta.servlet.FilterRegistration.Dynamic {
		val registration = FilterRegistrationImpl(name, filter, context)
		registration.addMappingForUrlPatterns(null, false, *urlPatterns)
		filters.add(registration)
		log.debug { "Registered filter '$name' mappings=${urlPatterns.toList()}" }
		return registration
	}

	/** 容器初始化：contextInitialized 监听器 → loadOnStartup 升序初始化 Servlet → 初始化 Filter。 */
	fun init() {
		bus.contextInitialized()
		servlets.values
			.sortedBy { if (it.loadOnStartupValue < 0) Int.MAX_VALUE else it.loadOnStartupValue }
			.forEach {
				log.debug { "Initializing servlet '${it.name}' (loadOnStartup=${it.loadOnStartupValue})" }
				it.servlet.init(it.config())
			}
		filters.forEach {
			log.debug { "Initializing filter '${it.name}'" }
			it.filter.init(it.config())
		}
	}

	/** 容器销毁：清理会话 → contextDestroyed 监听器 → 逆序 destroy Filter/Servlet → 关闭调度器与协程。幂等。 */
	fun shutdown() {
		if (!shutdownOnce.compareAndSet(false, true)) {
			log.debug { "Container shutdown ignored, already shut down" }
			return
		}
		log.debug { "Container shutdown: ${servlets.size} servlets, ${filters.size} filters, activeRequests=$activeRequests" }
		webSockets.closeAll()
		sessions.clear()
		bus.contextDestroyed()
		filters.reversed().forEach { runCatching { it.filter.destroy() } }
		servlets.values.reversed().forEach { runCatching { it.servlet.destroy() } }
		scheduler.shutdownNow()
		scope.cancel()
	}

	/** 流式管线入口：每个请求在协程工作池中被调用，请求头已到、body 惰性可读。 */
	fun handle(nettyRequest: HttpRequest, body: RequestBody, ctx: ChannelHandlerContext) {
		active.incrementAndGet()
		val response = HttpServletResponseImpl(config.responseBufferSize)
		response.defaultEncoding = defaultResponseEncoding
		val request = HttpServletRequestImpl(nettyRequest, body, ctx, this, response)
		response.attach(ctx, nettyRequest)
		bus.requestInitialized(request)
		log.debug { "Request ${nettyRequest.method()} ${request.requestURI} (active=$activeRequests)" }
		try {
			if (com.mingliqiye.web.netty.server.io.WebSockets.isUpgradeRequest(nettyRequest) &&
				webSockets.tryUpgrade(request, response)
			) {
				log.debug { "Request ${request.requestURI} upgraded to WebSocket (native endpoint)" }
			} else {
				val path = request.requestURI.removePrefix(config.contextPath).ifEmpty { "/" }
				if (!runChain(path, request, response)) {
					log.debug { "No servlet matched path '$path', sending 404" }
					response.sendError(404)
				}
			}
		} catch (e: Exception) {
			log.error("Servlet processing failed: ${request.requestURI}", e)
			if (!response.isCommitted) {
				response.reset()
				response.errorException = e
				if (generateSequence(e as Throwable) { it.cause }.any { it is BodyTooLargeException }) {
					response.sendError(413)
				} else {
					response.sendError(500)
				}
			}
		}
		if (request._upgraded) {
			log.debug { "Request ${request.requestURI} upgraded to WebSocket, response deferred to WS session" }
			bus.requestDestroyed(request)
			body.abandon()
			active.decrementAndGet()
			return
		}
		if (!request.isAsyncStarted) {
			if (response.isError) {
				performErrorDispatch(request, response)
			}
			if (response.isStreaming) {
				response.finishStream()
			} else {
				Responses.write(ctx, nettyRequest, response.toFullHttpResponse())
			}
			bus.requestDestroyed(request)
			body.abandon()
			active.decrementAndGet()
		} else {
			log.debug { "Request ${request.requestURI} entered async mode, response deferred" }
		}
	}

	internal fun performErrorDispatch(request: HttpServletRequestImpl, response: HttpServletResponseImpl) {
		if (response.errorDispatched) return
		response.errorDispatched = true
		request.setAttribute(ERROR_STATUS_CODE, response.status)
		request.setAttribute(ERROR_MESSAGE, response.errorMessage ?: "")
		request.setAttribute(ERROR_REQUEST_URI, request.requestURI)
		request.setAttribute(ERROR_SERVLET_NAME, request._servletName)
		response.errorException?.let {
			request.setAttribute(ERROR_EXCEPTION, it)
			request.setAttribute(ERROR_EXCEPTION_TYPE, it.javaClass)
		}
		val location = findErrorPage(response.status, response.errorException)
		log.debug { "ERROR dispatch for ${request.requestURI} status=${response.status} errorPage=${location ?: "<default>"}" }
		val previousType = request._dispatcherType
		val previousUri = request._dispatchUri
		request._dispatcherType = DispatcherType.ERROR
		response.resetForError()
		try {
			if (location != null) {
				val target = location.removePrefix(config.contextPath).ifEmpty { "/" }
				request._dispatchUri = config.contextPath + target
				if (!runChain(target, request, response)) {
					response.renderDefaultError()
				}
			} else {
				response.renderDefaultError()
			}
		} catch (e: Exception) {
			log.error("Error dispatch failed: ${request.requestURI}", e)
			response.renderDefaultError()
		} finally {
			request._dispatcherType = previousType
			request._dispatchUri = previousUri
			response.markCommitted()
		}
	}

	private fun findErrorPage(status: Int, exception: Throwable?): String? {
		errorPages.firstOrNull { it.statusCode == status }?.let { return it.location }
		if (exception != null) {
			var clazz: Class<*>? = exception.javaClass
			while (clazz != null) {
				val type = clazz
				errorPages.firstOrNull { it.exceptionType == type }?.let { return it.location }
				clazz = clazz.superclass
			}
		}
		return errorPages.firstOrNull { it.statusCode == null && it.exceptionType == null }?.location
	}

	internal fun runChain(path: String, request: HttpServletRequestImpl, response: HttpServletResponseImpl): Boolean {
		val matched = matchServlet(path) ?: return false
		val (registration, pattern) = matched
		val (servletPath, pathInfo) = UrlPatternMatcher.servletPath(pattern, path)
		request._servletPath = servletPath
		request._pathInfo = pathInfo
		request._servletName = registration.name
		request._mappingPattern = pattern
		request._matchPath = path
		request._multipartConfig = registration.multipartConfig
		val type = request._dispatcherType
		val matchedFilters = filters.mapNotNull { f ->
			f.match(path, registration.name, type)?.let { f to it }
		}
		val chainFilters = matchedFilters.filter { !it.second.matchAfter }.map { it.first.filter } +
			matchedFilters.filter { it.second.matchAfter }.map { it.first.filter }
		request._asyncSupported = registration.asyncSupported &&
			matchedFilters.all { it.first.asyncSupported }
		log.debug { "Dispatch $type '$path' -> servlet '${registration.name}' pattern='$pattern' filters=${matchedFilters.map { it.first.name }}" }
		FilterChainImpl(chainFilters, registration.servlet).doFilter(request, response)
		return true
	}

	private fun matchServlet(path: String): Pair<ServletRegistrationImpl, String>? {
		for (registration in servlets.values) {
			UrlPatternMatcher.match(registration.mappings, path)?.let {
				return registration to it
			}
		}
		return null
	}

	internal fun dispatchAsync(async: AsyncContextImpl, path: String) {
		val request = async.request
		val response = async.response
		log.debug { "Async dispatch to '$path' for ${request.requestURI}" }
		request.resetAsyncForDispatch()
		val previousUri = request._dispatchUri
		try {
			val target = path.removePrefix(config.contextPath).ifEmpty { "/" }
			request._dispatchUri = config.contextPath + target
			if (!runChain(target, request, response) && !response.isCommitted) {
				response.sendError(404)
			}
		} catch (e: Exception) {
			async.fireError(e)
			return
		} finally {
			request._dispatchUri = previousUri
		}
		if (!request.isAsyncStarted) {
			async.complete()
		}
	}

	internal fun scheduleAsyncTimeout(async: AsyncContextImpl) {
		val timeout = async.timeout
		if (timeout > 0) {
			async.timeoutFuture = scheduler.schedule({ async.fireTimeout() }, timeout, TimeUnit.MILLISECONDS)
		}
	}

	internal fun getRequestDispatcher(path: String): RequestDispatcher = RequestDispatcherImpl(this, path)
}

/** 按路径获取的 RequestDispatcher：forward 全量移交、include 局部嵌入，均重走 Filter 链。 */
internal class RequestDispatcherImpl(
	private val container: ServletContainer,
	private val path: String,
) : RequestDispatcher {
	override fun forward(request: ServletRequest, response: ServletResponse) {
		check(!response.isCommitted) { "Response already committed" }
		response.resetBuffer()
		val req = request as HttpServletRequestImpl
		log.debug { "Forward ${req.requestURI} -> $path" }
		req.setAttribute("jakarta.servlet.forward.request_uri", req.requestURI)
		req.setAttribute("jakarta.servlet.forward.context_path", req.contextPath)
		req.setAttribute("jakarta.servlet.forward.servlet_path", req.servletPath)
		req.pathInfo?.let { req.setAttribute("jakarta.servlet.forward.path_info", it) }
		req.queryString?.let { req.setAttribute("jakarta.servlet.forward.query_string", it) }
		val previous = req._dispatcherType
		val previousUri = req._dispatchUri
		req._dispatcherType = DispatcherType.FORWARD
		req._dispatchUri = req.contextPath + path
		try {
			if (!container.runChain(path, req, response as HttpServletResponseImpl)) {
				response.sendError(404)
			}
		} finally {
			req._dispatcherType = previous
			req._dispatchUri = previousUri
		}
	}

	override fun include(request: ServletRequest, response: ServletResponse) {
		val req = request as HttpServletRequestImpl
		log.debug { "Include '$path' into ${req.requestURI}" }
		val previous = req._dispatcherType
		req.setAttribute("jakarta.servlet.include.request_uri", req.contextPath + path)
		req.setAttribute("jakarta.servlet.include.context_path", req.contextPath)
		req.setAttribute("jakarta.servlet.include.servlet_path", path)
		req.setAttribute("jakarta.servlet.include.path_info", null)
		req.setAttribute("jakarta.servlet.include.query_string", null)
		req._dispatcherType = DispatcherType.INCLUDE
		try {
			container.runChain(path, req, response as HttpServletResponseImpl)
		} finally {
			req._dispatcherType = previous
		}
	}

	companion object {
		private val log = Loggers.getLogger<RequestDispatcherImpl>()
	}
}

/** 按名称获取的 RequestDispatcher：直接调用目标 Servlet，不重走 Filter 链。 */
internal class NamedRequestDispatcher(private val servlet: Servlet) : RequestDispatcher {
	override fun forward(request: ServletRequest, response: ServletResponse) {
		check(!response.isCommitted) { "Response already committed" }
		response.resetBuffer()
		val req = request as HttpServletRequestImpl
		val previous = req._dispatcherType
		req._dispatcherType = DispatcherType.FORWARD
		try {
			servlet.service(request, response)
		} finally {
			req._dispatcherType = previous
		}
	}

	override fun include(request: ServletRequest, response: ServletResponse) {
		val req = request as HttpServletRequestImpl
		val previous = req._dispatcherType
		req._dispatcherType = DispatcherType.INCLUDE
		try {
			servlet.service(request, response)
		} finally {
			req._dispatcherType = previous
		}
	}
}