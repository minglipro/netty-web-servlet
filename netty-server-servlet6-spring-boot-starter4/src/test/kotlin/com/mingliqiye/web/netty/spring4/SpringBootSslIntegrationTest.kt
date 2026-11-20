package com.mingliqiye.web.netty.spring4

import io.netty.pkitesting.CertificateBuilder
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.test.assertContains

@SpringBootTest(
	classes = [TestApp::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class SpringBootSslIntegrationTest {
	companion object {
		private val keystoreFile: File = run {
			val certificate = CertificateBuilder().subject("CN=localhost").rsa2048().setIsCertificateAuthority(true).buildSelfSigned()
			val keyStore = KeyStore.getInstance("PKCS12")
			keyStore.load(null, null)
			keyStore.setKeyEntry("boot", certificate.keyPair.private, "changeit".toCharArray(), arrayOf(certificate.certificate))
			val file = File.createTempFile("netty-boot-ssl", ".p12")
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

	@Test
	fun `https endpoint works with server ssl properties and key alias`() {
		val tm = object : X509TrustManager {
			override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
			override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
			override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
		}
		val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
		val socket = context.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
		socket.startHandshake()
		socket.getOutputStream().write(
			"GET /hello HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray()
		)
		socket.getOutputStream().flush()
		val response = BufferedReader(InputStreamReader(socket.getInputStream())).readText()
		socket.close()
		assertContains(response, "200")
		assertContains(response, "hello-mvc")
	}
}