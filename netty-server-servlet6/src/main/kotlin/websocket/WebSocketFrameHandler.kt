package com.mingliqiye.web.netty.servlet.websocket

import com.mingliqiye.logger.Loggers
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import jakarta.websocket.CloseReason
import jakarta.websocket.Decoder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * WebSocket 帧处理器：101 握手完成后接管连接。
 *
 * 分片消息按类型聚合（受 Session 缓冲上限约束，超限以 1009 关闭），
 * Partial 处理器逐片投递、Whole 处理器在末片投递完整消息；
 * 配置了 [Decoder.Text]/[Decoder.Binary] 时解码后按类型分发。
 * Ping 自动回 Pong；Close 帧走标准关闭握手；连接断开兜底触发 onClose。
 */
internal class WebSocketFrameHandler(private val session: WebSocketSessionImpl) :
	SimpleChannelInboundHandler<WebSocketFrame>() {
	companion object {
		private val log = Loggers.getLogger<WebSocketFrameHandler>()
		private const val FRAGMENT_NONE = 0
		private const val FRAGMENT_TEXT = 1
		private const val FRAGMENT_BINARY = 2
	}

	private val textBuffer = StringBuilder()
	private val binaryBuffer = ByteArrayOutputStream()
	private var fragmentType = FRAGMENT_NONE

	override fun channelRead0(ctx: ChannelHandlerContext, frame: WebSocketFrame) {
		session.lastActivity = System.currentTimeMillis()
		when (frame) {
			is TextWebSocketFrame -> {
				fragmentType = FRAGMENT_TEXT
				handleText(ctx, frame.text(), frame.isFinalFragment)
			}

			is BinaryWebSocketFrame -> {
				fragmentType = FRAGMENT_BINARY
				handleBinary(ctx, frame.content().nioBuffer(), frame.isFinalFragment)
			}

			is ContinuationWebSocketFrame -> when (fragmentType) {
				FRAGMENT_TEXT -> handleText(ctx, frame.text(), frame.isFinalFragment)
				FRAGMENT_BINARY -> handleBinary(ctx, frame.content().nioBuffer(), frame.isFinalFragment)
				else -> {
					log.debug { "Unexpected continuation frame on session ${session.id}" }
					closeWith(ctx, CloseReason.CloseCodes.PROTOCOL_ERROR, "Unexpected continuation frame")
				}
			}

			is PingWebSocketFrame -> ctx.writeAndFlush(PongWebSocketFrame(frame.content().retainedDuplicate()))

			is PongWebSocketFrame -> {
				val bytes = ByteArray(frame.content().readableBytes())
				frame.content().readBytes(bytes)
				session.dispatchPong(ByteBuffer.wrap(bytes))
			}

			is CloseWebSocketFrame -> handleClose(ctx, frame)
		}
	}

	private fun handleText(ctx: ChannelHandlerContext, fragment: String, last: Boolean) {
		textBuffer.append(fragment)
		if (textBuffer.length > session.textBufferLimit()) {
			closeWith(ctx, CloseReason.CloseCodes.TOO_BIG, "Text message exceeds buffer limit")
			return
		}
		session.dispatchTextPartial(fragment, last)
		if (last) {
			val full = textBuffer.toString()
			textBuffer.setLength(0)
			fragmentType = FRAGMENT_NONE
			session.dispatchTextWhole(full)
			decodeText(ctx, full)
		}
	}

	private fun handleBinary(ctx: ChannelHandlerContext, fragment: ByteBuffer, last: Boolean) {
		val bytes = ByteArray(fragment.remaining())
		fragment.get(bytes)
		binaryBuffer.write(bytes)
		if (binaryBuffer.size() > session.binaryBufferLimit()) {
			closeWith(ctx, CloseReason.CloseCodes.TOO_BIG, "Binary message exceeds buffer limit")
			return
		}
		session.dispatchBinaryPartial(ByteBuffer.wrap(bytes), last)
		if (last) {
			val full = ByteBuffer.wrap(binaryBuffer.toByteArray())
			binaryBuffer.reset()
			fragmentType = FRAGMENT_NONE
			session.dispatchBinaryWhole(full)
			decodeBinary(ctx, full)
		}
	}

	private fun decodeText(ctx: ChannelHandlerContext, text: String) {
		for (decoderClass in session.config.decoders) {
			val decoder = decoderClass.getDeclaredConstructor().newInstance()
			if (decoder is Decoder.Text<*>) {
				decoder.init(session.config)
				try {
					if (decoder.willDecode(text)) {
						session.dispatchDecoded(decoder.decode(text) ?: return)
						return
					}
				} catch (e: Exception) {
					session.fireError(e)
					closeWith(ctx, CloseReason.CloseCodes.CANNOT_ACCEPT, "Message decode failed")
					return
				} finally {
					decoder.destroy()
				}
			}
		}
	}

	private fun decodeBinary(ctx: ChannelHandlerContext, data: ByteBuffer) {
		for (decoderClass in session.config.decoders) {
			val decoder = decoderClass.getDeclaredConstructor().newInstance()
			if (decoder is Decoder.Binary<*>) {
				decoder.init(session.config)
				try {
					if (decoder.willDecode(data)) {
						session.dispatchDecoded(decoder.decode(data) ?: return)
						return
					}
				} catch (e: Exception) {
					session.fireError(e)
					closeWith(ctx, CloseReason.CloseCodes.CANNOT_ACCEPT, "Message decode failed")
					return
				} finally {
					decoder.destroy()
				}
			}
		}
	}

	private fun handleClose(ctx: ChannelHandlerContext, frame: CloseWebSocketFrame) {
		val code = frame.statusCode()
		val closeCode = if (code == -1) CloseReason.CloseCodes.NO_STATUS_CODE else CloseReason.CloseCodes.getCloseCode(code)
		val reason = CloseReason(closeCode, frame.reasonText())
		if (session.closing) {
			log.debug { "Close echo received on session ${session.id}" }
			session.fireClose(session.closeReason ?: reason)
			ctx.close()
		} else {
			log.debug { "Peer initiated close on session ${session.id}: $code" }
			ctx.writeAndFlush(CloseWebSocketFrame(code, frame.reasonText()))
				.addListener(ChannelFutureListener.CLOSE)
			session.peerClosed(reason)
		}
	}

	private fun closeWith(ctx: ChannelHandlerContext, code: CloseReason.CloseCode, message: String) {
		session.close(CloseReason(code, message))
	}

	override fun channelInactive(ctx: ChannelHandlerContext) {
		session.fireClose(session.closeReason ?: CloseReason(CloseReason.CloseCodes.GOING_AWAY, "Connection closed"))
		super.channelInactive(ctx)
	}

	override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
		log.debug { "WebSocket transport error on session ${session.id}: $cause" }
		session.fireError(cause)
		ctx.close()
	}
}