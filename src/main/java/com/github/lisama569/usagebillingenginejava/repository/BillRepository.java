package com.github.lisama569.usagebillingenginejava.repository;

import com.github.lisama569.usagebillingenginejava.domain.Bill;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 账单存储：按 (account,period) 维护不可变版本列表。
 * 重算/纠错只追加新版本，旧版本永久保留 -> 历史结果可复现、可审计。
 */
@Component
public class BillRepository {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Bill>> bills = new ConcurrentHashMap<>();

    private static String key(String accountId, String period) {
        return accountId + "|" + period;
    }

    /**
     * 保存下一版本。版本号由存储层按当前大小+1 分配（在列表监视器内原子完成），
     * 调用方传入的版本号会被忽略。
     */
    public Bill saveNext(Bill bill) {
        CopyOnWriteArrayList<Bill> list =
                bills.computeIfAbsent(key(bill.accountId(), bill.period()),
                        k -> new CopyOnWriteArrayList<>());
        synchronized (list) {
            int next = list.size() + 1;
            Bill stored = new Bill(bill.accountId(), bill.period(), next, bill.finalized(),
                    bill.lines(), bill.totalQuantity(), bill.totalAmountMinor(),
                    bill.eventCount(), bill.correctionCount(), bill.computedAt());
            list.add(stored);
            return stored;
        }
    }

    public Optional<Bill> findLatest(String accountId, String period) {
        CopyOnWriteArrayList<Bill> list = bills.get(key(accountId, period));
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(list.get(list.size() - 1));
    }

    public Optional<Bill> findVersion(String accountId, String period, int version) {
        CopyOnWriteArrayList<Bill> list = bills.get(key(accountId, period));
        if (list == null) {
            return Optional.empty();
        }
        return list.stream().filter(b -> b.version() == version).findFirst();
    }

    public List<Bill> versions(String accountId, String period) {
        CopyOnWriteArrayList<Bill> list = bills.get(key(accountId, period));
        if (list == null) {
            return List.of();
        }
        List<Bill> copy = new ArrayList<>(list);
        copy.sort(Comparator.comparingInt(Bill::version));
        return copy;
    }
}
