package com.mingliqiye.web.netty.spring4

import io.netty.pkitesting.CertificateBuilder
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.test.assertEquals

/** Boot `server.http2.enabled=true` + `server.ssl.*`：starter 自动附加 ALPN，TLS 上协商 h2。 */
@SpringBootTest(
	classes = [TestApp::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["server.http2.enabled=true"],
)
class SpringBootHttp2SslIntegrationTest {
	companion object {
		private val keystoreFile: File = run {
			val certificate = CertificateBuilder().subject("CN=localhost").rsa2048().setIsCertificateAuthority(true).buildSelfSigned()
			val keyStore = KeyStore.getInstance("PKCS12")
			keyStore.load(null, null)
			keyStore.setKeyEntry("boot", certificate.keyPair.private, "changeit".toCharArray(), arrayOf(certificate.certificate))
			val file = File.createTempFile("netty-boot-h2-ssl", ".p12")
			file.deleteOnExit()
			keyStore.store(file.outputStream(), "changeit".toCharArray())
			file
		}

		@JvmStatic
		@DynamicPropertySource
		fun sslProperties(registry: DynamicPropertyRegistry) {
			registry.add("server.ssl.enabled") { "true" }
			registry.add("server.ssl.key-store") { "file:${keystoreFile.absolutePath}" }
			registry.add("server.ssl.key-store-password") { "changeit" }
			registry.add("server.ssl.key-store-type") { "PKCS12" }
			registry.add("server.ssl.key-alias") { "boot" }
		}
	}

	@Autowired
	lateinit var environment: Environment

	private val port: Int get() = environment.getProperty("local.server.port")!!.toInt()

	private fun trustAllContext(): SSLContext {
		val tm = object : X509TrustManager {
			override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
			override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
			override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
		}
		return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
	}

	@Test
	fun `mvc endpoint negotiated over tls alpn h2`() {
		val client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_2).sslContext(trustAllContext()).build()
		val resp = client.send(
			HttpRequest.newBuilder(URI.create("https://127.0.0.1:$port/hello")).GET().build(),
			HttpResponse.BodyHandlers.ofString(),
		)
		assertEquals(HttpClient.Version.HTTP_2, resp.version())
		assertEquals(200, resp.statusCode())
		assertEquals("hello-mvc", resp.body())
	}
}