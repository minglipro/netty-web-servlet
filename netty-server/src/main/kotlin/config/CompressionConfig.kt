package com.mingliqiye.web.netty.server.config

/**
 * 响应压缩配置（gzip/deflate，按 Accept-Encoding 协商）。
 *
 * @property mimeTypes 参与压缩的 Content-Type 列表（忽略 charset 参数，大小写不敏感），空列表表示不限制
 * @property minResponseSize 完整响应小于该字节数时不压缩（流式响应不受此限制）
 * @property excludedUserAgents 命中的 User-Agent 正则不压缩（视为移除其 Accept-Encoding）
 * @property compressionLevel deflate 压缩级别 0-9
 */
data class CompressionConfig(
	val mimeTypes: List<String> = listOf(
		"text/html", "text/xml", "text/plain", "text/css", "text/javascript",
		"application/javascript", "application/json", "application/xml",
	),
	val minResponseSize: Int = 2048,
	val excludedUserAgents: List<Regex> = emptyList(),
	val compressionLevel: Int = 6,
)