package com.github.lisama569.usagebillingenginejava.repository;

import com.github.lisama569.usagebillingenginejava.domain.PriceVersion;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 价格版本存储：价格一旦发布即不可变，套餐/价格切换=追加新版本。
 * 版本号在 (accountId, metric) 维度从 1 递增。
 */
@Component
public class PriceRepository {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<PriceVersion>> versions =
            new ConcurrentHashMap<>();

    private static String key(String accountId, String metric) {
        return accountId + "|" + metric;
    }

    /** 追加新价格版本。调用方负责校验生效时间与权限；此处仅分配版本号并落库。 */
    public PriceVersion append(PriceVersion version) {
        CopyOnWriteArrayList<PriceVersion> list =
                versions.computeIfAbsent(key(version.accountId(), version.metric()),
                        k -> new CopyOnWriteArrayList<>());
        PriceVersion stored;
        synchronized (list) {
            int next = list.size() + 1;
            stored = new PriceVersion(version.accountId(), version.metric(), next,
                    version.unitPriceMinor(), version.effectiveFrom(), version.createdAt());
            list.add(stored);
        }
        return stored;
    }

    /** 按生效时间升序返回（同生效时间按版本号）。 */
    public List<PriceVersion> versions(String accountId, String metric) {
        CopyOnWriteArrayList<PriceVersion> list = versions.get(key(accountId, metric));
        if (list == null) {
            return List.of();
        }
        return list.stream()
                .sorted(Comparator.comparing(PriceVersion::effectiveFrom)
                        .thenComparingInt(PriceVersion::version))
                .toList();
    }
}
