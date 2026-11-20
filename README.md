# netty-web-servlet

基于 **Netty 4.2 + Kotlin 协程** 的高性能 Web 服务器，以及构建在其上的 **Jakarta Servlet 6.1 容器**、**Jakarta WebSocket 2.2 服务端/客户端容器**、**Spring Boot 4 嵌入式服务器 Starter**，并可选挂载 **Redis 会话存储** 与 **JSP（Tomcat Jasper）引擎**。

- 业务逻辑运行在挂起式协程工作池中，**不占用 Netty 事件循环线程**
- 请求体/响应体全流式，带 **autoRead 背压**，大文件上传下载不占满内存
- 传输层按平台自动选择最优实现：**io_uring > epoll > kqueue > NIO**
- **HTTP/1.1 + HTTP/2**（h2c 升级 / prior-knowledge / TLS ALPN），**HTTPS**（Netty SslContext），**gzip/deflate 响应压缩**
- Servlet 容器可直接运行原生 Servlet/Filter/Listener，也可作为 Spring Boot 4 的嵌入式容器替代 Tomcat
- **容器管理的安全**（BASIC/FORM 认证、角色约束、程序式 login/logout）、**静态资源 DefaultServlet**、**会话 URL 重写 + 内存/磁盘/Redis 存储**
- WebSocket 同时支持原生 `@ServerEndpoint` 端点与 Spring `@EnableWebSocket` 两条升级路径，支持 **permessage-deflate** 与 **客户端 connectToServer**
- 引入 `netty-server-jsp` 即自动支持 **JSP 编译执行**（`*.jsp` 映射 + `addJspFile`）

[![maven](https://img.shields.io/maven-metadata/v.svg?metadataUrl=https://repository.mingliqiye.com/repository/maven-releases/com/mingliqiye/web/netty/netty-server/maven-metadata.xml&label=netty-server)](https://repository.mingliqiye.com/#preview/browse/search/component/maven2%3Acom.mingliqiye.web.netty%3Anetty-server)

[![maven](https://img.shields.io/maven-metadata/v.svg?metadataUrl=https://repository.mingliqiye.com/repository/maven-releases/com/mingliqiye/web/netty/netty-server-servlet6/maven-metadata.xml&label=netty-server-servlet6)](https://repository.mingliqiye.com/#preview/browse/search/component/maven2%3Acom.mingliqiye.web.netty%3Anetty-server-servlet6)

[![maven](https://img.shields.io/maven-metadata/v.svg?metadataUrl=https://repository.mingliqiye.com/repository/maven-releases/com/mingliqiye/web/netty/netty-server-servlet6-spring-boot-starter4/maven-metadata.xml&label=netty-server-servlet6-spring-boot-starter4)](https://repository.mingliqiye.com/#preview/browse/search/component/maven2%3Acom.mingliqiye.web.netty%3Anetty-server-servlet6-spring-boot-starter4)
>  JDK 17+ · Kotlin 2.2.0 · Netty 4.2.19.Final · kotlinx-coroutines 1.10.2 · License Apache-2.0
> 版本：`0.0.4` · JDK 17+ · Kotlin 2.2.0 · Netty 4.2.19.Final · kotlinx-coroutines 1.10.2 · License Apache-2.0
>
> 变更日志见 [CHANGELOG.md](CHANGELOG.md)。

---

## 目录

- [模块结构](#模块结构)
- [快速开始](#快速开始)
- [模块一：netty-server（HTTP 服务器内核）](#模块一netty-serverhttp-服务器内核)
- [模块二：netty-server-servlet6（Servlet 6.1 + WebSocket 2.2 容器）](#模块二netty-server-servlet6servlet-61--websocket-22-容器)
- [模块三：netty-server-servlet6-spring-boot-starter4](#模块三netty-server-servlet6-spring-boot-starter4)
- [模块四：netty-server-session-redis（Redis 会话存储）](#模块四netty-server-session-redisredis-会话存储)
- [模块五：netty-server-jsp（JSP 引擎）](#模块五netty-server-jspjsp-引擎)
- [测试](#测试)
- [性能参考](#性能参考)
- [构建与发布](#构建与发布)
- [已知限制](#已知限制)

---

## 模块结构

`settings.gradle.kts` 自动包含根目录下所有 `netty-*` 子目录，新增模块无需修改 settings。

| 模块                                           | Maven 坐标（groupId 统一为 `com.mingliqiye.web.netty`）                      | 说明                                                        |
|----------------------------------------------|-----------------------------------------------------------------------|-----------------------------------------------------------|
| `netty-server`                               | `com.mingliqiye.web.netty:netty-server`                               | HTTP 服务器内核：双管线、协程工作池、流式 IO、背压、TLS、压缩、HTTP/2、WebSocket 升级基础设施 |
| `netty-server-servlet6`                      | `com.mingliqiye.web.netty:netty-server-servlet6`                      | Jakarta Servlet 6.1 容器 + Jakarta WebSocket 2.2 服务端/客户端容器     |
| `netty-server-servlet6-spring-boot-starter4` | `com.mingliqiye.web.netty:netty-server-servlet6-spring-boot-starter4` | Spring Boot 4 / Framework 7 自动配置，替代 Tomcat/Jetty/Undertow |
| `netty-server-session-redis`                 | `com.mingliqiye.web.netty.session:netty-server-session-redis`         | 可选：基于 Lettuce 的 Redis 会话存储（跨实例共享会话）                       |
| `netty-server-jsp`                           | `com.mingliqiye.web.netty.jsp:netty-server-jsp`                       | 可选：嵌入 Tomcat Jasper 的 JSP 编译执行引擎，经 ServiceLoader 自动挂载        |

依赖关系：`starter4 → servlet6 → netty-server`；`session-redis → servlet6`；`jsp → servlet6`。两个可选模块彼此独立，按需引入。

关键第三方依赖：

| 依赖                                     | 版本            | 用途                                                                 |
|----------------------------------------|---------------|--------------------------------------------------------------------|
| io.netty（拆分构件，非 netty-all）             | 4.2.19.Final  | common/buffer/transport/codec-http + codec-http2/handler + epoll/kqueue/io_uring 传输类与原生库 |
| kotlinx-coroutines-core                | 1.10.2        | 挂起式工作池与容器协程作用域                                                     |
| jakarta.servlet-api                    | 6.1.0         | Servlet 规范 API                                                     |
| jakarta.websocket-api / client-api     | 2.2.0         | WebSocket 规范 API（服务端 + 客户端实现）                                       |
| commons-fileupload2-jakarta-servlet6   | 2.0.0-M2      | multipart 解析                                                       |
| spring-boot-web-server / autoconfigure | 4.0.0         | Starter 自动配置                                                       |
| io.lettuce:lettuce-core                | 7.5.2.RELEASE | Redis 会话存储（仅 session-redis 模块）                                      |
| org.apache.tomcat.embed:tomcat-embed-jasper | 11.0.27  | JSP 引擎（仅 jsp 模块，传递 ecj 3.46.100 + tomcat-embed-el/core 11.0.27）      |
| com.mingliqiye.logger:logger-slf4j-api | 1.0.3-S2.0.20 | 日志门面（`log.debug { }` 惰性求值风格）                                       |

Netty 原生传输按 `runtimeOnly` 携带多平台 classifier：epoll/io_uring 的 `linux-x86_64`、`linux-aarch_64`，kqueue 的 `osx-x86_64`、`osx-aarch_64`；Windows 等平台自动回退 NIO。

---

## 快速开始

### 1. 原生 HTTP 服务器（netty-server）

```kotlin
// 聚合模式：收齐完整请求体后处理
val server = NettyWebServer.server("0.0.0.0", 8080) { request, ctx ->
    val content = Unpooled.copiedBuffer("hello", Charsets.UTF_8)
    val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content)
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
    Responses.write(ctx, request, response)
}
server.startServer()   // 同步绑定，失败立即抛异常
server.sync()          // 阻塞直到监听通道关闭
```

```kotlin
// 流式模式：请求头到达即开始处理，请求体惰性读取
val server = NettyWebServer.streamingServer(port = 8080) { request, body, ctx ->
    body.inputStream.use { input -> /* 边收边读 */ }
    val stream = ResponseStream(ctx, request)
    stream.start(DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK))
    stream.writeChunk(Unpooled.copiedBuffer("chunk", Charsets.UTF_8))
    stream.finish()
}
server.startServer()
```

### 2. 原生 Servlet 容器（netty-server-servlet6）

```kotlin
val server = NettyServletServer(ServletServerConfig(port = 8080))

server.addServlet("app", object : HttpServlet() {
    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        resp.contentType = "text/plain;charset=UTF-8"
        resp.writer.write("hello ${req.getParameter("name")}")
    }
}, "/app/*").setLoadOnStartup(1)

server.addFilter("auth", authFilter, "/*")
server.container.addErrorPage(404, "/error/404")
server.container.context.addListener(MyContextListener())
server.container.webSockets.addEndpoint(ChatEndpoint::class.java)  // @ServerEndpoint

server.startServer()
println("bound at ${server.port}")  // port=0 时这里拿到真实随机端口
server.sync()
```

### 3. Spring Boot 4 应用（starter）

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc:4.0.0") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
    implementation("com.mingliqiye.web.netty:netty-server-servlet6-spring-boot-starter4:0.0.4")
}
```

排除 Tomcat 后，自动配置会注册 `NettyServletWebServerFactory` 作为嵌入式容器，`@SpringBootApplication` 无需任何改动。WebSocket 应用加 `@EnableWebSocket` 即可（starter 已传递 websocket-api）。

```yaml
server:
  port: 8443
  servlet:
    context-path: /api
    session:
      timeout: 30m
  ssl:                      # HTTPS：标准 server.ssl.* 全量消费（含 bundle / key-alias / client-auth）
    key-store: classpath:keystore.p12
    key-store-password: changeit
    key-store-type: PKCS12
  http2:
    enabled: true           # TLS ALPN 协商 h2；明文时支持 h2c 升级 / prior-knowledge
  compression:
    enabled: true           # gzip/deflate 响应压缩
    mime-types: text/html,application/json
    min-response-size: 2KB
  netty:                    # Netty 专属旋钮，见下文属性表
    queue-capacity: 4096
    worker-threads: 0       # 0 = Netty 默认（CPU 核数 x2）
    transport: NIO          # 可选 IO_URING / EPOLL / KQUEUE / NIO，缺省自动选择
    session-store-path: /var/lib/app/sessions.dat   # 非 null 启用磁盘会话持久化
```

---

## 模块一：netty-server（HTTP 服务器内核）

包名 `com.mingliqiye.web.netty.server.*`。入口类 `NettyWebServer`，全量配置实体 `NettyServerConfig`。

### 1.1 双管线架构

| 管线   | 触发条件                          | 处理器签名                                                               | 特点                                       |
|------|-------------------------------|---------------------------------------------------------------------|------------------------------------------|
| 聚合模式 | 只设置 `handler`（或都不设置，用内置 echo） | `suspend (FullHttpRequest, ChannelHandlerContext) -> Unit`          | `HttpObjectAggregator` 收齐完整请求后一次性交付，简单直接 |
| 流式模式 | 设置 `streamingHandler`（优先）     | `suspend (HttpRequest, RequestBody, ChannelHandlerContext) -> Unit` | 请求头到达即提交业务，请求体分块喂入 `RequestBody`，带背压     |

两种模式的业务都在 `CoroutineWorkerPool` 中挂起执行，事件循环只做 IO。

### 1.2 配置项（NettyServerConfig）

| 属性                                    | 默认值                                           | 说明                                  |
|---------------------------------------|-----------------------------------------------|-------------------------------------|
| `serverName`                          | `null`（自动生成 `类名@哈希`）                          | 日志与线程命名                             |
| `host`                                | `""`                                          | 绑定地址，空串 = 所有网卡                      |
| `port`                                | `8080`                                        | 绑定端口，`0` = 系统随机分配，启动后读 `actualPort` |
| `handler`                             | `null`                                        | 聚合模式业务处理器                           |
| `streamingHandler`                    | `null`                                        | 流式模式业务处理器，非 null 时优先                |
| `queueCapacity`                       | `4096`                                        | 工作池队列容量 = 最大并发数，超出投递失败 → **503 降级** |
| `maxRequestSize`                      | `1 MiB`                                       | 请求体上限（字节），超出 → **413**              |
| `bossThreads`                         | `1`                                           | accept 事件循环线程数                      |
| `workerThreads`                       | `0`                                           | 读写事件循环线程数，0 = Netty 默认（CPU 核数 x2）   |
| `bossThreadName` / `workerThreadName` | `NettyWebServerBoss` / `NettyWebServerWorker` | 线程名前缀                               |
| `sportIO`                             | `NettyIO.getSportIO()`                        | 传输层，默认按平台自动选最优                      |
| `sslContext`                          | `null`                                        | Netty `SslContext`，非 null 启用 HTTPS（管线最前端挂 `SslHandler`） |
| `compression`                         | `null`                                        | `CompressionConfig`，非 null 启用 gzip/deflate 协商压缩 |
| `http2Enabled`                        | `false`                                       | 启用 HTTP/2：明文 h2c 升级 + prior-knowledge，TLS ALPN 协商 |

**CompressionConfig**：`mimeTypes`（参与压缩的 Content-Type，忽略 charset，默认含 text/html、application/json 等 8 类）、`minResponseSize`（完整响应小于该字节数不压缩，默认 2048；流式响应不受此限）、`excludedUserAgents`（命中的 UA 正则不压缩）、`compressionLevel`（deflate 级别 0–9，默认 6）。

### 1.3 已实现功能清单

**传输层（`io/NettyIO`）**
- 枚举封装四种传输：`IO_URING`（Linux io_uring）、`EPOLL`（Linux epoll）、`KQUEUE`（macOS/BSD）、`NIO`（跨平台兜底）
- `getSportIO()` 按 `IoUring.isAvailable() → Epoll → KQueue → NIO` 顺序自动降级
- 每种传输绑定对应的 `ServerChannel` 类型与 `IoHandlerFactory`（Netty 4.2 新 IoHandler 体系，`MultiThreadIoEventLoopGroup`）

**挂起式协程工作池（`poll/CoroutineWorkerPool`）**
- 事件循环线程 `submit()` **非阻塞**投递（`Channel.trySend`），绝不阻塞 IO 线程
- **信号量 + 有界 Channel 双重限流**：并发上限与队列容量均为 `capacity`
- 队列满 → 投递失败返回 false，由管线处理器回 **503 并关闭连接**（高压快速降级）
- 业务异常 → 回 **500**，并通过 `releaseTask` 回调释放任务持有的资源（如 ByteBuf），防止泄漏
- 业务在 `Dispatchers.Default` 上挂起执行，可随意调用阻塞/挂起代码
- `shutdown()` 关闭任务通道并取消全部在途协程

**流式请求体（`io/RequestBody`）**
- 事件循环 `feed()` 数据块，业务线程经 `inputStream` 阻塞读取，读到 EOF 即 body 结束
- **autoRead 背压**：未读块数达到高水位 **16** 暂停读取，消费到低水位 **8** 恢复
- 累计字节超过 `maxRequestSize` → `BodyTooLargeException`（容器侧映射 413），并置 `MUST_CLOSE` 标记
- **Trailer fields 捕获**：`LastHttpContent.trailingHeaders()` 存入 `trailerFields`，`isFinished` 标记 body 结束（容器侧 `getTrailerFields()`/`isTrailerFieldsReady()` 据此暴露）
- `abandon()`：业务未读完 body 时释放已排队块并恢复 autoRead **排水**，让客户端把剩余 body 发完，**keep-alive 连接得以复用**（容器在每个请求结束时自动调用）
- `Expect: 100-continue` 自动回 100（`StreamingWebHandler`）

**响应写出（`io/Responses` / `io/ResponseStream`）**
- `Responses.write()`：聚合响应写出，自动处理 keep-alive / `Connection: close`
- `ResponseStream`：chunked 流式响应，`start()` 发响应头 → `writeChunk()` 逐块发 → `finish()` 发结束块；所有写操作序列化到事件循环，**任意业务线程可调用**；连接已断开时自动释放传入的 ByteBuf
- 通道级 `MUST_CLOSE` 属性：body 失败等场景强制关闭连接，避免半截请求污染复用连接

**TLS/SSL（`sslContext`）**
- 非 null 时在管线最前端挂 `SslHandler`，对外即 HTTPS；容器侧 `isSecure()` 返回 true、`getScheme()` = `https`、`getRequestURL()` 以 https 重建
- 支持任意 Netty `SslContextBuilder` 配置（证书链、客户端认证、协议/密码套件、ALPN）

**响应压缩（`handler/ConfigurableCompressor`）**
- 按请求 `Accept-Encoding` 协商 **gzip / deflate**，仅压缩 `mimeTypes` 命中且不小于 `minResponseSize` 的响应
- `excludedUserAgents` 命中的 UA 视为移除其 Accept-Encoding（不压缩）
- 聚合响应整体压缩并重算 Content-Length；流式响应边发边压；正确设置 `Content-Encoding`

**HTTP/2（`io/Http2Support` + `NettyWebServer`）**
- **明文 h2c**：`CleartextHttp2ServerUpgradeHandler` 同时处理 `Upgrade: h2c` 升级与 **prior-knowledge**（直接 HTTP/2 preface）；升级请求体上限取 `maxRequestSize`
- **TLS ALPN**：`ApplicationProtocolNegotiationHandler` 协商 `h2` / `http1.1`，`Http2Support.alpnConfig()` 提供 ALPN 配置；未协商到 h2 自动回退 HTTP/1.1
- **多路复用**：`Http2MultiplexHandler` 每流一条子管线，经 `Http2StreamFrameToHttpObjectCodec` 把 HTTP/2 帧转回 HTTP/1.1 对象，**复用同一套 Servlet 业务管线**（容器无需感知 HTTP/2）
- 不支持 server push

**WebSocket 升级基础设施（`io/WebSockets`）**
- `isUpgradeRequest()`：严格校验 GET + `Upgrade: websocket` + `Connection` 含 upgrade token + `Sec-WebSocket-Key` + 版本 13
- `acceptKey()`：RFC 6455 握手密钥 `Base64(SHA-1(key + GUID))`
- `upgrade()`：全部操作序列化到事件循环——写 101 响应（可带协商子协议/扩展）→ 移除 HTTP 处理器 → 挂 `WebSocket13FrameDecoder`/`WebSocket13FrameEncoder`（含 permessage-deflate 扩展）→ 业务帧处理器

**生命周期**
- `startServer(host, port)`：**同步绑定**（`bind().sync()`），失败立即抛异常，不忙等待
- `actualPort`：真实绑定端口，`port=0` 随机端口场景的唯一权威来源
- `stopListening()`：仅关闭监听通道停止接受新连接，已建立连接与在途请求不受影响（**优雅停机第一步**）
- `stopServer()`：关监听 → 排空关闭工作池 → 优雅关闭两个事件循环组；**AtomicBoolean 幂等**，重复调用直接返回
- `sync()`：阻塞当前线程直到监听通道关闭
- 工厂方法：`server(config)` / `server(host, port)` / `server(port)` / `server(host, port, handler, ...)` / `streamingServer(host, port, handler, ...)`，均 `@JvmStatic`/`@JvmOverloads`，Java 可直接调用

---

## 模块二：netty-server-servlet6（Servlet 6.1 + WebSocket 2.2 容器）

包名 `com.mingliqiye.web.netty.servlet.*`。入口类 `NettyServletServer`，核心 `ServletContainer`，全量配置实体 `ServletServerConfig`。容器构建在 netty-server **流式管线**之上，每个请求在协程工作池中执行 `ServletContainer.handle()`。

### 2.1 配置项（ServletServerConfig）

在 NettyServerConfig 全部旋钮（serverName/host/port/queueCapacity/maxRequestSize/boss/worker 线程与命名/sportIO/sslContext/compression/http2Enabled）基础上增加：

| 属性                        | 默认值                  | 说明                                                   |
|---------------------------|----------------------|------------------------------------------------------|
| `contextPath`             | `""`                 | 应用上下文路径，空串 = 根上下文                                    |
| `responseBufferSize`      | `8 KiB`              | 响应缓冲区，写满自动 flush 转 chunked                           |
| `sessionTimeoutSeconds`   | `1800`               | 会话默认不活动超时（秒）                                         |
| `asyncTimeoutMillis`      | `30000`              | 异步请求默认超时（毫秒），0 = 不超时                                 |
| `multipartMaxFileSize`    | `1 MiB`              | multipart 单文件默认上限，可被每 Servlet 的 `MultipartConfig` 覆盖 |
| `multipartMaxRequestSize` | `10 MiB`             | multipart 请求总默认上限，同上                                 |
| `docRoot`                 | `null`               | 静态资源文档根目录（文件系统路径），非 null 自动注册 DefaultServlet 映射 `/`，并启用 getRealPath/getResourcePaths 文件系统解析 |
| `realm`                   | `null`               | 认证域，非 null 启用容器安全（BASIC/FORM、角色约束、程序式登录）              |
| `loginConfig`             | `null`（缺省 BASIC）     | 登录配置（认证方式/realm 名/FORM 页面）                           |
| `sessionStore`            | `MemorySessionStore` | 会话存储 SPI，可换 `DiskSessionStore` / `RedisSessionStore`   |

### 2.2 路由与注册

**URL 模式匹配（`UrlPatternMatcher`，规范优先级）**
1. 精确匹配 `/foo/bar`
2. 最长路径前缀 `/foo/*`（多个前缀命中取最长）
3. 扩展名 `*.do`
4. 默认 Servlet `/`

命中后按模式正确拆分 `servletPath` / `pathInfo`；无匹配 → 404（走错误页体系）。请求路径中的 `;jsessionid=...` 路径参数在路由前剥离。

**Servlet 动态注册（`ServletRegistration.Dynamic` 完整实现）**
- `addServlet(name, servlet, vararg urlPatterns)`，支持一个 Servlet 多映射
- `setInitParameter(s)`：冲突键不覆盖并返回冲突集合（规范语义）
- `setLoadOnStartup(n)`：容器启动时按值升序初始化，负数懒加载
- `setAsyncSupported(bool)`：与链上所有 Filter 的 asyncSupported 取与
- `setMultipartConfig(MultipartConfigElement)`：**每 Servlet 独立** multipart 限额，覆盖全局默认
- `setServletSecurity(ServletSecurityElement)` / `setRunAsRole(role)`：声明式安全约束与 run-as 身份（见 2.13）
- `ServletContext.addServlet` 三形态（实例 / Class / 类名反射）、`getServletRegistration(s)` 查询、`addJspFile`（见模块五）

**Filter 动态注册（`FilterRegistration.Dynamic` 完整实现）**
- **每条映射独立携带 `DispatcherTypes`**（缺省仅 REQUEST）与 `isMatchAfter`
- `addMappingForUrlPatterns` / `addMappingForServletNames` 双映射方式
- 链序规则：before 映射（isMatchAfter=false）在前、after 映射在后，各自按注册顺序
- Filter 按 URL 模式 **或** Servlet 名匹配，且分发类型必须在该映射的 dispatcherTypes 内

### 2.3 五种分发（DispatcherType）

| 分发        | 入口                          | 行为                                                                                                                                               |
|-----------|-----------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| `REQUEST` | 正常请求                        | 匹配 Servlet + Filter 链执行                                                                                                                          |
| `FORWARD` | `RequestDispatcher.forward` | 校验未提交 → resetBuffer → 设置 `jakarta.servlet.forward.*` 五属性 → 以 FORWARD 重走目标 Filter 链；**重写 requestURI**（Spring 7 DispatcherServlet 按 requestURI 路由） |
| `INCLUDE` | `RequestDispatcher.include` | 设置 `jakarta.servlet.include.*` 属性 → 以 INCLUDE 重走链，输出嵌入当前响应                                                                                       |
| `ERROR`   | `sendError` / 未捕获异常         | 见 2.4                                                                                                                                            |
| `ASYNC`   | `AsyncContext.dispatch`     | 重走目标链，完成后自动 complete                                                                                                                             |

按名称获取的 `NamedRequestDispatcher` 直接调用目标 Servlet（不重走 Filter 链），符合规范。

### 2.4 错误页体系

- 三种注册：`addErrorPage(location)` 全局兜底 / `addErrorPage(statusCode, location)` 按状态码 / `addErrorPage(exceptionType, location)` 按异常类型
- 匹配顺序：**状态码精确 → 异常类型沿父类链逐级 → 全局兜底 → 内置默认错误渲染**
- ERROR 分发时设置全部规范属性：`jakarta.servlet.error.status_code` / `message` / `exception` / `exception_type` / `request_uri` / `servlet_name`
- ERROR 分发**保留原始 HTTP 方法**（错误页 Servlet 的 doGet/doPost 都可能被调用）
- 错误页本身抛异常时回退内置默认渲染，不会二次错误分发（`errorDispatched` 防重入）
- 未捕获异常统一 500；cause 链中含 `BodyTooLargeException` 时映射 **413**

### 2.5 HttpServletRequest 实现要点

- 请求行/URI：`getMethod` / `getRequestURI`（分发时重写）/ `getRequestURL`（HTTPS 时 https 重建）/ `getQueryString` / `getContextPath` / `getServletPath` / `getPathInfo` / `getProtocol`
- 连接信息：`getRemoteAddr/Host/Port`、`getLocalName/Addr/Port`、`getServerName`（优先 Host 头）/ `getServerPort`
- 头：`getHeader(s)` / `getHeaderNames` / `getIntHeader` / `getDateHeader`（HTTP 日期格式解析）
- Cookie：`getCookies()` 解析 Cookie 头
- 参数：query string + `application/x-www-form-urlencoded` 表单合并解析，`getParameter/Values/Names/Map`，多值保序
- **multipart**：`getParts()` / `getPart(name)`，commons-fileupload2 解析，限额取每 Servlet `MultipartConfig`，缺省用容器全局值
- 体：`getInputStream()`（流式 ServletInputStream）/ `getReader()`（按 charset 解码）/ `getContentLength(Long)` / `getContentType` / `setCharacterEncoding`
- 属性：`get/set/removeAttribute`，**触发 ServletRequestAttributeListener**
- 本地化：`getLocale(s)` 解析 Accept-Language 权重排序
- 会话：`getSession(create)` / `changeSessionId()` / `getRequestedSessionId` / `isRequestedSessionIdValid/FromCookie/FromURL`（解析 `;jsessionid`）
- 异步：`startAsync()` / `startAsync(req, resp)` / `isAsyncStarted` / `isAsyncSupported` / `getAsyncContext`
- **安全**：`getUserPrincipal` / `isUserInRole` / `getRemoteUser` / `getAuthType` / `login` / `authenticate` / `logout`（见 2.13）
- Servlet 6.x 新 API：`getHttpServletMapping()`（matchValue/pattern/servletName/MappingMatch 四元组）、`getRequestId()` / `getProtocolRequestId()` / `getServletConnection()`、**`getTrailerFields()` / `isTrailerFieldsReady()`**（body 结束后返回真实 trailer 表）
- `getDispatcherType()` 随分发实时变化

### 2.6 HttpServletResponse 实现要点

- 状态：`setStatus` / `getStatus` / `sendError(sc[, msg])`（触发 ERROR 分发）/ `sendRedirect(location[, sc, clearBuffer])`
- 头：`setHeader`（**value 传 null 时移除该头**，Spring 会这样调用）/ `addHeader` / `setIntHeader` / `addIntHeader` / `setDateHeader` / `addDateHeader` / `containsHeader` / `getHeader(s)` / `getHeaderNames`
- Cookie：`addCookie`（支持 name/value/domain/path/maxAge/secure/httpOnly/comment/自定义属性）
- 体：`getOutputStream()` / `getWriter()` 互斥；`setContentType`（含 charset 解析）/ `setCharacterEncoding` / `setContentLength(Long)` / `setLocale`
- **缓冲与流式**：缓冲区 `responseBufferSize`（`setBufferSize`/`getBufferSize`），响应完成前调用 `flushBuffer()` 即切换 **chunked 流式输出**；未 flush 则聚合为带 Content-Length 的完整响应
- `reset()`（清头+状态+缓冲）/ `resetBuffer()` / `isCommitted()`
- **`encodeURL` / `encodeRedirectURL`**：URL 会话跟踪生效且会话存在、ID 非来自 Cookie 时追加 `;jsessionid=<id>`（剥离旧值、保留 query/fragment），否则原样返回（见 2.7）

### 2.7 HttpSession 与会话管理

- 会话 ID：去横线 UUID；**COOKIE + URL 双跟踪**（默认两者），默认 Cookie 名 `JSESSIONID`
- `SessionCookieConfig` 全量可配：name/domain/path/httpOnly/secure/maxAge/comment/自定义属性
- `setSessionTrackingModes`：支持 COOKIE / URL（SSL 须单独使用），contextInitialized 后锁定；`getEffectiveSessionTrackingModes` 反映生效模式
- **URL 重写**：请求侧解析并剥离 `;jsessionid`（大小写不敏感）后路由；响应侧 `encodeURL`/`encodeRedirectURL` 按生效模式追加
- 惰性过期：命中时检查 `maxInactiveInterval`，过期就地销毁；`invalidate()` 立即失效
- `changeSessionId()`：防会话固定攻击，同一会话对象换 ID 重新入库，属性保留，触发 `HttpSessionIdListener`
- 属性读写触发 `HttpSessionAttributeListener` 与 `HttpSessionBindingListener`（valueBound/valueUnbound）
- 创建/销毁触发 `HttpSessionListener`；`isNew` / `getCreationTime` / `getLastAccessedTime` 完整
- `ServletContext.setSessionTimeout`（**单位分钟**，规范语义）/ `getSessionTimeout` 运行时可调
- **会话存储 SPI**（见 2.14）：内存 / 磁盘持久化 / Redis 共享，停机清理触发销毁事件

### 2.8 监听器事件体系（ListenerBus）

支持全部 8 类监听器，单个监听器抛异常只记录错误，不影响其他监听器与请求处理：

| 监听器                               | 事件                                    |
|-----------------------------------|---------------------------------------|
| `ServletContextListener`          | contextInitialized / contextDestroyed |
| `ServletContextAttributeListener` | added / removed / replaced            |
| `ServletRequestListener`          | requestInitialized / requestDestroyed |
| `ServletRequestAttributeListener` | added / removed / replaced            |
| `HttpSessionListener`             | sessionCreated / sessionDestroyed     |
| `HttpSessionIdListener`           | sessionIdChanged                      |
| `HttpSessionAttributeListener`    | added / removed / replaced            |
| `HttpSessionBindingListener`      | valueBound / valueUnbound（会话属性写入/移除时） |

注册方式：`ServletContext.addListener(实例 / Class / 类名)`；启动顺序为 **会话恢复 → contextInitialized → 挂载 JSP 引擎 → loadOnStartup Servlet 升序 init → Filter init**；销毁顺序为 **WebSocket closeAll → 会话清理 → contextDestroyed → Filter/Servlet 逆序 destroy**。

### 2.9 异步 Servlet（AsyncContextImpl）

- `startAsync()` / `startAsync(req, resp)`，`hasOriginalRequestAndResponse` 区分
- `dispatch()` / `dispatch(path)` / `dispatch(context, path)`：以 ASYNC 分发重走目标链，未再 startAsync 时自动 complete
- `start(Runnable)`：在容器协程作用域（`Dispatchers.Default`）执行
- `complete()`：提交响应、触发 onComplete、结束请求计数
- `setTimeout(ms)` / `getTimeout()`：默认取 `asyncTimeoutMillis`（30s），0 = 不超时；专用守护线程调度器 `servlet-async-timeout` 触发 `AsyncListener.onTimeout`
- `addListener` 两形态（带/不带原始 request/response），onComplete/onTimeout/onError/onStartAsync 全事件
- 异步期间请求不计入完成，`activeRequests` 持续统计（优雅停机排空依据）
- 链上 Servlet 或任一 Filter `asyncSupported=false` 时 `startAsync` 抛 IllegalStateException

### 2.10 WebSocket 容器（jakarta.websocket 2.2，服务端 + 客户端）

包 `websocket/*`，核心 `NettyWebSocketContainer`（同时实现 `ServerContainer` 与 `WebSocketContainer`），经 `ServletContext.getAttribute("jakarta.websocket.server.ServerContainer")` 暴露。

**两条服务端升级路径**

| 路径   | 入口                                                              | 时机                                                                                                                                                |
|------|-----------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| 原生端点 | `tryUpgrade()`：按请求路径匹配已注册端点（精确路径 + `/{param}` 模板）               | **Servlet Filter 链之前**，命中即升级，不进入 Servlet 链（与 Tomcat `WsFilter` 在链首拦截升级的语义一致）                                                                                                        |
| 框架集成 | `upgradeHttpToWebSocket(request, response, config, pathParams)` | Spring `StandardWebSocketUpgradeStrategy` 在 Servlet 链内调用；**自动解包 ServletRequestWrapper/ServletResponseWrapper 链**（sa-token 等 Filter 包装过的请求/响应也能升级） |

> 原生端点升级发生在 Filter 链之前是**有意设计**，与 Tomcat 行为一致：WebSocket 握手不是普通 Servlet 请求。若需用 Filter 拦截/鉴权 WS 握手，请走 Spring 集成路径（`@EnableWebSocket`），升级在链内发生。

**端点注册与握手**
- `addEndpoint(Class)`：解析 `@ServerEndpoint`（path/subprotocols/decoders/encoders/configurator）
- `addEndpoint(ServerEndpointConfig)`：编程式注册，路径必须以 `/` 开头
- 握手完整走 `Configurator`：`modifyHandshake` → `checkOrigin` → `getNegotiatedSubprotocol` → `getEndpointInstance`
- `HandshakeRequest` 暴露全部请求头、参数表、queryString、请求 URI、HttpSession；`HandshakeResponse` 头可写回
- 提供 `NettyWebSocketConfigurator` 并经 `META-INF/services/jakarta.websocket.server.ServerEndpointConfig$Configurator` 注册为容器默认 Configurator（**覆写全部委托方法**，避免 ServiceLoader 自引用递归）
- 端点实例非 `Endpoint` 时自动包 `AnnotatedEndpointAdapter`

**注解端点（@ServerEndpoint）支持**
- `@OnOpen` / `@OnMessage` / `@OnClose` / `@OnError` 全生命周期
- `@PathParam` 注入（含 String→基本类型自动转换），路径模板 `/{room}` 段级匹配
- 消息参数形态：`String` / `ByteBuffer` / `Reader` / `InputStream` / `PongMessage` / 解码对象；带 `boolean last` 尾参 → **Partial 分片消息**，否则 Whole 整条消息
- 方法返回值自动回写：String→文本帧、ByteBuffer→二进制帧、其他对象→Encoder 编码
- `Session` / `EndpointConfig` / `CloseReason` / `Throwable` 按类型注入

**编解码器**
- `Decoder.Text` / `Decoder.Binary` / `Decoder.TextStream` / `Decoder.BinaryStream` 与 `Encoder.Text` / `Encoder.Binary` 全支持（init/willDecode/decode/destroy 生命周期）
- 解码失败（DecodeException）→ 关闭码 **1003 CANNOT_ACCEPT**

**permessage-deflate 扩展**
- 服务端经 Netty `PerMessageDeflateServerExtensionHandshaker` 协商 **permessage-deflate**（默认参数），握手成功即挂压缩扩展
- `getInstalledExtensions()` 报告已安装的 `permessage-deflate`
- 客户端握手暂不协商扩展（handshaker 关闭扩展）

**会话与帧处理（WebSocketSessionImpl + WebSocketFrameHandler）**
- `Session` 完整实现：id/config/container/isOpen/pathParameters/queryString/requestURI/requestParameterMap/userProperties/maxIdleTimeout/文本与二进制缓冲上限
- `RemoteEndpoint.Basic` 与 `RemoteEndpoint.Async`：sendText/sendBinary/sendObject/sendPing/sendPong、分片发送、Async 提供 Future 与 SendHandler 双形态（超时取容器默认 30s）、批处理开关
- **分片重组**：TEXT/BINARY 连续帧缓冲合并，超过会话缓冲上限 → **1009 TOO_BIG**；Partial 处理器逐片回调，Whole 处理器收整条
- Ping → 自动 Pong；Pong → 分发给 PongMessage 处理器
- Close 握手：本端主动关闭走 closing 状态机，对端 Close 回显后关连接；无状态码关闭映射 `NO_STATUS_CODE`；连接断开 → GOING_AWAY；异常 → onError + 关闭
- 升级后所有出站写经 `channel().writeAndFlush`（从管线尾端走 WS 编码器，避免经已移除的 HTTP 处理器 ctx 写丢）
- 单帧负载上限 **65536** 字节

**客户端（connectToServer 四形态）**
- `connectToServer(annotatedInstance)` / `connectToServer(annotatedClass)` / `connectToServer(endpoint, ClientEndpointConfig)` / `connectToServer(endpointClass, ClientEndpointConfig)` 全部实现
- 支持 `ws` / `wss`（TLS），Netty `WebSocketClientHandshaker13` 握手，`@ClientEndpoint` 的 subprotocols/encoders/decoders/configurator 全 honored
- 返回客户端 `Session`，可收发文本/二进制、主动关闭

**容器默认值与运维**
- `defaultAsyncSendTimeout` 30s、`defaultMaxSessionIdleTimeout` 0（不限）、文本/二进制缓冲各 8192，均可运行时修改
- 守护线程 `websocket-idle-checker` 每秒扫描，按各会话 `maxIdleTimeout` 关闭空闲会话（GOING_AWAY）
- 容器停机 `closeAll()`：关闭检查器 + 以 GOING_AWAY 关闭全部会话
- `onOpen` 抛异常 → onError + 1011 UNEXPECTED_CONDITION 关闭

### 2.11 ServletContext 其他能力

- 版本申报 6.1；`getServerInfo()` = `NettyServlet/1.0`
- `getMimeType`（按文件名猜测 + 常见类型补充表）
- **资源访问**：`getResource` / `getResourceAsStream` **docRoot 文件系统优先、类路径回退**；`getRealPath` 解析 docRoot 内安全路径（越界 `..` 返回 null）；`getResourcePaths` 合并 docRoot 目录与类路径（file:/jar:）条目
- `getInitParameter(s)` / `setInitParameter`、属性读写（触发 context 属性监听器）
- `getRequestDispatcher(path)` / `getNamedDispatcher(name)`
- `setRequestCharacterEncoding` / `setResponseCharacterEncoding` 全局默认编码
- `getSessionCookieConfig` / `setSessionTrackingModes`（COOKIE/URL）
- `declareRoles`（见 2.13）、`addJspFile`（见模块五）、`getJspConfigDescriptor`（返回 null）
- `log(msg[, throwable])` 走统一日志门面

### 2.12 生命周期与优雅停机

- `startServer()`：容器 init（会话恢复 → 监听器 → JSP 挂载 → loadOnStartup → Filter）后同步绑定端口
- `stopListening()`：停止接受新连接，在途请求继续
- `activeRequests`：在途请求计数（含异步未完成），供排空判断
- `stopServer()`：网络层停机 + 容器 shutdown；**双层幂等**（NettyWebServer.stopped + ServletContainer.shutdownOnce），并发/重复调用只执行一次
- shutdown 顺序：WebSocket closeAll → 会话清理（落盘）→ contextDestroyed → Filter/Servlet 逆序 destroy → 关闭异步调度器与协程作用域

### 2.13 容器管理的安全（realm 非 null 时启用）

包 `security/*`。配置 `ServletServerConfig(realm = ..., loginConfig = ...)` 即开启。

- **认证域**：`Realm.authenticate(username, password): RealmPrincipal?`；`RealmPrincipal(username, roles)` 可序列化（随会话持久化）；内置 `InMemoryRealm`（Map 或 vararg Triple 构造）
- **BASIC**：未认证回 `WWW-Authenticate: Basic realm="<realmName>"` + 401；携带 `Authorization: Basic` 自动校验
- **FORM**：`LoginConfig(authMethod=FORM, loginPage, errorPage)`；受保护请求暂存 `SavedRequest` 并跳转 loginPage（缺省 `/login.html`）；POST `/j_security_check`（`j_username`/`j_password`）校验，成功跳回原始 URI+query，失败跳 errorPage
- **程序式**：`HttpServletRequest.login(user, pass)`（无 realm 或失败抛 ServletException）/ `authenticate(response): Boolean`（触发挑战）/ `logout()`（失效会话）
- **身份查询**：`getUserPrincipal` / `getRemoteUser` / `isUserInRole` / `getAuthType`（BASIC/FORM）；run-as 角色执行期间优先
- **声明式约束**：`declareRoles` 声明角色；`ServletRegistration.setServletSecurity` 按 HTTP 方法约束（PERMIT 放行、未认证挑战、角色不符/DENY → 403）；`setRunAsRole` 执行期间切换主体
- 登录成功 `changeSessionId()` 防会话固定；主体与认证方式存入会话

### 2.14 会话存储 SPI（内存 / 磁盘 / Redis）

包 `session/*`。`SessionStore` 接口：`load(id): StoredSession?` / `save(StoredSession)` / `remove(id)` / `loadAll(): Collection<StoredSession>` / `close()`。载体 `StoredSession(id, created, lastAccessed, maxInactive, attributes)` 可序列化。

- **MemorySessionStore**（默认）：无操作单例，会话仅存内存
- **DiskSessionStore(file)**：构造时 `ObjectInputStream` 加载（损坏文件 → 清空 + 告警），运行期保存于内存映射，`close()`（停机）时整体 `ObjectOutputStream` 落盘；启动 `restore()` 重新挂载未过期会话
- **RedisSessionStore**：见模块四
- `SessionManager` 在创建/属性变更/换 ID/访问时 `persist`，命中缺失时惰性 `restore`，启动时全量 `restore`；停机 `clear()` 触发销毁事件但保留存储数据，随后 `store.close()`
- 经 `ServletServerConfig.sessionStore` 注入；Boot 经 `server.netty.session-store-path` 或自定义 `SessionStore` Bean 选择

### 2.15 静态资源（DefaultServlet）

包 `resources/DefaultServlet`。`docRoot` 非 null 且无 `/` 映射时自动注册为 `"default"` 映射 `/`。

- init 参数：`listings`（默认 false，目录无欢迎文件时 404；true 输出 HTML 目录列表）、`welcomeFiles`（逗号分隔，默认 `index.html,index.htm`）
- GET/HEAD；`..` 越界 → 400；Content-Type 按 `getMimeType`；`Accept-Ranges: bytes` + Last-Modified
- `If-Modified-Since` → 304；单段 `Range`（`start-end`/`start-`/`-suffix`）→ 206 + Content-Range，不可满足 → 416（`bytes */len`）；Range 仅对文件生效
- 欢迎文件优先，其次可选目录列表；也可手动注册到任意路径（如 `/static/*`）服务类路径资源

---

## 模块三：netty-server-servlet6-spring-boot-starter4

包名 `com.mingliqiye.web.netty.spring4`。目标 **Spring Boot 4.0.x / Framework 7**，四个类构成完整自动配置：

| 类                                        | 职责                                                                                                                                                |
|------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| `NettyServletWebServerAutoConfiguration` | 自动配置入口，经 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 发现；注册默认 `SessionStore` Bean                |
| `NettyServletWebServerFactory`           | 嵌入式服务器工厂，继承 Boot 4 的 `AbstractConfigurableWebServerFactory` 并实现 `ConfigurableServletWebServerFactory`（Boot 4 已移除 AbstractServletWebServerFactory） |
| `NettyServerProperties`                  | `server.netty.*` 属性绑定                                                                                                                             |
| `NettyWebServerAdapter`                  | Boot `WebServer` 生命周期适配 + 优雅停机                                                                                                                    |

### 3.1 自动配置条件

- `@ConditionalOnClass(ServletRequest, NettyServletServer)` 且 `@ConditionalOnWebApplication(SERVLET)`
- `@ConditionalOnMissingBean(ServletWebServerFactory)`：用户自定义任意工厂时让位
- `@Import(ServletWebServerConfiguration)`：引入 Boot 通用的 ServerProperties 定制器、WebServerFactoryCustomizer 与 ErrorPageRegistrar 后处理器
- `@ConditionalOnMissingBean(SessionStore)`：默认按 `server.netty.session-store-path` 选 Disk/Memory，用户自定义 `SessionStore` Bean（如 Redis）时让位

### 3.2 已实现功能

- **标准 `server.*` 属性生效**：`server.port`（含 0 随机端口）、`server.address`、`server.servlet.context-path`、`server.servlet.session.timeout`、display-name 等，由 Boot 定制器注入工厂
- **`server.ssl.*` 全量消费**：构建 Netty `SslContext`，支持 keystore/truststore、**SSL bundle**、`key-alias`（提取对应私钥与证书链）、`client-auth`（WANT→OPTIONAL / NEED→REQUIRE + trustManager）、enabled-protocols / ciphers；`server.http2.enabled` 时自动加 ALPN 配置
- **`server.compression.*` 消费**：enabled / mime-types / min-response-size / excluded-user-agents → `CompressionConfig`
- **`server.http2.enabled` 消费**：明文 h2c 升级 / prior-knowledge，TLS ALPN 协商
- **ServletContextInitializer 全量应用**：`ServletContextInitializers.from(settings, ...)` 依次 onStartup（DispatcherServlet 注册、Filter/Listener 注册等全部走容器动态注册 API）
- **web 监听器类名注册**（`addWebListeners`）、**错误页注册**（ErrorPageRegistrar 全局/状态码/异常类型，Spring Boot 默认 `/error` 兜底正常）
- **会话存储**：`server.netty.session-store-path` 启用磁盘持久化；自定义 `SessionStore` Bean 启用 Redis 共享
- **优雅停机**（`server.shutdown=graceful`）：后台守护线程先 `stopListening()`，再每 50ms 轮询 `activeRequests` 排空，最长 30 秒；超时报告 `REQUESTS_ACTIVE`，否则 `IDLE`，最后完整停机并回调
- **Spring WebSocket 集成**：`@EnableWebSocket` + `WebSocketConfigurer` 注册 `TextWebSocketHandler` 即可工作；Filter 链包装（wrapper）也能正确升级（容器侧自动解包）
- 启动失败包装为 `WebServerException`，符合 Boot 契约

### 3.3 `server.netty.*` 属性表（NettyServerProperties）

| 属性                                        | 默认值                    | 说明                                            |
|-------------------------------------------|------------------------|-----------------------------------------------|
| `server.netty.queue-capacity`             | `4096`                 | 协程工作池队列容量 = 最大并发数，超出回 503                     |
| `server.netty.max-request-size`           | `1048576`（1 MiB）       | 请求体总上限（字节），超出回 413                            |
| `server.netty.response-buffer-size`       | `8192`                 | 响应缓冲区（字节），写满自动 flush 转 chunked                |
| `server.netty.async-timeout-millis`       | `30000`                | 异步请求默认超时，0 = 不超时                              |
| `server.netty.multipart-max-file-size`    | `1048576`              | multipart 单文件默认上限（字节）                         |
| `server.netty.multipart-max-request-size` | `10485760`             | multipart 请求总默认上限（字节）                         |
| `server.netty.boss-threads`               | `1`                    | accept 事件循环线程数                                |
| `server.netty.worker-threads`             | `0`                    | 读写事件循环线程数，0 = Netty 默认                        |
| `server.netty.boss-thread-name`           | `NettyWebServerBoss`   | boss 线程名前缀                                    |
| `server.netty.worker-thread-name`         | `NettyWebServerWorker` | worker 线程名前缀                                  |
| `server.netty.transport`                  | `null`（自动选择）           | 强制传输层：`IO_URING` / `EPOLL` / `KQUEUE` / `NIO` |
| `server.netty.session-store-path`         | `null`                 | 会话磁盘持久化文件路径，非 null 启用 DiskSessionStore（停机落盘、启动恢复）；null 仅内存 |

> TLS / 压缩 / HTTP/2 走 Boot **标准** `server.ssl.*` / `server.compression.*` / `server.http2.*`（不在 `server.netty.*` 下）。Spring MVC 的 multipart 上限（`spring.servlet.multipart.*`）由 Spring 自身解析链处理；容器层限额是兜底防线，两者取更严格者生效。

### 3.4 Spring WebSocket 示例

```kotlin
@Configuration
@EnableWebSocket
class WebSocketConfig : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(object : TextWebSocketHandler() {
            override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
                session.sendMessage(TextMessage("echo:${message.payload}"))
            }
        }, "/ws").setAllowedOrigins("*")
    }
}
```

---

## 模块四：netty-server-session-redis（Redis 会话存储）

包名 `com.mingliqiye.web.netty.session.redis`。可选模块，引入即提供跨实例共享会话的 `SessionStore` 实现。

```kotlin
dependencies {
    implementation("com.mingliqiye.web.netty.session:netty-server-session-redis:0.0.4")
}
```

- **RedisSessionStore**：`RedisSessionStore(client: RedisClient, keyPrefix = "netty:session:")` 或便捷构造 `RedisSessionStore(uri: String, keyPrefix = "netty:session:")`
- 基于 **Lettuce 7.5.2.RELEASE** 同步命令 + `ByteArrayCodec`；会话以 Java 序列化 `StoredSession` 存储
- `save`：`maxInactive > 0` 时 `SET key value EX <maxInactive>`（随会话不活动超时自动过期），否则无 TTL
- `loadAll`：`KEYS <prefix>*` 逐个反序列化（损坏值告警跳过）；`close()` 关闭连接并 shutdown 客户端
- 原生容器：`ServletServerConfig(sessionStore = RedisSessionStore("redis://127.0.0.1:6379"))`
- Spring Boot：注册为 Bean 即覆盖默认存储

```kotlin
@Bean
fun sessionStore(): SessionStore = RedisSessionStore("redis://127.0.0.1:6379")
```

> 多实例共享同一 Redis 即可共享会话；各节点惰性从存储恢复，序列化属性须可序列化（`RealmPrincipal` 已实现 Serializable）。

---

## 模块五：netty-server-jsp（JSP 引擎）

包名 `com.mingliqiye.web.netty.jsp`。可选模块，**仅嵌入 Tomcat Jasper 编译/执行引擎**（Jasper + ECJ + EL），不引入 Tomcat 连接器或容器——Servlet 语义仍由本项目 Netty 容器提供。

```kotlin
dependencies {
    implementation("com.mingliqiye.web.netty.jsp:netty-server-jsp:0.0.4")
}
```

- **自动挂载**：`JasperJspEngine` 经 `META-INF/services/com.mingliqiye.web.netty.servlet.jsp.JspEngine` 被容器 `ServiceLoader` 发现；容器 init 时自动注册 JSP Servlet 映射 `*.jsp` / `*.jspx`（用户已自定义同名映射时让位）
- **addJspFile**：`ServletContext.addJspFile(servletName, jspFile)` 注册命名 JSP Servlet（映射 jspFile 路径、设 `jspFile` 初始化参数，启动即预编译）；未引入本模块时抛 UnsupportedOperationException
- **initialize** 设置：全局 `JspFactory`（`JspFactoryImpl`）、Jasper 工作目录（`jakarta.servlet.context.tempdir` 临时目录）、实例管理器（`org.apache.tomcat.InstanceManager` = `SimpleInstanceManager`）、编译类路径（`org.apache.catalina.jsp_classpath`，汇总 TCCL 及父链 URLClassLoader 的 file: URL + `java.class.path`）
- **编译执行**：JSP 源经 `ServletContext.getResource/getRealPath` 解析（需配置 `docRoot` 或将 JSP 置于类路径），ECJ（`JDTCompiler`）真实编译为 Servlet 类并执行，支持 scriptlet / 表达式 / EL（含 `param`、`pageContext` 等隐式对象）

```kotlin
val server = NettyServletServer(ServletServerConfig(port = 8080, docRoot = "/var/www"))
// /var/www/hello.jsp 经 *.jsp 自动映射编译执行
server.startServer()
```

> JSP 编译类路径依赖 **file: URL（展开的类路径）**。Spring Boot 可执行 fat-jar 的嵌套 jar 不在 ECJ 类路径上，故 fat-jar 内 JSP 编译不受支持——请以展开类路径运行，或将 JSP 预编译后部署。

---

## 测试

共 **141 个自动化测试**（JUnit 5），全部基于真实端口绑定 + JDK HttpClient / JDK WebSocket 客户端端到端验证，**0 失败 / 0 错误 / 0 跳过**：

| 模块             | 测试类                                        | 数量 | 覆盖                                                                                                                              |
|----------------|--------------------------------------------|----|---------------------------------------------------------------------------------------------------------------------------------|
| netty-server   | `NettyServerConfigTest` / `WebHandlerTest` | 4  | 配置默认值、聚合管线行为                                                                                                                    |
| servlet6       | `NettyServletServerIntegrationTest`        | 15 | 路由/参数/表单/会话/异步/分发/Filter 顺序/超时/Cookie/优雅停机等                                                                                     |
| servlet6       | `ServletSpecIntegrationTest`               | 11 | ERROR 分发与错误页、DispatcherTypes、isMatchAfter、监听器事件、changeSessionId、HttpServletMapping、每 Servlet multipart 限额、loadOnStartup 等规范缺口回归 |
| servlet6       | `StreamingBodyIntegrationTest`             | 7  | 流式上传、背压、abandon 排水复用连接、413、100-continue                                                                                         |
| servlet6       | `ResponseStreamingIntegrationTest`         | 5  | flushBuffer 转 chunked、流式下载、缓冲边界                                                                                                 |
| servlet6       | `StaticResourceIntegrationTest`            | 15 | DefaultServlet 欢迎文件/目录列表/MIME/Range 206·416/304/HEAD/越界 400/getRealPath/getResourcePaths/类路径资源                                  |
| servlet6       | `SecurityIntegrationTest`                  | 13 | BASIC/FORM 认证、程序式 login/authenticate/logout、角色约束、run-as、declareRoles、会话固定防护                                                       |
| servlet6       | `SessionUrlRewriteIntegrationTest`         | 6  | encodeURL/encodeRedirectURL ;jsessionid、URL 跟踪模式、请求侧解析剥离                                                                          |
| servlet6       | `SessionDiskPersistenceIntegrationTest`    | 2  | 磁盘存储重启恢复、失效会话不恢复                                                                                                                |
| servlet6       | `CompressionIntegrationTest`               | 6  | gzip/deflate 协商、mimeTypes/minResponseSize 门槛、excludedUserAgents、流式压缩、无 Accept-Encoding 不压缩                                          |
| servlet6       | `HttpsAndTrailerIntegrationTest`           | 2  | HTTPS isSecure/scheme/URL、请求 trailer fields 暴露                                                                                  |
| servlet6       | `Http2CleartextIntegrationTest`            | 5  | h2c GET/POST 升级、prior-knowledge、HTTP/1.1 回退、多路复用                                                                                  |
| servlet6       | `Http2TlsIntegrationTest`                  | 2  | TLS ALPN h2、HTTP/1.1 over TLS 回退                                                                                                |
| servlet6       | `WebSocketIntegrationTest`                 | 9  | 文本/二进制回显、子协议协商、分片重组、ping/pong、@ServerEndpoint+@PathParam、服务端主动关闭、客户端关闭回调、超大消息 1009、非升级请求 404                                    |
| servlet6       | `WebSocketDeflateIntegrationTest`          | 5  | permessage-deflate 协商、压缩帧往返、getInstalledExtensions                                                                               |
| servlet6       | `WebSocketClientIntegrationTest`           | 6  | connectToServer 四形态、@ClientEndpoint、ws/wss、客户端收发与关闭                                                                              |
| starter4       | `NettyServletWebServerFactoryTest`         | 3  | 工厂属性注入、错误页、随机端口                                                                                                                 |
| starter4       | `SpringBootMvcIntegrationTest`             | 6  | @SpringBootTest 端到端 MVC、参数绑定、错误页、会话、优雅停机                                                                                        |
| starter4       | `SpringBootWebSocketStartupTest`           | 2  | @EnableWebSocket 启动 + 真实 WS 回显，**含 Filter 包装 request/response 的升级回归**                                                           |
| starter4       | `SpringBootSslIntegrationTest`             | 1  | server.ssl.* keystore → HTTPS 端到端                                                                                              |
| starter4       | `SpringBootCompressionIntegrationTest`     | 2  | server.compression.* → gzip 端到端                                                                                                |
| starter4       | `SpringBootStaticResourceIntegrationTest`  | 2  | docRoot 静态资源端到端                                                                                                                |
| starter4       | `SpringBootSessionUrlIntegrationTest`      | 1  | URL 会话重写端到端                                                                                                                    |
| starter4       | `SpringBootSessionStoreIntegrationTest`    | 2  | session-store-path → DiskSessionStore Bean + MVC 会话计数                                                                          |
| starter4       | `SpringBootHttp2IntegrationTest`           | 1  | server.http2.enabled → 明文 h2 端到端                                                                                                |
| starter4       | `SpringBootHttp2SslIntegrationTest`        | 1  | server.ssl.* + http2 → TLS ALPN h2 端到端                                                                                          |
| session-redis  | `RedisSessionStoreIntegrationTest`         | 2  | 存储往返、跨实例会话共享（embedded-redis）                                                                                                  |
| jsp            | `JspIntegrationTest`                       | 5  | ECJ 真实编译执行 scriptlet/表达式/EL、addJspFile 预编译、编译复用、缺失 404                                                                            |

`NettyWebServerTests`（netty-server）为**手动压测/演示 harness**（无 @Test，不计入上表）。建议按模块分别跑：

```bash
gradle :netty-server:test
gradle :netty-server-servlet6:test
gradle :netty-server-servlet6-spring-boot-starter4:test
gradle :netty-server-session-redis:test
gradle :netty-server-jsp:test
```

测试日志统一走 `test-log-resources/log4j2.xml`（log4j-slf4j2-impl）。starter 测试已排除 `spring-boot-starter-tomcat` 与 `spring-boot-starter-logging`（避免与 log4j2 绑定冲突）。servlet6/starter 测试 JVM 设 `jdk.internal.httpclient.disableHostnameVerification=true`（自签证书无 SAN，JDK HttpClient 主机名校验静态读取该属性）。session-redis 测试用 `com.github.codemonstur:embedded-redis:1.4.3` 起本地 Redis。

---

## 性能参考

2026-11-19 在 32 核 Windows / NIO 传输上对 netty-server 挂起式协程池版本做过基准压测：**峰值约 55k RPS**（keep-alive 小响应）。Linux 上启用 epoll/io_uring 后预期更高。数据为特定环境参考值，非承诺指标；压测时注意 Windows 定时器精度对延迟统计的影响。

---

## 构建与发布

环境：JDK 17+（toolchain 自动匹配）、Gradle（开发使用 9.8.1）。

```bash
gradle build                 # 编译 + 测试 + 全部产物 jar
gradle :netty-server-servlet6:test --tests "com.mingliqiye.web.netty.servlet.WebSocketIntegrationTest"
gradle publishToMavenLocal   # 本地发布
```

`build` 产物（每模块）：
- 主 jar（含 LICENSE/NOTICE 与完整 Specification/Implementation manifest）
- `-sources.jar`
- `-javadoc.jar`（Dokka 2.1.0 javadoc 格式）
- `-kotlindoc.jar`（Dokka HTML 格式）

发布经 `maven-publish` + GPG 签名（`useGpgCmd()`，密钥取环境变量 `SIGNING_KEY_ID`），POM 含 Apache-2.0 许可、开发者与 SCM 信息。全量源码带 KDoc，关键路径有 `log.debug { }` 惰性日志。

---

## 已知限制

以下为本版本**有意未覆盖**或受外部约束的点（其余 Servlet 6.1 / WebSocket 2.2 核心语义均已实现）：

- **HTTP/2 server push**：未实现（无 push 处理器）；h2c 升级 / prior-knowledge / TLS ALPN / 多路复用均已支持
- **JSP 在 fat-jar 内编译**：ECJ 编译类路径仅取 file: URL，Spring Boot 可执行 jar 的嵌套 jar 不在其上；JSP 需展开类路径运行或预编译部署。`getJspConfigDescriptor` 返回 null（不解析 web.xml `<jsp-config>` 的 taglib/property-group），未捆绑 JSTL
- **WebSocket 客户端扩展**：客户端握手不协商 permessage-deflate（服务端支持）；原生端点升级在 Filter 链之前（与 Tomcat `WsFilter` 一致，需 Filter 拦截握手时走 Spring 集成路径）
- **会话集群**：Redis 存储可跨实例共享会话，但各节点惰性恢复、无内存活跃会话的主动复制/事件广播；磁盘存储仅在停机 `close()` 时整体落盘（非实时写盘）
- **容器安全**：轻量 `Realm` 模型（BASIC/FORM + 角色约束 + 程序式登录），非完整 JAAS；复杂授权建议结合应用层（sa-token、Spring Security Filter 链）
- **会话跟踪 SSL 模式**：`SessionTrackingMode.SSL` 须单独使用（规范约束），默认 COOKIE + URL

---

## 许可

Apache License 2.0，见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。
