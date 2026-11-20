package com.mingliqiye.web.netty.servlet.session

/**
 * 会话持久化存储 SPI：将 [HttpSessionImpl] 的可序列化状态（[StoredSession]）存取于外部介质。
 *
 * [SessionManager] 仍在内存中持有活跃会话对象并负责事件分发；本接口只承担状态的加载/保存/删除，
 * 使会话可在进程重启（磁盘）或多实例（Redis）间恢复。默认 [MemorySessionStore] 不做任何持久化。
 *
 * 实现约定：
 * - [save] 在会话创建、属性变更、换 ID、访问时间刷新时被调用，应保存最新快照；
 * - [load]/[loadAll] 返回的快照由管理器重新装配为会话对象（reattach），过期快照应被丢弃；
 * - [close] 在容器停机时调用，磁盘实现可在此落盘，Redis 实现可在此关闭连接。
 */
interface SessionStore {
	/** 按 ID 加载会话快照，不存在时返回 null。 */
	fun load(id: String): StoredSession?

	/** 保存（覆盖）会话快照。 */
	fun save(session: StoredSession)

	/** 删除指定会话快照。 */
	fun remove(id: String)

	/** 加载全部会话快照，供启动时恢复。 */
	fun loadAll(): Collection<StoredSession>

	/** 容器停机时释放资源/落盘，默认空实现。 */
	fun close() {}
}

/**
 * 会话可序列化状态快照。
 *
 * @property id 会话 ID
 * @property created 创建时间戳（毫秒）
 * @property lastAccessed 最近访问时间戳（毫秒）
 * @property maxInactive 不活动超时（秒），0 或负数表示永不过期
 * @property attributes 会话属性；磁盘/Redis 等持久化介质要求值可序列化（[java.io.Serializable]）
 */
data class StoredSession(
	val id: String,
	val created: Long,
	val lastAccessed: Long,
	val maxInactive: Int,
	val attributes: Map<String, Any?>,
) : java.io.Serializable