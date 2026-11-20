package com.mingliqiye.web.netty.servlet.session

import com.mingliqiye.logger.Loggers
import com.mingliqiye.web.netty.servlet.core.ListenerBus
import jakarta.servlet.ServletContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话管理器：按 ID 存取会话，惰性过期（命中时检查），创建/销毁/换 ID 均触发监听器事件。
 * 仅支持 Cookie 跟踪，会话 ID 为去横线 UUID。
 */
class SessionManager(private val context: ServletContext) {
	companion object {
		private val log = Loggers.getLogger<SessionManager>()
	}

	private val sessions = ConcurrentHashMap<String, HttpSessionImpl>()

	internal lateinit var bus: ListenerBus
	internal var defaultTimeoutSeconds: Int = 1800

	/** 按 ID 查找会话；已失效或过期的会话就地销毁并返回 null，命中则刷新访问时间。 */
	fun find(id: String?): HttpSessionImpl? {
		if (id == null) return null
		val session = sessions[id] ?: return null
		if (!session.valid || session.isExpired()) {
			log.debug { "Session $id expired or invalid, destroying" }
			destroyed(session)
			return null
		}
		session.touch()
		session.new = false
		return session
	}

	/** 创建新会话并触发 sessionCreated 事件。 */
	fun create(): HttpSessionImpl {
		val session = HttpSessionImpl(newId(), context)
		session.maxInactive = defaultTimeoutSeconds
		session.manager = this
		session.bus = bus
		sessions[session.id] = session
		log.debug { "Session created ${session.id} (timeout=${defaultTimeoutSeconds}s, total=${sessions.size})" }
		bus.sessionCreated(session)
		return session
	}

	/** 更换会话 ID（防会话固定攻击）：同一对象重新入库，属性保留，触发 sessionIdChanged。 */
	fun changeId(old: HttpSessionImpl): HttpSessionImpl {
		val oldId = old.id
		sessions.remove(oldId)
		old.changeId(newId())
		sessions[old.id] = old
		log.debug { "Session id changed $oldId -> ${old.id}" }
		bus.sessionIdChanged(old, oldId)
		return old
	}

	fun remove(id: String) {
		sessions.remove(id)
	}

	internal fun destroyed(session: HttpSessionImpl) {
		sessions.remove(session.id)
		if (session.valid) {
			session.expire()
		}
		log.debug { "Session destroyed ${session.id} (total=${sessions.size})" }
		bus.sessionDestroyed(session)
	}

	internal fun clear() {
		sessions.values.toList().forEach { destroyed(it) }
	}

	private fun newId(): String = UUID.randomUUID().toString().replace("-", "")
}