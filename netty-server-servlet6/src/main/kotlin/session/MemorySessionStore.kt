package com.mingliqiye.web.netty.servlet.session

/**
 * 默认内存存储：不做任何外部持久化，会话仅存在于 [SessionManager] 的内存表中，进程结束即丢失。
 * 无状态单例，所有写操作为空操作，读操作恒返回空。
 */
object MemorySessionStore : SessionStore {
	override fun load(id: String): StoredSession? = null
	override fun save(session: StoredSession) {}
	override fun remove(id: String) {}
	override fun loadAll(): Collection<StoredSession> = emptyList()
}