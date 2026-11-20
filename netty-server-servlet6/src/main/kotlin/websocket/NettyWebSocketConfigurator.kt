package com.mingliqiye.web.netty.servlet.websocket

import jakarta.websocket.Extension
import jakarta.websocket.HandshakeResponse
import jakarta.websocket.server.HandshakeRequest
import jakarta.websocket.server.ServerEndpointConfig

/**
 * 容器默认 Configurator：经 META-INF/services 注册供 jakarta.websocket API 的
 * ServiceLoader 发现（Builder.build 与注解默认配置均依赖此入口）。
 *
 * 必须覆写全部委托方法：基类实现会回调 getContainerDefaultConfigurator()，
 * 而 ServiceLoader 返回的正是本类，不覆写将导致无限递归。
 */
class NettyWebSocketConfigurator : ServerEndpointConfig.Configurator() {
	override fun getNegotiatedSubprotocol(supported: List<String>, requested: List<String>): String =
		requested.firstOrNull { it in supported } ?: ""

	override fun getNegotiatedExtensions(installed: List<Extension>, requested: List<Extension>): List<Extension> =
		emptyList()

	override fun checkOrigin(originHeaderValue: String): Boolean = true

	override fun modifyHandshake(config: ServerEndpointConfig, request: HandshakeRequest, response: HandshakeResponse) {}

	override fun <T> getEndpointInstance(clazz: Class<T>): T = clazz.getDeclaredConstructor().newInstance()
}