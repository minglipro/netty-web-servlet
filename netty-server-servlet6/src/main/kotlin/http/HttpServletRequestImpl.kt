package com.mingliqiye.web.netty.servlet.http

import com.mingliqiye.web.netty.server.io.RequestBody
import com.mingliqiye.web.netty.servlet.async.AsyncContextImpl
import com.mingliqiye.web.netty.servlet.core.ServletContainer
import com.mingliqiye.web.netty.servlet.multipart.FileItemPart
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.handler.codec.http.cookie.ServerCookieDecoder
import jakarta.servlet.AsyncContext
import jakarta.servlet.DispatcherType
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.ServletConnection
import jakarta.servlet.ServletContext
import jakarta.servlet.ServletException
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletMapping
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import jakarta.servlet.http.MappingMatch
import jakarta.servlet.http.Part
import org.apache.commons.fileupload2.core.DiskFileItemFactory
import org.apache.commons.fileupload2.jakarta.servlet6.JakartaServletFileUpload
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.Charset
import java.security.Principal
import java.util.Collections
import java.util.Enumeration
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * HttpServletRequest 实现：包装 Netty 请求头与流式 [RequestBody]。
 *
 * 容器特定语义：
 * - 参数惰性解析（query + form-urlencoded），编码取 setCharacterEncoding > 容器默认 > UTF-8；
 * - multipart 限额优先取每 Servlet 的 MultipartConfig，缺省回退容器全局配置；
 * - ERROR/FORWARD/ASYNC 分发期间 [_dispatchUri] 重写 requestURI/queryString；
 * - 会话仅 Cookie 跟踪，Cookie 属性遵循 SessionCookieConfig。
 */
internal class HttpServletRequestImpl(
	val nettyRequest: HttpRequest,
	val body: RequestBody,
	val ctx: ChannelHandlerContext,
	val container: ServletContainer,
	val servletResponse: HttpServletResponseImpl,
) : HttpServletRequest {
	companion object {
		private val log = com.mingliqiye.logger.Loggers.getLogger<HttpServletRequestImpl>()
	}

	private val attributes = ConcurrentHashMap<String, Any>()
	private val decoder = QueryStringDecoder(nettyRequest.uri())

	private var charset: Charset? = null
	private var parameters: MutableMap<String, MutableList<String>>? = null
	private var parts: List<Part>? = null
	private var session: HttpSession? = null
	private var asyncContext: AsyncContextImpl? = null

	internal var _servletPath: String = ""
	internal var _pathInfo: String? = null
	internal var _dispatcherType: DispatcherType = DispatcherType.REQUEST
	internal var _servletName: String = ""
	internal var _mappingPattern: String? = null
	internal var _matchPath: String = ""
	internal var _multipartConfig: jakarta.servlet.MultipartConfigElement? = null
	internal var _asyncSupported: Boolean = true

	/** 分发期间的 URI 重写（ERROR/FORWARD/ASYNC），null 表示原始请求 URI。 */
	internal var _dispatchUri: String? = null

	/** 请求已升级为 WebSocket：容器不再写出 HTTP 响应，连接由 WS 会话接管。 */
	internal var _upgraded = false

	private val _requestId: String = UUID.randomUUID().toString()

	override fun getMethod(): String = nettyRequest.method().name()

	override fun getRequestURI(): String = _dispatchUri?.substringBefore('?') ?: decoder.path()

	override fun getRequestURL(): StringBuffer {
		val host = getHeader("Host") ?: "${getLocalName()}:${getLocalPort()}"
		return StringBuffer("$scheme://$host$requestURI")
	}

	override fun getQueryString(): String? =
		(_dispatchUri ?: nettyRequest.uri()).substringAfter('?', "").ifEmpty { null }

	override fun getContextPath(): String = container.config.contextPath

	override fun getServletPath(): String = _servletPath

	override fun getPathInfo(): String? = _pathInfo

	override fun getPathTranslated(): String? = null

	override fun getProtocol(): String = nettyRequest.protocolVersion().text()

	override fun getScheme(): String = "http"

	override fun getServerName(): String = getHeader("Host")?.substringBefore(':') ?: getLocalName()

	override fun getServerPort(): Int =
		getHeader("Host")?.substringAfter(':', "")?.toIntOrNull() ?: getLocalPort()

	override fun getRemoteAddr(): String =
		(ctx.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress ?: ""

	override fun getRemoteHost(): String =
		(ctx.channel().remoteAddress() as? InetSocketAddress)?.hostString ?: getRemoteAddr()

	override fun getRemotePort(): Int =
		(ctx.channel().remoteAddress() as? InetSocketAddress)?.port ?: 0

	override fun getLocalName(): String =
		(ctx.channel().localAddress() as? InetSocketAddress)?.address?.hostAddress ?: ""

	override fun getLocalAddr(): String = getLocalName()

	override fun getLocalPort(): Int =
		(ctx.channel().localAddress() as? InetSocketAddress)?.port ?: 0

	override fun isSecure(): Boolean = false

	override fun getHeader(name: String): String? = nettyRequest.headers().get(name)

	override fun getHeaders(name: String): Enumeration<String> =
		Collections.enumeration(nettyRequest.headers().getAll(name))

	override fun getHeaderNames(): Enumeration<String> =
		Collections.enumeration(nettyRequest.headers().names().toList())

	override fun getIntHeader(name: String): Int = getHeader(name)?.toIntOrNull() ?: -1

	override fun getDateHeader(name: String): Long {
		val value = getHeader(name) ?: return -1
		return try {
			java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
				.toInstant().toEpochMilli()
		} catch (e: Exception) {
			throw IllegalArgumentException("Cannot parse date header $name: $value")
		}
	}

	override fun getCookies(): Array<Cookie>? {
		val header = getHeader("Cookie") ?: return null
		val decoded = ServerCookieDecoder.STRICT.decode(header)
		if (decoded.isEmpty()) return null
		return decoded.map { Cookie(it.name(), it.value()) }.toTypedArray()
	}

	override fun getContentType(): String? = nettyRequest.headers().get("Content-Type")

	override fun getContentLength(): Int = getContentLengthLong().toInt()

	override fun getContentLengthLong(): Long =
		nettyRequest.headers().get("Content-Length")?.toLongOrNull() ?: -1L

	override fun getCharacterEncoding(): String? = charset?.name()

	override fun setCharacterEncoding(encoding: String) {
		check(parameters == null) { "Parameters already parsed" }
		charset = Charset.forName(encoding)
	}

	private fun effectiveCharset(): Charset =
		charset ?: container.defaultRequestEncoding?.let { Charset.forName(it) } ?: Charsets.UTF_8

	override fun getInputStream(): ServletInputStream = NettyServletInputStream(body)

	override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(body.inputStream, effectiveCharset()))

	private fun parseParameters(): MutableMap<String, MutableList<String>> {
		parameters?.let { return it }
		val result = LinkedHashMap<String, MutableList<String>>()
		QueryStringDecoder(nettyRequest.uri(), effectiveCharset()).parameters()
			.forEach { (k, v) -> result.getOrPut(k) { mutableListOf() }.addAll(v) }
		val type = contentType
		if (type != null && type.startsWith("application/x-www-form-urlencoded", true)) {
			val formBody = body.inputStream.readBytes().toString(effectiveCharset())
			QueryStringDecoder("?$formBody", effectiveCharset()).parameters()
				.forEach { (k, v) -> result.getOrPut(k) { mutableListOf() }.addAll(v) }
		}
		parameters = result
		return result
	}

	override fun getParameter(name: String): String? = parseParameters()[name]?.firstOrNull()

	override fun getParameterNames(): Enumeration<String> =
		Collections.enumeration(parseParameters().keys)

	override fun getParameterValues(name: String): Array<String>? =
		parseParameters()[name]?.toTypedArray()

	override fun getParameterMap(): Map<String, Array<String>> =
		parseParameters().mapValues { it.value.toTypedArray() }

	override fun getParts(): Collection<Part> {
		val type = contentType
		if (type == null || !type.startsWith("multipart/", true)) {
			throw ServletException("Request is not multipart")
		}
		parts?.let { return it }
		val mc = _multipartConfig
		log.debug { "Parsing multipart for $requestURI (perServletConfig=${mc != null})" }
		val factory = DiskFileItemFactory.builder()
		mc?.location?.takeIf { it.isNotEmpty() }?.let { factory.setPath(java.nio.file.Path.of(it)) }
		val upload = JakartaServletFileUpload(factory.get())
		upload.fileSizeMax = mc?.maxFileSize?.let { if (it < 0) Long.MAX_VALUE else it }
			?: container.config.multipartMaxFileSize
		upload.sizeMax = mc?.maxRequestSize?.let { if (it < 0) Long.MAX_VALUE else it }
			?: container.config.multipartMaxRequestSize
		val items = upload.parseRequest(this)
		val result = mutableListOf<Part>()
		for (item in items) {
			if (item.isFormField) {
				parseParameters().getOrPut(item.fieldName) { mutableListOf() }
					.add(item.getString(effectiveCharset()))
			} else {
				result.add(FileItemPart(item))
			}
		}
		parts = result
		log.debug { "Multipart parsed: ${result.size} file part(s) for $requestURI" }
		return result
	}

	override fun getPart(name: String): Part? {
		val type = contentType
		if (type == null || !type.startsWith("multipart/", true)) return null
		return getParts().firstOrNull { it.name == name }
	}

	override fun getAttribute(name: String): Any? = attributes[name]

	override fun getAttributeNames(): Enumeration<String> = Collections.enumeration(attributes.keys)

	override fun setAttribute(name: String, value: Any?) {
		if (value == null) {
			removeAttribute(name)
			return
		}
		val old = attributes.put(name, value)
		if (old == null) {
			container.bus.requestAttributeAdded(this, name, value)
		} else {
			container.bus.requestAttributeReplaced(this, name, old)
		}
	}

	override fun removeAttribute(name: String) {
		val old = attributes.remove(name) ?: return
		container.bus.requestAttributeRemoved(this, name, old)
	}

	override fun getLocale(): Locale = locales.nextElement()

	override fun getLocales(): Enumeration<Locale> {
		val header = getHeader("Accept-Language")
			?: return Collections.enumeration(listOf(Locale.getDefault()))
		val locales = header.split(",").mapNotNull {
			val tag = it.trim().substringBefore(';')
			runCatching { Locale.forLanguageTag(tag) }.getOrNull()
		}.ifEmpty { listOf(Locale.getDefault()) }
		return Collections.enumeration(locales)
	}

	override fun getRequestedSessionId(): String? =
		cookies?.firstOrNull { it.name == servletContext.sessionCookieConfig.name }?.value

	override fun getSession(create: Boolean): HttpSession? {
		session?.let {
			if ((it as com.mingliqiye.web.netty.servlet.session.HttpSessionImpl).valid) return it
		}
		val manager = container.sessions
		var found = manager.find(requestedSessionId)
		if (found == null && create) {
			found = manager.create()
			servletResponse.addCookie(sessionCookie(found.id))
		}
		session = found
		return found
	}

	override fun getSession(): HttpSession? = getSession(true)

	override fun changeSessionId(): String {
		val current = session as? com.mingliqiye.web.netty.servlet.session.HttpSessionImpl
			?: throw IllegalStateException("No session associated with request")
		val renewed = container.sessions.changeId(current)
		session = renewed
		servletResponse.addCookie(sessionCookie(renewed.id))
		return renewed.id
	}

	private fun sessionCookie(id: String): Cookie {
		val cfg = servletContext.sessionCookieConfig
		val cookie = Cookie(cfg.name, id)
		cookie.path = cfg.path ?: contextPath.ifEmpty { "/" }
		cfg.domain?.let { cookie.domain = it }
		cookie.isHttpOnly = cfg.isHttpOnly
		cookie.secure = cfg.isSecure
		if (cfg.maxAge >= 0) cookie.maxAge = cfg.maxAge
		return cookie
	}

	override fun isRequestedSessionIdValid(): Boolean =
		container.sessions.find(requestedSessionId) != null

	override fun isRequestedSessionIdFromCookie(): Boolean = requestedSessionId != null

	override fun isRequestedSessionIdFromURL(): Boolean = false

	override fun getAuthType(): String? = null

	override fun getRemoteUser(): String? = null

	override fun isUserInRole(role: String): Boolean = false

	override fun getUserPrincipal(): Principal? = null

	override fun authenticate(response: HttpServletResponse): Boolean =
		throw UnsupportedOperationException("Authentication not supported")

	override fun login(username: String, password: String) =
		throw UnsupportedOperationException("Login not supported")

	override fun logout() = throw UnsupportedOperationException("Logout not supported")

	override fun <T : jakarta.servlet.http.HttpUpgradeHandler> upgrade(handlerClass: Class<T>): T =
		throw UnsupportedOperationException("Upgrade not supported")

	override fun getRequestDispatcher(path: String): RequestDispatcher? =
		container.getRequestDispatcher(path)

	override fun getServletContext(): ServletContext = container.context

	override fun startAsync(): AsyncContext = startAsync(this, servletResponse)

	override fun startAsync(request: ServletRequest, response: ServletResponse): AsyncContext {
		check(isAsyncSupported) { "Async not supported for this request" }
		check(!isAsyncStarted) { "Async already started" }
		val async = AsyncContextImpl(this, servletResponse, container, request, response)
		asyncContext = async
		container.scheduleAsyncTimeout(async)
		log.debug { "Async started for $requestURI (timeout=${async.timeout}ms)" }
		return async
	}

	override fun isAsyncStarted(): Boolean = asyncContext != null && !asyncContext!!.completed

	override fun isAsyncSupported(): Boolean = _asyncSupported

	override fun getAsyncContext(): AsyncContext =
		asyncContext ?: throw IllegalStateException("Async not started")

	internal fun asyncCompleted() {
		asyncContext = null
	}

	internal fun resetAsyncForDispatch() {
		asyncContext = null
		_dispatcherType = DispatcherType.ASYNC
	}

	override fun getDispatcherType(): DispatcherType = _dispatcherType

	override fun getHttpServletMapping(): HttpServletMapping {
		val pattern = _mappingPattern
		val match = when {
			pattern == null -> MappingMatch.DEFAULT
			pattern.isEmpty() -> MappingMatch.CONTEXT_ROOT
			pattern == "/" -> MappingMatch.DEFAULT
			pattern.startsWith("*.") -> MappingMatch.EXTENSION
			pattern.endsWith("/*") -> MappingMatch.PATH
			else -> MappingMatch.EXACT
		}
		val matchValue = when (match) {
			MappingMatch.EXTENSION -> pattern!!.substring(2)
			MappingMatch.PATH -> _pathInfo?.removePrefix("/") ?: ""
			MappingMatch.EXACT -> _matchPath
			else -> ""
		}
		return object : HttpServletMapping {
			override fun getMatchValue(): String = matchValue
			override fun getPattern(): String = pattern ?: ""
			override fun getServletName(): String = _servletName
			override fun getMappingMatch(): MappingMatch = match
		}
	}

	override fun getRequestId(): String = _requestId

	override fun getProtocolRequestId(): String = ""

	override fun getServletConnection(): ServletConnection = object : ServletConnection {
		override fun getConnectionId(): String = _requestId
		override fun getProtocolConnectionId(): String = ""
		override fun getProtocol(): String = this@HttpServletRequestImpl.protocol
		override fun isSecure(): Boolean = false
	}

	override fun getTrailerFields(): Map<String, String> = emptyMap()

	override fun isTrailerFieldsReady(): Boolean = true
}