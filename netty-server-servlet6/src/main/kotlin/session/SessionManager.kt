package com.mingliqiye.web.netty.servlet.session

import com.mingliqiye.logger.Loggers
import com.mingliqiye.web.netty.servlet.core.ListenerBus
import jakarta.servlet.ServletContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话管理器：按 ID 存取会话，惰性过期（命中时检查），创建/销毁/换 ID 均触发监听器事件。
 * ID 来源（Cookie 或 URL ;jsessionid）由请求层解析，会话 ID 为去横线 UUID。
 */
class SessionManager(private val context: ServletContext) {
	companion object {
		private val log = Loggers.getLogger<SessionManager>()
	}

	private val sessions = ConcurrentHashMap<String, HttpSessionImpl>()

	internal lateinit var bus: ListenerBus
	internal var defaultTimeoutSeconds: Int = 1800

	/** 会话持久化存储，默认 [MemorySessionStore]（仅内存）。 */
	internal var store: SessionStore = MemorySessionStore

	/** 按 ID 查找会话；已失效或过期的会话就地销毁并返回 null，命中则刷新访问时间。内存未命中时尝试从存储恢复。 */
	fun find(id: String?): HttpSessionImpl? {
		if (id == null) return null
		val session = sessions[id] ?: return restore(id)
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
		persist(session)
		bus.sessionCreated(session)
		return session
	}

	/** 更换会话 ID（防会话固定攻击）：同一对象重新入库，属性保留，触发 sessionIdChanged。 */
	fun changeId(old: HttpSessionImpl): HttpSessionImpl {
		val oldId = old.id
		sessions.remove(oldId)
		store.remove(oldId)
		old.changeId(newId())
		sessions[old.id] = old
		log.debug { "Session id changed $oldId -> ${old.id}" }
		persist(old)
		bus.sessionIdChanged(old, oldId)
		return old
	}

	fun remove(id: String) {
		sessions.remove(id)
		store.remove(id)
	}

	/** 将会话最新快照写入存储（内存存储为空操作）。 */
	internal fun persist(session: HttpSessionImpl) {
		if (store !is MemorySessionStore) store.save(session.toStored())
	}

	internal fun destroyed(session: HttpSessionImpl) {
		sessions.remove(session.id)
		store.remove(session.id)
		if (session.valid) {
			session.expire()
		}
		log.debug { "Session destroyed ${session.id} (total=${sessions.size})" }
		bus.sessionDestroyed(session)
	}

	/** 启动时从存储恢复未过期会话（静默装配，不触发 sessionCreated）。 */
	internal fun restore() {
		for (stored in store.loadAll()) {
			val session = reattach(stored) ?: continue
			sessions[session.id] = session
		}
		if (sessions.isNotEmpty()) log.debug { "Restored ${sessions.size} sessions from ${store.javaClass.simpleName}" }
	}

	/** 内存未命中时按 ID 从存储加载并装配；过期则删除返回 null。 */
	private fun restore(id: String): HttpSessionImpl? {
		val stored = store.load(id) ?: return null
		val session = reattach(stored)
		if (session == null) {
			store.remove(id)
			return null
		}
		sessions[id] = session
		session.touch()
		return session
	}

	/** 将快照装配为会话对象，过期返回 null。 */
	private fun reattach(stored: StoredSession): HttpSessionImpl? {
		val session = HttpSessionImpl(stored.id, context, stored.created)
		session.lastAccessed = stored.lastAccessed
		session.maxInactive = stored.maxInactive
		session.manager = this
		session.bus = bus
		session.new = false
		stored.attributes.forEach { (name, value) -> if (value != null) session.restoreAttribute(name, value) }
		return if (session.isExpired()) null else session
	}

	/** 停机清理：触发销毁事件但保留存储数据，随后关闭存储（磁盘落盘/Redis 释放连接）。 */
	internal fun clear() {
		sessions.values.toList().forEach { session ->
			sessions.remove(session.id)
			if (session.valid) session.expire()
			bus.sessionDestroyed(session)
		}
		store.close()
	}

	private fun newId(): String = UUID.randomUUID().toString().replace("-", "")
}
