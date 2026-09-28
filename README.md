# 用量计费引擎（本地可验证）

一个不依赖任何外部服务（无数据库、无消息队列、无真实支付渠道、无真实凭据）的
Spring Boot 内存用量计费引擎，重点解决四类真实问题：

1. **事件重复**：同一 `eventId` 重复/并发提交只计一次；标识冲突与重复提交返回可区分结果。
2. **乱序补录**：账单按**事件时间**回放，晚到、先到结果一致且可复现。
3. **周期/套餐切换**：事件按其事件时间匹配当时生效的价格版本；跨月事件归属各自账期；
   新价格不回溯历史账单。
4. **账期纠错**：账期关闭后普通写入被拒；管理员可受控重开走纠错路径；
   重算/冲正/修正只产生账单新版本，不重复扣费，历史版本永久可查。

## 本地验证

要求 JDK 21+、Maven 3.9+（已在 JDK 25/Maven 3.9 上验证）。

```bash
mvn test            # 运行全部测试（9 个，含并发场景）
mvn spring-boot:run # 启动 HTTP 服务（端口随机，纯本地内存）
```

测试均为 `@SpringBootTest`，关键输入与判定依据通过 SLF4J 打印（日志前缀
`[输入]` / `[判定依据]`），可用 `-Dtest=...` 单独运行：

| 测试 | 覆盖点 |
| --- | --- |
| `ConcurrentDuplicateIngestTest` | 24 线程并发同一事件只受理一次；重试幂等；同 id 换内容=冲突 |
| `OutOfOrderBackfillTest` | 乱序（晚到）事件按事件时间出账；重算逐行一致；补录确定性增加 |
| `PlanSwitchBillingTest` | 月中套餐切换按版本拆行；跨月归属；v3 发布不回溯历史账单 |
| `ClosedPeriodAccessTest` | 关闭后写入 423；权限 403；状态冲突 409；并发关闭 vs 写入总量守恒 |
| `CorrectionRecomputeTest` | 定账/金额调整/重开/用量冲正/补录；纠错幂等不重复扣费；版本可复现 |
| `HttpApiIntegrationTest` | 端到端 HTTP，状态码 200/400/403/404/409/422/423 可区分 |

## HTTP 接口（角色由 `X-Operator-Role: USER|ADMIN` 头携带，默认 USER）

| 方法 & 路径 | 角色 | 说明 |
| --- | --- | --- |
| `POST /api/events` | 任意 | 上报/重试用量事件，返回 `duplicate` 标识 |
| `GET /api/events/{eventId}` | 任意 | 单事件查询 |
| `GET /api/events?accountId=&period=yyyy-MM` | 任意 | 账期事件（按事件时间排序） |
| `POST /api/prices` | ADMIN | 发布价格版本（只增不改） |
| `GET /api/prices?accountId=&metric=` | 任意 | 价格版本列表 |
| `POST /api/periods/close?accountId=&period=` | ADMIN | 关闭账期（可定账） |
| `POST /api/periods/reopen?accountId=&period=` | ADMIN | 受控重开，body `{"reason":"..."}` 必填 |
| `GET /api/periods?accountId=&period=` | 任意 | 账期状态 |
| `POST /api/bills/draft?accountId=&period=` | 任意 | 试算草案（不改状态） |
| `POST /api/bills/finalize?accountId=&period=` | ADMIN | 定账（OPEN 会先关闭）；重复定账幂等 |
| `POST /api/bills/corrections?accountId=&period=` | ADMIN | 人工纠错（见下） |
| `GET /api/bills?accountId=&period=[&version=n]` | 任意 | 最新账单/指定历史版本 |
| `GET /api/bills/versions?accountId=&period=` | 任意 | 全部账单版本 |

### 快速体验（curl）

```bash
curl -s -XPOST localhost:8080/api/prices -H 'X-Operator-Role: ADMIN' -H 'Content-Type: application/json' \
  -d '{"accountId":"a1","metric":"storage_gb","unitPriceMinor":3,"effectiveFrom":"2026-01-01T00:00:00Z"}'
curl -s -XPOST localhost:8080/api/events -H 'Content-Type: application/json' \
  -d '{"eventId":"e1","accountId":"a1","metric":"storage_gb","quantity":100,"eventTime":"2026-06-05T00:00:00Z"}'
curl -s -XPOST 'localhost:8080/api/bills/finalize?accountId=a1&period=2026-06' -H 'X-Operator-Role: ADMIN'
```

## 错误码（HTTP 状态可区分）

| errorCode | HTTP | 含义 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | 参数/格式/取值校验失败（含重开必须填原因） |
| `FORBIDDEN` | 403 | 非 ADMIN 调用管理操作 |
| `*_NOT_FOUND` | 404 | 事件/账单/纠错/价格不存在 |
| `EVENT_ID_CONFLICT` | 409 | eventId（或 correctionId）已存在但**内容不同**；或纠错单复用冲突 |
| `PERIOD_STATE_CONFLICT` | 409 | 账期状态机冲突（重复关闭、对未关闭账期重开） |
| `CORRECTION_NOT_ALLOWED` | 422 | OPEN 账期不允许走纠错路径；CLOSED 账期不允许用量冲正（需先重开） |
| `PERIOD_CLOSED` | 423 | 事件时间落在已关闭账期；或 REOPENED 账期的非管理员/非纠错写入 |

详见 [docs/BILLING_RULES.md](docs/BILLING_RULES.md)。
