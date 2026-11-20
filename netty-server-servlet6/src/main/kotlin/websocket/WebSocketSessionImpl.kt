package com.mingliqiye.web.netty.servlet.websocket

import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import jakarta.websocket.CloseReason
import jakarta.websocket.DecodeException
import jakarta.websocket.Decoder
import jakarta.websocket.EncodeException
import jakarta.websocket.Encoder
import jakarta.websocket.EndpointConfig
import jakarta.websocket.Extension
import jakarta.websocket.MessageHandler
import jakarta.websocket.PongMessage
import jakarta.websocket.RemoteEndpoint
import jakarta.websocket.SendHandler
import jakarta.websocket.SendResult
import jakarta.websocket.Session
import jakarta.websocket.WebSocketContainer
import java.io.IOException
import java.io.OutputStream
import java.io.Writer
import java.net.URI
import java.nio.ByteBuffer
import java.security.Principal
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 消息处理器登记项：处理器本体 + 消息类型 + 是否分片（Partial）模式。 */
internal class HandlerEntry(
	val handler: MessageHandler,
	val messageType: Class<*>,
	val partial: Boolean,
)

/**
 * jakarta.websocket.Session 实现：包装一条已完成 101 握手的 Netty 连接。
 *
 * 消息分发支持 Whole/Partial 两种处理器（按注册类型匹配），分片消息由 [WebSocketFrameHandler]
 * 聚合后投递 Whole、逐片投递 Partial；发送端 Basic 阻塞语义在非事件循环线程等待写结果，
 * Async 返回 [CompletableFuture]。关闭走标准 Close 帧握手，对端无响应时 5 秒强制断开。
 */
internal class WebSocketSessionImpl(
	private val wsContainer: NettyWebSocketContainer,
	transport: WebSocketTransport,
	val config: EndpointConfig,
	val endpoint: jakarta.websocket.Endpoint,
	private val pathParameters: Map<String, String>,
	private val subprotocol: String,
	private val negotiatedExtensions: List<io.netty.handler.codec.http.websocketx.extensions.WebSocketServerExtension> = emptyList(),
) : Session {
	companion object {
		private val log = com.mingliqiye.logger.Loggers.getLogger<WebSocketSessionImpl>()
	}

	private val id = java.util.UUID.randomUUID().toString()

	/** 升级后原 HTTP 业务处理器已移出管线，出站必须经 channel（从管线尾端走编码器）。 */
	private val channel: Channel = transport.channel
	private val userProperties = ConcurrentHashMap<String, Any>().also { it.putAll(config.userProperties) }
	private val handlers = mutableListOf<HandlerEntry>()
	private val open = AtomicBoolean(true)
	private val closeFired = AtomicBoolean(false)

	@Volatile
	internal var closing = false

	@Volatile
	internal var closeReason: CloseReason? = null

	@Volatile
	var lastActivity: Long = System.currentTimeMillis()
		internal set

	@Volatile
	private var maxIdleTimeout = wsContainer.defaultMaxSessionIdleTimeout

	@Volatile
	private var maxBinaryBufferSize = wsContainer.defaultMaxBinaryMessageBufferSize

	@Volatile
	private var maxTextBufferSize = wsContainer.defaultMaxTextMessageBufferSize

	private val requestUri: URI = transport.requestUri
	private val parameterMap: Map<String, List<String>> = transport.parameterMap
	private val queryString: String? = transport.queryString
	private val userPrincipal: Principal? = transport.userPrincipal
	private val secure: Boolean = transport.secure

	private val basicRemote = BasicRemote()
	private val asyncRemote = AsyncRemote()

	override fun getContainer(): WebSocketContainer = wsContainer

	override fun getId(): String = id

	override fun isOpen(): Boolean = open.get() && channel.isActive

	override fun isSecure(): Boolean = secure

	override fun getProtocolVersion(): String = "13"

	override fun getNegotiatedSubprotocol(): String = subprotocol

	override fun getNegotiatedExtensions(): List<Extension> = negotiatedExtensions.map { ext ->
		val data = ext.newReponseData()
		WebSocketExtensionImpl(
			data.name(),
			data.parameters().map { (name, value) -> WebSocketParameterImpl(name, value ?: "") },
		)
	}

	override fun getRequestURI(): URI = requestUri

	override fun getQueryString(): String? = queryString

	override fun getRequestParameterMap(): Map<String, List<String>> = parameterMap

	override fun getPathParameters(): Map<String, String> = pathParameters

	override fun getUserProperties(): MutableMap<String, Any> = userProperties

	override fun getUserPrincipal(): Principal? = userPrincipal

	override fun getOpenSessions(): Set<Session> =
		wsContainer.openSessions.filter { it.isOpen && it.requestURI.path == requestUri.path }.toSet()

	override fun getMaxIdleTimeout(): Long = maxIdleTimeout

	override fun setMaxIdleTimeout(milliseconds: Long) {
		maxIdleTimeout = milliseconds
	}

	override fun getMaxBinaryMessageBufferSize(): Int = maxBinaryBufferSize

	override fun setMaxBinaryMessageBufferSize(length: Int) {
		maxBinaryBufferSize = length
	}

	override fun getMaxTextMessageBufferSize(): Int = maxTextBufferSize

	override fun setMaxTextMessageBufferSize(length: Int) {
		maxTextBufferSize = length
	}

	override fun getBasicRemote(): RemoteEndpoint.Basic = basicRemote

	override fun getAsyncRemote(): RemoteEndpoint.Async = asyncRemote

	// ---- 处理器注册 ----

	override fun addMessageHandler(handler: MessageHandler) {
		val entry = introspect(handler)
			?: throw IllegalStateException("Cannot determine message type of ${handler.javaClass.name}, use the typed addMessageHandler overloads")
		synchronized(handlers) {
			if (handlers.any { it.messageType == entry.messageType && it.partial == entry.partial }) {
				throw IllegalStateException("A handler for ${entry.messageType.name} (partial=${entry.partial}) is already registered")
			}
			handlers.add(entry)
		}
	}

	override fun <T> addMessageHandler(clazz: Class<T>, handler: MessageHandler.Whole<T>) {
		synchronized(handlers) {
			if (handlers.any { it.messageType == clazz && !it.partial }) {
				throw IllegalStateException("A whole handler for ${clazz.name} is already registered")
			}
			handlers.add(HandlerEntry(handler, clazz, false))
		}
	}

	override fun <T> addMessageHandler(clazz: Class<T>, handler: MessageHandler.Partial<T>) {
		synchronized(handlers) {
			if (handlers.any { it.messageType == clazz && it.partial }) {
				throw IllegalStateException("A partial handler for ${clazz.name} is already registered")
			}
			handlers.add(HandlerEntry(handler, clazz, true))
		}
	}

	override fun getMessageHandlers(): Set<MessageHandler> = synchronized(handlers) { handlers.map { it.handler }.toSet() }

	override fun removeMessageHandler(handler: MessageHandler) {
		synchronized(handlers) { handlers.removeAll { it.handler === handler } }
	}

	/** 反射识别裸 [MessageHandler] 的泛型接口（Whole<T>/Partial<T>），Spring 适配器走此路径。 */
	private fun introspect(handler: MessageHandler): HandlerEntry? {
		var clazz: Class<*>? = handler.javaClass
		while (clazz != null && clazz != Any::class.java) {
			for (iface in clazz.genericInterfaces) {
				if (iface is java.lang.reflect.ParameterizedType) {
					val raw = iface.rawType
					val arg = iface.actualTypeArguments.firstOrNull() as? Class<*> ?: continue
					when (raw) {
						MessageHandler.Whole::class.java -> return HandlerEntry(handler, arg, false)
						MessageHandler.Partial::class.java -> return HandlerEntry(handler, arg, true)
					}
				}
			}
			clazz = clazz.superclass
		}
		return null
	}

	// ---- 消息分发（由 WebSocketFrameHandler 调用） ----

	internal fun dispatchTextPartial(text: String, last: Boolean) {
		val matched = synchronized(handlers) { handlers.filter { it.partial && it.messageType.isAssignableFrom(String::class.java) } }
		for (entry in matched) {
			runCatching {
				@Suppress("UNCHECKED_CAST")
				(entry.handler as MessageHandler.Partial<Any>).onMessage(text, last)
			}.onFailure { fireError(it) }
		}
	}

	internal fun dispatchTextWhole(text: String) {
		val matched = synchronized(handlers) { handlers.filter { !it.partial && it.messageType.isAssignableFrom(String::class.java) } }
		for (entry in matched) {
			runCatching {
				@Suppress("UNCHECKED_CAST")
				(entry.handler as MessageHandler.Whole<Any>).onMessage(text)
			}.onFailure { fireError(it) }
		}
	}

	internal fun dispatchBinaryPartial(data: ByteBuffer, last: Boolean) {
		val matched = synchronized(handlers) { handlers.filter { it.partial && it.messageType.isAssignableFrom(ByteBuffer::class.java) } }
		for (entry in matched) {
			runCatching {
				@Suppress("UNCHECKED_CAST")
				(entry.handler as MessageHandler.Partial<Any>).onMessage(data.asReadOnlyBuffer(), last)
			}.onFailure { fireError(it) }
		}
	}

	internal fun dispatchBinaryWhole(data: ByteBuffer) {
		val matched = synchronized(handlers) { handlers.filter { !it.partial && it.messageType.isAssignableFrom(ByteBuffer::class.java) } }
		for (entry in matched) {
			runCatching {
				@Suppress("UNCHECKED_CAST")
				(entry.handler as MessageHandler.Whole<Any>).onMessage(data.asReadOnlyBuffer())
			}.onFailure { fireError(it) }
		}
	}

	internal fun dispatchPong(data: ByteBuffer) {
		val message = object : PongMessage {
			override fun getApplicationData(): ByteBuffer = data.asReadOnlyBuffer()
		}
		val matched = synchronized(handlers) { handlers.filter { it.messageType.isAssignableFrom(PongMessage::class.java) } }
		for (entry in matched) {
			runCatching {
				@Suppress("UNCHECKED_CAST")
				(entry.handler as MessageHandler.Whole<Any>).onMessage(message)
			}.onFailure { fireError(it) }
		}
	}

	internal fun dispatchDecoded(message: Any) {
		val matched = synchronized(handlers) { handlers.filter { it.messageType.isInstance(message) } }
		for (entry in matched) {
			runCatching {
				@Suppress("UNCHECKED_CAST")
				(entry.handler as MessageHandler.Whole<Any>).onMessage(message)
			}.onFailure { fireError(it) }
		}
	}

	internal fun textBufferLimit(): Int = maxTextBufferSize

	internal fun binaryBufferLimit(): Int = maxBinaryBufferSize

	// ---- 关闭与错误 ----

	override fun close() = close(CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, ""))

	override fun close(reason: CloseReason) {
		if (!open.get() || closing) return
		closing = true
		closeReason = reason
		log.debug { "WebSocket session $id closing: ${reason.closeCode.code} ${reason.reasonPhrase}" }
		val frame = CloseWebSocketFrame(reason.closeCode.code, reason.reasonPhrase ?: "")
		channel.writeAndFlush(frame)
		channel.eventLoop().schedule({
			if (open.get()) channel.close()
		}, 5, TimeUnit.SECONDS)
	}

	/** 对端发起关闭或连接断开时由帧处理器调用。 */
	internal fun peerClosed(reason: CloseReason) {
		closeReason = reason
		fireClose(reason)
	}

	internal fun fireClose(reason: CloseReason) {
		if (!closeFired.compareAndSet(false, true)) return
		open.set(false)
		wsContainer.openSessions.remove(this)
		log.debug { "WebSocket session $id closed: ${reason.closeCode.code}" }
		runCatching { endpoint.onClose(this, reason) }.onFailure {
			log.debug { "onClose callback failed for session $id: $it" }
		}
	}

	internal fun fireError(t: Throwable) {
		log.debug { "WebSocket session $id error: $t" }
		runCatching { endpoint.onError(this, t) }
	}

	// ---- 发送 ----

	private fun checkOpen() {
		if (!isOpen) throw IOException("WebSocket session is closed")
	}

	private fun writeFrame(frame: WebSocketFrame, blocking: Boolean) {
		checkOpen()
		lastActivity = System.currentTimeMillis()
		val future = channel.writeAndFlush(frame)
		if (blocking && !channel.eventLoop().inEventLoop()) {
			future.await(asyncRemote.sendTimeout.coerceAtLeast(1), TimeUnit.MILLISECONDS)
			if (!future.isSuccess) throw IOException("WebSocket send failed", future.cause())
		}
	}

	private fun encodeText(obj: Any): String {
		val encoder = config.encoders.firstNotNullOfOrNull { enc ->
			val instance = enc.getDeclaredConstructor().newInstance()
			if (instance is Encoder.Text<*> && canEncode(instance.javaClass, obj)) instance else null
		} ?: throw EncodeException(obj, "No text encoder registered for ${obj.javaClass.name}")
		@Suppress("UNCHECKED_CAST")
		return (encoder as Encoder.Text<Any>).encode(obj)
	}

	private fun encodeBinary(obj: Any): ByteBuffer {
		val encoder = config.encoders.firstNotNullOfOrNull { enc ->
			val instance = enc.getDeclaredConstructor().newInstance()
			if (instance is Encoder.Binary<*> && canEncode(instance.javaClass, obj)) instance else null
		} ?: throw EncodeException(obj, "No binary encoder registered for ${obj.javaClass.name}")
		@Suppress("UNCHECKED_CAST")
		return (encoder as Encoder.Binary<Any>).encode(obj)
	}

	private fun canEncode(encoderClass: Class<*>, obj: Any): Boolean {
		var clazz: Class<*>? = encoderClass
		while (clazz != null && clazz != Any::class.java) {
			for (iface in clazz.genericInterfaces) {
				if (iface is java.lang.reflect.ParameterizedType) {
					val raw = iface.rawType
					if (raw == Encoder.Text::class.java || raw == Encoder.Binary::class.java) {
						val arg = iface.actualTypeArguments.firstOrNull() as? Class<*>
						if (arg != null && arg.isInstance(obj)) return true
					}
				}
			}
			clazz = clazz.superclass
		}
		return false
	}

	private inner class BasicRemote : RemoteEndpoint.Basic {
		private var textInProgress = false
		private var binaryInProgress = false

		override fun getBatchingAllowed(): Boolean = false
		override fun setBatchingAllowed(allowed: Boolean) {}
		override fun flushBatch() {}

		override fun sendText(text: String) {
			writeFrame(TextWebSocketFrame(Unpooled.copiedBuffer(text, Charsets.UTF_8)), blocking = true)
		}

		override fun sendText(fragment: String, isLast: Boolean) {
			val frame = if (!textInProgress) {
				textInProgress = true
				TextWebSocketFrame(isLast, 0, Unpooled.copiedBuffer(fragment, Charsets.UTF_8))
			} else {
				ContinuationWebSocketFrame(isLast, 0, Unpooled.copiedBuffer(fragment, Charsets.UTF_8))
			}
			if (isLast) textInProgress = false
			writeFrame(frame, blocking = true)
		}

		override fun sendBinary(data: ByteBuffer) {
			writeFrame(BinaryWebSocketFrame(Unpooled.wrappedBuffer(data)), blocking = true)
		}

		override fun sendBinary(fragment: ByteBuffer, isLast: Boolean) {
			val frame = if (!binaryInProgress) {
				binaryInProgress = true
				BinaryWebSocketFrame(isLast, 0, Unpooled.wrappedBuffer(fragment))
			} else {
				ContinuationWebSocketFrame(isLast, 0, Unpooled.wrappedBuffer(fragment))
			}
			if (isLast) binaryInProgress = false
			writeFrame(frame, blocking = true)
		}

		override fun sendPing(applicationData: ByteBuffer) {
			writeFrame(PingWebSocketFrame(Unpooled.wrappedBuffer(applicationData)), blocking = true)
		}

		override fun sendPong(applicationData: ByteBuffer) {
			writeFrame(PongWebSocketFrame(Unpooled.wrappedBuffer(applicationData)), blocking = true)
		}

		override fun sendObject(obj: Any) {
			val textEncoder = config.encoders.any { canEncode(it, obj) && Encoder.Text::class.java.isAssignableFrom(it) }
			if (textEncoder) sendText(encodeText(obj)) else sendBinary(encodeBinary(obj))
		}

		override fun getSendStream(): OutputStream = object : OutputStream() {
			private val buf = java.io.ByteArrayOutputStream()
			override fun write(b: Int) {
				buf.write(b)
			}

			override fun write(b: ByteArray, off: Int, len: Int) {
				buf.write(b, off, len)
			}

			override fun flush() {
				if (buf.size() > 0) {
					sendBinary(ByteBuffer.wrap(buf.toByteArray()), false)
					buf.reset()
				}
			}

			override fun close() {
				val bytes = buf.toByteArray()
				buf.reset()
				sendBinary(ByteBuffer.wrap(bytes), true)
			}
		}

		override fun getSendWriter(): Writer = object : Writer() {
			private val buf = StringBuilder()
			override fun write(cbuf: CharArray, off: Int, len: Int) {
				buf.append(cbuf, off, len)
			}

			override fun flush() {
				if (buf.isNotEmpty()) {
					sendText(buf.toString(), false)
					buf.setLength(0)
				}
			}

			override fun close() {
				val text = buf.toString()
				buf.setLength(0)
				sendText(text, true)
			}
		}
	}

	private inner class AsyncRemote : RemoteEndpoint.Async {
		@Volatile
		private var timeout: Long = wsContainer.defaultAsyncSendTimeout

		override fun getSendTimeout(): Long = timeout

		override fun setSendTimeout(timeout: Long) {
			this.timeout = timeout
		}

		override fun getBatchingAllowed(): Boolean = false
		override fun setBatchingAllowed(allowed: Boolean) {}
		override fun flushBatch() {}

		override fun sendPing(applicationData: ByteBuffer) {
			writeFrame(PingWebSocketFrame(Unpooled.wrappedBuffer(applicationData)), blocking = false)
		}

		override fun sendPong(applicationData: ByteBuffer) {
			writeFrame(PongWebSocketFrame(Unpooled.wrappedBuffer(applicationData)), blocking = false)
		}

		private fun send(frame: WebSocketFrame, handler: SendHandler?): Future<Void> {
			val result = CompletableFuture<Void>()
			checkOpen()
			lastActivity = System.currentTimeMillis()
			channel.writeAndFlush(frame).addListener { f ->
				val sendResult = SendResult(this@WebSocketSessionImpl, f.cause())
				if (f.isSuccess) result.complete(null) else result.completeExceptionally(f.cause() ?: IOException("Send failed"))
				handler?.onResult(sendResult)
			}
			return result
		}

		override fun sendText(text: String): Future<Void> =
			send(TextWebSocketFrame(Unpooled.copiedBuffer(text, Charsets.UTF_8)), null)

		override fun sendText(text: String, handler: SendHandler) {
			send(TextWebSocketFrame(Unpooled.copiedBuffer(text, Charsets.UTF_8)), handler)
		}

		override fun sendBinary(data: ByteBuffer): Future<Void> =
			send(BinaryWebSocketFrame(Unpooled.wrappedBuffer(data)), null)

		override fun sendBinary(data: ByteBuffer, handler: SendHandler) {
			send(BinaryWebSocketFrame(Unpooled.wrappedBuffer(data)), handler)
		}

		override fun sendObject(obj: Any): Future<Void> = try {
			val textEncoder = config.encoders.any { canEncode(it, obj) && Encoder.Text::class.java.isAssignableFrom(it) }
			if (textEncoder) sendText(encodeText(obj)) else sendBinary(encodeBinary(obj))
		} catch (e: Exception) {
			CompletableFuture<Void>().apply { completeExceptionally(e) }
		}

		override fun sendObject(obj: Any, handler: SendHandler) {
			try {
				val textEncoder = config.encoders.any { canEncode(it, obj) && Encoder.Text::class.java.isAssignableFrom(it) }
				if (textEncoder) sendText(encodeText(obj), handler) else sendBinary(encodeBinary(obj), handler)
			} catch (e: Exception) {
				handler.onResult(SendResult(this@WebSocketSessionImpl, e))
			}
		}
	}
}
