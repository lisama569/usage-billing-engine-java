package com.github.lisama569.usagebillingenginejava.repository;

import com.github.lisama569.usagebillingenginejava.domain.PeriodState;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 账期存储。
 * 每个账期在 ConcurrentHashMap 的同一 bin 内用 compute 完成"读取-判定-变更"，
 * 即按账期键串行；不同账期互不阻塞、不会长时间全局锁。
 */
@Component
public class PeriodRepository {

    private final ConcurrentHashMap<String, PeriodState> periods = new ConcurrentHashMap<>();

    private static String key(String accountId, String period) {
        return accountId + "|" + period;
    }

    /** 只读视图；账期不存在时返回一个临时 OPEN 视图（不落库，无副作用）。 */
    public PeriodState view(String accountId, String period) {
        PeriodState state = periods.get(key(accountId, period));
        return state != null ? state : new PeriodState(accountId, period);
    }

    public PeriodState getOrCreate(String accountId, String period) {
        return periods.computeIfAbsent(key(accountId, period),
                k -> new PeriodState(accountId, period));
    }

    /**
     * 在账期键的 bin 锁内原子执行 action。
     * action 返回 null 表示放弃变更（保持原状态）；其返回值原样透传给调用方。
     */
    public <T> T mutate(String accountId, String period, Function<PeriodState, T> action) {
        @SuppressWarnings("unchecked")
        T[] holder = (T[]) new Object[1];
        periods.compute(key(accountId, period), (k, existing) -> {
            PeriodState state = existing != null ? existing : new PeriodState(accountId, period);
            T result = action.apply(state);
            holder[0] = result;
            return result == null ? existing : state;
        });
        return holder[0];
    }
}
