package com.mingliqiye.web.netty.servlet

import com.mingliqiye.web.netty.servlet.config.ServletServerConfig
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketVersion
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler
import jakarta.websocket.Endpoint
import jakarta.websocket.EndpointConfig
import jakarta.websocket.MessageHandler
import jakarta.websocket.Session
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** permessage-deflate 端到端：Netty 客户端（带/不带压缩扩展）→ 容器协商 → 压缩帧收发。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebSocketDeflateIntegrationTest {
	private lateinit var server: NettyServletServer
	private val port = 18108

	class DeflateEchoEndpoint : Endpoint() {
		override fun onOpen(session: Session, config: EndpointConfig) {
			session.maxTextMessageBufferSize = 200_000
			session.maxBinaryMessageBufferSize = 200_000
			negotiatedStatic.add(session.negotiatedExtensions.map { it.name })
			session.addMessageHandler(String::class.java, MessageHandler.Whole<String> { text ->
				session.basicRemote.sendText("echo:$text")
			})
			session.addMessageHandler(ByteBuffer::class.java, MessageHandler.Whole<ByteBuffer> { data ->
				session.basicRemote.sendBinary(data)
			})
		}

		override fun onClose(session: Session, closeReason: jakarta.websocket.CloseReason) {}
		override fun onError(session: Session, thr: Throwable) {}
	}

	companion object {
		val negotiatedStatic = LinkedBlockingQueue<List<String>>()
	}

	@BeforeAll
	fun setup() {
		server = NettyServletServer(ServletServerConfig(port = port))
		server.container.webSockets.addEndpoint(
			jakarta.websocket.server.ServerEndpointConfig.Builder.create(DeflateEchoEndpoint::class.java, "/ws/deflate").build()
		)
		server.startServer()
	}

	@AfterAll
	fun teardown() {
		if (::server.isInitialized) server.stopServer()
	}

	private class WsClient(
		val group: MultiThreadIoEventLoopGroup,
		val channel: Channel,
		val texts: LinkedBlockingQueue<String>,
		val binaries: LinkedBlockingQueue<ByteArray>,
	) {
		fun sendText(text: String) {
			channel.writeAndFlush(TextWebSocketFrame(text)).sync()
		}

		fun sendBinary(data: ByteArray) {
			channel.writeAndFlush(BinaryWebSocketFrame(io.netty.buffer.Unpooled.wrappedBuffer(data))).sync()
		}

		fun close() {
			runCatching { channel.writeAndFlush(CloseWebSocketFrame()).sync() }
			runCatching { channel.close().sync() }
			group.shutdownGracefully(0, 1, TimeUnit.SECONDS)
		}
	}

	private fun connect(compression: Boolean): WsClient {
		val group = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
		val texts = LinkedBlockingQueue<String>()
		val binaries = LinkedBlockingQueue<ByteArray>()
		val handshakeDone = CompletableFuture<Void>()
		val bootstrap = Bootstrap().group(group).channel(NioSocketChannel::class.java)
			.handler(object : ChannelInitializer<SocketChannel>() {
				override fun initChannel(ch: SocketChannel) {
					val pipeline = ch.pipeline()
					pipeline.addLast(HttpClientCodec())
					pipeline.addLast(HttpObjectAggregator(65536))
					if (compression) {
						// 0 = 默认最大帧载荷，等价于已弃用的 INSTANCE 单例
						pipeline.addLast(WebSocketClientCompressionHandler(0))
					}
					pipeline.addLast(
						WebSocketClientProtocolHandler(
							URI.create("ws://127.0.0.1:$port/ws/deflate"),
							WebSocketVersion.V13, null, true, DefaultHttpHeaders(), 65536
						)
					)
					pipeline.addLast(object : SimpleChannelInboundHandler<WebSocketFrame>() {
						override fun channelRead0(ctx: io.netty.channel.ChannelHandlerContext, frame: WebSocketFrame) {
							when (frame) {
								is TextWebSocketFrame -> texts.add(frame.text())
								is BinaryWebSocketFrame -> {
									val bytes = ByteArray(frame.content().readableBytes())
									frame.content().readBytes(bytes)
									binaries.add(bytes)
								}
								else -> {}
							}
						}

						override fun userEventTriggered(ctx: io.netty.channel.ChannelHandlerContext, evt: Any) {
							if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
								handshakeDone.complete(null)
							}
							ctx.fireUserEventTriggered(evt)
						}
					})
				}
			})
		val channel = bootstrap.connect("127.0.0.1", port).sync().channel()
		handshakeDone.get(5, TimeUnit.SECONDS)
		return WsClient(group, channel, texts, binaries)
	}

	@Test
	fun `deflate negotiated and text echoes through compressed frames`() {
		val client = connect(compression = true)
		try {
			client.sendText("hello-deflate")
			assertEquals("echo:hello-deflate", client.texts.poll(5, TimeUnit.SECONDS))
			assertEquals(listOf("permessage-deflate"), negotiatedStatic.poll(5, TimeUnit.SECONDS))
		} finally {
			client.close()
		}
	}

	@Test
	fun `large text roundtrip with deflate`() {
		val client = connect(compression = true)
		try {
			val big = "x".repeat(20_000)
			client.sendText(big)
			assertEquals("echo:$big", client.texts.poll(5, TimeUnit.SECONDS))
			negotiatedStatic.poll(5, TimeUnit.SECONDS)
		} finally {
			client.close()
		}
	}

	@Test
	fun `binary roundtrip with deflate`() {
		val client = connect(compression = true)
		try {
			val data = ByteArray(5000) { (it % 251).toByte() }
			client.sendBinary(data)
			val echoed = client.binaries.poll(5, TimeUnit.SECONDS)
			assertContentEquals(data, echoed)
			negotiatedStatic.poll(5, TimeUnit.SECONDS)
		} finally {
			client.close()
		}
	}

	@Test
	fun `client without extension offer gets no negotiation`() {
		val client = connect(compression = false)
		try {
			client.sendText("plain")
			assertEquals("echo:plain", client.texts.poll(5, TimeUnit.SECONDS))
			assertEquals(emptyList(), negotiatedStatic.poll(5, TimeUnit.SECONDS))
		} finally {
			client.close()
		}
	}

	@Test
	fun `installed extensions advertise permessage-deflate`() {
		assertTrue(server.container.webSockets.installedExtensions.any { it.name == "permessage-deflate" })
	}
}