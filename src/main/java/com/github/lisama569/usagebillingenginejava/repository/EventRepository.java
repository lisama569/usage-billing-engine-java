package com.github.lisama569.usagebillingenginejava.repository;

import com.github.lisama569.usagebillingenginejava.domain.UsageEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 用量事件内存存储。
 * - byId：全局事件幂等表（eventId 唯一）；
 * - byAccountPeriod：账期事件列表，只追加；读时按事件时间+eventId 稳定排序。
 */
@Component
public class EventRepository {

    private final ConcurrentHashMap<String, UsageEvent> byId = new ConcurrentHashMap<>();

    /** key = accountId + '|' + period */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<UsageEvent>> byAccountPeriod =
            new ConcurrentHashMap<>();

    private static String key(String accountId, String period) {
        return accountId + "|" + period;
    }

    /** 幂等插入：若 eventId 已存在，返回已有事件，不重复追加。 */
    public UsageEvent saveIfAbsent(UsageEvent event, String period) {
        UsageEvent existing = byId.putIfAbsent(event.eventId(), event);
        if (existing != null) {
            return existing;
        }
        byAccountPeriod.computeIfAbsent(key(event.accountId(), period), k -> new CopyOnWriteArrayList<>())
                .add(event);
        return event;
    }

    public boolean exists(String eventId) {
        return byId.containsKey(eventId);
    }

    public Optional<UsageEvent> findById(String eventId) {
        return Optional.ofNullable(byId.get(eventId));
    }

    /** 按 (eventTime, eventId) 排序，保证乱序补录后出账结果确定稳定。 */
    public List<UsageEvent> findByAccountAndPeriod(String accountId, String period) {
        CopyOnWriteArrayList<UsageEvent> list = byAccountPeriod.get(key(accountId, period));
        if (list == null) {
            return List.of();
        }
        List<UsageEvent> copy = new ArrayList<>(list);
        copy.sort(Comparator.comparing(UsageEvent::eventTime).thenComparing(UsageEvent::eventId));
        return copy;
    }
}
