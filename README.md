# 用量计费引擎（Usage Billing Engine）

本地可完整验证的用量计费服务：事件去重、乱序补录、账期关闭/受控重开、价格版本切换、
人工纠错与账单重算。**不依赖任何外部服务、真实凭据或付费渠道**，存储为内嵌 H2 内存库。

## 1. 本地运行与验证

要求 JDK 21+（在 25 上同样验证通过）、Maven 3.9+。

```bash
# 运行全部测试（离线可跑，依赖已在本地仓库）
mvn test

# 启动服务（默认 8080，可用 SERVER_PORT 覆盖）
mvn spring-boot:run
```

测试共 11 个，覆盖：幂等去重、并发重复写入、并发关账竞争、乱序补录、
跨套餐月中切价、跨周期归属、关账写入拒绝、权限/状态/校验三类错误区分、
受控重开 + 补录/冲正 + 重算全流程。测试日志以 INFO 打印**关键输入与判定依据**
（事件载荷、命中的价格版本、账期状态、错误码），可用
`grep "判定依据\|判定=" target/surefire-reports/*.txt` 或控制台输出查看。

每个测试类使用独立的 H2 内存库（`@TestPropertySource` 指定不同库名），互不污染。

## 2. 数据模型与状态

| 表 | 说明 |
|---|---|
| `usage_event` | 用量事件。`event_id` 全局唯一（幂等键）；`state ∈ ACTIVE/REVERSED`；归属 `period` 由事件时间决定 |
| `billing_period` | 账期（账户 + `yyyy-MM` 唯一），`status ∈ OPEN/CLOSED/REOPENED`，带 JPA 乐观锁版本 |
| `period_lock` | 账期命名锁（主键 `account|period`），写入与关账都在该行悲观写锁内完成 |
| `price_plan_version` | 不可变价格版本，按 `validFrom` 构成首尾相接、无空隙无重叠的版本链 |
| `bill` | 账单快照，账户 + 账期 + `version` 唯一，`status ∈ DRAFT/FINAL`，明细以 JSON 落库 |
| `correction_record` | 纠错流水，`correction_id` 唯一（纠错幂等键） |

账期状态机：

```
OPEN ──关账──▶ CLOSED ──受控重开(ADMIN)──▶ REOPENED ──重新关账──▶ CLOSED
```

- `OPEN`：接受普通写入；
- `CLOSED`：拒绝一切写入；重复关账是幂等的（不产生新账单、不重复扣费）；
- `REOPENED`：普通写入仍被拒绝，只有 `ADMIN` 通过纠错接口（补录/冲正）可改。

## 3. 计费口径（唯一计算入口）

1. **账期归属只看事件时间**：事件按 `eventTimeEpochMs` 的 **UTC 自然月**归入
   `yyyy-MM`，与服务端接收时间、提交顺序无关（乱序/跨周期结果确定）。
2. **逐条计价**：账期内每个 `ACTIVE` 事件按其 `eventTime` 命中的价格版本计价，
   `行金额 = quantity × unitPrice`；账单金额为行金额之和。
3. **已冲正事件**：`state=REVERSED` 的事件行保留不删除，金额计 0，
   其数量计入账单的 `reversedQuantity`（总量守恒、审计可追溯）。
4. **价格缺失即拒绝出账**：任何 ACTIVE 事件找不到覆盖其事件时间的价格版本，
   关账/重算返回 `409 PRICE_NOT_DEFINED`，账期保持原状态（不会静默少算）。
5. 明细按 `eventTime` 升序输出；同输入（事件集 + 历史价格版本）重算结果逐字节稳定。

### 价格版本与套餐变更规则

- 价格版本**只增不改**：发布新版本（`ADMIN`）时，旧的当前版本 `validUntil`
  被闭合为新版本的 `validFrom`，版本链首尾相接。
- 新 `validFrom` 必须严格晚于当前版本的 `validFrom`，否则
  `409 PRICE_PLAN_CONFLICT`。
- 已落账金额永不因后续调价而变化：历史事件永远命中其事件时间当时的版本，
  因此**历史结果可复现**，补录历史事件也按当时价格计价。
  （允许补建一个 `validFrom` 覆盖过去的首版本来为早期事件补价。）

## 4. 幂等、并发与不丢量

- **事件幂等**：`event_id` 是唯一去重依据。
  - 同 `eventId` 同载荷重复提交：HTTP 200 且 `idempotentReplay=true`，不重复累计；
  - 同 `eventId` 载荷不同：`409 EVENT_ID_CONFLICT`；
  - 数据库唯一约束是并发/重试下的最终兜底。
- **账期串行化**：写入与关账都先取 `period_lock` 对应行的悲观写锁。
  - 写事务 = 取锁 → 读账期状态 → 插入 ACTIVE 事件，单事务原子提交；
  - 关账事务 = 取锁 → 读全部 ACTIVE 事件出账 → 置 CLOSED。
  - 因此“返回 200 接受”与“最终 FINAL 账单包含该事件”严格等价，
    不存在“关账读账单后、提交前”的在途窗口；并发关账下每个事件要么计入、
    要么收到 `409 PERIOD_CLOSED`，不丢量、不重累。
- 锁等待有上限（`jakarta.persistence.lock.timeout=3000ms`），不会长时间阻塞。
- 账期行/锁行使用 `insert ... where not exists` 原子首建，避免靠约束异常建行
  导致的事务 rollback-only 问题。

## 5. 纠错边界

- 只有 `ADMIN`（请求头 `X-Operator-Role: ADMIN`，可选 `X-Operator-Id`）可访问
  `/api/admin/**`；普通角色或无头返回 `403 FORBIDDEN`。
- 纠错只允许在 **REOPENED** 账期上执行：必须先 `ADMIN` 关账 → 受控重开。
  - `BACKFILL`：补录一条事件（携带 `correctionId` 标记），事件时间必须落在该账期，
    否则 `422 VALIDATION_FAILED`；新 `eventId` 撞已有返回 `409 EVENT_ID_CONFLICT`。
  - `REVERSAL`：把一条 ACTIVE 事件转为 REVERSED（行保留）；目标不存在 `404 NOT_FOUND`；
    重复冲正返回 `409 CORRECTION_CONFLICT`。
- `correctionId` 唯一：同一纠错重试是幂等的，不会第二次改账、不产生重复扣费。
- 每次纠错/重算都**追加一个新版本账单**（REOPENED 期间为 DRAFT），
  重新关账生成 FINAL；旧版本保留可查，金额差异完全由事件变化解释。
- 纠错完成后必须重新关账才回到 CLOSED。

### 错误码一览（原因可区分）

| HTTP | code | 触发条件 |
|---|---|---|
| 422 | `VALIDATION_FAILED` | 字段校验失败（如 quantity≤0）、补录事件时间跨账期、未知纠错类型 |
| 409 | `EVENT_ID_CONFLICT` | eventId 被不同载荷占用 |
| 409 | `PERIOD_CLOSED` | 向 CLOSED/REOPENED 账期普通写入 |
| 409 | `PERIOD_STATUS_CONFLICT` | OPEN 上重开、CLOSED 上重算、非 REOPENED 上纠错、并发状态冲突 |
| 409 | `CORRECTION_CONFLICT` | 重复冲正 / correctionId 并发处理中（可安全重试） |
| 409 | `PRICE_NOT_DEFINED` | 出账时事件无适用价格版本 |
| 409 | `PRICE_PLAN_CONFLICT` | 价格版本 validFrom 未严格递增 |
| 403 | `FORBIDDEN` | 非 ADMIN 访问管理接口 |
| 404 | `NOT_FOUND` | 重开/冲正目标不存在 |

## 6. HTTP 接口

普通接口（无需角色）：

| 方法 路径 | 说明 |
|---|---|
| `POST /api/events` | 写入事件 body: `eventId, accountId, metric, quantity, eventTimeEpochMs` |
| `GET  /api/events?accountId&period` | 查询账期内 ACTIVE/REVERSED 事件（按事件时间升序） |
| `GET  /api/bills?accountId&period` | 最新账单；OPEN 账期返回不落库试算（version=0, DRAFT） |

管理接口（需 `X-Operator-Role: ADMIN`）：

| 方法 路径 | 说明 |
|---|---|
| `POST /api/admin/plans` | 发布价格版本 `accountId, metric, unitPrice, validFromEpochMs` |
| `POST /api/admin/periods/close` | 关账并出 FINAL（重复关账幂等） |
| `POST /api/admin/periods/reopen` | CLOSED → REOPENED（受控重开） |
| `POST /api/admin/periods/recompute` | 重算（REOPENED 追加 DRAFT；OPEN 试算；CLOSED 拒绝） |
| `GET  /api/admin/periods?accountId&period` | 查询账期状态 |
| `POST /api/admin/corrections` | 纠错：`correctionId, accountId, period, type=BACKFILL/REVERSAL, eventId, metric?, quantity?, eventTimeEpochMs?, reason` |

### 快速手工冒烟（服务启动后）

```bash
BASE=http://localhost:8080
curl -s -XPOST $BASE/api/admin/plans -H 'X-Operator-Role: ADMIN' -H 'Content-Type: application/json' \
  -d '{"accountId":"a1","metric":"api_calls","unitPrice":2,"validFromEpochMs":1735689600000}'
curl -s -XPOST $BASE/api/events -H 'Content-Type: application/json' \
  -d '{"eventId":"e1","accountId":"a1","metric":"api_calls","quantity":10,"eventTimeEpochMs":1735776000000}'
curl -s "$BASE/api/bills?accountId=a1&period=2025-01"
curl -s -XPOST $BASE/api/admin/periods/close -H 'X-Operator-Role: ADMIN' -H 'Content-Type: application/json' \
  -d '{"accountId":"a1","period":"2025-01"}'
# 关账后写入 -> 409 PERIOD_CLOSED
curl -s -XPOST $BASE/api/events -H 'Content-Type: application/json' \
  -d '{"eventId":"e2","accountId":"a1","metric":"api_calls","quantity":1,"eventTimeEpochMs":1735862400000}'
```

金额单位为最小货币单位的整数；时间一律为 epoch 毫秒（UTC）。
