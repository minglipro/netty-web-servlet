package com.mingliqiye.web.netty.servlet

import com.mingliqiye.web.netty.server.io.Http2Support
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** TLS HTTP/2 端到端：ALPN 协商 h2 与 http/1.1 回退，验证 isSecure/scheme 在流子通道上正确。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Http2TlsIntegrationTest {
	private lateinit var server: NettyServletServer
	private lateinit var certificate: X509Bundle
	private val port = 18111

	private fun trustAllContext(): SSLContext {
		val tm = object : X509TrustManager {
			override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
			override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
			override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
		}
		return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
	}

	private fun client(version: HttpClient.Version): HttpClient = HttpClient.newBuilder()
		.version(version).sslContext(trustAllContext()).connectTimeout(Duration.ofSeconds(5)).build()

	@BeforeAll
	fun setup() {
		certificate = CertificateBuilder().subject("CN=localhost").rsa2048().setIsCertificateAuthority(true).buildSelfSigned()
		server = NettyServletServer(
			ServletServerConfig(
				port = port,
				http2Enabled = true,
				sslContext = SslContextBuilder.forServer(certificate.keyPair.private, certificate.certificate)
					.applicationProtocolConfig(Http2Support.alpnConfig()).build(),
			)
		)
		server.addServlet("secure", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.contentType = "text/plain; charset=UTF-8"
				resp.writer.write("secure=${req.isSecure};scheme=${req.scheme};url=${req.requestURL}")
			}
		}, "/secure")
		server.startServer()
	}

	@AfterAll
	fun teardown() {
		if (::server.isInitialized) server.stopServer()
	}

	private fun get(client: HttpClient): HttpResponse<String> = client.send(
		HttpRequest.newBuilder(URI.create("https://127.0.0.1:$port/secure")).GET().build(),
		HttpResponse.BodyHandlers.ofString(),
	)

	@Test
	fun `alpn negotiates h2 and reports secure`() {
		val resp = get(client(HttpClient.Version.HTTP_2))
		assertEquals(HttpClient.Version.HTTP_2, resp.version())
		assertEquals(200, resp.statusCode())
		assertContains(resp.body(), "secure=true;scheme=https;url=https://127.0.0.1:$port/secure")
	}

	@Test
	fun `alpn falls back to http1 over tls`() {
		val resp = get(client(HttpClient.Version.HTTP_1_1))
		assertEquals(HttpClient.Version.HTTP_1_1, resp.version())
		assertEquals(200, resp.statusCode())
		assertContains(resp.body(), "secure=true;scheme=https")
	}
}