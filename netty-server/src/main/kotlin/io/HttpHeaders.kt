package com.mingliqiye.web.netty.server.io

import com.mingliqiye.web.netty.server.config.NettyServerConfig
import io.netty.handler.codec.http.HttpResponse

object HeaderUtils {

	var config: NettyServerConfig? = null

	val metadata: String? by lazy {
		try {
			HeaderUtils.javaClass.getResourceAsStream("/META-INF/attributes/com.mingliqiye.web.netty.version")?.reader()?.use { stream ->
				stream.readText()
			}?.trim()
		} catch (e: Exception) {
			null
		}
	}

	fun getServerName(): String {
		return config?.serverName ?: "netty-server${
			metadata?.let { 
				"/$it"
			} ?: ""
		}"
	}
	fun HttpResponse.addHttpHeaders(vararg data: Pair<String, String>) {
		this.headers().apply {
			data.forEach {
				add(it.first, it.second)
			}
		}
	}

	fun HttpResponse.addSystemHeaders(vararg data: Pair<String, String>) {
		addHttpHeaders(
			"server" to getServerName(),
			*data
		)
	}
}
