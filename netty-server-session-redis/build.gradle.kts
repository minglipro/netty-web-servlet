group = "com.mingliqiye.web.netty.session"
description = "会话存储 Redis 实现：基于 Lettuce 的 SessionStore，TTL 对齐会话不活动超时，支持多实例共享会话"

dependencies {
	api(project(":netty-server-servlet6"))
	api("io.lettuce:lettuce-core:7.5.2.RELEASE")
	testImplementation("com.github.codemonstur:embedded-redis:1.4.3")
}