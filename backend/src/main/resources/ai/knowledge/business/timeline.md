# 时间线事件

## 事件类型

| changeType | 含义 | 是否锚点 |
| --- | --- | --- |
| FIRST_CHECK | 该 URL 的第一次检查，记录基线哈希 | 是 |
| CONTENT_CHANGED | 检查成功且内容哈希与保存的不同 | 否 |
| RECOVERED | 上次状态为 DOWN，本次检查成功 | 否 |
| UNAVAILABLE | 没有得到可用响应（4xx/5xx 或网络错误），且此前不是 DOWN | 否 |

「没有变化」不产生任何记录。时间线接口返回的 status 字段由 changeType 推导：UNAVAILABLE 对应 DOWN，其余对应 UP。

## 保留策略

每个 URL 最多保留 CHANGE_RETENTION_PER_URL（默认 10）条事件：锚点（change_no = 1 的第一次检查，无论类型）永不删除，加上最新的 9 条。新事件插入时在同一事务里删掉最旧的非锚点行。change_no 单调递增，锚点始终是 1。

monitored_url.change_count 统计该 URL 历史上全部事件数（包括被清理的），所以接口能返回 totalCount（共 N 条），而实际只存 10 条。

## 读取接口

GET /api/urls/{id}/timeline?limit=10 按时间倒序返回该 URL 的事件，limit 默认 10、限制在 1-100。接口只读，与手动检查和调度器都不会冲突。

## 修改 URL 地址会清空时间线

把 URL 修改为另一个地址时，旧的时间线描述的是旧页面，因此会删除全部事件、重置监控状态，并安排一次新的 FIRST_CHECK 作为新基线。只改名称或备注不会动时间线。

## 没有推送通知

浏览器不会被主动通知，前端只有调用接口时才知道新事件。这是有意设计，推送属于未来功能。
