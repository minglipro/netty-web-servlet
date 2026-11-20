package com.mingliqiye.web.netty.servlet.security

import java.io.Serializable
import java.security.Principal

/**
 * 认证域：校验用户名/口令，成功返回带角色的主体，失败返回 null。
 * 容器在 BASIC/FORM 认证与程序式 login 时调用。
 */
interface Realm {
	fun authenticate(username: String, password: String): RealmPrincipal?
}

/** 已认证主体：用户名与角色集合，可序列化以便随会话持久化。 */
data class RealmPrincipal(val username: String, val roles: Set<String>) : Principal, Serializable {
	override fun getName(): String = username

	companion object {
		private const val serialVersionUID = 1L
	}
}

/** 内存认证域：用户名 -> (口令, 角色)，适合测试与小型应用。 */
class InMemoryRealm(private val users: Map<String, Pair<String, Set<String>>>) : Realm {
	constructor(vararg users: Triple<String, String, Set<String>>) :
		this(users.associate { it.first to (it.second to it.third) })

	override fun authenticate(username: String, password: String): RealmPrincipal? {
		val (expected, roles) = users[username] ?: return null
		if (expected != password) return null
		return RealmPrincipal(username, roles)
	}
}