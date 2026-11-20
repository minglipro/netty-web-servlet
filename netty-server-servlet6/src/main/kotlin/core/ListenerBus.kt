package com.mingliqiye.web.netty.servlet.core

import com.mingliqiye.logger.Loggers
import jakarta.servlet.ServletContextAttributeEvent
import jakarta.servlet.ServletContextAttributeListener
import jakarta.servlet.ServletContextEvent
import jakarta.servlet.ServletContextListener
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletRequestAttributeEvent
import jakarta.servlet.ServletRequestAttributeListener
import jakarta.servlet.ServletRequestEvent
import jakarta.servlet.ServletRequestListener
import jakarta.servlet.http.HttpSession
import jakarta.servlet.http.HttpSessionAttributeListener
import jakarta.servlet.http.HttpSessionBindingEvent
import jakarta.servlet.http.HttpSessionEvent
import jakarta.servlet.http.HttpSessionIdListener
import jakarta.servlet.http.HttpSessionListener
import java.util.EventListener

/**
 * 监听器事件总线：把容器生命周期与属性变更事件分发给已注册的各类 EventListener。
 * 单个监听器抛异常只记录错误，不影响其他监听器与请求处理。
 */
internal class ListenerBus(private val context: ServletContextImpl) {
	companion object {
		private val log = Loggers.getLogger<ListenerBus>()
	}

	private inline fun <reified T : EventListener> each(action: (T) -> Unit) {
		val targets = context.listeners.filterIsInstance<T>()
		if (targets.isEmpty()) return
		log.debug { "Notify ${targets.size} ${T::class.simpleName} listener(s)" }
		targets.forEach {
			runCatching { action(it) }.onFailure { e -> log.error("Listener ${it.javaClass.name} failed", e) }
		}
	}

	fun contextInitialized() = each<ServletContextListener> { it.contextInitialized(ServletContextEvent(context)) }

	fun contextDestroyed() = each<ServletContextListener> { it.contextDestroyed(ServletContextEvent(context)) }

	fun requestInitialized(request: ServletRequest) =
		each<ServletRequestListener> { it.requestInitialized(ServletRequestEvent(context, request)) }

	fun requestDestroyed(request: ServletRequest) =
		each<ServletRequestListener> { it.requestDestroyed(ServletRequestEvent(context, request)) }

	fun sessionCreated(session: HttpSession) = each<HttpSessionListener> { it.sessionCreated(HttpSessionEvent(session)) }

	fun sessionDestroyed(session: HttpSession) = each<HttpSessionListener> { it.sessionDestroyed(HttpSessionEvent(session)) }

	fun sessionIdChanged(session: HttpSession, oldId: String) =
		each<HttpSessionIdListener> { it.sessionIdChanged(HttpSessionEvent(session), oldId) }

	fun requestAttributeAdded(request: ServletRequest, name: String, value: Any?) =
		each<ServletRequestAttributeListener> { it.attributeAdded(ServletRequestAttributeEvent(context, request, name, value)) }

	fun requestAttributeRemoved(request: ServletRequest, name: String, value: Any?) =
		each<ServletRequestAttributeListener> { it.attributeRemoved(ServletRequestAttributeEvent(context, request, name, value)) }

	fun requestAttributeReplaced(request: ServletRequest, name: String, value: Any?) =
		each<ServletRequestAttributeListener> { it.attributeReplaced(ServletRequestAttributeEvent(context, request, name, value)) }

	fun sessionAttributeAdded(session: HttpSession, name: String, value: Any?) =
		each<HttpSessionAttributeListener> { it.attributeAdded(HttpSessionBindingEvent(session, name, value)) }

	fun sessionAttributeRemoved(session: HttpSession, name: String, value: Any?) =
		each<HttpSessionAttributeListener> { it.attributeRemoved(HttpSessionBindingEvent(session, name, value)) }

	fun sessionAttributeReplaced(session: HttpSession, name: String, value: Any?) =
		each<HttpSessionAttributeListener> { it.attributeReplaced(HttpSessionBindingEvent(session, name, value)) }

	fun contextAttributeAdded(name: String, value: Any?) =
		each<ServletContextAttributeListener> { it.attributeAdded(ServletContextAttributeEvent(context, name, value)) }

	fun contextAttributeRemoved(name: String, value: Any?) =
		each<ServletContextAttributeListener> { it.attributeRemoved(ServletContextAttributeEvent(context, name, value)) }

	fun contextAttributeReplaced(name: String, value: Any?) =
		each<ServletContextAttributeListener> { it.attributeReplaced(ServletContextAttributeEvent(context, name, value)) }
}