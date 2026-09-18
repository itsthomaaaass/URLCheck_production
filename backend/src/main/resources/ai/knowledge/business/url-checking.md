# URL 检查流程

## 一次检查做了什么

UrlChecker 是系统里唯一发起对外 HTTP 请求的组件，手动检查和定时检查共用它。一次检查的流程：解析 URL → 发送 HTTP GET → 手动跟随重定向 → 计算 SHA-256 哈希 → 返回探测结果（状态、HTTP 状态码、响应耗时、最终 URL、错误类型、内容哈希）。

## 请求参数与超时

- 请求头：User-Agent 为 UrlCheckBot/0.1
- 连接超时 5 秒，整个请求超时 10 秒
- 只允许 http/https 协议和 80/443 端口
- 最多跟随 5 次重定向（301/302/303/307/308），每一跳都重新做 SSRF 检查；超过 5 次返回 TOO_MANY_REDIRECTS

## 状态判定

2xx 和 3xx 判定为 UP，其余（4xx/5xx 或网络错误）判定为 DOWN。网站无法访问时接口仍然返回 200，用 status: DOWN 和 errorType 说明原因——访问失败不是这个 API 的错误。

## 错误类型（errorType）

- HTTP_ERROR：对方应答了 4xx/5xx 状态码
- TIMEOUT：连接或读取超时
- DNS_ERROR：域名无法解析
- SSL_ERROR：TLS 握手或证书校验失败
- CONNECTION_REFUSED：主机和端口上没有服务接受连接
- BLOCKED_TARGET：目标解析到内网地址或使用了非 80/443 端口，被 SSRF 防护拒绝，从未真正发起请求
- INVALID_URL：保存的 URL 不是合法的 http(s) 地址
- TOO_MANY_REDIRECTS：重定向超过 5 次
- IO_ERROR：其他 I/O 错误
- INTERRUPTED：检查线程被中断

## SSRF 防护

被监控的 URL 由用户提供，所以后端在每次请求前都做 SSRF 检查（SsrfGuard）：解析主机名后检查它解析出的每一个 IP 地址，环回地址、内网段（10/8、172.16/12、192.168/16 等）、链路本地地址、云元数据地址（169.254.169.254）、组播地址、IPv4 映射的 IPv6 等一律拒绝，返回 BLOCKED_TARGET，请求从不真正发出。保存 URL 时还会做一次不做 DNS 解析的快速检查，直接拒绝 localhost、.local、.internal 等主机名和 IP 字面量。

## 手动检查不写数据库

POST /api/urls/{id}/check 是一次实时探测：结果只存在于响应里，不写数据库、不产生时间线事件。URL 列表里展示的 lastStatus、lastCheckedAt 等始终来自定时检查器，手动检查不会改变它们。
