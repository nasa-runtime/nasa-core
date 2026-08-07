package io.github.nasaruntime.core.base;

import io.github.nasaruntime.core.function.Action;

import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

/**
 * 分布式锁
 */
public interface DistributedLock {

    /**
     * 业务作用：按键取得分布式锁，由具体实现决定其后端。
     *
     * @param key 上下文键
     * 返回: 该键对应的分布式锁。
     */
    Lock getLock(String key);

    /**
     * 业务作用：取锁后执行业务逻辑，结束时无论正常还是异常都释放锁。
     *
     * @param key 上下文键
     * @param supplier 取到锁后执行的业务逻辑
     * 返回: 业务逻辑的返回值。
     */
    default <T> T lockAndUnlock(String key, Supplier<T> supplier) {
        Lock lock = getLock(key);
        lock.lock();
        try {
            return supplier.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：取锁后执行业务逻辑，结束时无论正常还是异常都释放锁。
     *
     * @param key 上下文键
     * @param action 条件成立时执行的动作
     * 返回: 业务逻辑的返回值。
     */
    default void lockAndUnlock(String key, Action action) {
        Lock lock = getLock(key);
        lock.lock();
        try {
            action.action();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：尝试取锁，取到则执行业务逻辑，取不到立即走回退而不阻塞。用于宁可跳过也不愿排队的场景。
     *
     * @param key 上下文键
     * @param supplier 取到锁后执行的业务逻辑
     * @param dft 取不到时返回的默认值
     * 返回: 取到锁时返回业务逻辑结果，否则返回回退结果或默认值。
     */
    default <T> T tryLockAndUnlock(String key, Supplier<T> supplier, T dft) {
        Lock lock = getLock(key);
        if (!lock.tryLock()) {
            if (lock instanceof ObjectPool.Recycler<?> r) r.recycle();
            return dft;
        }
        try {
            return supplier.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：尝试取锁，取到则执行业务逻辑，取不到立即走回退而不阻塞。用于宁可跳过也不愿排队的场景。
     *
     * @param key 上下文键
     * @param supplier 取到锁后执行的业务逻辑
     * @param tryFail 抢锁失败时执行的回退逻辑
     * 返回: 取到锁时返回业务逻辑结果，否则返回回退结果或默认值。
     */
    default <T> T tryLockAndUnlock(String key, Supplier<T> supplier, Supplier<T> tryFail) {
        Lock lock = getLock(key);
        if (!lock.tryLock()) {
            if (lock instanceof ObjectPool.Recycler<?> r) r.recycle();
            return tryFail.get();
        }
        try {
            return supplier.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：尝试取锁，取到则执行业务逻辑，取不到立即走回退而不阻塞。用于宁可跳过也不愿排队的场景。
     *
     * @param key 上下文键
     * @param action 条件成立时执行的动作
     * 返回: 取到锁时返回业务逻辑结果，否则返回回退结果或默认值。
     */
    default void tryLockAndUnlock(String key, Action action) {
        Lock lock = getLock(key);
        if (!lock.tryLock()) {
            if (lock instanceof ObjectPool.Recycler<?> r) r.recycle();
            return;
        }
        try {
            action.action();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：尝试取锁，取到则执行业务逻辑，取不到立即走回退而不阻塞。用于宁可跳过也不愿排队的场景。
     *
     * @param key 上下文键
     * @param action 条件成立时执行的动作
     * @param tryFail 抢锁失败时执行的回退逻辑
     * 返回: 取到锁时返回业务逻辑结果，否则返回回退结果或默认值。
     */
    default void tryLockAndUnlock(String key, Action action, Action tryFail) {
        Lock lock = getLock(key);
        if (!lock.tryLock()) {
            if (lock instanceof ObjectPool.Recycler<?> r) r.recycle();
            tryFail.action();
            return;
        }
        try {
            action.action();
        } finally {
            lock.unlock();
        }
    }
}
