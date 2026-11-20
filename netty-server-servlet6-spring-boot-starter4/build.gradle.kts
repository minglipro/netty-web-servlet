
group = "com.mingliqiye.web.netty.spring4"
description = "Spring Boot 4 嵌入式 Netty Servlet 容器 starter：自动配置 NettyServletWebServerFactory 替代 Tomcat"

dependencies {
	api(project(":netty-server-servlet6"))
	api("org.springframework.boot:spring-boot-web-server:4.0.0")
	api("org.springframework.boot:spring-boot-autoconfigure:4.0.0")
	testImplementation("org.springframework.boot:spring-boot-starter-test:4.0.0")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc:4.0.0")
	testImplementation("org.springframework.boot:spring-boot-starter-websocket:4.0.0")
}

configurations.testImplementation {
	exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
	exclude(group = "org.springframework.boot", module = "spring-boot-tomcat")
	exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
}
