package com.mingliqiye.web.netty.jsp

import com.mingliqiye.logger.Loggers
import com.mingliqiye.web.netty.servlet.jsp.JspEngine
import jakarta.servlet.Servlet
import jakarta.servlet.ServletContext
import jakarta.servlet.jsp.JspFactory
import org.apache.jasper.runtime.JspFactoryImpl
import org.apache.jasper.servlet.JspServlet
import org.apache.tomcat.InstanceManager
import org.apache.tomcat.SimpleInstanceManager
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files

/**
 * 基于 Tomcat Jasper 的 [JspEngine] 实现：仅嵌入 JSP 编译/执行引擎（Jasper + ECJ + EL），
 * 不引入 Tomcat 连接器或容器，Servlet 语义仍由本项目的 Netty 容器提供。
 *
 * 经 `META-INF/services/com.mingliqiye.web.netty.servlet.jsp.JspEngine` 被容器 ServiceLoader 发现。
 * [initialize] 设置全局 [JspFactory]、Jasper 工作目录（`jakarta.servlet.context.tempdir`）、
 * 实例管理器（`org.apache.tomcat.InstanceManager`）与编译类路径（`org.apache.catalina.jsp_classpath`）；
 * [createServlet] 返回原生 [JspServlet]，由容器按 `*.jsp`/`*.jspx` 映射或 addJspFile 的 `jspFile` 初始化参数驱动。
 *
 * JSP 源文件经 ServletContext.getResource/getRealPath 解析，因此需配置 docRoot 或将 JSP 置于类路径。
 */
class JasperJspEngine : JspEngine {
	companion object {
		private val log = Loggers.getLogger<JasperJspEngine>()

		/** Jasper 读取编译类路径的上下文属性名（Tomcat 约定）。 */
		private const val JSP_CLASSPATH_ATTR = "org.apache.catalina.jsp_classpath"
	}

	override fun initialize(context: ServletContext) {
		if (JspFactory.getDefaultFactory() == null) {
			JspFactory.setDefaultFactory(JspFactoryImpl())
			log.debug { "JspFactory set to JspFactoryImpl" }
		}
		val scratch = Files.createTempDirectory("netty-jsp-").toFile()
		scratch.deleteOnExit()
		context.setAttribute(ServletContext.TEMPDIR, scratch)
		context.setAttribute(InstanceManager::class.java.name, SimpleInstanceManager())
		val classpath = buildClasspath()
		context.setAttribute(JSP_CLASSPATH_ATTR, classpath)
		log.debug { "Jasper scratchDir=$scratch classpathEntries=${classpath.split(File.pathSeparatorChar).size}" }
	}

	override fun createServlet(): Servlet = JspServlet()

	/**
	 * 汇总编译类路径：线程上下文类加载器及其父链中所有 [URLClassLoader] 的 file: URL，
	 * 叠加 `java.class.path`。JDK 17 应用类加载器非 URLClassLoader，故 Gradle/Spring Boot
	 * 启动期 TCCL（URLClassLoader）是 jar 路径的主要来源。
	 */
	private fun buildClasspath(): String {
		val entries = linkedSetOf<String>()
		val seen = mutableSetOf<ClassLoader>()
		var loader: ClassLoader? = Thread.currentThread().contextClassLoader
		while (loader != null && seen.add(loader)) {
			if (loader is URLClassLoader) {
				for (url in loader.urLs) {
					if (url.protocol == "file") {
						runCatching { entries.add(File(url.toURI()).absolutePath) }
					}
				}
			}
			loader = loader.parent
		}
		System.getProperty("java.class.path")
			?.split(File.pathSeparatorChar)
			?.filter { it.isNotBlank() }
			?.forEach { entries.add(it) }
		return entries.joinToString(File.pathSeparator)
	}
}