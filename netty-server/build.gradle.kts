import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
	id("org.springframework.boot") version "4.1.2"
}

group = "com.mingliqiye.web.netty.server"
description = "基于 Netty 与 Kotlin 协程的高性能 HTTP 服务器，支持聚合与流式双管线、背压与优雅停机"

val nettyVersion = "4.2.19.Final"

configurations{
	register("fatjarRuntimeOnly")
}

dependencies {
	api("io.netty:netty-common:$nettyVersion")
	api("io.netty:netty-buffer:$nettyVersion")
	api("io.netty:netty-transport:$nettyVersion")
	api("io.netty:netty-codec-http:$nettyVersion")
	api("io.netty:netty-codec-http2:$nettyVersion")
	api("io.netty:netty-handler:$nettyVersion")
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
	"fatjarRuntimeOnly"("tools.jackson.module:jackson-module-kotlin:3.3.0")
	"fatjarRuntimeOnly"("com.mingliqiye.utils:mingli-utils:5.5.5")
	"fatjarRuntimeOnly"("org.apache.logging.log4j:log4j-slf4j2-impl:2.26.1")
	"fatjarRuntimeOnly"("org.apache.tika:tika-core:2.9.2")
	"fatjarRuntimeOnly"("org.apache.tika:tika-parsers-standard-package:2.9.2")
	"fatjarRuntimeOnly"("commons-io:commons-io:2.19.0")

	compileOnly("tools.jackson.module:jackson-module-kotlin:3.3.0")
	compileOnly("com.mingliqiye.utils:mingli-utils:5.5.5")
	compileOnly("org.apache.logging.log4j:log4j-slf4j2-impl:2.26.1")
	compileOnly("org.apache.tika:tika-core:2.9.2")
	compileOnly("org.apache.tika:tika-parsers-standard-package:2.9.2")
	compileOnly("commons-io:commons-io:2.19.0")
}

tasks.bootJar{
	enabled = false
}

tasks.register<BootJar>("fatjar"){
	description = "fatjar to web file Server"
	mainClass = "com.mingliqiye.web.netty.server.Main"
	targetJavaVersion = JavaVersion.VERSION_17
	archiveClassifier.set("fatjar")
	classpath(
		project.configurations.runtimeClasspath.get(),
		project.configurations["fatjarRuntimeOnly"]
	)
	from(sourceSets.main.get().output) {
		into("BOOT-INF/classes")
	}

	from(sourceSets.test.get().resources.srcDirs) {
		into("BOOT-INF/classes")
	}
}

tasks.jar{
	archiveClassifier.set(null)
}

tasks.build{
	dependsOn("fatjar")
}

tasks.processResources{
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	outputs.upToDateWhen { false }
	filesMatching(
		"META-INF/attributes/com.mingliqiye.web.netty.version"
	){
		expand(
			"version" to project.version,
		)
	}
}

tasks.register<BootJar>("testfatjar"){
	description = "fatjar to web file Server"
	mainClass = "com.mingliqiye.web.netty.server.Main"
	targetJavaVersion = JavaVersion.VERSION_17
	archiveFileName.set("netty-server-fatjar.jar")
	classpath(
		project.configurations.runtimeClasspath.get(),
		project.configurations["fatjarRuntimeOnly"]
	)
	from(sourceSets.main.get().output) {
		into("BOOT-INF/classes")
	}

	from(sourceSets.test.get().resources.srcDirs) {
		into("BOOT-INF/classes")
	}
}
