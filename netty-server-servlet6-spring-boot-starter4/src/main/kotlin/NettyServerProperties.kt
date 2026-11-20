package com.mingliqiye.web.netty.spring4

import com.mingliqiye.web.netty.server.io.NettyIO
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Netty 专属配置属性，绑定前缀 `server.netty.*`。
 * 通用属性（port/context-path/session 等）走 Boot 标准 `server.*`。
 *
 * @property queueCapacity 协程工作池队列容量与最大并发数，超出时回 503
 * @property maxRequestSize 请求体总上限（字节），超出回 413
 * @property responseBufferSize 响应缓冲区大小（字节），写满自动 flush（chunked）
 * @property asyncTimeoutMillis 异步请求默认超时（毫秒），0 表示不超时
 * @property multipartMaxFileSize multipart 单文件默认上限（字节）
 * @property multipartMaxRequestSize multipart 请求总默认上限（字节）
 * @property bossThreads accept 事件循环线程数
 * @property workerThreads 读写事件循环线程数，0 表示 Netty 默认
 * @property bossThreadName boss 线程名前缀
 * @property workerThreadName worker 线程名前缀
 * @property transport 传输层实现（IO_URING/EPOLL/KQUEUE/NIO），null 时按平台自动选择
 */
@ConfigurationProperties(prefix = "server.netty")
data class NettyServerProperties(
	var queueCapacity: Int = 4096,
	var maxRequestSize: Int = 1024 * 1024,
	var responseBufferSize: Int = 8 * 1024,
	var asyncTimeoutMillis: Long = 30_000,
	var multipartMaxFileSize: Long = 1024 * 1024,
	var multipartMaxRequestSize: Long = 10 * 1024 * 1024,
	var bossThreads: Int = 1,
	var workerThreads: Int = 0,
	var bossThreadName: String = "NettyWebServerBoss",
	var workerThreadName: String = "NettyWebServerWorker",
	var transport: NettyIO? = null,
)