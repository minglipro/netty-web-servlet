package com.mingliqiye.web.netty.session.redis

import com.mingliqiye.logger.Loggers
import com.mingliqiye.web.netty.servlet.session.SessionStore
import com.mingliqiye.web.netty.servlet.session.StoredSession
import io.lettuce.core.RedisClient
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.codec.ByteArrayCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * 会话存储 Redis 实现（Lettuce 同步命令，连接线程安全可并发复用）。
 *
 * 会话快照经 Java 序列化存为字节值，键为 `keyPrefix + 会话ID`；写入时以 [StoredSession.maxInactive]
 * 秒设置 TTL，使 Redis 过期与会话不活动超时对齐（maxInactive<=0 表示永不过期，不设 TTL）。
 * 属性值须可序列化（[java.io.Serializable]）。[close] 关闭连接并释放客户端资源。
 *
 * @param client Lettuce 客户端（本存储拥有其生命周期，[close] 时 shutdown）
 * @param keyPrefix 键前缀，默认 `netty:session:`，多应用共享实例时用以隔离
 */
class RedisSessionStore(
	private val client: RedisClient,
	private val keyPrefix: String = "netty:session:",
) : SessionStore, java.io.Closeable {
	companion object {
		private val log = Loggers.getLogger<RedisSessionStore>()
	}

	/** 以 Redis URI（如 `redis://localhost:6379`）创建客户端。 */
	constructor(uri: String, keyPrefix: String = "netty:session:") : this(RedisClient.create(uri), keyPrefix)

	private val connection: StatefulRedisConnection<ByteArray, ByteArray> = client.connect(ByteArrayCodec.INSTANCE)
	private val commands: RedisCommands<ByteArray, ByteArray> = connection.sync()

	private fun key(id: String): ByteArray = (keyPrefix + id).toByteArray(Charsets.UTF_8)

	override fun load(id: String): StoredSession? {
		val bytes = commands.get(key(id)) ?: return null
		return deserialize(bytes)
	}

	override fun save(session: StoredSession) {
		val bytes = serialize(session)
		if (session.maxInactive > 0) {
			commands.set(key(session.id), bytes, SetArgs().ex(session.maxInactive.toLong()))
		} else {
			commands.set(key(session.id), bytes)
		}
	}

	override fun remove(id: String) {
		commands.del(key(id))
	}

	override fun loadAll(): Collection<StoredSession> =
		commands.keys("$keyPrefix*").mapNotNull { k -> commands.get(k)?.let { deserialize(it) } }

	override fun close() {
		runCatching { connection.close() }
		runCatching { client.shutdown() }
	}

	private fun serialize(session: StoredSession): ByteArray {
		val buffer = ByteArrayOutputStream()
		ObjectOutputStream(buffer).use { it.writeObject(session) }
		return buffer.toByteArray()
	}

	private fun deserialize(bytes: ByteArray): StoredSession? = try {
		ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as StoredSession }
	} catch (e: Exception) {
		log.warn("Failed to deserialize session from Redis, dropping: $e")
		null
	}
}