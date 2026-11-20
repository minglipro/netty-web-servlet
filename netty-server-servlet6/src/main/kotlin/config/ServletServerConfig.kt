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
 * @property sslContext TLS 上下文，非 null 时启用 HTTPS（isSecure/scheme 随之变化）
 * @property compression 响应压缩配置，非 null 时启用 gzip/deflate 协商压缩
 * @property docRoot 静态资源文档根目录（文件系统路径），非 null 时自动注册 DefaultServlet 映射 `/`，
 * 并启用 getRealPath/getResourcePaths 的文件系统解析（优先于类路径）
 * @property realm 认证域，非 null 时启用容器安全（BASIC/FORM 认证、角色约束、程序式登录）
 * @property loginConfig 登录配置（认证方式/realm 名/FORM 页面），缺省 BASIC
 * @property http2Enabled 启用 HTTP/2（明文 h2c 升级/prior-knowledge，TLS ALPN 协商）
 * @property sessionStore 会话持久化存储，默认 [com.mingliqiye.web.netty.servlet.session.MemorySessionStore]（仅内存）；
 * 磁盘用 DiskSessionStore，Redis 用 netty-server-session-redis 模块的 RedisSessionStore
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
	val sslContext: io.netty.handler.ssl.SslContext? = null,
	val compression: com.mingliqiye.web.netty.server.config.CompressionConfig? = null,
	val docRoot: String? = null,
	val realm: com.mingliqiye.web.netty.servlet.security.Realm? = null,
	val loginConfig: com.mingliqiye.web.netty.servlet.security.LoginConfig? = null,
	val http2Enabled: Boolean = false,
	val sessionStore: com.mingliqiye.web.netty.servlet.session.SessionStore =
		com.mingliqiye.web.netty.servlet.session.MemorySessionStore,
)
