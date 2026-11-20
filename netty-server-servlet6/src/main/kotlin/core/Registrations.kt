package com.mingliqiye.web.netty.servlet.core

import jakarta.servlet.Filter
import jakarta.servlet.FilterConfig
import jakarta.servlet.FilterRegistration
import jakarta.servlet.Servlet
import jakarta.servlet.ServletConfig
import jakarta.servlet.ServletContext
import jakarta.servlet.ServletRegistration
import jakarta.servlet.ServletSecurityElement
import java.util.Collections
import java.util.Enumeration

/** Servlet 初始化配置快照：名称、上下文与初始化参数。 */
internal class ServletConfigImpl(
	private val name: String,
	private val context: ServletContext,
	private val params: Map<String, String>,
) : ServletConfig {
	override fun getServletName(): String = name
	override fun getServletContext(): ServletContext = context
	override fun getInitParameter(name: String): String? = params[name]
	override fun getInitParameterNames(): Enumeration<String> = Collections.enumeration(params.keys)
}

/** Filter 初始化配置快照：名称、上下文与初始化参数。 */
internal class FilterConfigImpl(
	private val name: String,
	private val context: ServletContext,
	private val params: Map<String, String>,
) : FilterConfig {
	override fun getFilterName(): String = name
	override fun getServletContext(): ServletContext = context
	override fun getInitParameter(name: String): String? = params[name]
	override fun getInitParameterNames(): Enumeration<String> = Collections.enumeration(params.keys)
}

/**
 * Servlet 动态注册：URL 映射、初始化参数、loadOnStartup、asyncSupported、multipartConfig。
 * 容器按注册顺序遍历映射做路由匹配。
 */
internal class ServletRegistrationImpl(
	private val name: String,
	val servlet: Servlet,
	private val context: ServletContext,
) : ServletRegistration.Dynamic {
	private val mappings = mutableSetOf<String>()
	private val params = linkedMapOf<String, String>()

	/** loadOnStartup 值，负数表示懒加载（首次请求时初始化）。 */
	var loadOnStartupValue: Int = -1
		private set

	/** 是否支持异步；链上任一 Filter 不支持时整个链不支持。 */
	var asyncSupported: Boolean = true
		private set

	/** 每 Servlet 的 multipart 限额配置，null 时使用容器全局默认。 */
	var multipartConfig: jakarta.servlet.MultipartConfigElement? = null
		private set

	/** 声明式安全约束（setServletSecurity），null 表示无约束。 */
	var servletSecurity: ServletSecurityElement? = null
		private set

	/** run-as 角色：执行期间以该角色身份运行（getUserPrincipal/isUserInRole 随之变化）。 */
	var runAsRoleValue: String? = null
		private set

	fun config(): ServletConfig = ServletConfigImpl(name, context, params)

	override fun getName(): String = name

	override fun getClassName(): String = servlet.javaClass.name

	override fun setInitParameters(initParameters: Map<String, String>): Set<String> {
		val conflicts = initParameters.keys.filter { params.containsKey(it) }.toSet()
		params.putAll(initParameters.filterKeys { it !in conflicts })
		return conflicts
	}

	override fun setInitParameter(name: String, value: String): Boolean {
		if (params.containsKey(name)) return false
		params[name] = value
		return true
	}

	override fun getInitParameters(): Map<String, String> = params

	override fun getInitParameter(name: String): String? = params[name]

	override fun addMapping(vararg urlPatterns: String): Set<String> = addMapping(urlPatterns.toList())

	fun addMapping(urlPatterns: Collection<String>): Set<String> {
		val conflicts = urlPatterns.filter { it in mappings }.toSet()
		mappings.addAll(urlPatterns.filter { it !in conflicts })
		return conflicts
	}

	override fun getMappings(): Collection<String> = mappings

	override fun getRunAsRole(): String? = runAsRoleValue

	override fun setLoadOnStartup(loadOnStartup: Int) {
		this.loadOnStartupValue = loadOnStartup
	}

	override fun setServletSecurity(constraint: ServletSecurityElement): Set<String> {
		servletSecurity = constraint
		return emptySet()
	}

	override fun setMultipartConfig(multipartConfig: jakarta.servlet.MultipartConfigElement?) {
		this.multipartConfig = multipartConfig
	}

	override fun setRunAsRole(roleName: String?) {
		runAsRoleValue = roleName
	}

	override fun setAsyncSupported(isAsyncSupported: Boolean) {
		asyncSupported = isAsyncSupported
	}
}

/**
 * Filter 动态注册：每条映射独立携带 dispatcherTypes 与 isMatchAfter 语义。
 * 链序规则：before 映射（isMatchAfter=false）在前、after 映射在后，各自按注册顺序。
 */
internal class FilterRegistrationImpl(
	private val name: String,
	val filter: Filter,
	private val context: ServletContext,
) : FilterRegistration.Dynamic {
	/** 单条映射：拦截的分发类型、排序位置、URL 模式与 Servlet 名称。 */
	internal class Mapping(
		val dispatcherTypes: Set<jakarta.servlet.DispatcherType>,
		val matchAfter: Boolean,
		val urlPatterns: List<String>,
		val servletNames: List<String>,
	)

	private val mappings = mutableListOf<Mapping>()
	private val params = linkedMapOf<String, String>()

	/** 是否支持异步处理。 */
	var asyncSupported: Boolean = true
		private set

	fun config(): FilterConfig = FilterConfigImpl(name, context, params)

	/** 返回第一条分发类型与路径/Servlet 名均匹配的映射，无匹配返回 null。 */
	fun match(path: String, servletName: String, type: jakarta.servlet.DispatcherType): Mapping? =
		mappings.firstOrNull {
			type in it.dispatcherTypes &&
				(it.urlPatterns.any { p -> UrlPatternMatcher.matches(p, path) } || servletName in it.servletNames)
		}

	override fun getName(): String = name

	override fun getClassName(): String = filter.javaClass.name

	override fun setInitParameters(initParameters: Map<String, String>): Set<String> {
		val conflicts = initParameters.keys.filter { params.containsKey(it) }.toSet()
		params.putAll(initParameters.filterKeys { it !in conflicts })
		return conflicts
	}

	override fun setInitParameter(name: String, value: String): Boolean {
		if (params.containsKey(name)) return false
		params[name] = value
		return true
	}

	override fun getInitParameters(): Map<String, String> = params

	override fun getInitParameter(name: String): String? = params[name]

	override fun addMappingForUrlPatterns(
		dispatcherTypes: java.util.EnumSet<jakarta.servlet.DispatcherType>?,
		isMatchAfter: Boolean,
		vararg urlPatterns: String,
	) {
		mappings.add(
			Mapping(
				dispatcherTypes ?: java.util.EnumSet.of(jakarta.servlet.DispatcherType.REQUEST),
				isMatchAfter,
				urlPatterns.toList(),
				emptyList(),
			)
		)
	}

	override fun addMappingForServletNames(
		dispatcherTypes: java.util.EnumSet<jakarta.servlet.DispatcherType>?,
		isMatchAfter: Boolean,
		vararg servletNames: String,
	) {
		mappings.add(
			Mapping(
				dispatcherTypes ?: java.util.EnumSet.of(jakarta.servlet.DispatcherType.REQUEST),
				isMatchAfter,
				emptyList(),
				servletNames.toList(),
			)
		)
	}

	override fun getUrlPatternMappings(): Collection<String> = mappings.flatMap { it.urlPatterns }.distinct()

	override fun getServletNameMappings(): Collection<String> = mappings.flatMap { it.servletNames }.distinct()

	override fun setAsyncSupported(isAsyncSupported: Boolean) {
		asyncSupported = isAsyncSupported
	}
}
