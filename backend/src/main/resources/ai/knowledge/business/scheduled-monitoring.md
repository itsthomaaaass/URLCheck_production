# 定时监控调度

## 调度方式

ScheduledChecker 每 15 秒（CHECK_SCHEDULER_TICK_SECONDS）运行一次，查询 next_check_at 到期的 URL，每次最多检查 CHECK_BATCH_SIZE（默认 20）个。每个 URL 多久检查一次由它自己的 check_interval_seconds 决定，默认 CHECK_INTERVAL_SECONDS = 900 秒（15 分钟）。调度器只决定「多久问一次数据库」，URL 的检查频率存在数据库里。

## 领取（Claim）机制

检查一个 URL 前先执行一条带条件的 UPDATE：只有 next_check_at 为空或已到期的行才会被更新并推进 next_check_at，且只有成功更新了一行的调用者才继续检查。这保证两个后端实例（或轮询器和创建时的立即检查）不会把同一次变化记录两遍。时间计算全部在数据库时钟上完成。

## 一次定时检查的完整流程

1. Claim：条件更新 next_check_at
2. 探测：UrlChecker 发 HTTP GET，计算内容哈希
3. 判定：ChangeDetector 根据基线（保存的哈希、上次状态）得出结论
4. 落库：MonitorWriter 在一个事务里完成——必要时插入时间线事件、按保留策略清理旧事件、更新 URL 的监控状态（content_hash、last_status、last_http_status、last_error_type、last_checked_at、change_count）

检查因意外异常失败时，会把该 URL 的 next_check_at 拉回近期，避免它一整个周期不被检查。

## 创建与修改地址时的立即检查

默认 CHECK_ON_CREATE = true：新建 URL、或把 URL 改成另一个地址后，会在创建事务提交后立即安排一次检查，几秒内就能看到 FIRST_CHECK 事件，不用等下一个调度周期。

## 并发模型

对外检查只跑在一个线程（checkExecutor，队列容量 200，CallerRunsPolicy）：一个慢的目标不会让后端同时打开几十个连接，队列满时由调用方线程承担背压，而不是丢弃或无限排队。

## 主要配置项

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| CHECK_SCHEDULER_ENABLED | true | 是否开启自动检查 |
| CHECK_SCHEDULER_TICK_SECONDS | 15 | 调度器查询到期 URL 的频率 |
| CHECK_INTERVAL_SECONDS | 900 | 每个 URL 的默认检查间隔 |
| CHECK_BATCH_SIZE | 20 | 每次 tick 最多检查的 URL 数 |
| CHANGE_RETENTION_PER_URL | 10 | 每个 URL 保留的时间线行数 |
| CHECK_ON_CREATE | true | 创建/改地址后是否立即检查 |

单个 URL 可以通过 monitored_url.check_interval_seconds 覆盖默认间隔（目前只能直接在 SQL 里设置，没有界面）。
