package com.github.lisama569.usagebillingenginejava.repository;

import com.github.lisama569.usagebillingenginejava.domain.Correction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 纠错单存储：correctionId 全局幂等，重复提交不会重复生效。
 * 同时按 (account,period) 维护列表，供出账重算时稳定回放。
 */
@Component
public class CorrectionRepository {

    private final ConcurrentHashMap<String, Correction> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Correction>> byPeriod =
            new ConcurrentHashMap<>();

    private static String key(String accountId, String period) {
        return accountId + "|" + period;
    }

    /** 幂等插入：已存在则返回原纠错单（调用方据此跳过重复生效）。 */
    public Correction saveIfAbsent(Correction correction) {
        Correction existing = byId.putIfAbsent(correction.correctionId(), correction);
        if (existing != null) {
            return existing;
        }
        byPeriod.computeIfAbsent(key(correction.accountId(), correction.period()),
                k -> new CopyOnWriteArrayList<>()).add(correction);
        return correction;
    }

    public Optional<Correction> findById(String correctionId) {
        return Optional.ofNullable(byId.get(correctionId));
    }

    /** 金额调整类纠错单，按 correctionId 稳定排序回放。 */
    public List<Correction> findAmountAdjustments(String accountId, String period) {
        CopyOnWriteArrayList<Correction> list = byPeriod.get(key(accountId, period));
        if (list == null) {
            return List.of();
        }
        List<Correction> copy = new ArrayList<>(list);
        copy.sort(Comparator.comparing(Correction::correctionId));
        return copy.stream().filter(c -> c.type() == Correction.Type.AMOUNT_ADJUSTMENT).toList();
    }
}
