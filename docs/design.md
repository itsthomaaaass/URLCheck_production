# URL 变更监控系统 · 软件设计文档

> 本项目用于监控 URL 的变化，并在前端以 Timeline 形式展示变化、不可访问与恢复事件。
> 本文档描述**当前已实现**的系统（As-built）。各部分的精确契约见：
> `docs/api.md`（HTTP 接口）、`docs/database.md`（表结构）、`docs/timeline.md`（监控与时间线行为）、
> `docs/ai_context_design.md`（对话记忆）、`docs/knowledge_base.md`（RAG 知识库）。

| 属性 | 内容 |
| --- | --- |
| 文档版本 | v1.0 |
| 文档状态 | 与当前实现一致 |
| 架构模式 | 模块化单体架构（Modular Monolith） |
| 数据库 | MySQL 8.0+ |
| 后端 | Java 21 + Spring Boot |
| 前端 | React + TypeScript + Vite |
| 部署 | Docker + Render Blueprint |

## 1. 项目概述

已实现的功能：

- 用户注册、登录（基于 HTTP 会话的认证）
- URL 的增删改查（每个 URL 属于一个用户，数据按用户隔离）
- 手动检查：对任意已保存 URL 发起一次实时探测
- 定时自动检查：调度器按每个 URL 自己的到期时间驱动
- 内容变化检测：对响应内容计算 SHA-256，与保存的基线哈希比较
- 时间线：记录内容变化、无法访问、恢复事件，带保留策略
- SSRF 防护：防止用户提供的 URL 让后端访问内网
- AI 助手：对话式操作（列出/检查/删除 URL）+ 关于系统本身的问答（RAG 知识库）

## 2. 系统架构

模块化单体：一个 Spring Boot 后端、一个 React 单页应用，共享一个 MySQL 数据库。

```text
                     ┌─────────────────────┐
                     │       Browser       │
                     │  React + TypeScript │
                     └──────────┬──────────┘
                                │  HTTP / JSON（会话 Cookie）
                                ▼
                     ┌─────────────────────┐
                     │   Spring Boot       │
                     │   Controller        │
                     │        ↓            │
                     │   Service           │
                     │   MyBatis ──→ MySQL │
                     └──────────┬──────────┘
                                │  Java HttpClient
                                ▼
                        被监控的外部网站
```

自动监控链路：

```text
Scheduler → 查询到期 URL → UrlChecker（HTTP GET 外部网站）
    → SHA-256 哈希 → 变化检测 → MySQL（状态 + 时间线事件）
```

前端不直接访问被监控的网站，所有对外请求都从后端发出（浏览器 → 本系统后端 → 目标网站）。

## 3. 技术栈

| 模块 | 技术 | 用途 |
| --- | --- | --- |
| 前端 | React + TypeScript + Vite | 单页应用（App.tsx、AiChat.tsx、api.ts） |
| 后端 | Java 21 + Spring Boot | REST API、依赖管理、应用框架 |
| HTTP 客户端 | Java HttpClient | 请求外部 URL |
| 数据库 | MySQL 8.0+ | 数据持久化（MyBatis 访问） |
| 调度 | Spring Scheduler（@Scheduled fixedDelay） | 定时检查 |
| AI 助手 | Spring AI | 对话、工具调用；默认 DeepSeek，任何 OpenAI 兼容接口可替换 |
| 向量检索 | Qdrant + 本地 ONNX 嵌入模型（bge-small-zh-v1.5） | 知识库 RAG |
| 测试 | JUnit 5 + Mockito | 单元与集成测试 |
| 部署 | Docker / Render Blueprint | 后端容器 + 前端静态站 |

## 4. 后端模块结构

```text
com.urlcheck
├── check/       UrlChecker（唯一对外 HTTP）、ContentHasher、ChangeDetector、
│                CheckService（手动检查，只读）
├── monitor/     ScheduledChecker、MonitorRunner、MonitorWriter（唯一写入者）、
│                MonitorMapper、CheckProperties、UrlCheckRequestedListener
├── url/         MonitoredUrlController、MonitoredUrlService、MonitoredUrlMapper
├── timeline/    TimelineController、TimelineService、TimelineMapper
├── user/        UserController、AuthController、UserService、UserPasswordHasher
├── security/    SsrfGuard
├── web/         ApiExceptionHandler、SessionUser、CorsConfig
└── ai/          agent（AiAgent）、controller、conversation、message、memory、
                 tool（MonitoredUrlTools）、knowledge（摄入/检索/嵌入）、deletion
```

一条保持职责边界的重要规则：`com.urlcheck.check` 不得依赖 `com.urlcheck.monitor` 或
`com.urlcheck.timeline`。手动检查路径只能看到只读的 `MonitoredUrlMapper`，从结构上保证它无法写监控状态。

## 5. URL 管理

端点：`GET /api/urls`、`POST /api/urls`、`PUT /api/urls/{id}`、`DELETE /api/urls/{id}`。

校验规则：

- `name` 必填，1-100 字符
- `url` 必填，必须以 `http://` 或 `https://` 开头
- `description` 可选，0-1000 字符，省略时存为空字符串
- 保存时做一次不解析 DNS 的 SSRF 快速检查：`localhost`、`.local`、`.internal`、
  内网 IP 字面量等直接被拒绝

创建 URL 后会发布 `UrlCheckRequestedEvent`，在创建事务提交后异步触发一次立即检查
（受 `CHECK_ON_CREATE` 控制），因此新 URL 几秒内就有 FIRST_CHECK 事件，不必等下一个调度周期。

把 `url` 修改为**另一个地址**时，旧的时间线与基线描述的是旧页面：系统会删除全部时间线事件、
重置监控状态（哈希、状态、change_count 等），并安排一次新的 FIRST_CHECK。只改名称或备注不动历史。

## 6. URL 检查（UrlChecker）

`UrlChecker` 是唯一发起对外 HTTP 请求的组件，手动检查和定时检查共用它，返回统一的
`ProbeResult`（状态、HTTP 状态码、响应耗时、最终 URL、错误类型、内容哈希）。

- 一次 HTTP GET；User-Agent 为 `UrlCheckBot/0.1`
- 连接超时 5 秒，整个请求超时 10 秒
- 只允许 http/https 协议与 80/443 端口
- 重定向（301/302/303/307/308）由代码手动跟随，最多 5 次；**每一跳都重新做 SSRF 检查**
  ——不让 HttpClient 自动跟随，防止公网主机把后端重定向进内网
- 2xx/3xx 判定为 UP，4xx/5xx 或网络错误判定为 DOWN
- 响应体以流式方式计算 SHA-256（8 KB 缓冲、恒定内存），最多读取 2 MiB

错误类型（`errorType`）枚举：`HTTP_ERROR`（4xx/5xx）、`TIMEOUT`、`DNS_ERROR`、
`SSL_ERROR`、`CONNECTION_REFUSED`、`BLOCKED_TARGET`（内网地址或非 80/443 端口，从未真正发出请求）、
`INVALID_URL`、`TOO_MANY_REDIRECTS`、`IO_ERROR`、`INTERRUPTED`。

## 7. 内容变化检测（ChangeDetector）

通过比较 SHA-256 内容哈希判断变化：每次成功检查（2xx/3xx）计算响应体的哈希，
与保存的基线哈希比较。`ChangeDetector` 是无状态的纯规则，手动检查与定时检查共用，
保证两边的结论永远一致：

| 上次状态 | 本次探测 | 产生的事件 | changed |
| --- | --- | --- | --- |
| 没有保存的哈希 | 成功 | FIRST_CHECK | true |
| 哈希相同 | 成功 | 无事件 | false |
| 哈希不同 | 成功 | CONTENT_CHANGED | true |
| 任意 | 4xx/5xx 或网络错误 | UNAVAILABLE（每次故障只记一次） | null |
| DOWN | 成功 | RECOVERED | true |

要点：

- 检查失败**不是**内容变化；故障期间保留上一次成功的内容哈希，恢复后仍有基线可比较
- 一次故障只记录一个事件：站点保持 DOWN 期间，后续失败只刷新 last_http_status /
  last_error_type，不追加事件
- 恢复永远是独立事件，即使恢复时内容与之前完全相同

已知限制：动态页面（时间戳、随机数、轮播广告）会几乎每次都触发 CONTENT_CHANGED；
2 MiB 之后的内容变化不可见；403 记录为 UNAVAILABLE，含义是「系统无法读取该页面」，
不代表网站宕机。HTML 归一化、CSS 选择器监控等属于未来功能。

## 8. 定时调度

`ScheduledChecker` 每 `CHECK_SCHEDULER_TICK_SECONDS`（默认 15 秒，fixedDelay，不会重叠）运行一次，
查询 `next_check_at` 到期的 URL，每次最多 `CHECK_BATCH_SIZE`（默认 20）个。
每个 URL 的检查间隔由它自己的 `check_interval_seconds` 决定，默认 `CHECK_INTERVAL_SECONDS`
（900 秒）；调度器只决定「多久问一次数据库」。

一次定时检查的流程：

1. **Claim**：带条件的 UPDATE —— 只有 `next_check_at` 为空或已到期的行才被推进，
   且只有更新成功（影响 1 行）的调用者继续。两个后端实例（或轮询器与创建时检查）
   因此不会把同一次变化记录两遍。时间计算全部在数据库时钟上完成
2. **探测**：`UrlChecker` 发出 GET 并计算哈希
3. **判定**：`ChangeDetector` 根据基线（保存的哈希、上次状态）得出结论
4. **落库**：`MonitorWriter` 在**一个事务**里完成——必要时插入时间线事件、
   按保留策略清理旧事件、更新 URL 的监控状态（content_hash、last_status、
   last_http_status、last_error_type、last_checked_at、change_count）

检查因意外异常失败时，把该 URL 的 next_check_at 拉回近期（+300 秒），避免它一整个周期不被检查。

并发模型：对外检查只跑在一个线程（`checkExecutor`，队列 200，CallerRunsPolicy）——
一个慢的目标不会让后端同时打开几十个连接，队列满时由调用方线程承担背压。

## 9. 时间线

事件类型：

| changeType | 含义 | 锚点 |
| --- | --- | --- |
| FIRST_CHECK | 该 URL 的第一次检查，记录基线哈希 | 是 |
| CONTENT_CHANGED | 检查成功且内容哈希与保存的不同 | 否 |
| RECOVERED | 上次状态为 DOWN，本次检查成功 | 否 |
| UNAVAILABLE | 没有得到可用响应（4xx/5xx 或网络错误），且此前不是 DOWN | 否 |

「没有变化」不产生任何记录。接口返回的 status 由 changeType 推导（UNAVAILABLE → DOWN，其余 → UP），不单独存储。

保留策略：每个 URL 最多保留 `CHANGE_RETENTION_PER_URL`（默认 10）条事件——锚点
（change_no = 1，无论类型）永不删除，加上最新的 9 条；新事件插入时在同一事务里清理
最旧的非锚点行。`monitored_url.change_count` 统计全部历史事件（含被清理的），
接口因此能返回 totalCount（共 N 条）。

`checks` 表（每次检查一行）在结构上存在但有意不写：系统只保存「值得关注的事件」，
`changes.check_id` 永远为 NULL。

## 10. 一个写入者，两个只读者

时间线只有一个写入者（定时检查器），两个只读者（手动检查、时间线接口）：

```text
                     UrlChecker.probe()
              （网络、SSRF 检查、超时、SHA-256）
                            │
              ┌─────────────┴─────────────┐
              ▼                           ▼
   CheckService（手动检查）        MonitorRunner（定时检查）
   读基线 → 判定 → 返回，          Claim → 探测 → MonitorWriter
   不写任何东西                    （单事务：事件 + 清理 + 状态）
              │                           │
              ▼                           ▼
   POST /api/urls/{id}/check      monitored_url 状态 + changes 行
```

因此手动检查永远不会改变 URL 列表里的 lastStatus/lastCheckedAt，也不会产生时间线事件。

## 11. 用户与认证

- 认证基于 HTTP 会话：登录成功后在服务端会话保存用户 id，浏览器通过 JSESSIONID Cookie
  维持会话。Cookie 默认 HttpOnly；SameSite（默认 lax）与 Secure（默认 false，生产应设为 true）
  由环境变量控制
- 注册（`POST /api/users`）：username 1-50 字符（任意语言的字母、数字、组合字符及 `_`、`-`、`.`）；
  password 仅限英文字母、数字、下划线；用户名重复返回 409。注册不自动登录
- 密码存储：PBKDF2-SHA256（PBKDF2WithHmacSHA256，210,000 次迭代、16 字节随机盐、
  256 位密钥），编码为 `pbkdf2-sha256$迭代次数$盐$哈希`，校验用恒定时间比较；
  密码字段从不序列化返回
- 登录 `POST /api/auth/login`、登出 `POST /api/auth/logout`（销毁会话）、
  `GET /api/auth/me` 返回当前用户或 null
- 数据隔离：所有资源接口只操作当前登录用户自己的数据；请求别人的 URL id 与请求不存在的
  URL 一样返回 404，不泄露资源是否存在

## 12. SSRF 防护（SsrfGuard）

被监控的 URL 由用户提供，必须防止后端被当作内网代理。两层防护：

- **请求时**（每次检查、每一跳重定向之前）：解析主机名，检查它解析出的**每一个** IP 地址。
  环回、链路本地、站点本地（RFC 1918 内网段）、组播、IPv6 ULA/文档段、NAT64 内嵌 IPv4、
  IPv4 映射的 IPv6（先解包再检查）等一律拒绝，返回 BLOCKED_TARGET，请求从不真正发出
- **保存时**：不解析 DNS 的快速检查，直接拒绝 `localhost`、`.localhost`、`.local`、
  `.internal` 和内网 IP 字面量，保证保存操作足够快；解析后指向内网的域名仍在请求时被拦截

策略刻意保守：任何不是全球可路由公网地址的目标都会被拒绝。

## 13. AI 助手

默认开启：配置了 API Key 就暴露 `/api/ai` 下的端点，没有 LLM 密钥的部署行为
与没有该功能完全一致。`AI_ENABLED=false` 即使有密钥也保持关闭；
`AI_ENABLED=true` 但缺少密钥则在启动时报错。

组成部分：

- **Agent**（`AiAgent`）：系统提示词 + 模型调用。上下文由三部分组成：
  - 工具：`listUrls`、`checkUrls`、`deleteUrl`（绑定当前用户）+ `searchKnowledge`（知识库）
  - 对话记忆：MySQL 中保存的对话历史，每次请求回放最近 `AI_MEMORY_MAX_MESSAGES`（默认 20）条
  - 工具结果按需获取，模型自行决定调用哪个工具
- **删除的两步确认**：模型请求删除时不会直接删除；后端把待删 URL 记录在会话里并返回
  confirmation token，用户在前端确认后提交 token 才真正删除。第二步不再调用模型，
  结果确定且无法被模型或客户端输入左右
- **对话历史**：conversation / chat_message 表是唯一记录；每个用户最多保留
  `AI_MAX_CONVERSATIONS`（默认 5）个最近使用的对话，超出时删除最久未用的
- **知识库（RAG）**：回答「系统如何工作」类问题。文档是随 jar 发布的 Markdown 文件
  （`backend/src/main/resources/ai/knowledge/`，当前为中文），启动时由本地 ONNX 嵌入模型
  （bge-small-zh-v1.5，512 维）向量化后存入 Qdrant；`ai_knowledge_document` 表保存每份
  文档的 SHA-256 台账，重启只嵌入有变化的文档。检索通过 `searchKnowledge` 工具按需触发
  （top-k 4、余弦相似度阈值 0.5）。操作细节见 `docs/knowledge_base.md`
- 工具结果中的文本（URL 名称、备注、网页内容）一律视为数据而非指令，防止恶意页面变成提示词注入

## 14. 配置项

全部通过环境变量注入，换部署环境不需要重新构建：

| 分组 | 环境变量（默认值） |
| --- | --- |
| 监控 | `CHECK_SCHEDULER_ENABLED`（true）、`CHECK_SCHEDULER_TICK_SECONDS`（15）、`CHECK_SCHEDULER_INITIAL_DELAY_SECONDS`（20）、`CHECK_INTERVAL_SECONDS`（900）、`CHECK_BATCH_SIZE`（20）、`CHANGE_RETENTION_PER_URL`（10）、`CHECK_ON_CREATE`（true） |
| AI 助手 | `AI_ENABLED`（未设置即跟随 `AI_API_KEY`）、`AI_API_KEY`、`AI_BASE_URL`（https://api.deepseek.com）、`AI_MODEL`（deepseek-flash）、`AI_TEMPERATURE`（0.2）、`AI_MAX_TOKENS`（1024）、`AI_MAX_RETRIES`（2）、`AI_TIMEOUT_SECONDS`（60）、`AI_MAX_URLS_PER_CHECK`（10）、`AI_MEMORY_MAX_MESSAGES`（20）、`AI_MAX_CONVERSATIONS`（5） |
| 知识库 | `AI_KNOWLEDGE_ENABLED`（false）、`AI_KNOWLEDGE_INGEST_ON_START`（true）、`QDRANT_URL`、`QDRANT_API_KEY`、`QDRANT_COLLECTION`（urlcheck_knowledge）、`AI_KNOWLEDGE_TOP_K`（4）、`AI_KNOWLEDGE_SIMILARITY_THRESHOLD`（0.5）、`AI_KNOWLEDGE_MAX_CHUNK_CHARS`（800） |
| 会话与跨域 | `SESSION_COOKIE_SAME_SITE`（lax）、`SESSION_COOKIE_SECURE`（false）、`CORS_ALLOWED_ORIGINS`（空 = 不写 CORS 头） |
| 其他 | `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`、`SERVER_PORT`（或 `PORT`） |

## 15. 数据库设计摘要

| 表 | 用途 |
| --- | --- |
| users | 用户（username 唯一，PBKDF2 密码哈希） |
| monitored_url | 被监控的 URL + 当前监控状态（哈希基线、状态、下次检查时间、事件计数、间隔覆盖） |
| checks | 预留的「每次检查一行」表，当前设计有意不写 |
| changes | 时间线事件（事件类型、新旧哈希、附加细节、每 URL 递增的 change_no） |
| conversation / chat_message | AI 助手的对话历史 |
| ai_knowledge_document | 知识库文档的 SHA-256 台账 |

完整结构（列、索引、外键、迁移清单）见 `docs/database.md`。

## 16. API 摘要

```text
POST   /api/users                    注册
POST   /api/auth/login               登录（建立会话）
POST   /api/auth/logout              退出登录
GET    /api/auth/me                  当前用户或 null
GET    /api/urls                     当前用户的 URL 列表
POST   /api/urls                     创建 URL
PUT    /api/urls/{id}                修改 URL
DELETE /api/urls/{id}                删除 URL
POST   /api/urls/{id}/check          手动检查（只读）
GET    /api/urls/{id}/timeline       时间线
POST   /api/ai/chat                  AI 助手对话（可选）
POST/GET /api/ai/conversations       对话历史（可选）
GET/DELETE /api/ai/conversations/{id}
```

完整的请求/响应契约、字段规则与错误语义见 `docs/api.md`。

## 17. 前端

React + TypeScript + Vite 单页应用：

- `App.tsx`：登录/注册、URL 列表（状态徽章、手动检查按钮）、时间线渲染
- `AiChat.tsx`：AI 助手聊天组件
- `api.ts`：带类型的 API 客户端（credentials: include 携带会话 Cookie）

开发时 Vite 把 `/api/*` 代理到后端；部署为静态站时通过 `VITE_API_BASE_URL` 指向后端，
并用 `CORS_ALLOWED_ORIGINS` 允许跨域。

## 18. 部署

- 后端：Docker 镜像（`backend/Dockerfile`），`render.yaml`（Render Blueprint）定义一个
  Docker web 服务；健康检查指向 `/actuator/health`（含数据源检查）
- 前端：静态站，构建产物为 `dist/`
- 数据库迁移：`database/*.sql` 按文件名顺序执行（脚本幂等，可重复运行）
- 嵌入模型打包在 jar 内，知识库无需任何额外的外部服务或推理 API

## 19. 测试

- 核心规则单元测试：`ChangeDetectorTest`（判定表）、`ContentHasherTest`（哈希与截断）、
  `UrlCheckerTest`（错误分类、重定向、SSRF）、`SsrfGuardTest`（39 个地址用例）、
  `MonitorWriterTest`（事务内事件与清理）、`TimelineServiceTest`、用户与密码哈希测试
- 架构性质测试：`ManualCheckIsReadOnlyTest`（手动检查不写库）
- AI 侧：`AiAgentIntegrationTest` 在无密钥环境跳过；知识库测试
  （chunker、loader、摄入、检索、工具、ONNX 模型）全部离线运行，不需要网络或 Qdrant
- 前端：构建时 `tsc --noEmit` 做类型检查，暂无测试框架

## 20. 已知限制

- 动态页面（时间戳/随机数）会频繁触发 CONTENT_CHANGED
- 超过 2 MiB 的响应体只哈希前 2 MiB
- 没有推送通知，前端只在调用接口时得知新事件
- 单个 URL 的检查间隔覆盖值目前只能在 SQL 里设置（无 UI）
- checks 表预留未使用
- 每个 URL 的时间线最多保留 10 条（锚点 + 9 条）
- 知识库默认关闭，需要显式配置；AI 助手配置了 API Key 即默认开启
- 403 等反爬响应记录为 UNAVAILABLE（「无法读取」），不代表网站宕机

## 21. 后续规划

- 变化检测：HTML 归一化、CSS 选择器 / 元素级监控、文本 Diff、忽略规则
- 通知：Email、Discord、Telegram、Push
- 监控：JavaScript 渲染（Playwright）、页面截图、视觉 Diff
- 知识库：混合检索、重排序、元数据过滤
- 其他：per-URL 间隔的界面、多实例部署（Claim 机制已为此做好准备）
