package com.mingliqiye.web.netty.servlet.core

import com.mingliqiye.logger.Loggers
import jakarta.servlet.Filter
import jakarta.servlet.FilterRegistration
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.Servlet
import jakarta.servlet.ServletContext
import jakarta.servlet.ServletRegistration
import jakarta.servlet.SessionCookieConfig
import jakarta.servlet.SessionTrackingMode
import jakarta.servlet.descriptor.JspConfigDescriptor
import java.io.InputStream
import java.net.URL
import java.net.URLConnection
import java.util.Collections
import java.util.Enumeration
import java.util.EventListener
import java.util.concurrent.ConcurrentHashMap

/** 会话 Cookie 配置（名称/域/路径/HttpOnly/Secure/MaxAge/自定义属性），默认名 JSESSIONID。 */
internal class SessionCookieConfigImpl : SessionCookieConfig {
	private var name = "JSESSIONID"
	private var domain: String? = null
	private var path: String? = null
	private var comment: String? = null
	private var httpOnly = false
	private var secure = false
	private var maxAge = -1
	private val attributes = linkedMapOf<String, String>()

	override fun setName(name: String) { this.name = name }
	override fun getName(): String = name
	override fun setDomain(domain: String?) { this.domain = domain }
	override fun getDomain(): String? = domain
	override fun setPath(path: String?) { this.path = path }
	override fun getPath(): String? = path

	@Deprecated(message = "This is no longer required with RFC 6265", level = DeprecationLevel.HIDDEN)
	override fun setComment(comment: String?) { this.comment = comment }

	@Deprecated(message = "With the adoption of support for RFC 6265, this method should no longer be used.", level = DeprecationLevel.HIDDEN)
	override fun getComment(): String? = comment
	override fun setHttpOnly(httpOnly: Boolean) { this.httpOnly = httpOnly }
	override fun isHttpOnly(): Boolean = httpOnly
	override fun setSecure(secure: Boolean) { this.secure = secure }
	override fun isSecure(): Boolean = secure
	override fun setMaxAge(maxAge: Int) { this.maxAge = maxAge }
	override fun getMaxAge(): Int = maxAge
	override fun setAttribute(name: String, value: String?) {
		if (value == null) attributes.remove(name) else attributes[name] = value
	}
	override fun getAttribute(name: String): String? = attributes[name]
	override fun getAttributes(): Map<String, String> = attributes
}

/**
 * ServletContext 实现：应用属性（带监听器事件）、初始化参数、Servlet/Filter/监听器注册入口、
 * 会话 Cookie 与超时配置、请求/响应默认编码。
 *
 * 容器特定语义：会话超时以分钟为单位存取（内部按秒保存）；会话跟踪支持 COOKIE 与 URL（默认两者）；
 * 资源访问优先 docRoot 文件系统、回退类路径；JSP 经 ServiceLoader 挂载引擎（引入 netty-server-jsp 时支持）。
 */
internal class ServletContextImpl(private val container: ServletContainer) : ServletContext {
	companion object {
		private val log = Loggers.getLogger<ServletContextImpl>()

		private val EXTRA_MIME = mapOf(
			"js" to "text/javascript",
			"mjs" to "text/javascript",
			"css" to "text/css",
			"json" to "application/json",
			"svg" to "image/svg+xml",
			"woff" to "font/woff",
			"woff2" to "font/woff2",
			"ttf" to "font/ttf",
			"otf" to "font/otf",
			"wasm" to "application/wasm",
			"md" to "text/markdown",
			"yaml" to "text/yaml",
			"yml" to "text/yaml",
		)
	}

	private val attributes = ConcurrentHashMap<String, Any>()
	private val initParams = linkedMapOf<String, String>()
	private val cookieConfig = SessionCookieConfigImpl()
	internal val listeners = mutableListOf<EventListener>()

	private var trackingModes: Set<SessionTrackingMode>? = null

	/** contextInitialized 完成后置 true，此后禁止修改会话跟踪模式。 */
	internal var initialized = false

	private val docRoot: java.nio.file.Path? =
		container.config.docRoot?.let { java.nio.file.Path.of(it).toAbsolutePath().normalize() }

	/** 将上下文相对路径解析为 docRoot 内的安全路径，越界（..）返回 null。 */
	private fun docFile(path: String): java.nio.file.Path? {
		val root = docRoot ?: return null
		val resolved = root.resolve(path.removePrefix("/")).normalize()
		return if (resolved.startsWith(root)) resolved else null
	}

	override fun getContextPath(): String = container.config.contextPath

	override fun getContext(uripath: String): ServletContext? = null

	override fun getMajorVersion(): Int = 6

	override fun getMinorVersion(): Int = 1

	override fun getEffectiveMajorVersion(): Int = 6

	override fun getEffectiveMinorVersion(): Int = 1

	override fun getMimeType(file: String): String? =
		URLConnection.guessContentTypeFromName(file) ?: EXTRA_MIME[file.substringAfterLast('.', "").lowercase()]

	override fun getResourcePaths(path: String): Set<String>? {
		if (!path.startsWith("/") || !path.endsWith("/")) return null
		val result = sortedSetOf<String>()
		docFile(path)?.takeIf { java.nio.file.Files.isDirectory(it) }?.let { dir ->
			java.nio.file.Files.list(dir).use { entries ->
				entries.forEach { p ->
					val name = dir.relativize(p).toString().replace('\\', '/')
					result.add(path + name + if (java.nio.file.Files.isDirectory(p)) "/" else "")
				}
			}
		}
		val dirName = path.removePrefix("/").removeSuffix("/")
		if (dirName.isNotEmpty()) {
			val urls = classLoader.getResources(dirName)
			while (urls.hasMoreElements()) {
				val url = urls.nextElement()
				when (url.protocol) {
					"file" -> {
						val dir = java.nio.file.Path.of(url.toURI())
						java.nio.file.Files.list(dir).use { entries ->
							entries.forEach { p ->
								val name = p.fileName.toString()
								result.add(path + name + if (java.nio.file.Files.isDirectory(p)) "/" else "")
							}
						}
					}
					"jar" -> {
						val jar = (url.openConnection() as java.net.JarURLConnection).jarFile
						val prefix = "$dirName/"
						jar.entries().asSequence().forEach { entry ->
							if (entry.name.startsWith(prefix) && entry.name != prefix) {
								val rest = entry.name.removePrefix(prefix)
								val top = rest.substringBefore('/')
								result.add(path + top + if (rest.contains('/') || entry.isDirectory) "/" else "")
							}
						}
					}
				}
			}
		}
		return result.ifEmpty { null }
	}

	override fun getResource(path: String): URL? {
		docFile(path)?.takeIf { java.nio.file.Files.exists(it) }?.let { return it.toUri().toURL() }
		return classLoader.getResource(path.removePrefix("/"))
	}

	override fun getResourceAsStream(path: String): InputStream? {
		docFile(path)?.takeIf { java.nio.file.Files.isRegularFile(it) }?.let { return java.nio.file.Files.newInputStream(it) }
		return classLoader.getResourceAsStream(path.removePrefix("/"))
	}

	override fun getRequestDispatcher(path: String): RequestDispatcher? {
		if (!path.startsWith("/")) return null
		return RequestDispatcherImpl(container, path)
	}

	override fun getNamedDispatcher(name: String): RequestDispatcher? {
		val registration = container.servlets[name] ?: return null
		return NamedRequestDispatcher(registration.servlet)
	}

	override fun getRealPath(path: String): String? = docFile(path)?.toString()

	override fun getServerInfo(): String = "NettyServlet/1.0"

	override fun getInitParameter(name: String): String? = initParams[name]

	override fun getInitParameterNames(): Enumeration<String> = Collections.enumeration(initParams.keys)

	override fun setInitParameter(name: String, value: String): Boolean {
		if (initParams.containsKey(name)) return false
		initParams[name] = value
		return true
	}

	override fun getAttribute(name: String): Any? =
		attributes[name] ?: if (name == "jakarta.websocket.server.ServerContainer") container.webSockets else null

	override fun getAttributeNames(): Enumeration<String> = Collections.enumeration(attributes.keys)

	override fun setAttribute(name: String, value: Any?) {
		if (value == null) {
			removeAttribute(name)
			return
		}
		val old = attributes.put(name, value)
		if (old == null) {
			container.bus.contextAttributeAdded(name, value)
		} else {
			container.bus.contextAttributeReplaced(name, old)
		}
	}

	override fun removeAttribute(name: String) {
		val old = attributes.remove(name) ?: return
		container.bus.contextAttributeRemoved(name, old)
	}

	override fun getServletContextName(): String = "NettyServlet"

	override fun addServlet(servletName: String, servletClass: Class<out Servlet>): ServletRegistration.Dynamic =
		addServlet(servletName, createServlet(servletClass))

	override fun addServlet(servletName: String, servlet: Servlet): ServletRegistration.Dynamic =
		container.addServlet(servletName, servlet)

	override fun addServlet(servletName: String, servletClassName: String): ServletRegistration.Dynamic {
		@Suppress("UNCHECKED_CAST")
		val clazz = Class.forName(servletClassName) as Class<out Servlet>
		return addServlet(servletName, clazz)
	}

	override fun <T : Servlet> createServlet(clazz: Class<T>): T = clazz.getDeclaredConstructor().newInstance()

	override fun getServletRegistration(servletName: String): ServletRegistration? = container.servlets[servletName]

	override fun getServletRegistrations(): Map<String, ServletRegistration> = container.servlets

	override fun addFilter(filterName: String, filterClass: Class<out Filter>): FilterRegistration.Dynamic =
		addFilter(filterName, createFilter(filterClass))

	override fun addFilter(filterName: String, filter: Filter): FilterRegistration.Dynamic =
		container.addFilter(filterName, filter)

	override fun addFilter(filterName: String, filterClassName: String): FilterRegistration.Dynamic {
		@Suppress("UNCHECKED_CAST")
		val clazz = Class.forName(filterClassName) as Class<out Filter>
		return addFilter(filterName, clazz)
	}

	override fun <T : Filter> createFilter(clazz: Class<T>): T = clazz.getDeclaredConstructor().newInstance()

	override fun getFilterRegistration(filterName: String): FilterRegistration? =
		container.filters.firstOrNull { it.name == filterName }

	override fun getFilterRegistrations(): Map<String, FilterRegistration> =
		container.filters.associateBy { it.name }

	override fun getSessionCookieConfig(): SessionCookieConfig = cookieConfig

	override fun setSessionTrackingModes(sessionTrackingModes: Set<SessionTrackingMode>) {
		check(!initialized) { "Session tracking modes cannot be changed after context initialization" }
		if (SessionTrackingMode.SSL in sessionTrackingModes) {
			require(sessionTrackingModes.size == 1) { "SSL session tracking must be used alone" }
		}
		trackingModes = sessionTrackingModes.toSet()
	}

	override fun getDefaultSessionTrackingModes(): Set<SessionTrackingMode> =
		setOf(SessionTrackingMode.COOKIE, SessionTrackingMode.URL)

	override fun getEffectiveSessionTrackingModes(): Set<SessionTrackingMode> =
		trackingModes ?: defaultSessionTrackingModes

	override fun addListener(listenerClass: Class<out EventListener>) {
		listeners.add(createListener(listenerClass))
	}

	override fun <T : EventListener> addListener(t: T) {
		log.debug { "Added listener ${t.javaClass.name}" }
		listeners.add(t)
	}

	override fun addListener(listenerClassName: String) {
		@Suppress("UNCHECKED_CAST")
		val clazz = Class.forName(listenerClassName) as Class<out EventListener>
		addListener(clazz)
	}

	override fun <T : EventListener> createListener(clazz: Class<T>): T =
		clazz.getDeclaredConstructor().newInstance()

	override fun getClassLoader(): ClassLoader = javaClass.classLoader

	override fun declareRoles(vararg roleNames: String) {
		container.declaredRoles.addAll(roleNames)
	}

	override fun getVirtualServerName(): String = "localhost"

	/** 会话默认超时（分钟，规范要求单位）。 */
	override fun getSessionTimeout(): Int = container.sessionTimeoutSeconds / 60

	/** 设置会话默认超时，参数单位为分钟。 */
	override fun setSessionTimeout(sessionTimeout: Int) {
		log.debug { "Session timeout set to $sessionTimeout minutes" }
		container.sessionTimeoutSeconds = sessionTimeout * 60
	}

	override fun getRequestCharacterEncoding(): String? = container.defaultRequestEncoding

	override fun setRequestCharacterEncoding(encoding: String?) {
		container.defaultRequestEncoding = encoding
	}

	override fun getResponseCharacterEncoding(): String? = container.defaultResponseEncoding

	override fun setResponseCharacterEncoding(encoding: String?) {
		container.defaultResponseEncoding = encoding
	}

	override fun getJspConfigDescriptor(): JspConfigDescriptor? = null

	override fun addJspFile(servletName: String, jspFile: String): ServletRegistration.Dynamic {
		val engine = container.jspEngine
			?: throw UnsupportedOperationException("JSP not supported: add the netty-server-jsp module")
		container.ensureJspEngine()
		val registration = container.addServlet(servletName, engine.createServlet(), jspFile)
		registration.setInitParameter("jspFile", jspFile)
		return registration
	}

	override fun log(msg: String) {
		log.info(msg)
	}

	override fun log(message: String, throwable: Throwable) {
		log.error(message, throwable)
	}
}
