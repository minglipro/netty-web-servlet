package com.mingliqiye.web.netty.servlet.config

import com.mingliqiye.web.netty.server.io.NettyIO

/**
 * Servlet 容器全量配置实体（Spring Boot starter 的属性绑定目标）。
 *
 * @property serverName 服务器名（日志/线程命名），null 时自动生成
 * @property host 绑定地址，空串表示所有网卡
 * @property port 绑定端口，0 表示系统随机分配
 * @property contextPath 应用上下文路径，空串表示根上下文
 * @property queueCapacity 协程工作池队列容量与最大并发数，超出时回 503
 * @property maxRequestSize 请求体总上限（字节），超出回 413
 * @property responseBufferSize 响应缓冲区大小（字节），写满自动 flush（chunked）
 * @property sessionTimeoutSeconds 会话默认不活动超时（秒）
 * @property asyncTimeoutMillis 异步请求默认超时（毫秒），0 表示不超时
 * @property multipartMaxFileSize multipart 单文件默认上限（字节），可被每 Servlet 的 MultipartConfig 覆盖
 * @property multipartMaxRequestSize multipart 请求总默认上限（字节），可被每 Servlet 的 MultipartConfig 覆盖
 * @property bossThreads accept 事件循环线程数
 * @property workerThreads 读写事件循环线程数，0 表示 Netty 默认（CPU 核数 x2）
 * @property bossThreadName boss 线程名前缀
 * @property workerThreadName worker 线程名前缀
 * @property sportIO 传输层实现，默认按平台自动选择最优
 */
data class ServletServerConfig(
	val serverName: String? = null,
	val host: String = "",
	val port: Int = 8080,
	val contextPath: String = "",
	val queueCapacity: Int = 4096,
	val maxRequestSize: Int = 1024 * 1024,
	val responseBufferSize: Int = 8 * 1024,
	val sessionTimeoutSeconds: Int = 1800,
	val asyncTimeoutMillis: Long = 30_000,
	val multipartMaxFileSize: Long = 1024 * 1024,
	val multipartMaxRequestSize: Long = 10 * 1024 * 1024,
	val bossThreads: Int = 1,
	val workerThreads: Int = 0,
	val bossThreadName: String = "NettyWebServerBoss",
	val workerThreadName: String = "NettyWebServerWorker",
	val sportIO: NettyIO = NettyIO.getSportIO(),
)
