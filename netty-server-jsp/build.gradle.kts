group = "com.mingliqiye.web.netty.jsp"
description = "JSP 引擎：嵌入 Tomcat Jasper 编译执行 JSP，经 ServiceLoader 挂载到 Servlet 容器（*.jsp 映射、addJspFile）"

dependencies {
	api(project(":netty-server-servlet6"))
	api("org.apache.tomcat.embed:tomcat-embed-jasper:11.0.27")
}