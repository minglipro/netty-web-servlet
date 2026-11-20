package com.mingliqiye.web.netty.server

import com.mingliqiye.logger.Loggers
import com.mingliqiye.web.netty.server.io.NamedThreadFactory
import com.mingliqiye.web.netty.server.config.NettyServerConfig
import com.mingliqiye.web.netty.server.data.StreamingTask
import com.mingliqiye.web.netty.server.handler.StreamingWebHandler
import com.mingliqiye.web.netty.server.handler.WebHandler
import com.mingliqiye.web.netty.server.io.RequestBody
import com.mingliqiye.web.netty.server.io.Responses
import com.mingliqiye.web.netty.server.poll.CoroutineWorkerPool
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.handler.codec.http.*
import kotlinx.coroutines.CancellationException
import java.net.InetSocketAddress

/** 聚合模式业务处理器：收到完整 [FullHttpRequest] 后在协程工作池中挂起执行。 */
typealias RequestHandler = suspend (FullHttpRequest, ChannelHandlerContext) -> Unit

/** 流式模式业务处理器：请求头到达即开始执行，请求体通过 [RequestBody] 惰性读取。 */
typealias StreamingRequestHandler = suspend (HttpRequest, RequestBody, ChannelHandlerContext) -> Unit

/**
 * 基于 Netty 与 Kotlin 协程的 HTTP 服务器。
 *
 * 支持两种管线（由 [NettyServerConfig] 决定）：
 * - 聚合模式：[HttpObjectAggregator] 收齐完整请求后交给 [RequestHandler]；
 * - 流式模式：请求头到达即提交 [StreamingRequestHandler]，请求体分块喂给 [RequestBody]，带 autoRead 背压。
 *
 * 业务在 [CoroutineWorkerPool] 挂起式协程池中执行，不占用事件循环线程。
 * 通过 [startServer] 绑定端口（port=0 时由系统分配，真实端口见 [actualPort]），
 * [stopListening] 仅停止接受新连接（优雅停机第一步），[stopServer] 完整停机。
 */
open class NettyWebServer internal constructor(
	val config: NettyServerConfig,
) {
	companion object {
		private val log = Loggers.getLogger(NettyWebServer::class.java)

		/** 以完整配置实体创建服务器。 */
		@JvmStatic
		fun server(config: NettyServerConfig): NettyWebServer {
			return NettyWebServer(config)
		}

		/** 以默认配置创建服务器（聚合模式，echo 处理器）。 */
		@JvmOverloads
		@JvmStatic
		fun server(host: String = "", port: Int = 8080): NettyWebServer {
			return server(NettyServerConfig(host = host, port = port))
		}

		/** 以默认配置在指定端口创建服务器。 */
		@JvmStatic
		fun server(port: Int): NettyWebServer {
			return server("", port)
		}

		/** 以便捷参数创建聚合模式服务器。 */
		@JvmOverloads
		@JvmStatic
		fun server(
			host: String,
			port: Int,
			handler: RequestHandler,
			queueCapacity: Int = 4096,
			maxRequestSize: Int = 1024 * 1024,
		): NettyWebServer {
			return server(
				NettyServerConfig(host = host, port = port, handler = handler, queueCapacity = queueCapacity, maxRequestSize = maxRequestSize)
			)
		}

		/** 以便捷参数创建流式模式服务器。 */
		@JvmOverloads
		@JvmStatic
		fun streamingServer(
			host: String = "",
			port: Int = 8080,
			handler: StreamingRequestHandler,
			queueCapacity: Int = 4096,
			maxRequestSize: Int = 1024 * 1024,
		): NettyWebServer {
			return server(
				NettyServerConfig(host = host, port = port, streamingHandler = handler, queueCapacity = queueCapacity, maxRequestSize = maxRequestSize)
			)
		}

		private val echoHandler: RequestHandler = { request, ctx ->
			val content = Unpooled.copiedBuffer(request.uri(), Charsets.UTF_8)
			val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content)
			response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8")
			response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
			Responses.write(ctx, request, response)
		}
	}

	/** 日志与线程命名用的服务器名，未配置时按类名+哈希生成。 */
	val serverName = config.serverName?:"${this.javaClass.simpleName}@${this.hashCode().toString(16)}"

	/** 配置的绑定地址，空串表示所有网卡。 */
	val host: String get() = config.host

	/** 配置的绑定端口，0 表示系统随机分配。 */
	val port: Int get() = config.port

	/** 实际绑定端口；未启动或随机端口场景下以此为准。 */
	val actualPort: Int get() = (serverChannel?.localAddress() as? java.net.InetSocketAddress)?.port ?: port

	/** 传输层实现（NIO/EPOLL/KQUEUE/IO_URING）。 */
	val sportIO = config.sportIO

	/** 接受连接的 boss 事件循环组。 */
	val bossGroup = MultiThreadIoEventLoopGroup(
		config.bossThreads,
		NamedThreadFactory { a, b, c -> "${config.bossThreadName}-$b-$c" },
		sportIO.newFactory()
	)

	/** 处理连接读写的事件循环组。 */
	val workerGroup = MultiThreadIoEventLoopGroup(
		config.workerThreads,
		NamedThreadFactory { a, b, c -> "${config.workerThreadName}-$b-$c" },
		sportIO.newFactory()
	)

	private var serverChannel: Channel? = null

	private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)

	private val fullPool: CoroutineWorkerPool<FullHttpRequest>? =
		if (config.streamingHandler == null) CoroutineWorkerPool(config.queueCapacity, { it.release() }, config.handler ?: echoHandler) else null

	private val streamPool: CoroutineWorkerPool<StreamingTask>? =
		if (config.streamingHandler != null) CoroutineWorkerPool(config.queueCapacity) { task, ctx ->
			try {
				config.streamingHandler.invoke(task.request, task.body, ctx)
			} catch (e: CancellationException) {
				task.body.abandon()
				throw e
			}
		} else null

	private val serverBootstrap: ServerBootstrap = ServerBootstrap()
		.channel(sportIO.channelClass)
		.group(bossGroup, workerGroup)
		.childHandler(object : ChannelInitializer<Channel>() {
			override fun initChannel(ch: Channel) {
				ch.pipeline().apply {
					addLast(HttpServerCodec())
					if (streamPool != null) {
						addLast(StreamingWebHandler(streamPool, config.maxRequestSize))
					} else {
						addLast(HttpObjectAggregator(config.maxRequestSize))
						addLast(WebHandler(fullPool!!))
					}
				}
			}
		})


	/**
	 * 同步绑定端口并启动服务器。
	 *
	 * @param host 绑定地址，默认取配置值；空串绑定所有网卡
	 * @param port 绑定端口，默认取配置值；0 表示随机端口，启动后从 [actualPort] 读取
	 */
	fun startServer(host: String = this.host, port: Int = this.port): NettyWebServer {
		log.info("$serverName Starting $host:$port")
		log.debug { "$serverName transport=${sportIO.ioName} bossThreads=${config.bossThreads} workerThreads=${config.workerThreads} queueCapacity=${config.queueCapacity} maxRequestSize=${config.maxRequestSize} mode=${if (streamPool != null) "streaming" else "aggregated"}" }
		val lin = if (host.isEmpty()) {
			serverBootstrap.bind(port)
		} else {
			serverBootstrap.bind(InetSocketAddress(host, port))
		}
		lin.sync()
		this.serverChannel = lin.channel()
		log.info { "$serverName Available ${sportIO.ioName} startServer at ${host.ifEmpty { '*' }}:$actualPort" }
		return this
	}


	/** 仅关闭监听通道停止接受新连接，已建立连接与在途请求不受影响（优雅停机第一步）。 */
	fun stopListening() {
		log.debug { "$serverName stopListening on port $actualPort" }
		serverChannel?.close()?.sync()
	}

	/** 完整停机：关闭监听、排空并关闭协程工作池、优雅关闭两个事件循环组。幂等，重复调用直接返回。 */
	fun stopServer() {
		if (!stopped.compareAndSet(false, true)) {
			log.debug { "$serverName stopServer ignored, already stopped" }
			return
		}
		log.info { "$serverName Close" }
		serverChannel?.close()?.sync()
		log.debug { "$serverName shutting down worker pools" }
		fullPool?.shutdown()
		streamPool?.shutdown()
		log.debug { "$serverName shutting down event loop groups" }
		bossGroup.shutdownGracefully()
		workerGroup.shutdownGracefully()
		log.info { "$serverName Stopped" }
	}

	/** 阻塞当前线程直到服务器监听通道关闭。 */
	fun sync() {
		serverChannel?.closeFuture()?.sync()
	}
}
