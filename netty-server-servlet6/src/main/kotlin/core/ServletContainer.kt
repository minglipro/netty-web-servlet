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

		internal const val PRINCIPAL_KEY = "com.mingliqiye.web.netty.servlet.security.principal"
		internal const val AUTH_TYPE_KEY = "com.mingliqiye.web.netty.servlet.security.authType"
		internal const val SAVED_REQUEST_KEY = "com.mingliqiye.web.netty.servlet.security.savedRequest"
	}

	private val contextImpl = ServletContextImpl(this)

	/** 应用级 ServletContext。 */
	val context: jakarta.servlet.ServletContext = contextImpl
	internal val bus = ListenerBus(contextImpl)

	/** 会话管理器。 */
	val sessions = SessionManager(context).also {
		it.bus = bus
		it.defaultTimeoutSeconds = config.sessionTimeoutSeconds
		it.store = config.sessionStore
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

	/** declareRoles 声明的角色集合。 */
	internal val declaredRoles = mutableSetOf<String>()

	private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
		Thread(r, "servlet-async-timeout").apply { isDaemon = true }
	}

	internal val servlets = linkedMapOf<String, ServletRegistrationImpl>()
	internal val filters = mutableListOf<FilterRegistrationImpl>()

	/** JSP 引擎：经 ServiceLoader 发现，引入 netty-server-jsp 模块时非 null。惰性加载以取用启动期 TCCL。 */
	internal val jspEngine: com.mingliqiye.web.netty.servlet.jsp.JspEngine? by lazy {
		java.util.ServiceLoader.load(com.mingliqiye.web.netty.servlet.jsp.JspEngine::class.java).firstOrNull()
	}

	private var jspInitialized = false

	/** 确保 JSP 引擎运行环境已就绪（工作目录/编译类路径/全局工厂），幂等。 */
	internal fun ensureJspEngine() {
		val engine = jspEngine ?: return
		if (!jspInitialized) {
			engine.initialize(contextImpl)
			jspInitialized = true
			log.debug { "JSP engine initialized: ${engine.javaClass.name}" }
		}
	}

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

	/** 容器初始化：docRoot 自动注册默认 Servlet → contextInitialized 监听器 → 挂载 JSP 引擎（*.jsp）→ loadOnStartup 升序初始化 Servlet → 初始化 Filter。 */
	fun init() {
		sessions.restore()
		if (config.docRoot != null && servlets.values.none { "/" in it.mappings }) {
			addServlet("default", com.mingliqiye.web.netty.servlet.resources.DefaultServlet(), "/")
		}
		bus.contextInitialized()
		contextImpl.initialized = true
		jspEngine?.let { engine ->
			ensureJspEngine()
			if (servlets.values.none { reg -> reg.mappings.any { it == "*.jsp" || it == "*.jspx" } }) {
				addServlet("jsp", engine.createServlet(), "*.jsp", "*.jspx")
				log.debug { "JSP servlet mounted at *.jsp/*.jspx" }
			}
		}
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
		response.servletRequest = request
		bus.requestInitialized(request)
		log.debug { "Request ${nettyRequest.method()} ${request.requestURI} (active=$activeRequests)" }
		try {
			if (com.mingliqiye.web.netty.server.io.WebSockets.isUpgradeRequest(nettyRequest) &&
				webSockets.tryUpgrade(request, response)
			) {
				log.debug { "Request ${request.requestURI} upgraded to WebSocket (native endpoint)" }
			} else {
				val path = request.requestURI.removePrefix(config.contextPath).ifEmpty { "/" }
				if (config.realm != null) {
					authenticateBasicIfPresent(request)
				}
				val handled = if (isFormLoginPost(path, request)) {
					handleFormLogin(request, response)
					true
				} else {
					runChain(path, request, response)
				}
				if (!handled) {
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
		if (config.realm != null && request._dispatcherType == DispatcherType.REQUEST) {
			val security = registration.servletSecurity
			if (security != null) {
				val constraint = security.httpMethodConstraints.firstOrNull { it.methodName == request.method } ?: security
				if (!enforceConstraint(constraint, request, response)) return true
			}
		}
		log.debug { "Dispatch $type '$path' -> servlet '${registration.name}' pattern='$pattern' filters=${matchedFilters.map { it.first.name }}" }
		val previousRunAs = request._runAs
		registration.runAsRoleValue?.let { request._runAs = com.mingliqiye.web.netty.servlet.security.RealmPrincipal(it, setOf(it)) }
		try {
			FilterChainImpl(chainFilters, registration.servlet).doFilter(request, response)
		} finally {
			request._runAs = previousRunAs
		}
		return true
	}

	private fun matchServlet(path: String): Pair<ServletRegistrationImpl, String>? {
		for (registration in servlets.values) {
			if (path in registration.mappings) return registration to path
		}
		var best: Pair<ServletRegistrationImpl, String>? = null
		for (registration in servlets.values) {
			for (pattern in registration.mappings) {
				if (pattern.endsWith("/*") && UrlPatternMatcher.matches(pattern, path) &&
					(best == null || pattern.length > best.second.length)
				) {
					best = registration to pattern
				}
			}
		}
		best?.let { return it }
		for (registration in servlets.values) {
			registration.mappings.firstOrNull { it.startsWith("*.") && UrlPatternMatcher.matches(it, path) }
				?.let { return registration to it }
		}
		for (registration in servlets.values) {
			if ("/" in registration.mappings) return registration to "/"
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

	// ---- 容器安全（realm 非 null 时启用） ----

	/** 从会话读取已认证主体。 */
	internal fun principalOf(request: HttpServletRequestImpl): com.mingliqiye.web.netty.servlet.security.RealmPrincipal? {
		val session = request.getSession(false) ?: return null
		return session.getAttribute(PRINCIPAL_KEY) as? com.mingliqiye.web.netty.servlet.security.RealmPrincipal
	}

	/** 登录成功：主体与认证方式写入会话并更换会话 ID（防会话固定）。 */
	internal fun establishLogin(
		request: HttpServletRequestImpl,
		principal: com.mingliqiye.web.netty.servlet.security.RealmPrincipal,
		authType: String,
	) {
		val session = request.getSession(true)!!
		session.setAttribute(PRINCIPAL_KEY, principal)
		session.setAttribute(AUTH_TYPE_KEY, authType)
		request.changeSessionId()
		log.debug { "Login established for ${principal.username} (authType=$authType)" }
	}

	private fun decodeBasic(header: String): com.mingliqiye.web.netty.servlet.security.RealmPrincipal? {
		if (!header.startsWith("Basic ", true)) return null
		return try {
			val decoded = String(java.util.Base64.getDecoder().decode(header.substring(6).trim()), Charsets.UTF_8)
			config.realm?.authenticate(decoded.substringBefore(':'), decoded.substringAfter(':', ""))
		} catch (e: IllegalArgumentException) {
			null
		}
	}

	private fun authenticateBasicIfPresent(request: HttpServletRequestImpl) {
		val header = request.getHeader("Authorization") ?: return
		val principal = decodeBasic(header) ?: return
		if (principalOf(request)?.username == principal.username) return
		establishLogin(request, principal, com.mingliqiye.web.netty.servlet.security.AuthMethod.BASIC.name)
	}

	/** 触发认证：BASIC 凭据通过或已认证返回 true；否则发 401 挑战或 FORM 登录跳转并返回 false。 */
	internal fun challenge(request: HttpServletRequestImpl, response: HttpServletResponseImpl): Boolean {
		val login = config.loginConfig ?: com.mingliqiye.web.netty.servlet.security.LoginConfig()
		if (login.authMethod == com.mingliqiye.web.netty.servlet.security.AuthMethod.BASIC) {
			request.getHeader("Authorization")?.let { header ->
				decodeBasic(header)?.let {
					establishLogin(request, it, com.mingliqiye.web.netty.servlet.security.AuthMethod.BASIC.name)
					return true
				}
			}
			response.setHeader("WWW-Authenticate", "Basic realm=\"${login.realmName}\"")
			response.sendError(401)
			return false
		}
		val session = request.getSession(true)!!
		session.setAttribute(
			SAVED_REQUEST_KEY,
			com.mingliqiye.web.netty.servlet.security.SavedRequest(request.method, request.requestURI, request.queryString),
		)
		response.sendRedirect(config.contextPath + (login.loginPage ?: "/login.html"))
		return false
	}

	private fun isFormLoginPost(path: String, request: HttpServletRequestImpl): Boolean =
		config.realm != null &&
			config.loginConfig?.authMethod == com.mingliqiye.web.netty.servlet.security.AuthMethod.FORM &&
			path == "/j_security_check" &&
			request.method == "POST"

	private fun handleFormLogin(request: HttpServletRequestImpl, response: HttpServletResponseImpl) {
		val login = config.loginConfig!!
		val username = request.getParameter("j_username") ?: ""
		val password = request.getParameter("j_password") ?: ""
		val principal = config.realm?.authenticate(username, password)
		if (principal == null) {
			log.debug { "FORM login failed for '$username'" }
			response.sendRedirect(config.contextPath + (login.errorPage ?: "/login.html"))
			return
		}
		val session = request.getSession(true)!!
		val saved = session.getAttribute(SAVED_REQUEST_KEY) as? com.mingliqiye.web.netty.servlet.security.SavedRequest
		session.removeAttribute(SAVED_REQUEST_KEY)
		establishLogin(request, principal, com.mingliqiye.web.netty.servlet.security.AuthMethod.FORM.name)
		val target = saved?.let { it.uri + if (it.query != null) "?${it.query}" else "" }
			?: config.contextPath + "/"
		log.debug { "FORM login ok for '$username', redirecting to $target" }
		response.sendRedirect(target)
	}

	/** 角色约束执行：PERMIT 放行；未认证触发挑战；已认证但角色不符/DENY 回 403。 */
	private fun enforceConstraint(
		constraint: jakarta.servlet.HttpConstraintElement,
		request: HttpServletRequestImpl,
		response: HttpServletResponseImpl,
	): Boolean {
		val roles = constraint.rolesAllowed
		val deny = constraint.emptyRoleSemantic == jakarta.servlet.annotation.ServletSecurity.EmptyRoleSemantic.DENY
		if (roles.isEmpty() && !deny) return true
		val principal = principalOf(request)
		if (principal == null) {
			challenge(request, response)
			return false
		}
		if (deny || roles.none { it in principal.roles }) {
			log.debug { "Access denied for ${principal.username} to ${request.requestURI} (required=${roles.toList()})" }
			response.sendError(403)
			return false
		}
		return true
	}
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
