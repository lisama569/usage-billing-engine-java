package com.github.lisama569.usagebillingenginejava.repository;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按键分段加锁工具（当前实现直接使用 ConcurrentHashMap.compute 的 bin 互斥，
 * 保留该类以便后续需要跨聚合协调时复用）。
 */
public final class LockManager {

    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public Object lockFor(String key) {
        return locks.computeIfAbsent(key, k -> new Object());
    }
}
