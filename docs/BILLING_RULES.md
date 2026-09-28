# 计费口径、价格/周期切换、幂等与纠错边界

## 1. 账期模型

- 账期键 = 自然月，UTC，格式 `yyyy-MM`（`Periods`）。
- 账期按 `(accountId, period)` 懒创建，初始状态 `OPEN`。
- 状态机：

```
OPEN ──close──▶ CLOSED ──reopen(reason)──▶ REOPENED ──close──▶ CLOSED ...
```

- `close` / `reopen` 均为 **ADMIN** 操作；`reopen` 必须提供非空原因（审计留痕）。
- 同一账期的"状态判定 + 事件受理 + 受理集合更新"在 `ConcurrentHashMap.compute`
  的 bin 监视器内原子完成；不同账期互不阻塞，无全局锁、不长时间阻塞。
- 并发下每个写入只有两种结局：在关闭前**受理**，或以 `PERIOD_CLOSED` **拒绝**；
  不会出现"已返回成功但丢失"或"被拒绝却累计"（见 `ClosedPeriodAccessTest`）。

## 2. 事件写入口径

### 2.1 幂等与冲突（可区分）

- 全局 `eventId` 唯一；账期内维护 `acceptedEventIds`。
- 同 `eventId` **同内容**（账号/计量项/数量/事件时间/类型/纠错关联）再次提交：
  按幂等成功返回 `200 {"duplicate":true}`，**绝不重复累计**。并发首提与重试同等待遇。
- 同 `eventId` **不同内容**：返回 `409 EVENT_ID_CONFLICT`。
  这与"重复提交的幂等成功"明确区分，避免上游用同一个 id 传两种业务事实。
- 普通事件 `quantity > 0`；纠错事件数量非 0（允许负数冲正）；`eventTime` 不接受
  超过当前时钟 1 分钟的未来时间（防呆），违反 => `400 VALIDATION_FAILED`。

### 2.2 账期门禁

| 账期状态 | USER 普通写入 | ADMIN 普通写入 | ADMIN 纠错路径 |
| --- | --- | --- | --- |
| OPEN | 接受 | 接受 | 不允许（`CORRECTION_NOT_ALLOWED`，直接补录事件即可） |
| CLOSED | 拒绝 `PERIOD_CLOSED(423)` | 拒绝 `PERIOD_CLOSED(423)` | 仅 `AMOUNT_ADJUSTMENT`；`USAGE_DELTA` 需先重开 |
| REOPENED | 拒绝 `PERIOD_CLOSED(423)` | 拒绝 `PERIOD_CLOSED(423)` | 接受 `USAGE_DELTA` / `AMOUNT_ADJUSTMENT` |

- 归属账期由 **eventTime** 决定，而非写入时刻：跨月补录会进入 eventTime 所在账期，
  该账期若已关闭则按上表拒绝（需重开走纠错）。

## 3. 价格版本与套餐切换

- 价格维度 `(accountId, metric)`，版本号在该维度从 1 单调递增；
  **版本只追加、不可修改、不可删除**，切换套餐/单价 = 发布新版本 + `effectiveFrom`。
- 计价规则：对每条事件取满足 `effectiveFrom <= eventTime` 的**最新**价格版本
  （事件时间定价）。因此：
  - 月中切换：切换前事件用旧价、切换后用新价，账单按 `(metric, priceVersion)`
    稳定拆成多行（见 `PlanSwitchBillingTest`）；
  - 后发布的价格**不回溯**历史事件，历史账单重算结果不变（结果可复现）；
  - 乱序补录与按序上报，只要事件集合相同，账单逐行一致
    （事件按 `(eventTime, eventId)` 稳定排序回放）。
- 出账时若某事件没有任何生效价格 => `PRICE_NOT_FOUND(404)`，不会静默按 0 计。
- 金额单位为最小货币单位（分），用量行金额 = `数量净值 × 单价`（`long` 精确运算，
  无浮点误差；乘法溢出快速失败）。

## 4. 出账与账单版本

- `draft`：纯只读重算，可反复调用，不改变账期与账单存储。
- `finalize`：OPEN 账期先 `close` 再生成 `finalized=true` 账单；
  账期已 CLOSED 且已有定账单时，**幂等返回原定账单**，不产生新版本。
- 账单字段：按 `(metric, priceVersion)` 聚合的 `USAGE` 行 + 每条金额调整一条
  `ADJUSTMENT` 行，以及 `totalQuantity / totalAmountMinor / eventCount /
  correctionCount / computedAt`。
- 账单版本只增不覆盖：`BillRepository` 为每个 `(account,period)` 维护不可变版本序列，
  可通过 `GET /api/bills?version=n` 精确复现任一期历史结果。

## 5. 人工纠错边界

### 5.1 两种纠错类型

| 类型 | 适用状态 | 作用方式 |
| --- | --- | --- |
| `AMOUNT_ADJUSTMENT` | CLOSED / REOPENED | 带符号金额调整，独立 `ADJUSTMENT` 行（账期保持关闭） |
| `USAGE_DELTA` | 仅 REOPENED | 带符号用量（负数冲正/正数补录），生成 `kind=CORRECTION` 事件，参与用量行聚合 |

- 两种操作都要求 **ADMIN** 且必须提供 `reason`；`USAGE_DELTA` 可带 `linkedEventId`
  指向被冲正的原事件。
- `USAGE_DELTA` 的纠错事件时间固定为目标账期 1 日 00:00 UTC，保证每次重算稳定。
- OPEN 账期没有"纠错"概念：漏报直接以普通事件补录（拒绝纠错路径，返回 422，
  防止把正常补录误记为人工干预）。

### 5.2 不重复扣费 / 总量守恒

- 纠错单以 `correctionId` 全局幂等；同单重试（含并发）内容一致 => 返回当前最新账单，
  **不再生成新版本、不二次冲正**；同 id 不同内容 => `EVENT_ID_CONFLICT(409)`。
- 纠错在账期 bin 锁内完成"建单 → 落纠错事件 → 基于完整事件集合重算 → 追加账单版本"，
  账单新版本 = 旧事实 + 恰好一份纠错增量；不存在"旧账单扣费 + 新账单再扣一次"的叠加，
  因为任何版本的金额都由事件集合**全量重算**得出，而非在旧金额上做增量。
- 冲正后 `totalQuantity` = 账期内全部事件（含负数纠错事件）代数和，天然守恒
  （见 `CorrectionRecomputeTest`：100 → -20 冲正 → 80；+20 补录 → 100）。
- 定账之后的纠错产生的新版本仍标记 `finalized=true`，表示"这是当前生效的定账结果"；
  旧 finalized 版本原样保留用于审计复现。

### 5.3 并发一致性总结

| 场景 | 机制 |
| --- | --- |
| 并发/重试写同一事件 | 全局事件表 `putIfAbsent` + 账期锁内"查重→门禁→受理" |
| 并发关闭账期与写入 | 同账期 compute 串行，门禁与受理原子 |
| 并发价格发布 | 价格列表监视器内分配递增版本号；已发布版本不可变 |
| 并发纠错/重算 | 账期锁内建单+重算；账单列表监视器内分配版本号；`correctionId` 幂等 |

## 6. 范围与限制（设计边界）

- 存储为**进程内内存**实现：重启数据清空，定位为本地可验证/单机演示的领域实现；
  仓储接口已隔离，切换持久化实现时幂等键唯一约束、账期行锁/乐观版本需在持久层保留。
- 鉴权用 `X-Operator-Role` 请求头模拟，无真实登录/凭据体系；接入真实系统时应替换为
  认证主体的授权判定。
- 不涉及税费、预付款抵扣、真实支付渠道调用。
