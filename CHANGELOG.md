# Changelog

本文件记录 `netty-web-servlet` 各版本的变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [0.0.4] - 2026-11-21

维护与示例增强：**Server 响应头 / 版本元数据**、**弃用 API 全量迁移**（编译零 deprecation 警告）、**Main.kt 示例服务器重写并支持 HTTP Range 多线程下载**。此前未单独提交/打标签的 0.0.3 里程碑内容随本版本一并发布（见下节）。自动化测试 **141 个**全绿（5 模块，0 失败 / 0 错误 / 0 跳过），**无破坏性 API 变更**。

### 新增功能

- **Server 响应头与版本元数据**：新增 `HeaderUtils`；`Responses.write` 与 `ResponseStream.start` 统一附加 `Server` 头（缺省 `netty-server/<version>`，可用 `serverName` 覆盖）。版本号来自构建期展开的资源 `META-INF/attributes/com.mingliqiye.web.netty.version`（`${version}` ← `gradle.properties`），`NettyWebServer.serverName` 复用同一命名。
- **示例服务器（Main.kt）重写**：canonicalize 安全路径解析（防目录穿越、目录 → index.html）、HTML 错误页、`isWritable` 背压 + `copiedBuffer` 避免 ByteBuf 复用竞态、shutdown hook 优雅停机、`server-config.json` 外部配置。
- **HTTP Range 多线程下载（示例）**：`Accept-Ranges: bytes`；单段 `bytes=start-end` / `start-` / `-N` 后缀范围 → **206** + `Content-Range`；越界 → **416** + `Content-Range: bytes */total`；多段范围回退 200 全文；HEAD 仅回头部；`RandomAccessFile.seek` 定位，支持多客户端并发分段下载。

### 变更

- **7 处弃用 API 全量迁移**（Netty 4.2 / Jakarta WebSocket 2.2）：
  - `HttpContentCompressor` 弃用构造 → `StandardCompressionOptions.gzip/deflate`；
  - `NioEventLoopGroup` → `MultiThreadIoEventLoopGroup` + `NioIoHandler.newFactory()`；
  - `JdkSslContext` 弃用构造 → 显式 `ApplicationProtocolConfig.DISABLED` + `IdentityCipherSuiteFilter`；
  - `PerMessageDeflateServerExtensionHandshaker()` → `(0)`（默认窗口上限）；
  - `SendResult(Throwable)` → `SendResult(Session, Throwable)`；
  - 测试 `SelfSignedCertificate` → `netty-pkitesting` `CertificateBuilder` / `X509Bundle`（自签证书须 `setIsCertificateAuthority(true)` 以满足 Netty 根 CA 校验）；
  - `WebSocketClientCompressionHandler.INSTANCE` → `WebSocketClientCompressionHandler(0)`。
- 新增测试依赖 `io.netty:netty-pkitesting:4.2.19.Final`。
- 全项目 clean 重编译（main + test）**零 Kotlin deprecation 警告**。

### 文档

- README 版本号与依赖坐标示例更新至 `0.0.4`。

---

## [0.0.3] - 2026-11-20

> 注：0.0.3 未单独提交/打标签，其内容随 [0.0.4] 一并发布。

补齐 0.0.2 README 列出的**全部 9 项「未实现」**，新增 **2 个可选模块**（Redis 会话存储、JSP 引擎）。自动化测试达 **141 个**（5 模块，0 失败 / 0 错误 / 0 跳过）。配置实体均为带默认值的新增字段，**无破坏性 API 变更**。

### 新增模块

- **`netty-server-session-redis`**（`com.mingliqiye.web.netty.session`）：基于 Lettuce 7.5.2.RELEASE 的 `RedisSessionStore`，跨实例共享会话；`SET ... EX maxInactive` 随会话不活动超时自动过期，Java 序列化 `StoredSession`，可经 URI 或 `RedisClient` 构造、自定义 key 前缀（缺省 `netty:session:`）。
- **`netty-server-jsp`**（`com.mingliqiye.web.netty.jsp`）：仅嵌入 Tomcat Jasper 11.0.27 编译/执行引擎（ECJ + EL），不引入 Tomcat 连接器/容器。`JasperJspEngine` 经 ServiceLoader 自动挂载，支持 `*.jsp`/`*.jspx` 映射与 `addJspFile` 预编译。

### 新增功能

**HTTP 服务器内核（netty-server）**
- **TLS/SSL**：`NettyServerConfig.sslContext` 非 null 即启用 HTTPS（管线最前端挂 `SslHandler`）；容器侧 `isSecure()`/`getScheme()`/`getRequestURL()` 随之变化。
- **HTTP/2**：`http2Enabled` 开启后，明文支持 **h2c 升级**与 **prior-knowledge**，TLS 支持 **ALPN** 协商（`Http2Support.alpnConfig()`）；`Http2MultiplexHandler` 多路复用，每流经 `Http2StreamFrameToHttpObjectCodec` 复用同一套 HTTP/1.1 Servlet 业务管线；未协商到 h2 自动回退 HTTP/1.1。
- **响应压缩**：`ConfigurableCompressor`（继承 Netty `HttpContentCompressor`）按 `Accept-Encoding` 协商 **gzip/deflate**，支持 Content-Type 白名单、`minResponseSize` 门槛、`excludedUserAgents` 排除、压缩级别配置。
- **Trailer fields 捕获**：`RequestBody` 收集 `LastHttpContent.trailingHeaders()`，`isFinished` 标记 body 结束。

**Servlet 容器（netty-server-servlet6）**
- **容器管理的安全**：`Realm`/`InMemoryRealm` 认证域 + `LoginConfig`；**BASIC**（`WWW-Authenticate` 挑战）与 **FORM**（`/j_security_check` + `j_username`/`j_password` + 原始请求暂存回跳）认证；程序式 `login`/`authenticate`/`logout`；`getUserPrincipal`/`isUserInRole`/`getRemoteUser`/`getAuthType`；`declareRoles`/`setServletSecurity`（按 HTTP 方法约束、PERMIT/DENY）/`setRunAsRole` 全部生效；登录成功 `changeSessionId()` 防会话固定。
- **静态资源**：`DefaultServlet`（`docRoot` 非 null 自动注册映射 `/`），支持欢迎文件、目录列表（`listings`）、MIME、`Accept-Ranges`/Last-Modified、`If-Modified-Since`→304、单段 `Range`→206/416、`..` 越界→400；`getResource`/`getResourceAsStream`/`getRealPath`/`getResourcePaths` 支持 docRoot 文件系统解析（类路径回退）。
- **会话 URL 重写**：`encodeURL`/`encodeRedirectURL` 按生效跟踪模式追加 `;jsessionid`；请求侧解析并剥离路径参数后路由；`SessionTrackingMode` 支持 **COOKIE + URL**（SSL 须单独使用，init 后锁定）。
- **会话存储 SPI**：`SessionStore`（load/save/remove/loadAll/close）+ 可序列化 `StoredSession`；内置 `MemorySessionStore`（默认）与 `DiskSessionStore`（停机落盘、启动恢复）；`SessionManager` 在创建/属性变更/换 ID/访问时持久化，命中缺失惰性恢复。
- **WebSocket permessage-deflate**：服务端经 `PerMessageDeflateServerExtensionHandshaker` 协商压缩扩展，`getInstalledExtensions()` 报告。
- **WebSocket 客户端**：`connectToServer` 四形态（注解实例/类、`Endpoint`/类 + `ClientEndpointConfig`）全部实现，支持 `ws`/`wss`（TLS）与 `@ClientEndpoint`。
- **JSP 挂载**：新增 `JspEngine` SPI，容器经 ServiceLoader 发现引擎并自动注册 `*.jsp`/`*.jspx`；`ServletContext.addJspFile` 委托引擎注册命名 JSP Servlet。
- **Trailer fields 暴露**：`getTrailerFields()`/`isTrailerFieldsReady()` 在 body 结束后返回真实 trailer 表。

**Spring Boot Starter（starter4）**
- 消费标准 **`server.ssl.*`**（keystore/truststore、SSL bundle、`key-alias`、`client-auth` WANT/NEED、enabled-protocols/ciphers），`server.http2.enabled` 时自动加 ALPN。
- 消费 **`server.compression.*`**（enabled/mime-types/min-response-size/excluded-user-agents）与 **`server.http2.enabled`**。
- 新增 **`server.netty.session-store-path`** 启用磁盘会话持久化；`@ConditionalOnMissingBean(SessionStore)` 默认存储 Bean，用户自定义（如 Redis）时让位。

### 行为改进（规范对齐）

- `encodeURL`/`encodeRedirectURL` 由原样返回改为按 URL 跟踪模式重写。
- `getResource`/`getRealPath`/`getResourcePaths` 由仅类路径/返回 null 改为 docRoot 文件系统优先。
- `getTrailerFields` 由返回空表改为返回真实 trailer。
- `isSecure`/`getScheme`/`getRequestURL` 由恒 false/http 改为随 TLS 变化。
- `addJspFile` 与 WebSocket `connectToServer` 由抛 `UnsupportedOperationException` 改为完整实现。

### 文档

- **README 全面重写**：5 模块结构与依赖图、各功能章节与配置表（NettyServerConfig/ServletServerConfig/`server.netty.*`）、141 测试明细表、快速开始（含 HTTPS/HTTP2/压缩/会话存储 YAML 示例）。
- 「未实现」清单改为真实的「**已知限制**」，并澄清原生 WebSocket 端点升级发生在 Filter 链之前与 Tomcat `WsFilter` 语义一致（非缺陷）。

### 已知限制

- **HTTP/2 server push** 未实现（h2c/prior-knowledge/ALPN/多路复用已支持）。
- **fat-jar 内 JSP 编译**不支持（ECJ 类路径仅取 file: URL，嵌套 jar 不在其上）；`getJspConfigDescriptor` 返回 null，未捆绑 JSTL。
- **WebSocket 客户端**握手不协商 permessage-deflate（服务端支持）。
- **会话集群**：Redis 可共享会话但各节点惰性恢复、无内存活跃会话主动复制/事件广播；磁盘存储仅停机 `close()` 时整体落盘。
- **容器安全**为轻量 `Realm` 模型（非完整 JAAS），复杂授权建议结合应用层。

---

## [0.0.2] - 2026-11-20

初始发布。

- **Netty 4.2 + Kotlin 协程 HTTP 服务器内核**：聚合/流式双管线、挂起式协程工作池（信号量 + 有界 Channel 双重限流、503/500 降级）、流式请求体 autoRead 背压、`abandon()` 排水复用连接、`Expect: 100-continue`、chunked 流式响应、传输层自动选择（io_uring > epoll > kqueue > NIO）、同步绑定与幂等优雅停机。
- **Jakarta Servlet 6.1 容器**：规范优先级路由、Servlet/Filter 动态注册、五种分发（REQUEST/FORWARD/INCLUDE/ERROR/ASYNC）、错误页体系、HttpSession（Cookie 跟踪、changeSessionId、监听器）、异步 Servlet、multipart（每 Servlet 限额）、全部 8 类监听器、Servlet 6.x 新 API（getHttpServletMapping/getRequestId/getServletConnection 等）。
- **Jakarta WebSocket 2.2 服务端容器**：原生 `@ServerEndpoint`（路径模板 + `@PathParam`）与 Spring `@EnableWebSocket` 两条升级路径、编解码器、分片重组、ping/pong、Close 状态机、空闲检查。
- **Spring Boot 4 / Framework 7 嵌入式服务器 Starter**：替代 Tomcat/Jetty/Undertow，标准 `server.*` 属性、ServletContextInitializer 全量应用、错误页注册、优雅停机、`server.netty.*` 旋钮。

[0.0.4]: https://github.com/minglipro/netty-web-servlet/releases/tag/0.0.4
[0.0.3]: https://github.com/minglipro/netty-web-servlet/compare/0.0.2...0.0.4
[0.0.2]: https://github.com/minglipro/netty-web-servlet/releases/tag/0.0.2