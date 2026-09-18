# 数据库表结构

## 概述

使用 MySQL 8.0+，数据库名 url_monitor，所有业务表使用 InnoDB，字符集 utf8mb4。表结构由 database/ 目录下的迁移脚本按文件名顺序创建，应用通过 MyBatis 访问数据库。

## 表关系

users 与 monitored_url 是一对多，monitored_url 与 changes 是一对多，users 与 conversation、conversation 与 chat_message 都是一对多。删除用户时级联删除其 URL、事件与对话；删除 URL 时级联删除其事件。

## users 用户表

| 列 | 说明 |
| --- | --- |
| id | 主键 |
| username | 用户名，唯一 |
| password_hash | PBKDF2-SHA256 密码哈希 |
| created_at | 创建时间 |

## monitored_url 被监控的 URL

| 列 | 说明 |
| --- | --- |
| id | 主键 |
| user_id | 所属用户，外键到 users |
| name | 自定义名称（1-100 字符） |
| url | 被监控地址，最多 2048 字符 |
| description | 备注（0-1000 字符） |
| content_hash | 上次成功检查的 SHA-256 基线哈希 |
| last_status | 上次状态 UP/DOWN |
| last_http_status | 上次 HTTP 状态码 |
| last_error_type | 上次错误类型 |
| last_checked_at | 上次检查时间 |
| next_check_at | 下次检查时间，调度器按它挑选到期 URL |
| change_count | 历史事件总数（含被清理的） |
| check_interval_seconds | 该 URL 的检查间隔覆盖值，可为空 |

## changes 时间线事件表

| 列 | 说明 |
| --- | --- |
| id | 主键 |
| url_id | 所属 URL，外键 |
| detected_at | 事件发生时间 |
| change_type | FIRST_CHECK / CONTENT_CHANGED / RECOVERED / UNAVAILABLE |
| old_hash | 变化前的内容哈希 |
| new_hash | 变化后的内容哈希 |
| http_status | 事件发生时的 HTTP 状态码 |
| error_type | 事件发生时的错误类型 |
| response_time_ms | 本次检查的响应耗时 |
| change_no | 每个 URL 内单调递增的事件编号，1 是锚点 |

## checks 表是预留的

checks 表（每次检查一行）在结构上存在，但当前监控设计有意不写它：只保存事件行（changes），changes.check_id 永远为 NULL。这样数据库里只存「值得关注的事」，而不是每一次检查。

## AI 相关的表

conversation 和 chat_message 保存 AI 助手与用户的对话历史；ai_knowledge_document 保存知识库文档的 SHA-256 台账，用于判断哪些文档需要重新向量化。
