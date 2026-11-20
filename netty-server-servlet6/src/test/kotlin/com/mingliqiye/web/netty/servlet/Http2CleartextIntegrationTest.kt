package com.mingliqiye.web.netty.servlet

import com.mingliqiye.web.netty.servlet.config.ServletServerConfig
import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http2.DefaultHttp2Headers
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame
import io.netty.handler.codec.http2.Http2DataFrame
import io.netty.handler.codec.http2.Http2Frame
import io.netty.handler.codec.http2.Http2FrameCodecBuilder
import io.netty.handler.codec.http2.Http2HeadersFrame
import io.netty.handler.codec.http2.Http2MultiplexHandler
import io.netty.handler.codec.http2.Http2StreamChannel
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** 明文 HTTP/2 端到端：h2c 升级、prior-knowledge、HTTP/1.1 回退与多路复用。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Http2CleartextIntegrationTest {
	private lateinit var server: NettyServletServer
	private val port = 18110
	private val h2Client: HttpClient = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(5)).build()
	private val h1Client: HttpClient = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()

	@BeforeAll
	fun setup() {
		server = NettyServletServer(ServletServerConfig(port = port, http2Enabled = true))
		server.addServlet("echo", object : HttpServlet() {
			override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
				val body = req.inputStream.readBytes().toString(Charsets.UTF_8)
				resp.contentType = "text/plain; charset=UTF-8"
				resp.writer.write("method=${req.method};path=${req.requestURI};query=${req.queryString};body=$body")
			}
		}, "/echo")
		server.startServer()
	}

	@AfterAll
	fun teardown() {
		server.stopServer()
	}

	private fun get(client: HttpClient, path: String): HttpResponse<String> =
		client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

	@Test
	fun `h2c upgrade GET negotiates HTTP2`() {
		val resp = get(h2Client, "/echo?x=1")
		assertEquals(HttpClient.Version.HTTP_2, resp.version())
		assertEquals(200, resp.statusCode())
		assertContains(resp.body(), "method=GET;path=/echo;query=x=1;body=")
	}

	@Test
	fun `h2c upgrade POST carries request body`() {
		val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/echo"))
			.POST(HttpRequest.BodyPublishers.ofString("hello-h2")).build()
		val resp = h2Client.send(request, HttpResponse.BodyHandlers.ofString())
		assertEquals(HttpClient.Version.HTTP_2, resp.version())
		assertEquals(200, resp.statusCode())
		assertContains(resp.body(), "method=POST;path=/echo;query=null;body=hello-h2")
	}

	@Test
	fun `http1 client falls back to HTTP1`() {
		val resp = get(h1Client, "/echo?fallback=1")
		assertEquals(HttpClient.Version.HTTP_1_1, resp.version())
		assertContains(resp.body(), "method=GET;path=/echo;query=fallback=1")
	}

	@Test
	fun `multiple requests multiplex over one HTTP2 connection`() {
		val futures = (1..8).map { i ->
			h2Client.sendAsync(
				HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/echo?n=$i")).GET().build(),
				HttpResponse.BodyHandlers.ofString(),
			)
		}
		val responses = futures.map { it.get(10, TimeUnit.SECONDS) }
		responses.forEachIndexed { index, resp ->
			assertEquals(HttpClient.Version.HTTP_2, resp.version())
			assertContains(resp.body(), "query=n=${index + 1}")
		}
	}

	@Test
	fun `prior knowledge client speaks HTTP2 directly`() {
		val body = priorKnowledgeGet("/echo?pk=1")
		assertContains(body, "method=GET;path=/echo;query=pk=1;body=")
	}

	/** Netty HTTP/2 客户端：直接发送连接 preface（prior-knowledge），不经 h2c 升级。 */
	private fun priorKnowledgeGet(path: String): String {
		val group = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
		val responseFuture = CompletableFuture<String>()
		try {
			val streamHandler = object : ChannelInitializer<Http2StreamChannel>() {
				override fun initChannel(ch: Http2StreamChannel) {
					ch.pipeline().addLast(object : SimpleChannelInboundHandler<Http2Frame>() {
						val body = StringBuilder()
						override fun channelRead0(ctx: ChannelHandlerContext, frame: Http2Frame) {
							when (frame) {
								is Http2DataFrame -> {
									body.append(frame.content().toString(Charsets.UTF_8))
									if (frame.isEndStream) responseFuture.complete(body.toString())
								}

								is Http2HeadersFrame -> if (frame.isEndStream) responseFuture.complete(body.toString())
							}
						}

						override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
							responseFuture.completeExceptionally(cause)
							ctx.close()
						}
					})
				}
			}
			val bootstrap = Bootstrap().group(group).channel(NioSocketChannel::class.java)
				.handler(object : ChannelInitializer<SocketChannel>() {
					override fun initChannel(ch: SocketChannel) {
						ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build())
						ch.pipeline().addLast(Http2MultiplexHandler(streamHandler))
					}
				})
			val channel = bootstrap.connect("127.0.0.1", port).sync().channel()
			val streamChannel = Http2StreamChannelBootstrap(channel).handler(streamHandler).open().sync().now
			val headers = DefaultHttp2Headers().method("GET").path(path)
				.authority("127.0.0.1:$port").scheme("http")
			streamChannel.writeAndFlush(DefaultHttp2HeadersFrame(headers, true)).sync()
			return responseFuture.get(10, TimeUnit.SECONDS)
		} finally {
			group.shutdownGracefully(0, 1, TimeUnit.SECONDS)
		}
	}
}