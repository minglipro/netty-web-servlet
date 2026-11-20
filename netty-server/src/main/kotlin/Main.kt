@file:JvmName("Main")

package com.mingliqiye.web.netty.server

import com.mingliqiye.logger.Loggers
import com.mingliqiye.utils.json.api.JSONA
import com.mingliqiye.utils.json.api.JSONA.toJson
import com.mingliqiye.utils.json.api.Jackson3JsonApi
import com.mingliqiye.utils.json.api.base.JsonApi.Companion.parseFrom
import com.mingliqiye.utils.json.converters.DateTimeJsonConverter
import com.mingliqiye.utils.json.converters.NetworkEndpointJsonConverter
import com.mingliqiye.utils.json.converters.UUIDJsonConverter
import com.mingliqiye.utils.json.converters.base.getJackson3Module
import com.mingliqiye.web.netty.server.config.NettyServerConfig
import com.mingliqiye.web.netty.server.io.HeaderUtils
import com.mingliqiye.web.netty.server.io.HeaderUtils.addHttpHeaders
import com.mingliqiye.web.netty.server.io.NettyIO
import com.mingliqiye.web.netty.server.io.RequestBody
import com.mingliqiye.web.netty.server.io.ResponseStream
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.apache.tika.Tika
import org.slf4j.helpers.MessageFormatter
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

const val SERVER_CONFIG_PATH = "server-config.json"

const val SERVER_ERROR = """
<html>
<head><title>{}</title></head>
<body>
<center><h1>{}</h1></center>
<hr><center>{}</center>
</body>
"""

private val log = Loggers.getLogger("com.mingliqiye.web.netty.server.Main")

private val tika = Tika()

data class ServerConfig(
	val serverName: String? = null,
	val host: String = "",
	val port: Int = 8080,
	val queueCapacity: Int = 4096,
	val maxRequestSize: Int = 1024 * 1024,
	val bossThreads: Int = 1,
	val workerThreads: Int = 0,
	val bossThreadName: String = "NettyWebServerBoss",
	val workerThreadName: String = "NettyWebServerWorker",
	val sportIO: NettyIO = NettyIO.getSportIO(),
	val http2Enabled: Boolean = false,
	val rootDir: String = "",
)

fun initJson(): Jackson3JsonApi {
	val mapper = jsonMapper {
		addModules(
			kotlinModule(),
			DateTimeJsonConverter().getJackson3Module(),
			UUIDJsonConverter().getJackson3Module(),
			NetworkEndpointJsonConverter().getJackson3Module(),
		)
	}
	val jsonApi = Jackson3JsonApi(mapper)
	JSONA.setJsonApi(jsonApi)
	return jsonApi
}

/**
 * 在 [baseDir] 内安全解析用户请求路径：先规范化再校验是否仍在根目录内（防 ../ 穿越），
 * 目录请求回落到其 index.html。找不到抛 [FileNotFoundException]，越界抛 [SecurityException]。
 */
fun safeResolve(baseDir: File, userInput: String): File {
	val base = baseDir.canonicalFile
	val rel = userInput.trimStart('/')
	val target = File(base, rel).canonicalFile

	if (target.path != base.path && !target.path.startsWith(base.path + File.separator)) {
		throw SecurityException("非法路径访问尝试: $userInput")
	}
	if (!target.exists()) {
		throw FileNotFoundException(userInput)
	}
	return when {
		target.isFile -> target
		target.isDirectory -> File(target, "index.html").takeIf { it.isFile }
			?: throw FileNotFoundException(userInput)

		else -> throw IllegalArgumentException("目标不是有效的文件")
	}
}

suspend fun safeExtractText(file: File): String? {
	return try {
		withTimeout(5.seconds) {
			tika.parseToString(file)
		}
	} catch (e: TimeoutCancellationException) {
		log.warn("文件解析超时，已强制取消: ${file.name}")
		null
	}
}

suspend fun httped(
	httpRequest: HttpRequest,
	requestBody: RequestBody,
	ctx: ChannelHandlerContext,
	rootDir: File,
) {
	// 静态资源不读请求体，排水以复用 keep-alive 连接
	requestBody.abandon()

	// 去掉查询串并解码 %xx，只取路径部分
	val path = QueryStringDecoder(httpRequest.uri()).path()
	log.debug { "${httpRequest.method()} $path" }

	var status = HttpResponseStatus.OK
	val file = try {
		safeResolve(rootDir, path)
	} catch (e: FileNotFoundException) {
		status = HttpResponseStatus.NOT_FOUND
		null
	} catch (e: SecurityException) {
		status = HttpResponseStatus.FORBIDDEN
		null
	} catch (e: IllegalArgumentException) {
		status = HttpResponseStatus.BAD_REQUEST
		null
	} catch (e: Exception) {
		log.error("解析路径失败: $path", e)
		status = HttpResponseStatus.INTERNAL_SERVER_ERROR
		null
	}


	val contentType = if (file != null) tika.detect(file) ?: "text/html" else "text/html"

	val isHead = httpRequest.method() == HttpMethod.HEAD

	if (file != null) {
		val total = file.length()
		// 解析单范围 Range（多线程下载各连接各取一段）；多范围回退整文件 200
		var start = 0L
		var end = total - 1
		var partial = false
		var unsatisfiable = false
		val rangeHeader = httpRequest.headers().get(HttpHeaderNames.RANGE)
		if (rangeHeader != null && rangeHeader.startsWith("bytes=") && ',' !in rangeHeader) {
			val spec = rangeHeader.removePrefix("bytes=").trim()
			val dash = spec.indexOf('-')
			if (dash >= 0) {
				val s = spec.substring(0, dash).trim()
				val e = spec.substring(dash + 1).trim()
				val from: Long?
				val to: Long?
				if (s.isEmpty()) {
					// 后缀范围 bytes=-N：取末尾 N 字节
					val n = e.toLongOrNull()
					from = if (n != null && n > 0) maxOf(0L, total - n) else null
					to = total - 1
				} else {
					from = s.toLongOrNull()
					to = if (e.isEmpty()) total - 1 else e.toLongOrNull()
				}
				if (from != null && to != null) {
					if (from < 0 || from >= total || from > to) {
						unsatisfiable = true
					} else {
						start = from
						end = minOf(to, total - 1)
						partial = true
					}
				}
			}
		}

		val respStatus = when {
			unsatisfiable -> HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE
			partial -> HttpResponseStatus.PARTIAL_CONTENT
			else -> HttpResponseStatus.OK
		}
		val head = DefaultHttpResponse(httpRequest.protocolVersion(), respStatus)
		head.addHttpHeaders(
			"content-type" to contentType,
			"accept-ranges" to "bytes",
		)
		if (unsatisfiable) {
			head.headers().set(HttpHeaderNames.CONTENT_RANGE, "bytes */$total")
			HttpUtil.setContentLength(head, 0)
		} else {
			HttpUtil.setContentLength(head, end - start + 1)
			if (partial) {
				head.headers().set(HttpHeaderNames.CONTENT_RANGE, "bytes $start-$end/$total")
			}
		}
		val stream = ResponseStream(ctx, httpRequest).also { it.start(head) }

		if (!isHead && !unsatisfiable && total > 0) {
			RandomAccessFile(file, "r").use { raf ->
				raf.seek(start)
				var remaining = end - start + 1
				val buf = ByteArray(8192)
				while (remaining > 0 && ctx.channel().isActive) {
					// 写侧背压：outbound 缓冲过高时让出，待事件循环刷出后再继续
					if (!ctx.channel().isWritable) {
						delay(5.milliseconds)
						continue
					}
					val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
					if (n == -1) break
					remaining -= n
					// 必须拷贝：buf 会被下一轮 read 复用，而 writeChunk 异步写出
					stream.writeChunk(Unpooled.copiedBuffer(buf, 0, n))
				}
			}
		}
		stream.finish()
		return
	}

	// 错误响应：HTML 错误页
	val head = DefaultHttpResponse(httpRequest.protocolVersion(), status)
	head.addHttpHeaders("content-type" to contentType)
	HttpUtil.setTransferEncodingChunked(head, true)
	val stream = ResponseStream(ctx, httpRequest).also { it.start(head) }
	if (!isHead) {
		val reasonPhrase = "${status.code()} ${status.reasonPhrase()}"
		stream.writeChunk(
			Unpooled.copiedBuffer(
				MessageFormatter.arrayFormat(
					SERVER_ERROR,
					arrayOf(
						reasonPhrase, reasonPhrase, HeaderUtils.getServerName(),
					)
				).message, Charsets.UTF_8
			)
		)
	}
	stream.finish()
}

fun main(args: Array<String>) {
	val jsonApi = initJson()

	val configFile = if (args.isEmpty()) {
		File(SERVER_CONFIG_PATH).apply {
			parentFile?.mkdirs()
			try {
				if (createNewFile()) {
					log.info("Default config file created at: $absolutePath")
					jsonApi.format(ServerConfig(), this)
				}
			} catch (e: IOException) {
				log.error("Failed to create config file: $absolutePath", e)
				exitProcess(1)
			}
		}
	} else {
		File(args[0])
	}

	if (!configFile.exists() || !configFile.isFile) {
		log.error("Config file not found or invalid: \"${configFile.absolutePath}\"")
		exitProcess(1)
	}

	val config = jsonApi.parseFrom<ServerConfig>(configFile)
	log.info("Server configuration loaded successfully.")
	log.info { "config ${config.toJson()}" }

	val rootDir = File(config.rootDir).absoluteFile
	log.info { "rootDir: $rootDir" }

	val server = NettyWebServer.server(
		NettyServerConfig(
			serverName = config.serverName,
			host = config.host,
			port = config.port,
			queueCapacity = config.queueCapacity,
			maxRequestSize = config.maxRequestSize,
			bossThreads = config.bossThreads,
			workerThreads = config.workerThreads,
			bossThreadName = config.bossThreadName,
			workerThreadName = config.workerThreadName,
			sportIO = config.sportIO,
			http2Enabled = config.http2Enabled,
			streamingHandler = { httpRequest, requestBody, ctx ->
				httped(httpRequest, requestBody, ctx, rootDir)
			},
		)
	)

	Runtime.getRuntime().addShutdownHook(Thread {
		server.stopServer()
	})

	server.startServer()
	server.sync()
}
