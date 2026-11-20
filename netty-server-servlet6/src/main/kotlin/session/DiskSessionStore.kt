package com.mingliqiye.web.netty.servlet.session

import com.mingliqiye.logger.Loggers
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 磁盘会话存储：运行期在内存表中累积快照，容器停机 [close] 时整体序列化落盘，
 * 构造时若文件存在则读回，供 [SessionManager] 启动恢复，实现跨进程重启的会话持久化。
 *
 * 属性值须可序列化（[java.io.Serializable]），否则落盘时抛异常。文件损坏时按空存储处理并告警。
 *
 * @param file 会话快照文件（父目录不存在时自动创建）
 */
class DiskSessionStore(private val file: File) : SessionStore {
	companion object {
		private val log = Loggers.getLogger<DiskSessionStore>()
	}

	private val sessions = ConcurrentHashMap<String, StoredSession>()

	init {
		if (file.isFile && file.length() > 0) {
			try {
				ObjectInputStream(file.inputStream().buffered()).use { input ->
					@Suppress("UNCHECKED_CAST")
					val loaded = input.readObject() as Map<String, StoredSession>
					sessions.putAll(loaded)
				}
				log.debug { "Disk session store loaded ${sessions.size} sessions from ${file.absolutePath}" }
			} catch (e: Exception) {
				log.warn("Failed to read session store ${file.absolutePath}, starting empty: $e")
				sessions.clear()
			}
		}
	}

	override fun load(id: String): StoredSession? = sessions[id]

	override fun save(session: StoredSession) {
		sessions[session.id] = session
	}

	override fun remove(id: String) {
		sessions.remove(id)
	}

	override fun loadAll(): Collection<StoredSession> = sessions.values.toList()

	override fun close() {
		try {
			file.parentFile?.mkdirs()
			ObjectOutputStream(file.outputStream().buffered()).use { out ->
				out.writeObject(HashMap(sessions))
			}
			log.debug { "Disk session store persisted ${sessions.size} sessions to ${file.absolutePath}" }
		} catch (e: Exception) {
			log.warn("Failed to persist session store ${file.absolutePath}: $e")
		}
	}
}