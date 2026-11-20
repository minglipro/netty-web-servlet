package com.mingliqiye.web.netty.server.config

import com.mingliqiye.web.netty.server.RequestHandler
import com.mingliqiye.web.netty.server.StreamingRequestHandler
import com.mingliqiye.web.netty.server.io.NettyIO

/**
 * NettyWebServer 全量配置实体。
 *
 * [handler] 与 [streamingHandler] 二选一：设置了 [streamingHandler] 即启用流式管线，
 * 否则走聚合管线（两者都未设置时使用内置 echo 处理器）。
 *
 * @property serverName 服务器名（日志/线程命名），null 时自动生成
 * @property host 绑定地址，空串表示所有网卡
 * @property port 绑定端口，0 表示系统随机分配
 * @property handler 聚合模式业务处理器
 * @property streamingHandler 流式模式业务处理器，非 null 时优先
 * @property queueCapacity 协程工作池队列容量与最大并发数，超出时投递失败（503 降级）
 * @property maxRequestSize 聚合模式请求体上限（字节），超出回 413
 * @property bossThreads accept 事件循环线程数
 * @property workerThreads 读写事件循环线程数，0 表示 Netty 默认（CPU 核数 x2）
 * @property bossThreadName boss 线程名前缀
 * @property workerThreadName worker 线程名前缀
 * @property sportIO 传输层实现，默认按平台自动选择最优（IO_URING/EPOLL/KQUEUE/NIO）
 */
data class NettyServerConfig(
	val serverName: String? = null,
	val host: String = "",
	val port: Int = 8080,
	val handler: RequestHandler? = null,
	val streamingHandler: StreamingRequestHandler? = null,
	val queueCapacity: Int = 4096,
	val maxRequestSize: Int = 1024 * 1024,
	val bossThreads: Int = 1,
	val workerThreads: Int = 0,
	val bossThreadName: String = "NettyWebServerBoss",
	val workerThreadName: String = "NettyWebServerWorker",
	val sportIO: NettyIO = NettyIO.getSportIO(),
)
