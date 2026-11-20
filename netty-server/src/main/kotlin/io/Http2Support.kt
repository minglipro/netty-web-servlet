package com.mingliqiye.web.netty.server.io

import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ApplicationProtocolNames

/** HTTP/2 辅助工具。 */
object Http2Support {
	/**
	 * h2 + http/1.1 的 ALPN 协议配置。
	 * 构建服务端 [io.netty.handler.ssl.SslContext] 时经 `applicationProtocolConfig(...)` 传入，
	 * TLS 握手即可协商 HTTP/2；未携带此配置时 ALPN 回退为 HTTP/1.1。
	 */
	fun alpnConfig(): ApplicationProtocolConfig = ApplicationProtocolConfig(
		ApplicationProtocolConfig.Protocol.ALPN,
		ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
		ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
		ApplicationProtocolNames.HTTP_2,
		ApplicationProtocolNames.HTTP_1_1,
	)
}