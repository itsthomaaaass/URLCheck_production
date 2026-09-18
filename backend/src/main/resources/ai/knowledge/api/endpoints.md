# HTTP API 接口

## 约定

后端开发服务器是 http://localhost:8080，前端开发时由 Vite 把 /api/* 代理到后端。请求与响应都是 JSON，浏览器调用需携带会话 Cookie。未登录访问需要认证的接口返回 401 {"message": "请先登录"}。错误响应统一为 {"message": "..."}；常用状态码：400 参数错误、401 未登录、404 不存在或不属于当前用户、409 用户名已存在、503 AI 服务不可用。

## 接口一览

| 方法与路径 | 说明 |
| --- | --- |
| POST /api/users | 注册用户 |
| POST /api/auth/login | 登录（建立会话） |
| POST /api/auth/logout | 退出登录 |
| GET /api/auth/me | 当前用户或 null |
| GET /api/urls | 当前用户的 URL 列表 |
| POST /api/urls | 创建 URL，返回 201 |
| PUT /api/urls/{id} | 修改 URL |
| DELETE /api/urls/{id} | 删除 URL |
| POST /api/urls/{id}/check | 立即检查（只读，不落库） |
| GET /api/urls/{id}/timeline | 时间线事件 |
| POST /api/ai/chat | AI 助手对话（可选功能） |
| POST/GET/DELETE /api/ai/conversations | AI 对话历史管理（可选功能） |

## 创建与修改 URL 的校验

name 必填、1-100 字符；url 必填、必须以 http:// 或 https:// 开头、主机不能是内网地址；description 可选、0-1000 字符、省略时存为空字符串。创建成功后几秒内会自动完成第一次检查。

## 手动检查接口

POST /api/urls/{id}/check 返回 status（UP/DOWN）、httpStatus、responseTimeMs、finalUrl、errorType、contentHash、changed、changeType。changed 为 true 表示内容与基线不同（或还没有基线），false 表示相同，errorType 有值时 changed 为 null。结果只存在于响应中，不写数据库、不进时间线。

## 时间线接口

GET /api/urls/{id}/timeline 返回 totalCount（历史事件总数）和 events（倒序事件列表）。事件字段包括 changeType、status、detectedAt、httpStatus、errorType、oldHash、newHash、changeNo 等。

## AI 助手接口

AI 功能默认开启，配置了 API Key 就暴露 /api/ai 下的接口；AI_ENABLED=false 即使有密钥也保持关闭，AI_ENABLED=true 但缺少密钥会在启动时报错。POST /api/ai/chat 一问一答，可携带 conversationId 继续对话；助手能列出和检查用户自己的 URL，删除需要两步确认（后端先返回 confirmation token，用户在前端确认后再提交 token 才会真正删除）。AI 提供方不可达时返回 503，其余接口不受影响。
