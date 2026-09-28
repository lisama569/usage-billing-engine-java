package com.github.lisama569.usagebillingenginejava.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.usagebillingenginejava.api.dto.BillLineView;
import com.github.lisama569.usagebillingenginejava.api.dto.BillView;
import com.github.lisama569.usagebillingenginejava.api.dto.CorrectionRequest;
import com.github.lisama569.usagebillingenginejava.api.dto.PeriodStatusView;
import com.github.lisama569.usagebillingenginejava.domain.Bill;
import com.github.lisama569.usagebillingenginejava.domain.BillingPeriod;
import com.github.lisama569.usagebillingenginejava.domain.BillingPeriodStatus;
import com.github.lisama569.usagebillingenginejava.domain.CorrectionRecord;
import com.github.lisama569.usagebillingenginejava.domain.CorrectionType;
import com.github.lisama569.usagebillingenginejava.domain.PricePlanVersion;
import com.github.lisama569.usagebillingenginejava.domain.UsageEvent;
import com.github.lisama569.usagebillingenginejava.error.BillingException;
import com.github.lisama569.usagebillingenginejava.error.ErrorCode;
import com.github.lisama569.usagebillingenginejava.repo.BillRepository;
import com.github.lisama569.usagebillingenginejava.repo.BillingPeriodRepository;
import com.github.lisama569.usagebillingenginejava.repo.CorrectionRecordRepository;
import com.github.lisama569.usagebillingenginejava.repo.PricePlanVersionRepository;
import com.github.lisama569.usagebillingenginejava.repo.UsageEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final BillingPeriodRepository periodRepo;
    private final UsageEventRepository eventRepo;
    private final PricePlanVersionRepository planRepo;
    private final BillRepository billRepo;
    private final CorrectionRecordRepository correctionRepo;
    private final TxActions txActions;

    public BillingService(BillingPeriodRepository periodRepo,
                          UsageEventRepository eventRepo,
                          PricePlanVersionRepository planRepo,
                          BillRepository billRepo,
                          CorrectionRecordRepository correctionRepo,
                          TxActions txActions) {
        this.periodRepo = periodRepo;
        this.eventRepo = eventRepo;
        this.planRepo = planRepo;
        this.billRepo = billRepo;
        this.correctionRepo = correctionRepo;
        this.txActions = txActions;
    }

    // ============================ 账期状态 ============================

    @Transactional(readOnly = true)
    public PeriodStatusView getStatus(String accountId, String period) {
        BillingPeriod p = periodRepo.findByAccountIdAndPeriod(accountId, period).orElse(null);
        BillingPeriodStatus status = p == null ? BillingPeriodStatus.OPEN : p.getStatus();
        int reopenCount = p == null ? 0 : p.getReopenCount();
        log.info("查询账期状态 account={}, period={}, 行存在={}, status={}, reopenCount={}",
                accountId, period, p != null, status, reopenCount);
        return new PeriodStatusView(accountId, period, status.name(), reopenCount);
    }

    /**
     * 关账：OPEN/REOPENED -> CLOSED，锁定账期串行执行，按当前 ACTIVE 事件集出一张 FINAL 账单。
     * 对已 CLOSED 的账期重复关账为幂等操作，返回现有状态，不产生新版本、不重复扣费。
     */
    public PeriodStatusView closePeriod(String accountId, String period, String operator) {
        log.info("请求关账 account={}, period={}, operator={}", accountId, period, operator);
        return txActions.runLocked(accountId, period, () -> doClosePeriod(accountId, period, operator));
    }

    private PeriodStatusView doClosePeriod(String accountId, String period, String operator) {
        BillingPeriod p = lockOrCreatePeriod(accountId, period);
        if (p.getStatus() == BillingPeriodStatus.CLOSED) {
            log.info("账期已为 CLOSED，关账幂等返回 account={}, period={}", accountId, period);
            return statusView(p);
        }
        Computed computed = compute(accountId, period);
        saveNewVersion(accountId, period, computed, "FINAL");
        p.setStatus(BillingPeriodStatus.CLOSED);
        p.setClosedBy(operator);
        p.setClosedAtEpochMs(System.currentTimeMillis());
        log.info("关账完成 account={}, period={}, amount={}, 有效量={}, 冲正量={}, 判定 OPEN/REOPENED->CLOSED",
                accountId, period, computed.totalAmount, computed.totalQuantity, computed.reversedQuantity);
        return statusView(p);
    }

    /**
     * 受控重开：仅 CLOSED -> REOPENED（记 reopenCount），在账期命名锁内执行。
     * OPEN/REOPENED 上重开为状态冲突（409）；账期不存在为 NOT_FOUND（404）。
     */
    public PeriodStatusView reopenPeriod(String accountId, String period, String operator) {
        log.info("请求重开账期 account={}, period={}, operator={}", accountId, period, operator);
        return txActions.runLocked(accountId, period, () -> {
            BillingPeriod p = periodRepo.findByAccountIdAndPeriod(accountId, period)
                    .orElseThrow(() -> new BillingException(ErrorCode.NOT_FOUND,
                            "账期不存在: " + accountId + "/" + period));
            if (p.getStatus() != BillingPeriodStatus.CLOSED) {
                log.warn("重开被拒 account={}, period={}, 当前状态={}，判定=PERIOD_STATUS_CONFLICT",
                        accountId, period, p.getStatus());
                throw new BillingException(ErrorCode.PERIOD_STATUS_CONFLICT,
                        "只有 CLOSED 账期可以重开，当前状态=" + p.getStatus());
            }
            p.setStatus(BillingPeriodStatus.REOPENED);
            p.setReopenCount(p.getReopenCount() + 1);
            log.info("账期重开完成 account={}, period={}, reopenCount={}, 判定 CLOSED->REOPENED",
                    accountId, period, p.getReopenCount());
            return statusView(p);
        });
    }

    // ============================ 账单 ============================

    /**
     * 查询账期最新账单：
     * - CLOSED/REOPENED：返回已持久化的最新版本（没有则按当前数据补算）；
     * - OPEN：返回不落库的试算结果（version=0, DRAFT），事件继续写入不产生版本碎片。
     */
    @Transactional(readOnly = true)
    public BillView getBill(String accountId, String period) {
        BillingPeriod p = periodRepo.findByAccountIdAndPeriod(accountId, period).orElse(null);
        Optional<Bill> latest = billRepo.findFirstByAccountIdAndPeriodOrderByVersionDesc(accountId, period);
        if (latest.isPresent()) {
            log.info("读取已存账单 account={}, period={}, version={}, status={}",
                    accountId, period, latest.get().getVersion(), latest.get().getStatus());
            return toView(latest.get());
        }
        Computed computed = compute(accountId, period);
        int version = 0;
        String status = p != null && p.getStatus() == BillingPeriodStatus.CLOSED ? "FINAL" : "DRAFT";
        log.info("无已存账单，试算返回 account={}, period={}, amount={}, status={}",
                accountId, period, computed.totalAmount, status);
        return new BillView(accountId, period, version, status,
                computed.totalQuantity, computed.reversedQuantity, computed.totalAmount,
                computed.lines, System.currentTimeMillis());
    }

    /**
     * 管理员重算（ADMIN）：OPEN 返回试算不落库；REOPENED 追加一版 DRAFT 快照
     * （纠错后可反复重算，结果只取决于当前事件集与历史价格版本，保证可复现）；
     * CLOSED 拒绝重算，避免改动冻结结果。
     */
    public BillView recompute(String accountId, String period) {
        return txActions.runLocked(accountId, period, () -> doRecompute(accountId, period));
    }

    private BillView doRecompute(String accountId, String period) {
        BillingPeriod p = lockOrCreatePeriod(accountId, period);
        if (p.getStatus() == BillingPeriodStatus.CLOSED) {
            log.warn("重算被拒：账期已冻结 account={}, period={}, 判定=PERIOD_STATUS_CONFLICT",
                    accountId, period);
            throw new BillingException(ErrorCode.PERIOD_STATUS_CONFLICT,
                    "CLOSED 账期不可重算，如需修改请先受控重开");
        }
        Computed computed = compute(accountId, period);
        if (p.getStatus() == BillingPeriodStatus.REOPENED) {
            Bill bill = saveNewVersion(accountId, period, computed, "DRAFT");
            log.info("重算追加草稿版本 account={}, period={}, version={}, amount={}",
                    accountId, period, bill.getVersion(), computed.totalAmount);
            return toView(bill);
        }
        log.info("OPEN 账期试算（不落库）account={}, period={}, amount={}", accountId, period, computed.totalAmount);
        return new BillView(accountId, period, 0, "DRAFT",
                computed.totalQuantity, computed.reversedQuantity, computed.totalAmount,
                computed.lines, System.currentTimeMillis());
    }

    // ============================ 人工纠错 ============================

    /**
     * 纠错（ADMIN，账期必须 REOPENED）。correctionId 唯一保证纠错重试不重复生效：
     * - BACKFILL：补录一条事件（带 correctionId 标记），参与重算；
     * - REVERSAL：将一条已存在且未冲正的事件标记 reversed=true（不删行，总量守恒）。
     * 处理完追加一版 DRAFT 账单，纠正金额差异，绝不产生第二笔扣费。
     */
    public BillView applyCorrection(CorrectionRequest req, String operator) {
        log.info("收到纠错 correctionId={}, account={}, period={}, type={}, eventId={}, qty={}, eventTime={}, operator={}, reason={}",
                req.correctionId(), req.accountId(), req.period(), req.type(), req.eventId(),
                req.quantity(), req.eventTimeEpochMs(), operator, req.reason());

        // 幂等预检放在事务外：重试同一 correctionId 直接返回当前账单，绝不二次生效
        if (correctionRepo.findByCorrectionId(req.correctionId()).isPresent()) {
            log.info("纠错幂等重放 correctionId={}，判定=IDEMPOTENT_REPLAY", req.correctionId());
            return getBill(req.accountId(), req.period());
        }
        return txActions.runLocked(req.accountId(), req.period(), () -> doApplyCorrection(req, operator));
    }

    private BillView doApplyCorrection(CorrectionRequest req, String operator) {
        // 锁内二次确认（与并发纠错竞争）
        if (correctionRepo.findByCorrectionId(req.correctionId()).isPresent()) {
            log.info("锁内发现 correctionId={} 已存在，按幂等返回", req.correctionId());
            return getBill(req.accountId(), req.period());
        }

        BillingPeriod p = lockOrCreatePeriod(req.accountId(), req.period());
        if (p.getStatus() != BillingPeriodStatus.REOPENED) {
            log.warn("纠错被拒：账期未处于 REOPENED account={}, period={}, status={}, 判定=PERIOD_STATUS_CONFLICT",
                    req.accountId(), req.period(), p.getStatus());
            throw new BillingException(ErrorCode.PERIOD_STATUS_CONFLICT,
                    "纠错只允许在 REOPENED 账期执行，当前状态=" + p.getStatus());
        }

        CorrectionType type = parseCorrectionType(req.type());
        String targetEventId;
        if (type == CorrectionType.BACKFILL) {
            targetEventId = doBackfill(req, operator);
        } else {
            targetEventId = doReversal(req, operator);
        }

        Computed computed = compute(req.accountId(), req.period());
        Bill draft = saveNewVersion(req.accountId(), req.period(), computed, "DRAFT");

        CorrectionRecord rec = new CorrectionRecord();
        rec.setCorrectionId(req.correctionId());
        rec.setCorrectionType(type);
        rec.setAccountId(req.accountId());
        rec.setPeriod(req.period());
        rec.setTargetEventId(targetEventId);
        rec.setOperator(operator);
        rec.setReason(req.reason());
        rec.setBillVersionAfter(draft.getVersion());
        rec.setCreatedAt(System.currentTimeMillis());
        try {
            correctionRepo.saveAndFlush(rec);
        } catch (DataIntegrityViolationException dup) {
            // 并发下相同 correctionId 已提交：返回冲突，调用方可安全重试（重试走幂等分支）
            log.info("correctionId={} 并发撞唯一约束，返回 CORRECTION_CONFLICT 可重试", req.correctionId());
            throw new BillingException(ErrorCode.CORRECTION_CONFLICT,
                    "纠错 " + req.correctionId() + " 正在被并发处理，请重试（重试为幂等）");
        }
        log.info("纠错完成 correctionId={}, type={}, target={}, 新账单 version={}, amount={}, 有效量={}, 冲正量={}",
                req.correctionId(), type, targetEventId, draft.getVersion(),
                computed.totalAmount, computed.totalQuantity, computed.reversedQuantity);
        return toView(draft);
    }

    private String doBackfill(CorrectionRequest req, String operator) {
        if (req.eventId() == null || req.eventId().isBlank()
                || req.metric() == null || req.metric().isBlank()
                || req.quantity() == null || req.eventTimeEpochMs() == null) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED,
                    "BACKFILL 必须提供 eventId/metric/quantity/eventTimeEpochMs");
        }
        if (eventRepo.findByEventId(req.eventId()).isPresent()) {
            log.warn("补录冲突：eventId={} 已存在，判定=EVENT_ID_CONFLICT", req.eventId());
            throw new BillingException(ErrorCode.EVENT_ID_CONFLICT,
                    "补录事件 eventId=" + req.eventId() + " 已存在");
        }
        String eventPeriod = PeriodKeys.ofEpochMs(req.eventTimeEpochMs());
        if (!eventPeriod.equals(req.period())) {
            log.warn("补录校验失败：eventTime 归属 {}，纠错账期 {}，判定=VALIDATION_FAILED",
                    eventPeriod, req.period());
            throw new BillingException(ErrorCode.VALIDATION_FAILED,
                    "补录事件时间归属账期 " + eventPeriod + "，与纠错目标账期 " + req.period() + " 不一致");
        }
        UsageEvent e = new UsageEvent();
        e.setEventId(req.eventId());
        e.setAccountId(req.accountId());
        e.setMetric(req.metric());
        e.setQuantity(req.quantity());
        e.setEventTime(req.eventTimeEpochMs());
        e.setReceivedAt(System.currentTimeMillis());
        e.setPeriod(req.period());
        e.setReversed(false);
        e.setCorrectionId(req.correctionId());
        try {
            eventRepo.saveAndFlush(e);
        } catch (DataIntegrityViolationException dup) {
            throw new BillingException(ErrorCode.EVENT_ID_CONFLICT,
                    "补录事件 eventId=" + req.eventId() + " 在并发下已存在");
        }
        log.info("补录事件已计入 eventId={}, correctionId={}, period={}",
                req.eventId(), req.correctionId(), req.period());
        return req.eventId();
    }

    private String doReversal(CorrectionRequest req, String operator) {
        if (req.eventId() == null || req.eventId().isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED, "REVERSAL 必须提供 eventId");
        }
        UsageEvent target = eventRepo.findByEventId(req.eventId())
                .orElseThrow(() -> {
                    log.warn("冲正目标不存在 eventId={}, 判定=NOT_FOUND", req.eventId());
                    return new BillingException(ErrorCode.NOT_FOUND,
                            "待冲正事件 eventId=" + req.eventId() + " 不存在");
                });
        if (!target.getAccountId().equals(req.accountId()) || !target.getPeriod().equals(req.period())) {
            log.warn("冲正校验失败：事件归属 {}/{}，纠错目标 {}/{}，判定=VALIDATION_FAILED",
                    target.getAccountId(), target.getPeriod(), req.accountId(), req.period());
            throw new BillingException(ErrorCode.VALIDATION_FAILED,
                    "事件不属于指定的 account/period");
        }
        if (!"ACTIVE".equals(target.getState())) {
            log.warn("冲正冲突：eventId={} 状态={}（非 ACTIVE），判定=CORRECTION_CONFLICT",
                    req.eventId(), target.getState());
            throw new BillingException(ErrorCode.CORRECTION_CONFLICT,
                    "事件 eventId=" + req.eventId() + " 当前状态=" + target.getState()
                            + "，仅 ACTIVE 事件可冲正，不能重复冲正");
        }
        target.setState("REVERSED");
        target.setReversed(true);
        target.setReversedBy(operator);
        log.info("事件已冲正 eventId={}, period={}, qty={}, operator={}",
                target.getEventId(), target.getPeriod(), target.getQuantity(), operator);
        return target.getEventId();
    }

    // ============================ 计算口径（唯一） ============================

    /**
     * 账单计算口径，全系统唯一入口：
     * 取账期内全部事件（含已冲正，按 eventTime 排序）逐条处理——
     * 未冲正事件按 eventTime 命中的历史价格版本计价（quantity * unitPrice）；
     * 已冲正事件金额计 0 并单列。任何事件找不到适用价格版本即 PRICE_NOT_DEFINED。
     */
    private Computed compute(String accountId, String period) {
        // ACTIVE 行计价；REVERSED 行单列（金额0）；PENDING 属于未完成写入，任何账单都不计入
        List<UsageEvent> active = eventRepo
                .findByAccountIdAndPeriodAndStateOrderByEventTimeAsc(accountId, period, "ACTIVE");
        List<UsageEvent> reversed = eventRepo
                .findByAccountIdAndPeriodAndStateOrderByEventTimeAsc(accountId, period, "REVERSED");
        List<BillLineView> lines = new ArrayList<>();
        long totalQuantity = 0;
        long reversedQuantity = 0;
        long totalAmount = 0;

        for (UsageEvent e : active) {
            PricePlanVersion price = planRepo.findPriceAt(accountId, e.getMetric(), e.getEventTime())
                    .orElseThrow(() -> {
                        log.warn("价格缺失 account={}, metric={}, eventTime={}, eventId={}, 判定=PRICE_NOT_DEFINED",
                                accountId, e.getMetric(), e.getEventTime(), e.getEventId());
                        return new BillingException(ErrorCode.PRICE_NOT_DEFINED,
                                "事件 eventId=" + e.getEventId() + " 的时间 " + e.getEventTime()
                                        + " 没有适用的价格版本");
                    });
            long lineAmount = e.getQuantity() * price.getUnitPrice();
            totalQuantity += e.getQuantity();
            totalAmount += lineAmount;
            lines.add(new BillLineView(e.getEventId(), e.getMetric(), e.getQuantity(),
                    e.getEventTime(), price.getUnitPrice(), lineAmount, false, e.getCorrectionId()));
        }
        for (UsageEvent e : reversed) {
            reversedQuantity += e.getQuantity();
            lines.add(new BillLineView(e.getEventId(), e.getMetric(), e.getQuantity(),
                    e.getEventTime(), null, 0L, true, e.getCorrectionId()));
        }
        lines.sort((a, b) -> Long.compare(a.eventTimeEpochMs(), b.eventTimeEpochMs()));
        log.info("账单计算 account={}, period={}, ACTIVE={}, REVERSED={}, 有效量={}, 冲正量={}, amount={}",
                accountId, period, active.size(), reversed.size(),
                totalQuantity, reversedQuantity, totalAmount);
        return new Computed(lines, totalQuantity, reversedQuantity, totalAmount);
    }

    private Bill saveNewVersion(String accountId, String period, Computed computed, String status) {
        int nextVersion = billRepo.findFirstByAccountIdAndPeriodOrderByVersionDesc(accountId, period)
                .map(b -> b.getVersion() + 1)
                .orElse(1);
        Bill bill = new Bill();
        bill.setAccountId(accountId);
        bill.setPeriod(period);
        bill.setVersion(nextVersion);
        bill.setStatus(status);
        bill.setTotalQuantity(computed.totalQuantity);
        bill.setReversedQuantity(computed.reversedQuantity);
        bill.setTotalAmount(computed.totalAmount);
        bill.setDetailJson(toJson(computed.lines));
        bill.setCreatedAt(System.currentTimeMillis());
        billRepo.save(bill);
        return bill;
    }

    // ============================ 基础设施 ============================

    private BillingPeriod lockOrCreatePeriod(String accountId, String period) {
        // 调用方已持有账期命名锁；MERGE 保证账期行存在（已存在时无影响）
        periodRepo.mergeOpenPeriod(accountId, period);
        return periodRepo.findByAccountIdAndPeriod(accountId, period)
                .orElseThrow(() -> new IllegalStateException("账期行初始化失败 " + accountId + "/" + period));
    }

    private CorrectionType parseCorrectionType(String type) {
        try {
            return CorrectionType.valueOf(type);
        } catch (IllegalArgumentException ex) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED,
                    "未知纠错类型 type=" + type + "，仅支持 BACKFILL/REVERSAL");
        }
    }

    private PeriodStatusView statusView(BillingPeriod p) {
        return new PeriodStatusView(p.getAccountId(), p.getPeriod(),
                p.getStatus().name(), p.getReopenCount());
    }

    private BillView toView(Bill bill) {
        return new BillView(bill.getAccountId(), bill.getPeriod(), bill.getVersion(),
                bill.getStatus(), bill.getTotalQuantity(), bill.getReversedQuantity(),
                bill.getTotalAmount(), fromJson(bill.getDetailJson()), bill.getCreatedAt());
    }

    private String toJson(List<BillLineView> lines) {
        try {
            return JSON.writeValueAsString(lines);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("账单明细序列化失败", e);
        }
    }

    private List<BillLineView> fromJson(String json) {
        try {
            return JSON.readerForListOf(BillLineView.class).readValue(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("账单明细反序列化失败", e);
        }
    }

    private record Computed(List<BillLineView> lines, long totalQuantity,
                            long reversedQuantity, long totalAmount) {
    }
}
