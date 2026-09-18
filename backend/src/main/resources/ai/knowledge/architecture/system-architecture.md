# 系统总体架构

## 概述

URLCheck 是一个 URL 监控与网页变更检测系统，采用模块化单体架构：一个 Spring Boot 后端、一个 React + TypeScript 前端，共享一个 MySQL 8.0 数据库。

## 技术栈

- 后端：Java 21 + Spring Boot，MyBatis 访问 MySQL，Jackson 处理 JSON
- 前端：React + TypeScript + Vite
- 数据库：MySQL 8.0+，数据库名 url_monitor
- 定时任务：Spring Scheduler
- HTTP 客户端：Java HttpClient（请求被监控的网站）
- AI 助手：Spring AI，默认接 DeepSeek（任何 OpenAI 兼容接口都可替换）

## 核心数据流

用户操作链路：浏览器 → React 前端 → REST API → Controller → Service → MyBatis → MySQL。

自动监控链路：Scheduler → 查询到期 URL → UrlChecker（HTTP GET 外部网站）→ SHA-256 哈希 → 变化检测 → MySQL → 时间线。

前端不直接访问被监控的网站，所有对外请求都从后端发出（浏览器 → 本系统后端 → 目标网站），这样监控逻辑集中在后端，也避免部分 CORS 问题。

## 模块划分

后端按职责划分包：check（探测与变化检测）、monitor（定时检查）、url（URL 管理）、timeline（时间线）、user（用户与认证）、security（SSRF 防护）、ai（AI 助手）。

## 监控子系统的写入者划分

时间线只有一个写入者：定时检查器。手动检查（POST /api/urls/{id}/check）只读取基线并返回结果，不写数据库；时间线接口只读。两者共用同一套 ChangeDetector 判定规则，所以手动检查的结论和定时检查记录的结论永远不会不一致。

## AI 助手子系统

AI 助手由三部分上下文组成：对话记忆（MySQL 中保存的近期对话，最多回放 20 条消息）、知识库（本应用文档经本地 ONNX 嵌入模型向量化后存入 Qdrant，通过 searchKnowledge 工具检索）、工具（列出、检查、删除当前用户的 URL）。助手在回答「系统如何工作」类问题前会先检索知识库。
