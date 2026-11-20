package com.mingliqiye.web.netty.servlet

import com.mingliqiye.web.netty.servlet.config.ServletServerConfig
import jakarta.websocket.ClientEndpoint
import jakarta.websocket.ClientEndpointConfig
import jakarta.websocket.CloseReason
import jakarta.websocket.DeploymentException
import jakarta.websocket.Endpoint
import jakarta.websocket.EndpointConfig
import jakarta.websocket.HandshakeResponse
import jakarta.websocket.MessageHandler
import jakarta.websocket.OnClose
import jakarta.websocket.OnMessage
import jakarta.websocket.OnOpen
import jakarta.websocket.Session
import jakarta.websocket.server.HandshakeRequest
import jakarta.websocket.server.ServerEndpointConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** connectToServer 四形态端到端测试：本容器既当服务端又当客户端，loopback 验证 101 握手与帧收发。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebSocketClientIntegrationTest {
	private lateinit var server: NettyServletServer
	private val base = "ws://127.0.0.1:18109"

	class LoopbackServerEndpoint : Endpoint() {
		override fun onOpen(session: Session, config: EndpointConfig) {
			session.addMessageHandler(String::class.java, MessageHandler.Whole<String> { text ->
				session.basicRemote.sendText("echo:$text")
			})
			session.addMessageHandler(ByteBuffer::class.java, MessageHandler.Whole<ByteBuffer> { data ->
				session.basicRemote.sendBinary(data)
			})
		}

		override fun onClose(session: Session, closeReason: CloseReason) {
			serverClosedStatic[session.id] = closeReason
		}

		override fun onError(session: Session, thr: Throwable) {}
	}

	class TokenServerEndpoint : Endpoint() {
		override fun onOpen(session: Session, config: EndpointConfig) {
			session.basicRemote.sendText("token:" + (config.userProperties["clientToken"] ?: "none"))
		}
	}

	class CollectorEndpoint : Endpoint() {
		val texts = LinkedBlockingQueue<String>()
		val binaries = LinkedBlockingQueue<ByteBuffer>()
		val closed = CompletableFuture<CloseReason>()

		override fun onOpen(session: Session, config: EndpointConfig) {
			session.addMessageHandler(String::class.java, MessageHandler.Whole<String> { texts.put(it) })
			session.addMessageHandler(ByteBuffer::class.java, MessageHandler.Whole<ByteBuffer> { binaries.put(it) })
		}

		override fun onClose(session: Session, closeReason: CloseReason) {
			closed.complete(closeReason)
		}

		override fun onError(session: Session, thr: Throwable) {}
	}

	class ClassCollectorEndpoint : Endpoint() {
		override fun onOpen(session: Session, config: EndpointConfig) {
			session.addMessageHandler(String::class.java, MessageHandler.Whole<String> { classTextsStatic.put(it) })
		}

		override fun onClose(session: Session, closeReason: CloseReason) {
			classClosedStatic.complete(closeReason)
		}

		override fun onError(session: Session, thr: Throwable) {}
	}

	class InstanceAnnotatedClient {
		val texts = LinkedBlockingQueue<String>()

		@OnOpen
		fun open(session: Session) {
			session.userProperties["opened"] = true
		}

		@OnMessage
		fun onText(text: String) {
			texts.put(text)
		}
	}

	@ClientEndpoint(subprotocols = ["chat"])
	class AnnotatedClient {
		@OnOpen
		fun open(session: Session) {
			annotatedSubprotocolStatic.complete(session.negotiatedSubprotocol)
		}

		@OnMessage
		fun onText(text: String) {
			annotatedTextsStatic.put(text)
		}

		@OnClose
		fun close(reason: CloseReason) {
			annotatedClosedStatic.complete(reason)
		}
	}

	class CapturingConfigurator : ClientEndpointConfig.Configurator() {
		override fun beforeRequest(headers: MutableMap<String, MutableList<String>>) {
			headers["X-Client-Token"] = mutableListOf("abc123")
		}

		override fun afterResponse(response: HandshakeResponse) {
			afterResponseHeadersStatic.put(response.headers.toMap())
		}
	}

	companion object {
		val serverClosedStatic = ConcurrentHashMap<String, CloseReason>()
		val classTextsStatic = LinkedBlockingQueue<String>()
		val classClosedStatic = CompletableFuture<CloseReason>()
		val annotatedTextsStatic = LinkedBlockingQueue<String>()
		val annotatedSubprotocolStatic = CompletableFuture<String>()
		val annotatedClosedStatic = CompletableFuture<CloseReason>()
		val afterResponseHeadersStatic = LinkedBlockingQueue<Map<String, List<String>>>()
	}

	@BeforeAll
	fun setup() {
		server = NettyServletServer(ServletServerConfig(port = 18109))
		server.container.webSockets.addEndpoint(
			ServerEndpointConfig.Builder.create(LoopbackServerEndpoint::class.java, "/ws/echo")
				.subprotocols(listOf("chat", "superchat"))
				.build()
		)
		server.container.webSockets.addEndpoint(
			ServerEndpointConfig.Builder.create(TokenServerEndpoint::class.java, "/ws/token")
				.configurator(object : ServerEndpointConfig.Configurator() {
					override fun modifyHandshake(
						config: ServerEndpointConfig,
						request: HandshakeRequest,
						response: HandshakeResponse,
					) {
						request.headers["X-Client-Token"]?.firstOrNull()?.let {
							config.userProperties["clientToken"] = it
						}
					}
				})
				.build()
		)
		server.startServer()
	}

	@AfterAll
	fun teardown() {
		server.stopServer()
	}

	@Test
	fun `endpoint instance connect echoes text and binary with subprotocol`() {
		val client = CollectorEndpoint()
		val config = ClientEndpointConfig.Builder.create()
			.preferredSubprotocols(listOf("chat"))
			.build()
		val uri = URI.create("$base/ws/echo?a=1&a=2&b=x")
		val session = server.container.webSockets.connectToServer(client, config, uri)

		assertEquals("chat", session.negotiatedSubprotocol)
		assertEquals(uri, session.requestURI)
		assertEquals("a=1&a=2&b=x", session.queryString)
		assertEquals(listOf("1", "2"), session.requestParameterMap["a"])
		assertTrue(session.isOpen)

		session.basicRemote.sendText("hello")
		assertEquals("echo:hello", client.texts.poll(5, TimeUnit.SECONDS))

		val payload = byteArrayOf(1, 2, 3, 4, 5)
		session.basicRemote.sendBinary(ByteBuffer.wrap(payload))
		val echoed = client.binaries.poll(5, TimeUnit.SECONDS)!!
		val bytes = ByteArray(echoed.remaining())
		echoed.get(bytes)
		assertContentEquals(payload, bytes)

		session.close(CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "client-bye"))
		val clientReason = client.closed.get(5, TimeUnit.SECONDS)
		assertEquals(CloseReason.CloseCodes.NORMAL_CLOSURE, clientReason.closeCode)
		assertTrue(serverClosedStatic.values.any { it.reasonPhrase == "client-bye" })
	}

	@Test
	fun `endpoint class form instantiates and connects`() {
		val config = ClientEndpointConfig.Builder.create().build()
		val session = server.container.webSockets.connectToServer(
			ClassCollectorEndpoint::class.java, config, URI.create("$base/ws/echo")
		)
		session.basicRemote.sendText("class-form")
		assertEquals("echo:class-form", classTextsStatic.poll(5, TimeUnit.SECONDS))
		session.close()
		assertEquals(CloseReason.CloseCodes.NORMAL_CLOSURE, classClosedStatic.get(5, TimeUnit.SECONDS).closeCode)
	}

	@Test
	fun `annotated instance form connects without annotation`() {
		val client = InstanceAnnotatedClient()
		val session = server.container.webSockets.connectToServer(client, URI.create("$base/ws/echo"))
		assertEquals(true, session.userProperties["opened"])
		session.basicRemote.sendText("annotated")
		assertEquals("echo:annotated", client.texts.poll(5, TimeUnit.SECONDS))
		session.close()
	}

	@Test
	fun `annotated class form reads ClientEndpoint subprotocols`() {
		val session = server.container.webSockets.connectToServer(AnnotatedClient::class.java, URI.create("$base/ws/echo"))
		assertEquals("chat", annotatedSubprotocolStatic.get(5, TimeUnit.SECONDS))
		assertEquals("chat", session.negotiatedSubprotocol)
		session.basicRemote.sendText("annotated-class")
		assertEquals("echo:annotated-class", annotatedTextsStatic.poll(5, TimeUnit.SECONDS))
		session.close()
		assertEquals(CloseReason.CloseCodes.NORMAL_CLOSURE, annotatedClosedStatic.get(5, TimeUnit.SECONDS).closeCode)
	}

	@Test
	fun `configurator beforeRequest and afterResponse are invoked`() {
		val client = CollectorEndpoint()
		val config = ClientEndpointConfig.Builder.create()
			.configurator(CapturingConfigurator())
			.build()
		val session = server.container.webSockets.connectToServer(client, config, URI.create("$base/ws/token"))
		assertEquals("token:abc123", client.texts.poll(5, TimeUnit.SECONDS))
		val responseHeaders = afterResponseHeadersStatic.poll(5, TimeUnit.SECONDS)!!
		val upgrade = responseHeaders.entries.firstOrNull { it.key.equals("Upgrade", ignoreCase = true) }
		assertEquals(listOf("websocket"), upgrade?.value)
		session.close()
	}

	@Test
	fun `connect to unreachable server throws DeploymentException`() {
		val client = CollectorEndpoint()
		val config = ClientEndpointConfig.Builder.create().build()
		assertFailsWith<DeploymentException> {
			server.container.webSockets.connectToServer(client, config, URI.create("ws://127.0.0.1:18110/nope"))
		}
	}
}