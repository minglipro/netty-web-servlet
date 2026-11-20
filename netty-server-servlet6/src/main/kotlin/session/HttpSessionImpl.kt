package com.mingliqiye.web.netty.servlet.session

import com.mingliqiye.web.netty.servlet.core.ListenerBus
import jakarta.servlet.ServletContext
import jakarta.servlet.http.HttpSession
import jakarta.servlet.http.HttpSessionBindingEvent
import jakarta.servlet.http.HttpSessionBindingListener
import java.util.Collections
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap

/**
 * HttpSession 实现：属性变更触发 HttpSessionAttributeListener 与 HttpSessionBindingListener 事件；
 * [invalidate] 解绑全部属性并从管理器移除；失效后任何访问抛 IllegalStateException。
 *
 * @param id 会话 ID（changeSessionId 时经 [changeId] 原地更换，对象与属性保持不变）
 * @param context 所属 ServletContext
 * @param created 创建时间戳
 */
class HttpSessionImpl(
	id: String,
	private val context: ServletContext,
	val created: Long = System.currentTimeMillis(),
) : HttpSession {
	private var _id: String = id

	internal fun changeId(newId: String) {
		_id = newId
	}

	private val attributes = ConcurrentHashMap<String, Any>()

	internal var manager: SessionManager? = null
	internal var bus: ListenerBus? = null

	/** 最近访问时间戳（毫秒），每次请求命中会话时刷新。 */
	@Volatile
	var lastAccessed: Long = created

	/** 不活动超时（秒），0 或负数表示永不过期。 */
	@Volatile
	var maxInactive: Int = 1800

	/** 会话是否有效，invalidate/过期后置 false。 */
	@Volatile
	var valid = true

	/** 是否尚未被客户端确认（首次响应携带 Set-Cookie 前为 true）。 */
	@Volatile
	var new = true

	/** 刷新最近访问时间。 */
	fun touch() {
		lastAccessed = System.currentTimeMillis()
	}

	/** 是否已超过不活动超时。 */
	fun isExpired(): Boolean =
		maxInactive > 0 && System.currentTimeMillis() - lastAccessed > maxInactive * 1000L

	override fun getCreationTime(): Long {
		checkValid()
		return created
	}

	override fun getId(): String = _id

	override fun getLastAccessedTime(): Long {
		checkValid()
		return lastAccessed
	}

	override fun getServletContext(): ServletContext = context

	override fun setMaxInactiveInterval(interval: Int) {
		maxInactive = interval
	}

	override fun getMaxInactiveInterval(): Int = maxInactive

	override fun getAttribute(name: String): Any? {
		checkValid()
		return attributes[name]
	}

	override fun getAttributeNames(): Enumeration<String> {
		checkValid()
		return Collections.enumeration(attributes.keys)
	}

	override fun setAttribute(name: String, value: Any?) {
		checkValid()
		if (value == null) {
			removeAttribute(name)
			return
		}
		val old = attributes.put(name, value)
		if (old == null) {
			bus?.sessionAttributeAdded(this, name, value)
			(value as? HttpSessionBindingListener)?.valueBound(HttpSessionBindingEvent(this, name, value))
		} else {
			bus?.sessionAttributeReplaced(this, name, old)
		}
	}

	override fun removeAttribute(name: String) {
		checkValid()
		val old = attributes.remove(name) ?: return
		bus?.sessionAttributeRemoved(this, name, old)
		(old as? HttpSessionBindingListener)?.valueUnbound(HttpSessionBindingEvent(this, name, old))
	}

	/** 使会话失效：解绑全部属性、触发销毁事件并从管理器移除。 */
	override fun invalidate() {
		checkValid()
		expire()
		manager?.destroyed(this)
	}

	internal fun expire() {
		valid = false
		for ((name, value) in attributes) {
			bus?.sessionAttributeRemoved(this, name, value)
			(value as? HttpSessionBindingListener)?.valueUnbound(HttpSessionBindingEvent(this, name, value))
		}
		attributes.clear()
	}

	override fun isNew(): Boolean {
		checkValid()
		return new
	}

	private fun checkValid() {
		check(valid) { "Session $_id already invalidated" }
	}
}