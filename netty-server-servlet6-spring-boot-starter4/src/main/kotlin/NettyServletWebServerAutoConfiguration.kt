package com.mingliqiye.web.netty.spring4

import com.mingliqiye.web.netty.servlet.NettyServletServer
import jakarta.servlet.ServletRequest
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.autoconfigure.condition.SearchStrategy
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.server.autoconfigure.servlet.ServletWebServerConfiguration
import org.springframework.boot.web.server.servlet.ServletWebServerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * Netty Servlet 容器自动配置：Servlet Web 应用且类路径存在容器时生效，
 * 未自定义 [ServletWebServerFactory] 时注册 [NettyServletWebServerFactory]。
 *
 * [ServletWebServerConfiguration] 引入 Boot 通用的 ServerProperties 定制器与
 * WebServerFactoryCustomizer/ErrorPageRegistrar 后处理器注册。
 * 经 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 发现。
 */
@AutoConfiguration
@ConditionalOnClass(ServletRequest::class, NettyServletServer::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(NettyServerProperties::class)
@Import(ServletWebServerConfiguration::class)
open class NettyServletWebServerAutoConfiguration {
	/** 默认工厂 Bean；用户自定义任意 ServletWebServerFactory 时让位。 */
	@Bean
	@ConditionalOnMissingBean(ServletWebServerFactory::class, search = SearchStrategy.CURRENT)
	open fun nettyServletWebServerFactory(properties: NettyServerProperties): NettyServletWebServerFactory = NettyServletWebServerFactory(properties)
}
