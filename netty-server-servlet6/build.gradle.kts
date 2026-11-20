group = "com.mingliqiye.web.netty.servlet"
description = "Jakarta Servlet 6.1 容器实现：路由、Filter 链、HttpSession、异步、multipart、流式请求/响应"

dependencies {
	api(project(":netty-server"))
	api("jakarta.servlet:jakarta.servlet-api:6.1.0")
	api("jakarta.websocket:jakarta.websocket-api:2.2.0")
	api("jakarta.websocket:jakarta.websocket-client-api:2.2.0")
	implementation("org.apache.commons:commons-fileupload2-jakarta-servlet6:2.0.0-M2")
}

tasks.named<Test>("test") {
	// 自签证书无 SAN，JDK HttpClient 主机名校验在类加载时静态读取该属性，须在 JVM 启动参数设置
	systemProperty("jdk.internal.httpclient.disableHostnameVerification", "true")
}
