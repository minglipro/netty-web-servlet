package com.mingliqiye.web.netty.servlet.jsp

import jakarta.servlet.Servlet
import jakarta.servlet.ServletContext

/**
 * JSP 引擎 SPI：容器经 [java.util.ServiceLoader] 发现实现，从而在不硬依赖任何 JSP 库的前提下
 * 支持 `*.jsp`/`*.jspx` 映射与 [ServletContext.addJspFile]。
 *
 * 引入 `netty-server-jsp`（嵌入 Tomcat Jasper）即自动挂载；未引入时容器对 JSP 请求回 404，
 * addJspFile 抛 [UnsupportedOperationException]。
 *
 * 实现约定：
 * - [initialize] 在容器初始化早期调用一次，负责设置 JSP 运行所需的上下文属性
 *   （如 `jakarta.servlet.context.tempdir` 工作目录、编译类路径）与全局工厂；
 * - [createServlet] 每次返回一个全新的、尚未 init 的 JSP Servlet 实例，
 *   由容器按普通 Servlet 走 init/service/destroy 生命周期。
 */
interface JspEngine {
	/** 准备 JSP 运行环境（工作目录、编译类路径、全局工厂等），每个容器仅调用一次。 */
	fun initialize(context: ServletContext)

	/** 创建一个全新的 JSP Servlet 实例（未初始化），交由容器管理其生命周期。 */
	fun createServlet(): Servlet
}