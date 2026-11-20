package com.mingliqiye.web.netty.server.data

import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.HttpRequest

/**
 * 协程工作池任务封装。
 *
 * @property ctx 请求所在连接的通道上下文，用于写出响应
 * @property data 业务数据（聚合模式为 [io.netty.handler.codec.http.FullHttpRequest]，流式模式为 [StreamingTask]）
 * @property request 原始请求头，用于判断 keep-alive 等连接语义
 */
data class BusinessTask<T>(
	val ctx: ChannelHandlerContext,
	val data: T,
	val request: HttpRequest,
)