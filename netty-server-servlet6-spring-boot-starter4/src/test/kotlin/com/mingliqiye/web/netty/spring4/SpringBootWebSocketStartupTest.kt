package com.mingliqiye.web.netty.spring4

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootApplication
open class WebSocketTestApp

@Configuration
@EnableWebSocket
open class WebSocketConfig : WebSocketConfigurer {
	override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
		registry.addHandler(object : TextWebSocketHandler() {
			override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
				session.sendMessage(TextMessage("echo:${message.payload}"))
			}
		}, "/ws")
	}

	@org.springframework.context.annotation.Bean
	open fun wrappingFilter(): org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> {
		val registration = org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>(
			object : jakarta.servlet.Filter {
				override fun doFilter(
					request: jakarta.servlet.ServletRequest,
					response: jakarta.servlet.ServletResponse,
					chain: jakarta.servlet.FilterChain,
				) {
					chain.doFilter(
						object : jakarta.servlet.http.HttpServletRequestWrapper(request as jakarta.servlet.http.HttpServletRequest) {},
						object : jakarta.servlet.http.HttpServletResponseWrapper(response as jakarta.servlet.http.HttpServletResponse) {},
					)
				}
			}
		)
		registration.addUrlPatterns("/ws")
		return registration
	}
}

@SpringBootTest(
	classes = [WebSocketTestApp::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class SpringBootWebSocketStartupTest {
	@Autowired
	lateinit var context: ApplicationContext

	@LocalServerPort
	var port: Int = 0

	@Test
	fun `websocket handler mapping starts without tomcat websocket jar`() {
		assertTrue(context.containsBean("webSocketHandlerMapping"))
	}

	@Test
	fun `spring websocket handler echoes over real connection`() {
		val texts = LinkedBlockingQueue<String>()
		val listener = object : WebSocket.Listener {
			private val buffer = StringBuilder()

			override fun onOpen(webSocket: WebSocket) {
				webSocket.request(1)
			}

			override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
				buffer.append(data)
				if (last) {
					texts.add(buffer.toString())
					buffer.setLength(0)
				}
				webSocket.request(1)
				return null
			}
		}
		val ws = HttpClient.newHttpClient().newWebSocketBuilder()
			.buildAsync(URI.create("ws://127.0.0.1:$port/ws"), listener)
			.get(5, TimeUnit.SECONDS)
		ws.sendText("hello-spring", true).get(5, TimeUnit.SECONDS)
		val received = texts.poll(5, TimeUnit.SECONDS) ?: error("No echo received from Spring WebSocket handler")
		assertEquals("echo:hello-spring", received)
		ws.sendText("second", true).get(5, TimeUnit.SECONDS)
		assertEquals("echo:second", texts.poll(5, TimeUnit.SECONDS))
		ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
	}
}