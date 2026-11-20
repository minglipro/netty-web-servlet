package com.mingliqiye.web.netty.server.handler

import com.mingliqiye.web.netty.server.config.CompressionConfig
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.compression.StandardCompressionOptions
import io.netty.handler.codec.http.HttpContentCompressor
import io.netty.handler.codec.http.HttpContentEncoder
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse

/**
 * 可配置响应压缩器：在 Netty [HttpContentCompressor] 基础上增加
 * Content-Type 白名单与 User-Agent 排除（命中排除名单时移除请求的 Accept-Encoding）。
 */
class ConfigurableCompressor(private val config: CompressionConfig) : HttpContentCompressor(
	config.minResponseSize,
	StandardCompressionOptions.gzip(config.compressionLevel, 15, 8),
	StandardCompressionOptions.deflate(config.compressionLevel, 15, 8),
) {
	override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
		if (msg is HttpRequest && config.excludedUserAgents.isNotEmpty()) {
			val userAgent = msg.headers().get(HttpHeaderNames.USER_AGENT) ?: ""
			if (config.excludedUserAgents.any { it.containsMatchIn(userAgent) }) {
				msg.headers().remove(HttpHeaderNames.ACCEPT_ENCODING)
			}
		}
		super.channelRead(ctx, msg)
	}

	override fun beginEncode(response: HttpResponse, acceptEncoding: String): HttpContentEncoder.Result? {
		if (config.mimeTypes.isNotEmpty()) {
			val contentType = response.headers().get(HttpHeaderNames.CONTENT_TYPE)
				?.substringBefore(';')?.trim()
			if (contentType == null || config.mimeTypes.none { it.equals(contentType, ignoreCase = true) }) {
				return null
			}
		}
		return super.beginEncode(response, acceptEncoding)
	}
}