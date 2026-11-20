package com.mingliqiye.web.netty.server.io

import com.mingliqiye.logger.Loggers
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.HttpContent
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 请求体累计字节数超过上限时抛出，容器侧映射为 413。 */
class BodyTooLargeException(maxBytes: Long) : IOException("Request body exceeds $maxBytes bytes")

/**
 * 流式请求体：事件循环线程 feed 数据块，业务线程通过 [inputStream] 阻塞读取。
 * 队列水位触发 autoRead 背压；未被读完的 body 必须 [abandon] 排水以复用 keep-alive 连接。
 */
class RequestBody internal constructor(
	private val maxBytes: Long,
	private val ctx: ChannelHandlerContext,
) {
	private object EOF

	private val queue = LinkedBlockingQueue<Any>()
	private val received = AtomicLong()
	private val pending = AtomicInteger()
	private val paused = AtomicBoolean(false)

	@Volatile
	private var finished = false

	@Volatile
	private var abandoned = false

	@Volatile
	private var failure: Throwable? = null

	private var current: ByteBuf? = null

	/** body 是否已收完且全部读尽。 */
	val isExhausted: Boolean
		get() = finished && queue.isEmpty() && current?.isReadable != true

	/** body 是否已收完（含 trailer 到达），trailer 字段在此之后才可读。 */
	val isFinished: Boolean
		get() = finished

	/** chunked 请求的 trailer 字段（LastHttpContent 携带），未收完时为空表。 */
	var trailerFields: Map<String, String> = emptyMap()
		internal set

	internal fun feed(content: HttpContent) {
		val buf = content.content()
		if (received.addAndGet(buf.readableBytes().toLong()) > maxBytes) {
			log.debug { "Request body exceeds limit $maxBytes bytes, failing stream" }
			fail(BodyTooLargeException(maxBytes))
			return
		}
		if (abandoned || failure != null || !buf.isReadable) return
		buf.retain()
		queue.add(buf)
		if (pending.incrementAndGet() >= HIGH_WATERMARK && paused.compareAndSet(false, true)) {
			log.debug { "Request body backpressure: pause autoRead (pending=${pending.get()})" }
			ctx.channel().config().isAutoRead = false
		}
	}

	internal fun finish() {
		finished = true
		// 最后一个块已到，恢复读取以便复用连接处理下一个请求
		if (paused.compareAndSet(true, false)) {
			ctx.channel().config().isAutoRead = true
		}
		if (!abandoned && failure == null) queue.add(EOF)
	}

	internal fun fail(cause: Throwable) {
		if (failure != null) return
		failure = cause
		ctx.channel().attr(Responses.MUST_CLOSE).set(true)
		queue.add(cause)
	}

	/**
	 * 放弃读取剩余 body：释放已排队数据块并恢复 autoRead 排水，
	 * 使客户端能把剩余 body 发完，keep-alive 连接得以复用。业务未读完 body 时必须调用。
	 */
	fun abandon() {
		if (abandoned) return
		abandoned = true
		log.debug { "Request body abandoned, draining remaining chunks (received=${received.get()})" }
		while (true) {
			val item = queue.poll() ?: break
			if (item is ByteBuf) item.release()
		}
		pending.set(0)
		// 排水后必须恢复 autoRead，否则客户端剩余 body 发不完，连接死锁
		if (paused.compareAndSet(true, false)) {
			ctx.executor().execute { ctx.channel().config().isAutoRead = true }
		}
		// 唤醒可能仍在阻塞读取的线程，使其读到 EOF
		if (failure == null) queue.add(EOF)
	}

	/** 阻塞式请求体输入流，读到 EOF 表示 body 结束；body 失败时抛 [IOException]。 */
	val inputStream: InputStream = object : InputStream() {
		override fun read(): Int {
			val buf = nextBuffer() ?: return -1
			return buf.readByte().toInt() and 0xFF
		}

		override fun read(b: ByteArray, off: Int, len: Int): Int {
			if (len == 0) return 0
			val buf = nextBuffer() ?: return -1
			val n = minOf(len, buf.readableBytes())
			buf.readBytes(b, off, n)
			return n
		}

		override fun available(): Int = current?.readableBytes() ?: 0
	}

	private fun nextBuffer(): ByteBuf? {
		while (true) {
			val cur = current
			if (cur != null) {
				if (cur.isReadable) return cur
				cur.release()
				current = null
			}
			val item = queue.take()
			when {
				item === EOF -> {
					queue.add(EOF)
					return null
				}

				item is Throwable -> throw IOException("Request body read failed", item)
				item is ByteBuf -> {
					current = item
					if (pending.decrementAndGet() <= LOW_WATERMARK && paused.compareAndSet(true, false)) {
						log.debug { "Request body backpressure: resume autoRead (pending=${pending.get()})" }
						ctx.executor().execute { ctx.channel().config().isAutoRead = true }
					}
				}
			}
		}
	}

	companion object {
		private val log = Loggers.getLogger<RequestBody>()
		private const val HIGH_WATERMARK = 16
		private const val LOW_WATERMARK = 8
	}
}
