group = "com.mingliqiye.web.netty.server"
description = "基于 Netty 与 Kotlin 协程的高性能 HTTP 服务器，支持聚合与流式双管线、背压与优雅停机"

val nettyVersion = "4.2.19.Final"

dependencies {
	api("io.netty:netty-common:$nettyVersion")
	api("io.netty:netty-buffer:$nettyVersion")
	api("io.netty:netty-transport:$nettyVersion")
	api("io.netty:netty-codec-http:$nettyVersion")
	api("io.netty:netty-transport-classes-epoll:$nettyVersion")
	api("io.netty:netty-transport-classes-kqueue:$nettyVersion")
	api("io.netty:netty-transport-classes-io_uring:$nettyVersion")
	runtimeOnly("io.netty:netty-transport-native-epoll:$nettyVersion:linux-x86_64")
	runtimeOnly("io.netty:netty-transport-native-epoll:$nettyVersion:linux-aarch_64")
	runtimeOnly("io.netty:netty-transport-native-io_uring:$nettyVersion:linux-x86_64")
	runtimeOnly("io.netty:netty-transport-native-io_uring:$nettyVersion:linux-aarch_64")
	runtimeOnly("io.netty:netty-transport-native-kqueue:$nettyVersion:osx-x86_64")
	runtimeOnly("io.netty:netty-transport-native-kqueue:$nettyVersion:osx-aarch_64")
	api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
