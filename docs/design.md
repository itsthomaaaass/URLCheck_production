# URL 变更监控系统 · 软件设计文档

> 本项目用于监控 URL 的变化，并在前端以 Timeline 形式展示变化、不可访问与恢复事件。

| 属性 | 内容 |
| --- | --- |
| 文档版本 | v0.1 |
| 文档状态 | Draft / 初稿 |
| 项目阶段 | MVP |
| 架构模式 | 模块化单体架构（Modular Monolith） |
| 数据库 | MySQL 8.0+ |
| 后端 | Java + Spring Boot |
| 前端 | React + TypeScript |

## 1. 项目概述

### 1.1 项目背景

本项目旨在开发一个轻量级 URL 监控与网页变更检测系统。

用户可以向系统中添加 URL，并为每个 URL 设置一个自定义名称。系统会按照用户设定的时间间隔定期访问这些 URL，记录访问结果，并通过比较当前网页内容与历史内容来判断网页是否发生变化。

当检测到变化、网页无法访问或网页从不可访问状态恢复时，系统会记录相应事件，并在前端以时间线（Timeline）的形式展示。

### 1.2 项目目标

MVP 版本需要实现以下核心功能：

- 用户可以添加 URL。
- 用户可以为 URL 设置自定义名称。
- 用户可以查看、修改和删除已保存的 URL。
- 用户可以手动检查某个 URL。
- 系统可以自动定期检查 URL。
- 系统可以记录每次检查的结果。
- 系统可以判断网页内容是否发生变化。
- 系统可以记录网页变化事件。
- 系统可以记录网页不可访问以及恢复事件。
- 用户可以通过 Timeline 查看 URL 的历史事件。

## 2. MVP 范围

### 2.1 MVP 包含功能

#### URL 管理

- 添加 URL
- 修改 URL 名称
- 修改 URL 地址
- 删除 URL
- 查看 URL 列表
- 查看单个 URL 的详细信息

#### URL 监控

- 手动检查
- 自动检查
- HTTP 状态检测
- 请求耗时记录
- 网络错误记录
- Response Content Hash 计算

#### 变化检测

- 比较当前 Content Hash 与历史 Hash
- 判断网页内容是否发生变化
- 记录变化时间
- 记录变化前后的 Hash

#### Timeline

Timeline 至少支持显示：

- Content Changed
- Website Unavailable
- Website Recovered

## 3. 非 MVP 功能

以下功能不属于第一版实现范围：

- 用户注册
- OAuth 登录
- 多用户权限管理
- Email 通知
- Discord / Telegram 通知
- Push Notification
- JavaScript 动态网页渲染
- 页面截图
- Screenshot Diff
- CSS Selector / HTML Element 级别监控
- 高级文本 Diff
- AI 自动总结变化内容
- Redis
- Kafka / RabbitMQ
- Kubernetes
- 微服务架构

这些功能可以在后续版本中加入。

## 4. 系统总体架构

### 4.1 架构模式

系统采用**模块化单体架构（Modular Monolith）**：

- 整个 Backend 是一个 Spring Boot 应用，但内部按照不同职责划分为多个模块。
- 初期不采用微服务架构。

原因：

- 项目规模较小。
- 用户数量预计有限。
- 模块之间存在较强业务关联。
- 单体应用开发和部署更加简单。
- 模块化设计已经能够保证良好的代码结构。
- 如果未来规模扩大，可以再将特定模块拆分为独立服务。

## 5. 系统架构图

整体数据流如下：

```text
                     ┌─────────────────────┐
                     │       Browser       │
                     │  React + TypeScript │
                     └──────────┬──────────┘
                                │  HTTP / JSON
                                ▼
                     ┌─────────────────────┐
                     │   Spring Boot       │
                     │   ┌───────────────┐ │
                     │   │  Controller   │ │
                     │   └──────┬────────┘ │
                     │          ▼          │
                     │   ┌───────────────┐ │
                     │   │   Service     │ │
                     │   └──────┬────────┘ │
                     │          ▼          │
                     │   ┌───────────────┐ │
                     │   │ URL Fetcher   │ │
                     │   └──────┬────────┘ │
                     │          ▼          │
                     │   ┌───────────────┐ │
                     │   │ Repository /  │ │
                     │   │   MyBatis     │ │
                     │   └──────┬────────┘ │
                     └──────────┼──────────┘
                                │
                                ▼
                     ┌─────────────────────┐
                     │        MySQL        │
                     │  URLs / Checks      │
                     │  Changes / Users    │
                     └─────────────────────┘
```

调度链路（自动检查）：

```text
Scheduler → Check Service → URL Fetcher → External Website
    → Change Detection → MySQL → Timeline
```

## 6. 技术栈

| 模块 | 技术 | 用途 |
| --- | --- | --- |
| 前端 | React | 用户界面 |
| 前端语言 | TypeScript | 类型安全 |
| 前端构建 | Vite | 开发及生产构建 |
| UI 样式 | CSS / Tailwind CSS | 页面样式 |
| 后端语言 | Java 21 | 后端开发 |
| 后端框架 | Spring Boot | REST API、依赖管理、应用框架 |
| HTTP Client | Java HttpClient | 请求外部 URL |
| 数据库 | MySQL 8.0+ | 数据持久化 |
| Database Access | MyBatis | SQL 与 Java 对象映射 |
| JSON | Jackson | JSON 序列化/反序列化 |
| Scheduler | Spring Scheduler | 定时任务 |
| Backend Build | Maven | 项目构建和依赖管理 |
| Backend Test | JUnit 5 + Mockito | 单元测试 |
| Frontend Test | Vitest | 前端测试 |
| Version Control | Git | 版本控制 |
| Remote Repository | GitHub | 代码托管 |
| Container | Docker / Docker Compose | 本地环境和后续部署 |

## 7. 为什么选择 MySQL

本项目使用 **MySQL 8.0+**，而不是 PostgreSQL。主要原因：

- 项目的数据结构属于典型关系型数据。
- URL、Check、Change 等实体之间存在明确关系。
- 本项目不需要 PostgreSQL 特有的复杂功能。
- MySQL 足以满足 MVP 以及相当规模的后续扩展。
- MySQL 是常见的生产环境关系型数据库。
- 使用 MyBatis 可以让 SQL、表结构和 Java 数据模型之间的关系更加直观。

因此，本项目的数据库层设计将以 MySQL 为标准。

## 8. Backend 模块设计

```text
backend/
└── src/
    └── main/
        ├── java/
        │   └── com/example/urlmonitor/
        │       ├── controller/
        │       ├── service/
        │       ├── client/
        │       ├── scheduler/
        │       ├── repository/
        │       ├── model/
        │       ├── exception/
        │       └── config/
        └── resources/
            ├── mapper/
            └── application.yml
```

## 9. Controller Layer

Controller 是 Backend 与 Frontend 之间的接口。

主要职责：

- 接收 HTTP Request
- 验证基本请求参数
- 调用 Service
- 返回 HTTP Response
- 处理 HTTP 层面的错误

Controller 不负责：

- SQL / 数据库操作
- URL 检查
- Hash 计算
- Change Detection

### 9.1 URLController

```text
GET    /api/urls
POST   /api/urls
GET    /api/urls/{id}
PUT    /api/urls/{id}
DELETE /api/urls/{id}
```

### 9.2 CheckController

```text
POST /api/urls/{id}/check
```

用于用户手动触发 URL 检查。

### 9.3 TimelineController

```text
GET /api/urls/{id}/timeline
```

返回指定 URL 的历史事件。

## 10. Service Layer

Service Layer 是系统的核心业务逻辑层，主要模块：

```text
service/
├── UrlService
├── CheckService
├── ChangeDetectionService
└── TimelineService
```

### 10.1 UrlService

负责 URL 的生命周期管理：

```java
createUrl()
getUrl()
getAllUrls()
updateUrl()
deleteUrl()
```

### 10.2 CheckService

负责一次完整的 URL Check：

```java
checkUrl()
processCheckResult()
saveCheckResult()
updateNextCheck()
```

基本流程：

```text
CheckService
    │
    ▼
URL Fetcher
    │
    ▼
FetchResult
    │
    ├── HTTP Status
    ├── Response Body
    ├── Response Time
    ├── Final URL
    └── Error
    │
    ▼
ChangeDetectionService
    │
    ▼
Repository
    │
    ▼
MySQL
```

### 10.3 ChangeDetectionService

负责判断网页内容是否发生变化，主要流程：

```text
Current Response
    │
    ▼
Calculate SHA-256
    │
    ▼
Current Hash
    │
    ▼
Previous Hash
    │
    ▼
  Compare
   /    \
Same  Different
 │        │
 ▼        ▼
No Change  Create Change
```

### 10.4 TimelineService

负责从数据库中的历史数据生成 Timeline。例如：

```text
2026-09-07  ● Content Changed
2026-09-01  ● Website Unavailable
2026-08-31  ● Website Recovered
```

## 11. URL Fetcher

URL Fetcher 是一个独立模块：

```text
client/
└── UrlFetcher.java
```

它负责：

- 创建 HTTP Request
- 发送 Request
- 处理 Redirect
- 获取 HTTP Status
- 获取 Response Body
- 记录 Response Time
- 捕获网络错误

返回一个统一的数据结构 `FetchResult`：

```java
statusCode
body
responseTime
finalUrl
errorType
success
```

URL Fetcher 不负责：

- 修改数据库
- 判断网页是否发生变化
- 创建 Timeline Event

这些属于 Service Layer。

## 12. Scheduler

系统需要自动执行定期检查，因此需要 Scheduler，使用 **Spring Scheduler**。

Scheduler **不应该**为每一个 URL 创建一个独立的定时任务。推荐方式：

```text
Scheduler  ── 每小时运行一次
    │
    ▼
查询 MySQL
    │
    ▼
找到 next_check_at <= 当前时间的 URL
    │
    ▼
CheckService
    │
    ▼
执行检查
```

这样每一个 URL 可以拥有自己的 `check_interval` 和 `next_check_at`。例如：

| URL | Interval | Next Check |
| --- | --- | --- |
| Website A | 1 day | Sep 8 |
| Website B | 7 days | Sep 14 |
| Website C | 30 days | Oct 7 |

## 13. URL Check 设计

### 13.1 Check 流程

一次完整的 Check：

1. 获取 URL
2. 验证 URL
3. 发送 HTTP GET
4. 获取 Response
5. 记录 HTTP Status
6. 记录 Response Time
7. 计算 Content Hash
8. 与上一次结果比较
9. 创建 Change Event（如果需要）
10. 保存 Check
11. 更新 next_check_at

## 14. Content Change Detection

### 14.1 MVP 方法

MVP 使用 **SHA-256 Content Hash**。

例如，网页内容：

```html
<html>
  <h1>Hello</h1>
</html>
```

经过 SHA-256 得到：`A1B2C3...`

下一次检查，网页内容变为：

```html
<html>
  <h1>Hello World</h1>
</html>
```

得到：`D4E5F6...`

由于 `A1B2C3... != D4E5F6...`，因此判定为 `CONTENT_CHANGED`。

## 15. Change Detection 的限制

简单 Hash 有一个重要问题：网页中可能存在动态内容。

例如：

```text
Current time: 14:30
```

下一次：

```text
Current time: 14:31
```

即使用户真正关心的内容没有变化，Hash 仍然会不同。

MVP 阶段：

```text
Raw Response → SHA-256
```

后续版本可以加入：

```text
HTML Normalization → Remove Dynamic Content → Extract Relevant Content → SHA-256
```

未来还可以支持：

- CSS Selector
- HTML Element Monitoring
- Text Diff

## 16. 数据库设计

使用 **MySQL 8.0+**，主要数据表：

- users
- urls
- checks
- changes

如果 MVP 暂时没有用户系统，可以暂时不实现 `users` 表。

## 17. users 表

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT | 主键 |
| username | VARCHAR(255) | 用户名 |
| created_at | DATETIME(6) | 创建时间 |

目前仅作为未来多用户功能的预留。

## 18. urls 表

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT | 主键 |
| name | VARCHAR(255) | 自定义名称 |
| url | TEXT | 被监控的 URL 地址 |
| check_interval_seconds | BIGINT | 检查间隔（秒） |
| last_checked_at | DATETIME(6) | 上次检查时间 |
| next_check_at | DATETIME(6) | 下次检查时间 |
| created_at | DATETIME(6) | 创建时间 |
| updated_at | DATETIME(6) | 更新时间 |

示例：

```text
id:                     1
name:                   Oxford Physics
url:                    https://www.physics.ox.ac.uk/
check_interval_seconds: 604800
```

其中 `604800 = 7 × 24 × 60 × 60`，即一周。

## 19. checks 表

`checks` 表记录每一次检查。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT | 主键 |
| url_id | BIGINT | 外键，关联 urls |
| checked_at | DATETIME(6) | 检查时间 |
| http_status | INT | HTTP 状态码 |
| response_time_ms | BIGINT | 响应耗时（毫秒） |
| content_hash | CHAR(64) | 内容 SHA-256 |
| success | BOOLEAN | 是否成功 |
| error_type | VARCHAR(64) | 错误类型 |
| final_url | TEXT | 最终 URL（含重定向） |

示例：

```text
id:               100
url_id:           1
checked_at:       2026-09-07 10:00:00
http_status:      200
response_time_ms: 245
content_hash:     abc123...
success:          true
```

## 20. changes 表

`changes` 表只记录实际产生的事件。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT | 主键 |
| url_id | BIGINT | 外键，关联 urls |
| check_id | BIGINT | 外键，关联 checks |
| detected_at | DATETIME(6) | 事件检测时间 |
| change_type | VARCHAR(64) | 事件类型 |
| old_hash | CHAR(64) | 变化前的 Hash |
| new_hash | CHAR(64) | 变化后的 Hash |

例如：`change_type` 为 `CONTENT_CHANGED`。

未来可以支持：

- `CONTENT_CHANGED`
- `UNAVAILABLE`
- `RECOVERED`
- `STATUS_CHANGED`

## 21. 为什么 Check 和 Change 要分开

这是系统中的一个重要设计。

例如：

```text
Monday    Check → No Change
Tuesday   Check → No Change
Wednesday Check → Changed
Thursday  Check → No Change
```

数据库中：

```text
checks:   1  2  3  4
changes:            3
```

这样：

- `checks` 回答：系统什么时候检查过？
- `changes` 回答：什么时候真正发生了值得关注的事件？

这两个概念应该分开。

## 22. 数据库关系

```text
User
 │
 │ 1:N
 ▼
URL
 │
 ├──────────────┐
 │ 1:N          │ 1:N
 ▼              ▼
Check         Change
                 │
                 │ N:1
                 ▼
               Check
```

## 23. REST API 设计

### 23.1 获取 URL 列表

```text
GET /api/urls
```

### 23.2 创建 URL

```text
POST /api/urls
Content-Type: application/json
```

Request：

```json
{
  "name": "University Website",
  "url": "https://example.edu",
  "checkIntervalSeconds": 604800
}
```

### 23.3 获取单个 URL

```text
GET /api/urls/{id}
```

### 23.4 修改 URL

```text
PUT /api/urls/{id}
```

### 23.5 删除 URL

```text
DELETE /api/urls/{id}
```

### 23.6 手动 Check

```text
POST /api/urls/{id}/check
```

Response：

```json
{
  "status": "ONLINE",
  "changed": true,
  "checkedAt": "2026-09-07T10:00:00"
}
```

### 23.7 获取 Timeline

```text
GET /api/urls/{id}/timeline
```

Response：

```json
[
  {
    "type": "CONTENT_CHANGED",
    "timestamp": "2026-09-07T10:00:00"
  },
  {
    "type": "RECOVERED",
    "timestamp": "2026-09-01T10:00:00"
  }
]
```

> Implementation note: the snippets above are sketches. The shipped contract -
> concrete response fields, event types, and retention rules - is specified in
> `docs/api.md` (sections 9-10) and `docs/timeline.md`. In particular, the
> manual check never writes to the database; the timeline is maintained only by
> the scheduled checker.

## 24. Frontend 架构

Frontend 使用：

- React
- TypeScript
- Vite

目录结构：

```text
frontend/
└── src/
    ├── components/
    │   ├── UrlCard.tsx
    │   ├── UrlList.tsx
    │   ├── Timeline.tsx
    │   └── AddUrlForm.tsx
    ├── pages/
    │   ├── Dashboard.tsx
    │   └── UrlDetail.tsx
    ├── services/
    │   └── api.ts
    └── types/
        └── url.ts
```

## 25. Frontend 页面

### 25.1 Dashboard

显示 `My URLs`：

```text
┌─────────────────────────────────────────────┐
│ University Website                          │
│ https://example.edu                         │
│ ● Online          Last checked: 2 hours ago │
└─────────────────────────────────────────────┘

┌─────────────────────────────────────────────┐
│ Oxford Physics                              │
│ https://physics.example.edu                 │
│ ● Online          Last checked: 1 day ago   │
└─────────────────────────────────────────────┘

                 + Add URL
```

### 25.2 URL Detail

```text
University Website
https://example.edu

Status: ● Online
Last checked: 2 hours ago

[Check Now]

Timeline
─────────────────────────────────────
Sep 7   ● Content Changed
Sep 1   ● No Change
Aug 25  ● Website Recovered
```

## 26. Frontend 与 Backend 的职责边界

Frontend：

```text
用户操作 → HTTP Request → 显示 Response
```

Backend：

```text
HTTP Request → Controller → Service → Repository / External Website → Database → HTTP Response
```

Frontend **不直接访问**被监控的网站。

正确的方式：

```text
Browser → Our Backend → Target Website
```

而不是：

```text
Browser → Target Website
```

这样可以避免部分 CORS 问题，并且让监控逻辑集中在 Backend。

## 27. 错误处理

系统需要区分不同类型的错误：

```text
URL Check
│
├── HTTP 2xx  → SUCCESS
│
├── HTTP 3xx  → REDIRECT
│
├── HTTP 4xx  → CLIENT_ERROR
│
├── HTTP 5xx  → SERVER_ERROR
│
└── Network Error
    ├── TIMEOUT
    ├── DNS_ERROR
    ├── CONNECTION_ERROR
    └── SSL_ERROR
```

需要特别注意：

- HTTP 403 并不一定意味着网站不可用。
- 它可能只是网站拒绝了自动请求。
- 因此数据库应该保存实际 HTTP Status，而不是只保存一个简单的 online/offline。

## 28. Redirect 处理

默认情况下 `followRedirects = true`。例如：

```text
https://example.com → https://www.example.com → https://www.example.com/home
```

系统应该记录最终 URL：`final_url`。

未来可以进一步判断：Redirect 地址发生变化是否应该算作 Change Event。

MVP 暂时不将普通 Redirect 单独作为变化事件。

## 29. Security Design

由于系统需要访问用户提供的 URL，因此存在一个重要安全问题：**SSRF（Server-Side Request Forgery）**。

例如恶意用户输入：

```text
http://localhost
http://127.0.0.1
```

可能导致 Backend 访问本不应该访问的内部服务。因此生产环境必须考虑：

```text
URL Validation → Protocol Validation → DNS Resolution → Private IP Blocking → HTTP Request
```

需要限制：

- localhost
- 127.0.0.1
- Private IP
- Link-local IP
- Internal network
- Cloud metadata endpoints

同时，Redirect 也需要重新进行安全检查。

## 30. HTTP Request 限制

为了避免恶意或异常网页影响服务器，建议设置：

```text
Connection Timeout:      10 seconds
Read Timeout:            10 seconds
Maximum Response Size:   5 MB
```

具体数值可以在实际测试后调整。

## 31. Performance Design

MVP 假设：

- 用户数量 < 100
- 每个用户 URL < 100
- 总 URL < 10,000

在这个规模下，Spring Boot + MySQL + Spring Scheduler 足够使用。

暂时不需要：

- Redis
- Kafka
- RabbitMQ
- Kubernetes
- Microservices

## 32. 并发检查

当 URL 数量增加时，**不应该**：

```text
for each URL:
    check()
```

也就是完全串行执行。

未来可以使用有限大小的线程池：

```text
Scheduler
    │
    ▼
Due URLs
    │
    ▼
Bounded Executor
    │
    ├── Check URL A
    ├── Check URL B
    ├── Check URL C
    └── Check URL D
```

同时限制并发数量，避免服务器向外部网站发送过多请求。

MVP 可以先使用较简单的实现。

## 33. Logging

系统应该记录：

- URL ID
- Check ID
- Check 开始时间
- Check 结束时间
- Response Time
- HTTP Status
- Error Type

例如：

```text
INFO  URL check started    urlId=123
INFO  URL check completed  urlId=123 status=200 responseTime=245ms
```

不应该默认记录：

- Cookie
- Authorization Header
- 用户密码
- 完整 Response Body

## 34. Testing Strategy

### 34.1 Unit Test

测试：

- URL Validation
- Hash Comparison
- Change Detection
- Interval Calculation
- Error Classification

### 34.2 Integration Test

测试：

```text
Service → Repository → MySQL
```

### 34.3 API Test

测试：

```text
POST   /api/urls
GET    /api/urls
PUT    /api/urls/{id}
DELETE /api/urls/{id}
POST   /api/urls/{id}/check
```

### 34.4 HTTP Client Test

至少测试：

- 200 OK
- 404 Not Found
- 500 Server Error
- Redirect
- Timeout
- DNS Error

### 34.5 End-to-End Test

完整流程：

```text
Add URL → Manual Check → Save Check → Modify Test Website → Check Again → Detect Change → Timeline
```

## 35. 项目目录结构

最终项目建议：

```text
url-change-monitor/
│
├── frontend/
│   ├── src/
│   │   ├── components/
│   │   ├── pages/
│   │   ├── services/
│   │   └── types/
│   ├── package.json
│   └── vite.config.ts
│
├── backend/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   └── com/example/urlmonitor/
│   │   │   │       ├── controller/
│   │   │   │       ├── service/
│   │   │   │       ├── client/
│   │   │   │       ├── scheduler/
│   │   │   │       ├── repository/
│   │   │   │       ├── model/
│   │   │   │       ├── exception/
│   │   │   │       └── config/
│   │   │   └── resources/
│   │   │       ├── mapper/
│   │   │       └── application.yml
│   │   └── test/
│   └── pom.xml
│
├── database/
│   └── migrations/
│
├── docker-compose.yml
├── README.md
└── .gitignore
```

## 36. 开发阶段

### Phase 1：Backend 基础

目标：

```text
Spring Boot → MySQL → MyBatis
```

完成：

- 项目初始化
- MySQL 连接
- 数据库表
- URL CRUD API

### Phase 2：Frontend

完成：

- React 初始化
- Dashboard
- Add URL
- URL List
- URL Detail

### Phase 3：URL Fetcher

实现：

```text
Backend → HTTP GET → External Website
```

完成：

- HTTP Status
- Response Body
- Response Time
- Error Handling

### Phase 4：Change Detection

实现：

```text
Response Body → SHA-256 → Compare → Change Event
```

### Phase 5：Timeline

实现：

```text
Database → Timeline API → React Timeline
```

### Phase 6：Scheduler

实现：

```text
Spring Scheduler → next_check_at → CheckService
```

实现自动检查。

### Phase 7：测试与完善

加入：

- Unit Test
- Integration Test
- API Test
- Error Handling
- Logging
- Security Validation
- Docker Compose

## 37. MVP 完成标准

当以下条件全部满足时，MVP 可以认为完成：

- 用户可以添加 URL
- 用户可以设置 URL 名称
- URL 可以保存到 MySQL
- URL 可以在 Dashboard 中显示
- 用户可以修改 URL
- 用户可以删除 URL
- 用户可以手动 Check URL
- Backend 可以获取 HTTP Status
- Backend 可以记录 Response Time
- Backend 可以计算 Content Hash
- 系统可以判断 Content 是否发生变化
- 系统可以保存 Check History
- 系统可以保存 Change Event
- 系统可以显示 Timeline
- Scheduler 可以自动执行 Check
- 系统可以处理常见网络错误
- 系统具备基本 SSRF 防护

## 38. 后续版本规划

### V0.2

加入：

- HTML Normalization
- Text Diff
- 更详细的 Timeline
- Change Details

例如：

```diff
- Application deadline: October 1
+ Application deadline: October 15
```

### V0.3

加入：

- CSS Selector
- HTML Element Monitoring
- Ignore Rules

例如用户可以选择 `Monitor: #announcement`，而不是监控整个网页。

### V0.4

加入通知：

- Email
- Discord
- Telegram
- Push Notification

### V0.5

加入高级网页监控：

- Playwright
- JavaScript Rendering
- Screenshot
- Visual Diff

## 39. 未来可能的架构演进

当前架构：

```text
                       Spring Boot
                            │
            ┌───────────────┼───────────────┐
            │               │               │
          API           Scheduler     URL Fetcher
            │               │               │
            └───────────────┼───────────────┘
                            │
                          MySQL
```

如果未来 URL 数量达到非常大的规模，可以逐渐演进为：

```text
Frontend
    │
    ▼
API Server
    │
    ▼
Task Queue
    │
    ├────────── Worker 1
    ├────────── Worker 2
    ├────────── Worker 3
    └────────── Worker N
                │
                ▼
           External Web
                │
                ▼
              MySQL
```

但是在 MVP 阶段不实现。

## 40. 关键设计决策总结

| 设计 | 决定 | 原因 |
| --- | --- | --- |
| Architecture | Modular Monolith | 简单、清晰、适合 MVP |
| Backend | Spring Boot | 成熟的 Java Web 框架 |
| Frontend | React + TypeScript | 适合 SPA |
| Database | MySQL 8.0+ | 关系型数据、成熟、足够使用 |
| DB Access | MyBatis | SQL 明确，适合学习和控制数据库 |
| HTTP | Java HttpClient | MVP 不需要额外 HTTP 框架 |
| Scheduling | Spring Scheduler | 简单可靠 |
| Change Detection | SHA-256 | MVP 实现简单 |
| Check / Change | 分离 | 区分检查历史与实际变化 |
| Deployment | Docker Compose | 简化开发环境 |
| Architecture Style | Monolith | 避免过早复杂化 |

## 41. 总结

本项目采用一个以 Spring Boot 为核心的模块化单体架构。

核心数据流为：

```text
               User
                │
                ▼
             React
                │
             REST API
                │
                ▼
           Controller
                │
                ▼
             Service
            /       \
           ▼         ▼
    Repository   URL Fetcher
        │             │
        │             ▼
        │      External Website
        │             │
        │             ▼
        │      Change Detection
        │             │
        └──────┬──────┘
               ▼
             MySQL
               │
               ▼
           Timeline
               │
               ▼
             React
```

该架构的主要目标不是在 MVP 阶段追求复杂的企业级架构，而是在保持系统简单的同时，明确划分：

```text
Frontend → API → Controller → Service → Repository → MySQL
```

以及：

```text
Scheduler → Check Service → URL Fetcher → External Website → Change Detection → MySQL
```

这种设计可以满足当前项目需求，同时为未来加入 Diff、通知、Element Monitoring、JavaScript Rendering 等功能保留扩展空间。
