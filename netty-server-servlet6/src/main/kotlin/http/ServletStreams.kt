package com.mingliqiye.web.netty.servlet.http

import com.mingliqiye.web.netty.server.io.RequestBody
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import java.io.ByteArrayOutputStream

/**
 * Servlet 输入流适配：桥接流式 [RequestBody]。
 * 阻塞读语义（isReady 恒 true）；close 为空操作，body 生命周期由容器统一管理。
 */
internal class NettyServletInputStream(body: RequestBody) : ServletInputStream() {
	private val stream = body.inputStream
	private val bodyRef = body
	private var listener: ReadListener? = null

	override fun read(): Int = stream.read()

	override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)

	override fun available(): Int = stream.available()

	override fun isFinished(): Boolean = bodyRef.isExhausted

	override fun isReady(): Boolean = true

	override fun setReadListener(readListener: ReadListener) {
		listener = readListener
		if (isFinished) {
			readListener.onAllDataRead()
		} else {
			readListener.onDataAvailable()
		}
	}

	override fun close() {
		// body 生命周期由容器管理（响应完成后排水释放）
	}
}

/**
 * Servlet 输出流适配：写入响应缓冲区，flush 触发容器的 flushBuffer（可能切换 chunked 流式）。
 */
internal class BufferServletOutputStream(
	private val buffer: ByteArrayOutputStream,
	private val onFlush: () -> Unit = {},
) : ServletOutputStream() {
	private var listener: WriteListener? = null

	override fun write(b: Int) {
		buffer.write(b)
	}

	override fun write(b: ByteArray, off: Int, len: Int) {
		buffer.write(b, off, len)
	}

	override fun isReady(): Boolean = true

	override fun setWriteListener(writeListener: WriteListener) {
		listener = writeListener
		writeListener.onWritePossible()
	}

	override fun flush() {
		onFlush()
	}
}