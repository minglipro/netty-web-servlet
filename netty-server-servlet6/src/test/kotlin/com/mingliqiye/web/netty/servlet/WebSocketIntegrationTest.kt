package com.mingliqiye.web.netty.servlet

import com.mingliqiye.web.netty.servlet.config.ServletServerConfig
import jakarta.websocket.CloseReason
import jakarta.websocket.Endpoint
import jakarta.websocket.EndpointConfig
import jakarta.websocket.MessageHandler
import jakarta.websocket.OnClose
import jakarta.websocket.OnMessage
import jakarta.websocket.OnOpen
import jakarta.websocket.PongMessage
import jakarta.websocket.Session
import jakarta.websocket.server.PathParam
import jakarta.websocket.server.ServerEndpoint
import jakarta.websocket.server.ServerEndpointConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 原生 jakarta.websocket 端到端测试：JDK WebSocket 客户端 → Netty 容器 101 升级 → 帧收发。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebSocketIntegrationTest {
	private lateinit var server: NettyServletServer
	private val base = "ws://127.0.0.1:18097"

	class EchoEndpoint : Endpoint() {
		override fun onOpen(session: Session, config: EndpointConfig) {
			session.addMessageHandler(String::class.java, MessageHandler.Whole<String> { text ->
				if (text == "quit") {
					session.close(CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "server-bye"))
				} else {
					session.basicRemote.sendText("echo:$text")
				}
			})
			session.addMessageHandler(ByteBuffer::class.java, MessageHandler.Whole<ByteBuffer> { data ->
				session.basicRemote.sendBinary(data)
			})
		}

		override fun onClose(session: Session, closeReason: CloseReason) {
			closedSessionsStatic[session.id] = closeReason
		}

		override fun onError(session: Session, thr: Throwable) {}
	}

	class FragmentEndpoint : Endpoint() {
		private val buffer = StringBuilder()

		override fun onOpen(session: Session, config: EndpointConfig) {
			session.addMessageHandler(String::class.java, MessageHandler.Partial<String> { text, last ->
				buffer.append(text)
				if (last) {
					session.basicRemote.sendText("frag:${buffer}")
					buffer.setLength(0)
				}
			})
		}
	}

	@ServerEndpoint("/ws/room/{room}")
	class RoomEndpoint {
		@OnOpen
		fun open(session: Session, @PathParam("room") room: String) {
			session.userProperties["room"] = room
			session.basicRemote.sendText("joined:$room")
		}

		@OnMessage
		fun message(text: String, session: Session, @PathParam("room") room: String): String =
			"room=$room;msg=$text"

		@OnClose
		fun close(session: Session, reason: CloseReason) {
			closedSessionsStatic[session.id] = reason
		}
	}

	companion object {
		val closedSessionsStatic = ConcurrentHashMap<String, CloseReason>()
	}

	@BeforeAll
	fun setup() {
		server = NettyServletServer(ServletServerConfig(port = 18097))
		server.container.webSockets.addEndpoint(
			ServerEndpointConfig.Builder.create(EchoEndpoint::class.java, "/ws/echo")
				.subprotocols(listOf("chat", "superchat"))
				.build()
		)
		server.container.webSockets.addEndpoint(
			ServerEndpointConfig.Builder.create(FragmentEndpoint::class.java, "/ws/frag").build()
		)
		server.container.webSockets.addEndpoint(RoomEndpoint::class.java)
		server.startServer()
	}

	@AfterAll
	fun teardown() {
		server.stopServer()
	}

	private class Collector : WebSocket.Listener {
		val texts = LinkedBlockingQueue<String>()
		val binaries = LinkedBlockingQueue<ByteBuffer>()
		val pongs = LinkedBlockingQueue<ByteBuffer>()
		val closeCode = CompletableFuture<Int>()
		val closeReason = CompletableFuture<String>()
		private val textBuffer = StringBuilder()
		private val binaryBuffer = java.io.ByteArrayOutputStream()

		override fun onOpen(webSocket: WebSocket) {
			webSocket.request(1)
		}

		override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
			textBuffer.append(data)
			if (last) {
				texts.add(textBuffer.toString())
				textBuffer.setLength(0)
			}
			webSocket.request(1)
			return null
		}

		override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
			val bytes = ByteArray(data.remaining())
			data.get(bytes)
			binaryBuffer.write(bytes)
			if (last) {
				binaries.add(ByteBuffer.wrap(binaryBuffer.toByteArray()))
				binaryBuffer.reset()
			}
			webSocket.request(1)
			return null
		}

		override fun onPong(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
			val bytes = ByteArray(message.remaining())
			message.get(bytes)
			pongs.add(ByteBuffer.wrap(bytes))
			webSocket.request(1)
			return null
		}

		override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
			closeCode.complete(statusCode)
			closeReason.complete(reason)
			return null
		}
	}

	private fun connect(path: String, listener: Collector, subprotocol: String? = null): WebSocket {
		val builder = HttpClient.newHttpClient().newWebSocketBuilder()
		if (subprotocol != null) builder.subprotocols(subprotocol)
		return builder.buildAsync(URI.create("$base$path"), listener).get(5, TimeUnit.SECONDS)
	}

	private fun awaitText(listener: Collector): String =
		listener.texts.poll(5, TimeUnit.SECONDS) ?: error("No text message received")

	@Test
	fun `text echo with subprotocol negotiation`() {
		val listener = Collector()
		val ws = connect("/ws/echo", listener, "chat")
		assertEquals("chat", ws.subprotocol)
		ws.sendText("hello", true).get(5, TimeUnit.SECONDS)
		assertEquals("echo:hello", awaitText(listener))
		ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
		assertEquals(1000, listener.closeCode.get(5, TimeUnit.SECONDS))
	}

	@Test
	fun `binary echo`() {
		val listener = Collector()
		val ws = connect("/ws/echo", listener)
		val payload = byteArrayOf(1, 2, 3, 4, 5, 0, -1)
		ws.sendBinary(ByteBuffer.wrap(payload), true).get(5, TimeUnit.SECONDS)
		val received = listener.binaries.poll(5, TimeUnit.SECONDS) ?: error("No binary message received")
		val bytes = ByteArray(received.remaining())
		received.get(bytes)
		assertContentEquals(payload, bytes)
		ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
	}

	@Test
	fun `fragmented text delivered to partial handler`() {
		val listener = Collector()
		val ws = connect("/ws/frag", listener)
		ws.sendText("ab", false).get(5, TimeUnit.SECONDS)
		ws.sendText("cd", false).get(5, TimeUnit.SECONDS)
		ws.sendText("ef", true).get(5, TimeUnit.SECONDS)
		assertEquals("frag:abcdef", awaitText(listener))
		ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
	}

	@Test
	fun `ping answered with pong`() {
		val listener = Collector()
		val ws = connect("/ws/echo", listener)
		val payload = ByteBuffer.wrap("ping-data".toByteArray())
		ws.sendPing(payload).get(5, TimeUnit.SECONDS)
		val pong = listener.pongs.poll(5, TimeUnit.SECONDS) ?: error("No pong received")
		val bytes = ByteArray(pong.remaining())
		pong.get(bytes)
		assertEquals("ping-data", String(bytes))
		ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
	}

	@Test
	fun `annotated endpoint with path parameter`() {
		val listener = Collector()
		val ws = connect("/ws/room/lobby", listener)
		assertEquals("joined:lobby", awaitText(listener))
		ws.sendText("hi", true).get(5, TimeUnit.SECONDS)
		assertEquals("room=lobby;msg=hi", awaitText(listener))
		ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
		assertEquals(1000, listener.closeCode.get(5, TimeUnit.SECONDS))
	}

	@Test
	fun `server initiated close`() {
		val listener = Collector()
		val ws = connect("/ws/echo", listener)
		ws.sendText("quit", true).get(5, TimeUnit.SECONDS)
		assertEquals(1000, listener.closeCode.get(5, TimeUnit.SECONDS))
		assertEquals("server-bye", listener.closeReason.get(5, TimeUnit.SECONDS))
		assertTrue(closedSessionsStatic.isNotEmpty(), "endpoint onClose should have fired")
	}

	@Test
	fun `client close fires endpoint onClose`() {
		val before = closedSessionsStatic.size
		val listener = Collector()
		val ws = connect("/ws/echo", listener)
		ws.sendText("warmup", true).get(5, TimeUnit.SECONDS)
		awaitText(listener)
		ws.sendClose(WebSocket.NORMAL_CLOSURE, "client-bye").get(5, TimeUnit.SECONDS)
		assertEquals(1000, listener.closeCode.get(5, TimeUnit.SECONDS))
		val deadline = System.currentTimeMillis() + 5000
		while (closedSessionsStatic.size <= before && System.currentTimeMillis() < deadline) {
			Thread.sleep(50)
		}
		assertTrue(closedSessionsStatic.size > before, "endpoint onClose should have fired")
	}

	@Test
	fun `oversized text message closed with 1009`() {
		val listener = Collector()
		val ws = connect("/ws/echo", listener)
		ws.sendText("x".repeat(9000), true).get(5, TimeUnit.SECONDS)
		assertEquals(1009, listener.closeCode.get(5, TimeUnit.SECONDS))
	}

	@Test
	fun `plain http request to websocket path is not upgraded`() {
		val response = HttpClient.newHttpClient().send(
			java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:18097/ws/echo")).GET().build(),
			java.net.http.HttpResponse.BodyHandlers.ofString()
		)
		assertEquals(404, response.statusCode())
	}
}