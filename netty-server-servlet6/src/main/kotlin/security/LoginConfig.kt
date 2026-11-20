package com.mingliqiye.web.netty.servlet.security

import java.io.Serializable

/** 容器认证方式。 */
enum class AuthMethod { BASIC, FORM }

/**
 * 登录配置。
 *
 * @property authMethod 认证方式（默认 BASIC）
 * @property realmName BASIC 挑战中的 realm 名称
 * @property loginPage FORM 登录页（上下文相对路径，如 /login.html）
 * @property errorPage FORM 认证失败跳转页
 */
data class LoginConfig(
	val authMethod: AuthMethod = AuthMethod.BASIC,
	val realmName: String = "netty",
	val loginPage: String? = null,
	val errorPage: String? = null,
)

/** FORM 认证期间暂存的原始请求，认证成功后跳回。 */
internal data class SavedRequest(
	val method: String,
	val uri: String,
	val query: String?,
) : Serializable {
	companion object {
		private const val serialVersionUID = 1L
	}
}