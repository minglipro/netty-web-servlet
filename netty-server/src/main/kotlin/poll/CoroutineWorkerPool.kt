package com.mingliqiye.web.netty.server.poll

import com.mingliqiye.logger.Loggers
import com.mingliqiye.web.netty.server.data.BusinessTask
import com.mingliqiye.web.netty.server.io.Responses
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore

/**
 * 挂起式协程工作池：事件循环线程通过 [submit] 非阻塞投递任务，
 * 池内协程在 [Dispatchers.Default] 上挂起执行业务，不占用事件循环。
 *
 * 并发上限与队列容量均为 [capacity]（信号量 + 有界 Channel 双重限流），
 * 投递失败返回 false，由调用方决定降级行为（如回 503）。
 * 任务异常时回 500 并通过 [releaseTask] 释放任务持有的资源（如 ByteBuf）。
 *
 * @param capacity 队列容量与最大并发数
 * @param releaseTask 任务结束（含异常）后的资源释放回调
 * @param processRequest 业务处理挂起函数
 */
class CoroutineWorkerPool<T>(
	private val capacity: Int = 4096,
	private val releaseTask: (T) -> Unit = {},
	private val processRequest: suspend (T, ChannelHandlerContext) -> Unit,
) {
	companion object {
		private val log = Loggers.getLogger<CoroutineWorkerPool<*>>()
	}

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private val workChannel = Channel<BusinessTask<T>>(capacity = capacity)
	private val inflight = Semaphore(capacity)

	init {
		scope.launch {
			while (true) {
				inflight.acquire()
				val task = workChannel.receiveCatching().getOrNull() ?: break
				scope.launch {
					try {
						processRequest(task.data, task.ctx)
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						log.error("Exception occurred while processing task", e)
						writeError(task)
					} finally {
						releaseTask(task.data)
						inflight.release()
					}
				}
			}
		}
	}

	/** 非阻塞投递任务；队列已满时返回 false（高压降级信号）。 */
	fun submit(task: BusinessTask<T>): Boolean {
		val accepted = workChannel.trySend(task).isSuccess
		if (!accepted) log.debug { "Task rejected, worker pool queue full (capacity=$capacity)" }
		return accepted
	}

	/** 关闭任务通道并取消所有在途协程。 */
	fun shutdown() {
		log.debug { "Worker pool shutdown (capacity=$capacity)" }
		workChannel.close()
		scope.cancel()
	}

	private fun writeError(task: BusinessTask<T>) {
		val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR)
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
		Responses.write(task.ctx, task.request, response)
	}
}