package com.mingliqiye.web.netty.servlet

import com.mingliqiye.web.netty.servlet.config.ServletServerConfig
import io.netty.handler.ssl.SslContextBuilder
import io.netty.pkitesting.CertificateBuilder
import io.netty.pkitesting.X509Bundle
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.test.assertContains

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpsAndTrailerIntegrationTest {
	private lateinit var server: NettyServletServer
	private lateinit var certificate: X509Bundle
	private lateinit var trustAll: SSLContext
	private val port = 18099

	private fun trustAllContext(): SSLContext {
		val tm = object : X509TrustManager {
			override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
			override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
			override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
		}
		return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
	}

	@BeforeAll
	fun setup() {
		certificate = CertificateBuilder().subject("CN=localhost").rsa2048().setIsCertificateAuthority(true).buildSelfSigned()
		trustAll = trustAllContext()
		server = NettyServletServer(
			ServletServerConfig(
				port = port,
				sslContext = SslContextBuilder.forServer(certificate.keyPair.private, certificate.certificate).build(),
			)
		)
		server.addServlet("secure", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.writer.write(
					"secure=${req.isSecure};scheme=${req.scheme};url=${req.requestURL}" +
						";conn=${req.servletConnection.isSecure()}"
				)
			}
		}, "/secure")
		server.addServlet("trailers", object : HttpServlet() {
			override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
				req.inputStream.readBytes()
				resp.writer.write("ready=${req.isTrailerFieldsReady};checksum=${req.trailerFields["X-Checksum"]}")
			}
		}, "/trailers")
		server.startServer()
	}

	@AfterAll
	fun teardown() {
		if (::server.isInitialized) server.stopServer()
	}

	@Test
	fun `https request reports secure scheme and url`() {
		val socket = trustAll.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
		socket.startHandshake()
		socket.getOutputStream().write(
			"GET /secure HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray()
		)
		socket.getOutputStream().flush()
		val response = BufferedReader(InputStreamReader(socket.getInputStream())).readText()
		socket.close()
		assertContains(response, "secure=true;scheme=https;url=https://127.0.0.1:$port/secure;conn=true")
	}

	@Test
	fun `chunked trailer fields are exposed after body completes`() {
		val socket = trustAll.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
		socket.startHandshake()
		socket.getOutputStream().write(
			(
				"POST /trailers HTTP/1.1\r\n" +
					"Host: 127.0.0.1:$port\r\n" +
					"Transfer-Encoding: chunked\r\n" +
					"Trailer: X-Checksum\r\n" +
					"Connection: close\r\n" +
					"\r\n" +
					"5\r\nhello\r\n" +
					"0\r\n" +
					"X-Checksum: abc123\r\n" +
					"\r\n"
				).toByteArray()
		)
		socket.getOutputStream().flush()
		val response = BufferedReader(InputStreamReader(socket.getInputStream())).readText()
		socket.close()
		assertContains(response, "ready=true;checksum=abc123")
	}
}