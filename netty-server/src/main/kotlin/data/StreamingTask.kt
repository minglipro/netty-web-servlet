package com.mingliqiye.web.netty.server.data

import com.mingliqiye.web.netty.server.io.RequestBody
import io.netty.handler.codec.http.HttpRequest

/**
 * 流式模式任务：请求头与惰性请求体的组合。
 *
 * @property request 已到达的请求头（body 尚未收齐）
 * @property body 流式请求体，业务侧通过其 inputStream 阻塞读取
 */
data class StreamingTask(
	val request: HttpRequest,
	val body: RequestBody,
)