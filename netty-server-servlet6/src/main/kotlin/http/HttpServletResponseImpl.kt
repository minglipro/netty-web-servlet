package com.mingliqiye.web.netty.servlet.http

import com.mingliqiye.web.netty.server.io.ResponseStream
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.cookie.DefaultCookie
import io.netty.handler.codec.http.cookie.ServerCookieEncoder
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletResponse
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TreeMap

/**
 * HttpServletResponse 实现：缓冲输出，写满 [bufferSize] 或显式 flushBuffer 时切换为 chunked 流式输出。
 *
 * 错误语义：[sendError] 只记录错误状态（不写 body），由容器执行 ERROR 分发；
 * 无错误页时 [renderDefaultError] 输出纯文本兜底。[resetForError] 清空缓冲与头部但保留 Set-Cookie。
 *
 * @param initialBufferSize 初始缓冲区大小（字节）
 */
internal class HttpServletResponseImpl(private val initialBufferSize: Int = 8192) : HttpServletResponse {
	companion object {
		private val log = com.mingliqiye.logger.Loggers.getLogger<HttpServletResponseImpl>()
	}

	private val headers = TreeMap<String, MutableList<String>>(String.CASE_INSENSITIVE_ORDER)
	private val buffer = object : ByteArrayOutputStream(initialBufferSize) {
		override fun write(b: Int) {
			super.write(b)
			autoFlush()
		}

		override fun write(b: ByteArray, off: Int, len: Int) {
			super.write(b, off, len)
			autoFlush()
		}
	}

	private var status = 200
	private var committed = false
	private var bufferSize = initialBufferSize
	private var charset: String? = null
	private var contentType: String? = null
	private var locale: Locale = Locale.getDefault()
	private var outputStream: BufferServletOutputStream? = null
	private var writer: PrintWriter? = null

	private var attachCtx: ChannelHandlerContext? = null
	private var attachRequest: HttpRequest? = null
	private var stream: ResponseStream? = null
	private var flushing = false

	private var error = false
	internal var errorMessage: String? = null
	internal var errorException: Throwable? = null

	/** ERROR 分发是否已执行（防止递归分发）。 */
	internal var errorDispatched = false

	/** 容器级默认响应编码（ServletContext.setResponseCharacterEncoding）。 */
	internal var defaultEncoding: String? = null

	/** 当前缓冲内容快照。 */
	val body: ByteArray get() = buffer.toByteArray()

	/** 是否已切换为 chunked 流式输出。 */
	val isStreaming: Boolean get() = stream != null

	/** 是否处于 sendError 错误状态。 */
	val isError: Boolean get() = error

	internal fun attach(ctx: ChannelHandlerContext, request: HttpRequest) {
		attachCtx = ctx
		attachRequest = request
	}

	private fun autoFlush() {
		if (!flushing && !committed && buffer.size() >= bufferSize) {
			flushBuffer()
		}
	}

	override fun addCookie(cookie: Cookie) {
		if (committed) return
		val netty = DefaultCookie(cookie.name, cookie.value)
		cookie.domain?.let { netty.setDomain(it) }
		cookie.path?.let { netty.setPath(it) }
		if (cookie.maxAge >= 0) {
			netty.setMaxAge(cookie.maxAge.toLong())
		}
		netty.isSecure = cookie.secure
		netty.isHttpOnly = cookie.isHttpOnly
		addHeader("Set-Cookie", ServerCookieEncoder.STRICT.encode(netty))
	}

	override fun containsHeader(name: String): Boolean = headers.containsKey(name)

	override fun encodeURL(url: String): String = url

	override fun encodeRedirectURL(url: String): String = url

	override fun sendError(sc: Int) = sendError(sc, null)

	override fun sendError(sc: Int, msg: String?) {
		check(!committed) { "Response already committed" }
		log.debug { "sendError $sc ${msg ?: ""}" }
		buffer.reset()
		status = sc
		errorMessage = msg
		error = true
		committed = true
	}

	/** ERROR 分发前重置：清缓冲与头部，保留 Set-Cookie（会话 Cookie 不丢），解除提交状态。 */
	internal fun resetForError() {
		buffer.reset()
		val cookies = headers["Set-Cookie"]
		headers.clear()
		cookies?.let { headers["Set-Cookie"] = it }
		contentType = null
		committed = false
	}

	/** 无错误页匹配时的兜底输出：纯文本 errorMessage 或 "HTTP Status xxx"。 */
	internal fun renderDefaultError() {
		if (committed) return
		buffer.reset()
		contentType = "text/plain;charset=UTF-8"
		val text = errorMessage ?: "HTTP Status $status"
		buffer.write(text.toByteArray(Charsets.UTF_8))
	}

	internal fun markCommitted() {
		committed = true
	}

	override fun sendRedirect(location: String) = sendRedirect(location, 302, true)

	override fun sendRedirect(location: String, sc: Int, clearBuffer: Boolean) {
		check(!committed) { "Response already committed" }
		if (clearBuffer) {
			buffer.reset()
		}
		status = sc
		setHeader("Location", location)
		committed = true
	}

	override fun setDateHeader(name: String, date: Long) {
		setHeader(name, DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(date), ZoneOffset.UTC)))
	}

	override fun addDateHeader(name: String, date: Long) {
		addHeader(name, DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(date), ZoneOffset.UTC)))
	}

	override fun setHeader(name: String, value: String?) {
		if (committed) return
		if (value == null) headers.remove(name) else headers[name] = mutableListOf(value)
	}

	override fun addHeader(name: String, value: String?) {
		if (committed || value == null) return
		headers.getOrPut(name) { mutableListOf() }.add(value)
	}

	override fun setIntHeader(name: String, value: Int) = setHeader(name, value.toString())

	override fun addIntHeader(name: String, value: Int) = addHeader(name, value.toString())

	override fun setStatus(sc: Int) {
		if (committed) return
		status = sc
	}

	override fun getStatus(): Int = status

	override fun getHeader(name: String): String? = headers[name]?.firstOrNull()

	override fun getHeaders(name: String): Collection<String> = headers[name] ?: emptyList()

	override fun getHeaderNames(): Collection<String> = headers.keys

	override fun getCharacterEncoding(): String = charset ?: defaultEncoding ?: "ISO-8859-1"

	override fun getContentType(): String? = contentType

	override fun getOutputStream(): ServletOutputStream {
		check(writer == null) { "getWriter() already called" }
		return outputStream ?: BufferServletOutputStream(buffer) { flushBuffer() }.also { outputStream = it }
	}

	override fun getWriter(): PrintWriter {
		check(outputStream == null) { "getOutputStream() already called" }
		return writer ?: PrintWriter(java.io.OutputStreamWriter(buffer, charset(charset ?: defaultEncoding ?: "ISO-8859-1"))).also { writer = it }
	}

	override fun setCharacterEncoding(encoding: String) {
		if (!committed && writer == null) {
			charset = encoding
		}
	}

	override fun setContentLength(len: Int) = setIntHeader("Content-Length", len)

	override fun setContentLengthLong(len: Long) = setHeader("Content-Length", len.toString())

	override fun setContentType(type: String) {
		if (committed) return
		contentType = type
		val charsetPart = type.split(";").map { it.trim() }.firstOrNull { it.startsWith("charset=", true) }
		if (charsetPart != null && writer == null) {
			charset = charsetPart.substringAfter("=")
		}
	}

	override fun setBufferSize(size: Int) {
		check(!committed && buffer.size() == 0) { "Buffer size cannot be changed after content written" }
		bufferSize = size
	}

	override fun getBufferSize(): Int = bufferSize

	override fun flushBuffer() {
		if (flushing) return
		flushing = true
		try {
			writer?.flush()
			if (stream == null) {
				val ctx = checkNotNull(attachCtx) { "Response not attached to a channel" }
				val request = checkNotNull(attachRequest) { "Response not attached to a request" }
				log.debug { "Response committed, switching to chunked streaming (status=$status)" }
				val head = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status))
				for ((name, values) in headers) {
					if (name.equals("Content-Length", true)) continue
					head.headers().set(name, values)
				}
				contentType?.let { head.headers().set("Content-Type", it) }
				HttpUtil.setTransferEncodingChunked(head, true)
				stream = ResponseStream(ctx, request).also { it.start(head) }
			}
			sendBuffered()
			committed = true
		} finally {
			flushing = false
		}
	}

	private fun sendBuffered() {
		if (buffer.size() > 0) {
			stream?.writeChunk(Unpooled.wrappedBuffer(buffer.toByteArray()))
			buffer.reset()
		}
	}

	internal fun finishStream() {
		writer?.flush()
		sendBuffered()
		stream?.finish()
	}

	override fun resetBuffer() {
		check(!committed) { "Response already committed" }
		buffer.reset()
	}

	override fun isCommitted(): Boolean = committed

	override fun reset() {
		check(!committed) { "Response already committed" }
		buffer.reset()
		headers.clear()
		status = 200
		error = false
		errorMessage = null
		errorException = null
		errorDispatched = false
	}

	override fun setLocale(loc: Locale) {
		if (committed) return
		locale = loc
		if (contentType == null) {
			contentType = "text/plain"
		}
	}

	override fun getLocale(): Locale = locale

	fun toFullHttpResponse(): FullHttpResponse {
		writer?.flush()
		val bytes = buffer.toByteArray()
		val response = DefaultFullHttpResponse(
			HttpVersion.HTTP_1_1,
			HttpResponseStatus.valueOf(status),
			Unpooled.wrappedBuffer(bytes),
		)
		for ((name, values) in headers) {
			if (name.equals("Content-Length", true)) continue
			response.headers().set(name, values)
		}
		contentType?.let { response.headers().set("Content-Type", it) }
		response.headers().set("Content-Length", bytes.size)
		return response
	}
}