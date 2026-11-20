package com.mingliqiye.web.netty.servlet

import com.mingliqiye.web.netty.servlet.config.ServletServerConfig
import com.mingliqiye.web.netty.servlet.security.AuthMethod
import com.mingliqiye.web.netty.servlet.security.InMemoryRealm
import com.mingliqiye.web.netty.servlet.security.LoginConfig
import jakarta.servlet.HttpConstraintElement
import jakarta.servlet.HttpMethodConstraintElement
import jakarta.servlet.ServletException
import jakarta.servlet.ServletSecurityElement
import jakarta.servlet.annotation.ServletSecurity.EmptyRoleSemantic
import jakarta.servlet.annotation.ServletSecurity.TransportGuarantee
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityIntegrationTest {
	private lateinit var basicServer: NettyServletServer
	private lateinit var formServer: NettyServletServer
	private val client = HttpClient.newHttpClient()
	private val basicBase = "http://127.0.0.1:18106"
	private val formBase = "http://127.0.0.1:18107"

	private val realm = InMemoryRealm(
		Triple("admin", "admin123", setOf("admin", "user")),
		Triple("alice", "alice123", setOf("user")),
	)

	private fun echo(req: HttpServletRequest, resp: HttpServletResponse) {
		resp.contentType = "text/plain"
		resp.writer.write(
			"user=${req.remoteUser}|auth=${req.authType}|admin=${req.isUserInRole("admin")}" +
				"|principal=${req.userPrincipal?.name}"
		)
	}

	private fun roles(vararg roles: String) =
		ServletSecurityElement(HttpConstraintElement(EmptyRoleSemantic.PERMIT, TransportGuarantee.NONE, *roles))

	@BeforeAll
	fun setup() {
		basicServer = NettyServletServer(
			ServletServerConfig(
				port = 18106,
				realm = realm,
				loginConfig = LoginConfig(AuthMethod.BASIC, realmName = "test"),
			)
		)
		basicServer.container.context.declareRoles("admin", "user")
		basicServer.addServlet("admin", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) = echo(req, resp)
		}, "/admin").setServletSecurity(roles("admin"))
		basicServer.addServlet("user", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) = echo(req, resp)
		}, "/user").setServletSecurity(roles("user"))
		basicServer.addServlet("open", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) = echo(req, resp)
		}, "/open")
		basicServer.addServlet("deny", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) = echo(req, resp)
		}, "/deny").setServletSecurity(ServletSecurityElement(HttpConstraintElement(EmptyRoleSemantic.DENY)))
		basicServer.addServlet("method", object : HttpServlet() {
			override fun service(req: HttpServletRequest, resp: HttpServletResponse) = echo(req, resp)
		}, "/method").setServletSecurity(
			ServletSecurityElement(
				HttpConstraintElement(),
				listOf(HttpMethodConstraintElement("POST", HttpConstraintElement(EmptyRoleSemantic.PERMIT, TransportGuarantee.NONE, "admin"))),
			)
		)
		basicServer.addServlet("progLogin", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.contentType = "text/plain"
				try {
					req.login(req.getParameter("u") ?: "", req.getParameter("p") ?: "")
					resp.writer.write("ok|user=${req.remoteUser}|auth=${req.authType}")
				} catch (e: ServletException) {
					resp.status = 401
					resp.writer.write("login-failed")
				}
			}
		}, "/prog-login")
		basicServer.addServlet("progLogout", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				req.login("alice", "alice123")
				req.logout()
				resp.contentType = "text/plain"
				resp.writer.write("user=${req.remoteUser}|principal=${req.userPrincipal?.name}")
			}
		}, "/prog-logout")
		basicServer.addServlet("progAuth", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				if (req.authenticate(resp)) {
					resp.contentType = "text/plain"
					resp.writer.write("authenticated|user=${req.remoteUser}")
				}
			}
		}, "/prog-auth")
		basicServer.addServlet("runAs", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) = echo(req, resp)
		}, "/run-as").setRunAsRole("service")
		basicServer.startServer()

		formServer = NettyServletServer(
			ServletServerConfig(
				port = 18107,
				realm = realm,
				loginConfig = LoginConfig(AuthMethod.FORM, loginPage = "/login.html", errorPage = "/error.html"),
			)
		)
		formServer.addServlet("protected", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) = echo(req, resp)
		}, "/protected").setServletSecurity(roles("user"))
		formServer.addServlet("loginPage", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.contentType = "text/html"
				resp.writer.write("login-page")
			}
		}, "/login.html")
		formServer.addServlet("errorPage", object : HttpServlet() {
			override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
				resp.contentType = "text/html"
				resp.writer.write("error-page")
			}
		}, "/error.html")
		formServer.startServer()
	}

	@AfterAll
	fun teardown() {
		if (::basicServer.isInitialized) basicServer.stopServer()
		if (::formServer.isInitialized) formServer.stopServer()
	}

	private fun basicAuth(user: String, pass: String): String =
		"Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())

	private fun get(base: String, path: String, vararg headers: String): HttpResponse<String> {
		val builder = HttpRequest.newBuilder(URI.create("$base$path")).GET()
		headers.toList().chunked(2).forEach { (k, v) -> builder.header(k, v) }
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
	}

	private fun post(base: String, path: String, form: String, vararg headers: String): HttpResponse<String> {
		val builder = HttpRequest.newBuilder(URI.create("$base$path"))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(form))
		headers.toList().chunked(2).forEach { (k, v) -> builder.header(k, v) }
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
	}

	@Test
	fun `unauthenticated constrained request gets 401 challenge`() {
		val resp = get(basicBase, "/admin")
		assertEquals(401, resp.statusCode())
		assertEquals("Basic realm=\"test\"", resp.headers().firstValue("WWW-Authenticate").orElse(null))
	}

	@Test
	fun `wrong credentials get 401`() {
		val resp = get(basicBase, "/admin", "Authorization", basicAuth("admin", "wrong"))
		assertEquals(401, resp.statusCode())
	}

	@Test
	fun `basic admin passes role constraint`() {
		val resp = get(basicBase, "/admin", "Authorization", basicAuth("admin", "admin123"))
		assertEquals(200, resp.statusCode())
		assertEquals("user=admin|auth=BASIC|admin=true|principal=admin", resp.body())
	}

	@Test
	fun `authenticated without required role gets 403`() {
		val resp = get(basicBase, "/admin", "Authorization", basicAuth("alice", "alice123"))
		assertEquals(403, resp.statusCode())
	}

	@Test
	fun `alice passes user role constraint`() {
		val resp = get(basicBase, "/user", "Authorization", basicAuth("alice", "alice123"))
		assertEquals(200, resp.statusCode())
		assertContains(resp.body(), "user=alice")
		assertContains(resp.body(), "admin=false")
	}

	@Test
	fun `unconstrained servlet allows anonymous`() {
		val resp = get(basicBase, "/open")
		assertEquals(200, resp.statusCode())
		assertEquals("user=null|auth=null|admin=false|principal=null", resp.body())
	}

	@Test
	fun `deny semantic rejects even authenticated users`() {
		val resp = get(basicBase, "/deny", "Authorization", basicAuth("admin", "admin123"))
		assertEquals(403, resp.statusCode())
	}

	@Test
	fun `method specific constraint applies only to that method`() {
		assertEquals(200, get(basicBase, "/method", "Authorization", basicAuth("alice", "alice123")).statusCode())
		assertEquals(
			403,
			post(basicBase, "/method", "x=1", "Authorization", basicAuth("alice", "alice123")).statusCode()
		)
		assertEquals(
			200,
			post(basicBase, "/method", "x=1", "Authorization", basicAuth("admin", "admin123")).statusCode()
		)
	}

	@Test
	fun `programmatic login and logout`() {
		val ok = get(basicBase, "/prog-login?u=alice&p=alice123")
		assertEquals(200, ok.statusCode())
		assertEquals("ok|user=alice|auth=BASIC", ok.body())

		val failed = get(basicBase, "/prog-login?u=alice&p=bad")
		assertEquals(401, failed.statusCode())
		assertEquals("login-failed", failed.body())

		val out = get(basicBase, "/prog-logout")
		assertEquals("user=null|principal=null", out.body())
	}

	@Test
	fun `programmatic authenticate triggers challenge then succeeds`() {
		val challenged = get(basicBase, "/prog-auth")
		assertEquals(401, challenged.statusCode())

		val ok = get(basicBase, "/prog-auth", "Authorization", basicAuth("admin", "admin123"))
		assertEquals(200, ok.statusCode())
		assertEquals("authenticated|user=admin", ok.body())
	}

	@Test
	fun `run-as role overrides principal during servlet execution`() {
		val resp = get(basicBase, "/run-as", "Authorization", basicAuth("alice", "alice123"))
		assertEquals(200, resp.statusCode())
		assertContains(resp.body(), "principal=service")
	}

	@Test
	fun `form login flow redirects and restores original request`() {
		val formClient = HttpClient.newBuilder().cookieHandler(CookieManager()).build()
		fun send(request: HttpRequest): HttpResponse<String> =
			formClient.send(request, HttpResponse.BodyHandlers.ofString())

		val challenged = send(HttpRequest.newBuilder(URI.create("$formBase/protected")).GET().build())
		assertEquals(302, challenged.statusCode())
		assertEquals("/login.html", challenged.headers().firstValue("Location").orElse(null))

		val login = send(
			HttpRequest.newBuilder(URI.create("$formBase/j_security_check"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString("j_username=alice&j_password=alice123"))
				.build()
		)
		assertEquals(302, login.statusCode())
		assertEquals("/protected", login.headers().firstValue("Location").orElse(null))

		val restored = send(HttpRequest.newBuilder(URI.create("$formBase/protected")).GET().build())
		assertEquals(200, restored.statusCode())
		assertEquals("user=alice|auth=FORM|admin=false|principal=alice", restored.body())
	}

	@Test
	fun `form login failure redirects to error page`() {
		val formClient = HttpClient.newBuilder().cookieHandler(CookieManager()).build()
		val challenged = formClient.send(
			HttpRequest.newBuilder(URI.create("$formBase/protected")).GET().build(),
			HttpResponse.BodyHandlers.ofString()
		)
		assertEquals(302, challenged.statusCode())
		val login = formClient.send(
			HttpRequest.newBuilder(URI.create("$formBase/j_security_check"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString("j_username=alice&j_password=bad"))
				.build(),
			HttpResponse.BodyHandlers.ofString()
		)
		assertEquals(302, login.statusCode())
		assertEquals("/error.html", login.headers().firstValue("Location").orElse(null))
		assertTrue(true)
	}
}