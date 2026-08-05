package com.nasa.runtime.core.base;

import com.nasa.runtime.core.function.Action;

import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

/**
 * 分布式锁
 */
public interface DistributedLock {

    /**
     * 获取一把分布式锁
     */
    Lock getLock(String key);

    default <T> T lockAndUnlock(String key, Supplier<T> supplier) {
        Lock lock = getLock(key);
        lock.lock();
        try {
            return supplier.get();
        } finally {
            lock.unlock();
        }
    }

    default void lockAndUnlock(String key, Action action) {
        Lock lock = getLock(key);
        lock.lock();
        try {
            action.action();
        } finally {
            lock.unlock();
        }
    }

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
