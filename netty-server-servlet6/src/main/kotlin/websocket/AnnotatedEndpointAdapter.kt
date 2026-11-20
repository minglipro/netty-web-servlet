package com.mingliqiye.web.netty.servlet.websocket

import com.mingliqiye.logger.Loggers
import jakarta.websocket.CloseReason
import jakarta.websocket.Decoder
import jakarta.websocket.Endpoint
import jakarta.websocket.EndpointConfig
import jakarta.websocket.MessageHandler
import jakarta.websocket.OnClose
import jakarta.websocket.OnError
import jakarta.websocket.OnMessage
import jakarta.websocket.OnOpen
import jakarta.websocket.PongMessage
import jakarta.websocket.Session
import jakarta.websocket.server.PathParam
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.Reader
import java.io.StringReader
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import java.lang.reflect.ParameterizedType
import java.nio.ByteBuffer

/**
 * @ServerEndpoint 注解端点适配器：把注解方法包装为标准 [Endpoint]。
 *
 * onOpen 时按 @OnMessage 参数类型登记消息处理器（String/ByteBuffer/PongMessage/Reader/InputStream
 * 及 Decoder 解码类型，含 boolean last 的 Partial 形式），@OnOpen/@OnClose/@OnError 反射调用，
 * 参数注入 Session/EndpointConfig/CloseReason/Throwable/@PathParam；@OnMessage 非 void 返回值自动回写。
 */
internal class AnnotatedEndpointAdapter(private val instance: Any) : Endpoint() {
	companion object {
		private val log = Loggers.getLogger<AnnotatedEndpointAdapter>()
	}

	private var endpointConfig: EndpointConfig? = null

	override fun onOpen(session: Session, config: EndpointConfig) {
		endpointConfig = config
		for (method in instance.javaClass.methods) {
			if (method.isAnnotationPresent(OnMessage::class.java)) {
				registerMessageHandler(session, config, method)
			}
		}
		invokeAnnotated(OnOpen::class.java, session, null, null, null)
	}

	override fun onClose(session: Session, closeReason: CloseReason) {
		invokeAnnotated(OnClose::class.java, session, null, null, closeReason)
	}

	override fun onError(session: Session, thr: Throwable) {
		invokeAnnotated(OnError::class.java, session, null, null, null, thr)
	}

	// ---- @OnMessage 登记 ----

	private fun registerMessageHandler(session: Session, config: EndpointConfig, method: Method) {
		val messageParam = method.parameters.firstOrNull { !isInjected(it) }
			?: throw IllegalStateException("@OnMessage method ${method.name} has no message parameter")
		val hasLast = method.parameters.any { isLastFlag(it) }
		when {
			messageParam.type == String::class.java ->
				if (hasLast) {
					session.addMessageHandler(String::class.java, MessageHandler.Partial { text, last ->
						invokeMessage(method, session, text, last)
					})
				} else {
					session.addMessageHandler(String::class.java, MessageHandler.Whole { text ->
						invokeMessage(method, session, text, null)
					})
				}

			messageParam.type == ByteBuffer::class.java ->
				if (hasLast) {
					session.addMessageHandler(ByteBuffer::class.java, MessageHandler.Partial { data, last ->
						invokeMessage(method, session, data, last)
					})
				} else {
					session.addMessageHandler(ByteBuffer::class.java, MessageHandler.Whole { data ->
						invokeMessage(method, session, data, null)
					})
				}

			messageParam.type == PongMessage::class.java ->
				session.addMessageHandler(PongMessage::class.java, MessageHandler.Whole { pong ->
					invokeMessage(method, session, pong, null)
				})

			messageParam.type == Reader::class.java ->
				session.addMessageHandler(String::class.java, MessageHandler.Whole { text ->
					invokeMessage(method, session, text, null)
				})

			messageParam.type == InputStream::class.java ->
				session.addMessageHandler(ByteBuffer::class.java, MessageHandler.Whole { data ->
					invokeMessage(method, session, data, null)
				})

			else -> registerDecodedHandler(session, config, method, messageParam.type)
		}
	}

	private fun registerDecodedHandler(session: Session, config: EndpointConfig, method: Method, type: Class<*>) {
		val decoderClass = config.decoders.firstOrNull { decoderClass ->
			decoderTargetType(decoderClass)?.let { type.isAssignableFrom(it) } == true
		} ?: throw IllegalStateException("No decoder registered for @OnMessage parameter type ${type.name}")
		val decoder = decoderClass.getDeclaredConstructor().newInstance()
		when {
			decoder is Decoder.TextStream<*> -> {
				decoder.init(config)
				session.addMessageHandler(String::class.java, MessageHandler.Whole { text ->
					val decoded = decoder.decode(StringReader(text))
					invokeMessage(method, session, decoded, null)
				})
			}

			decoder is Decoder.BinaryStream<*> -> {
				decoder.init(config)
				session.addMessageHandler(ByteBuffer::class.java, MessageHandler.Whole { data ->
					val bytes = ByteArray(data.remaining())
					data.get(bytes)
					val decoded = decoder.decode(ByteArrayInputStream(bytes))
					invokeMessage(method, session, decoded, null)
				})
			}

			else -> {
				@Suppress("UNCHECKED_CAST")
				session.addMessageHandler(type as Class<Any>, MessageHandler.Whole { message ->
					invokeMessage(method, session, message, null)
				})
			}
		}
	}

	/** 解析 Decoder 实现类的目标消息类型（Text/Binary/TextStream/BinaryStream 的泛型参数）。 */
	private fun decoderTargetType(decoderClass: Class<*>): Class<*>? {
		var clazz: Class<*>? = decoderClass
		while (clazz != null && clazz != Any::class.java) {
			for (iface in clazz.genericInterfaces) {
				if (iface is ParameterizedType) {
					val raw = iface.rawType
					if (raw == Decoder.Text::class.java || raw == Decoder.Binary::class.java ||
						raw == Decoder.TextStream::class.java || raw == Decoder.BinaryStream::class.java
					) {
						return iface.actualTypeArguments.firstOrNull() as? Class<*>
					}
				}
			}
			clazz = clazz.superclass
		}
		return null
	}

	private fun invokeMessage(method: Method, session: Session, message: Any?, last: Boolean?) {
		try {
			val args = buildArgs(method, session, message, last, null, null)
			val result = method.invoke(instance, *args)
			if (method.returnType != Void.TYPE && result != null && result != Unit) {
				when (result) {
					is String -> session.basicRemote.sendText(result)
					is ByteBuffer -> session.basicRemote.sendBinary(result)
					else -> session.basicRemote.sendObject(result)
				}
			}
		} catch (e: Exception) {
			val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
			log.debug { "@OnMessage ${method.name} failed: $cause" }
			fireErrorOnSession(session, cause)
		}
	}

	private fun fireErrorOnSession(session: Session, cause: Throwable) {
		(session as? WebSocketSessionImpl)?.fireError(cause)
	}

	// ---- @OnOpen/@OnClose/@OnError 调用 ----

	private fun invokeAnnotated(
		annotation: Class<out Annotation>,
		session: Session,
		message: Any?,
		last: Boolean?,
		closeReason: CloseReason?,
		error: Throwable? = null,
	) {
		for (method in instance.javaClass.methods) {
			if (!method.isAnnotationPresent(annotation)) continue
			try {
				val args = buildArgs(method, session, message, last, closeReason, error)
				method.invoke(instance, *args)
			} catch (e: Exception) {
				val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
				log.debug { "@${annotation.simpleName} ${method.name} failed: $cause" }
				if (annotation != OnError::class.java) {
					fireErrorOnSession(session, cause)
				}
			}
		}
	}

	// ---- 参数注入 ----

	private fun isInjected(p: Parameter): Boolean =
		Session::class.java.isAssignableFrom(p.type) ||
			EndpointConfig::class.java.isAssignableFrom(p.type) ||
			CloseReason::class.java.isAssignableFrom(p.type) ||
			Throwable::class.java.isAssignableFrom(p.type) ||
			p.isAnnotationPresent(PathParam::class.java) ||
			isLastFlag(p)

	private fun isLastFlag(p: Parameter): Boolean =
		(p.type == Boolean::class.javaPrimitiveType || p.type == java.lang.Boolean::class.java) &&
			!p.isAnnotationPresent(PathParam::class.java)

	private fun buildArgs(
		method: Method,
		session: Session,
		message: Any?,
		last: Boolean?,
		closeReason: CloseReason?,
		error: Throwable?,
	): Array<Any?> = method.parameters.map { p ->
		when {
			Session::class.java.isAssignableFrom(p.type) -> session
			EndpointConfig::class.java.isAssignableFrom(p.type) -> endpointConfig
			CloseReason::class.java.isAssignableFrom(p.type) -> closeReason
			Throwable::class.java.isAssignableFrom(p.type) -> error
			p.isAnnotationPresent(PathParam::class.java) ->
				convertPathParam(p.getAnnotation(PathParam::class.java).value, p.type, session.pathParameters)

			isLastFlag(p) -> last ?: false
			p.type == Reader::class.java -> StringReader(message as String)
			p.type == InputStream::class.java -> {
				val buffer = message as ByteBuffer
				val bytes = ByteArray(buffer.remaining())
				buffer.duplicate().get(bytes)
				ByteArrayInputStream(bytes)
			}

			else -> message
		}
	}.toTypedArray()

	private fun convertPathParam(name: String, type: Class<*>, pathParameters: Map<String, String>): Any? {
		val raw = pathParameters[name]
		return when (type) {
			String::class.java -> raw
			Int::class.javaPrimitiveType, java.lang.Integer::class.java -> raw?.toInt()
			Long::class.javaPrimitiveType, java.lang.Long::class.java -> raw?.toLong()
			Short::class.javaPrimitiveType, java.lang.Short::class.java -> raw?.toShort()
			Byte::class.javaPrimitiveType, java.lang.Byte::class.java -> raw?.toByte()
			Double::class.javaPrimitiveType, java.lang.Double::class.java -> raw?.toDouble()
			Float::class.javaPrimitiveType, java.lang.Float::class.java -> raw?.toFloat()
			Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> raw?.toBoolean()
			else -> throw IllegalStateException("Unsupported @PathParam type ${type.name}")
		}
	}
}