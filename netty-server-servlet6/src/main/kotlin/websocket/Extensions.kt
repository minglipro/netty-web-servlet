package com.mingliqiye.web.netty.servlet.websocket

import jakarta.websocket.Extension

/** jakarta.websocket 扩展视图：名称 + 协商参数。 */
internal class WebSocketExtensionImpl(
	private val name: String,
	private val parameters: List<Extension.Parameter>,
) : Extension {
	override fun getName(): String = name
	override fun getParameters(): List<Extension.Parameter> = parameters
}

/** 扩展参数视图。 */
internal class WebSocketParameterImpl(
	private val name: String,
	private val value: String,
) : Extension.Parameter {
	override fun getName(): String = name
	override fun getValue(): String = value
}