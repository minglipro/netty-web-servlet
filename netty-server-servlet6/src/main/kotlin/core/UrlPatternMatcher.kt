package com.mingliqiye.web.netty.servlet.core

/**
 * Servlet 规范 URL 模式匹配：精确 > 最长路径前缀 > 扩展名 > 默认（`/`）。
 * 路径前缀模式形如 `/x` 加斜杠星号，扩展名模式形如星号点加后缀。
 */
internal object UrlPatternMatcher {
	/** 单个模式是否匹配路径。 */
	fun matches(pattern: String, path: String): Boolean = when {
		pattern == "/" -> true
		pattern.startsWith("*.") -> path.endsWith(pattern.substring(1))
		pattern.endsWith("/*") -> {
			val prefix = pattern.dropLast(2)
			path == prefix || path.startsWith("$prefix/")
		}
		else -> pattern == path
	}

	/** 按匹配模式拆分 servletPath 与 pathInfo。 */
	fun servletPath(pattern: String?, path: String): Pair<String, String?> = when {
		pattern == null -> Pair("", path)
		pattern.endsWith("/*") -> {
			val prefix = pattern.dropLast(2)
			Pair(prefix, path.removePrefix(prefix).ifEmpty { null })
		}
		else -> Pair(path, null)
	}
}
