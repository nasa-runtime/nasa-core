package com.nasa.runtime.core.config;

import com.nasa.runtime.core.base.KV;
import com.nasa.runtime.core.utils.ColUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Nasa 优雅停机
 */
@SuppressWarnings("unused")
@Slf4j
public abstract class Graceful {

    private Graceful() {}

    static final List<KV<Integer, Shutdown>> list = new ArrayList<>();
    private static final ReentrantLock lock = new ReentrantLock();

    /**
     * 注册停机逻辑
     */
    public static void registry(Shutdown shutdown) {
        registry(100, shutdown);
    }

    /**
     * 注册停机逻辑
     */
    public static void registry(int order, Shutdown shutdown) {
        lock.lock();
        try {
            for (KV<Integer, Shutdown> kv : list) if (kv.getValue() == shutdown) return;
            list.add(KV.of(order, shutdown));
            ColUtils.ascType(list, KV::getKey);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 执行
     */
    public static void shutdown() {
        for (KV<Integer, Shutdown> kv : list) {
            try {
                kv.getValue().shutdown();
            } catch (Throwable t) {
                log.error(t.getMessage(), t);
            }
        }
    }

    @FunctionalInterface
    public interface Shutdown {

        void shutdown();
    }
}
