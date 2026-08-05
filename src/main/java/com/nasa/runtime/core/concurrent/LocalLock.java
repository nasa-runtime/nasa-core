package com.nasa.runtime.core.concurrent;

import com.nasa.runtime.core.base.ObjectPool;
import com.nasa.runtime.core.exception.LocalLockException;
import com.nasa.runtime.core.utils.ContextUtils;
import lombok.Getter;
import lombok.Setter;

import java.io.Closeable;
import java.io.Serial;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Nasa
 * 注意：此锁不能用于Condition条件唤醒，只能加锁解锁
 */
@SuppressWarnings("all")
public class LocalLock extends ReentrantLock implements ObjectPool.Recycler<LocalLock>, Closeable {

    @Serial
    private static final long serialVersionUID = -40349292638916773L;

    /* 公平锁对象池 */
    private static final LocalLockPool FAIR_POOL = new LocalLockPool(true);
    /* 非公平锁对象池 */
    private static final LocalLockPool UNFAIR_POOL = new LocalLockPool(false);

    /* 对执行lock和tryLock成功的线程计数，表示当前有多少线程正在使用这把锁，锁重入也会加1 */
    private final AtomicInteger counter = new AtomicInteger(0);
    /* 锁标记 */
    @Setter
    @Getter
    private String lockKey;
    /* per-instance Handle, 池引用按 fair flag 锁定到对应 FAIR/UNFAIR pool */
    private final ObjectPool.PooledHandle<LocalLock> handle;

    private LocalLock(boolean fair) {
        super(fair);
        this.handle = new ObjectPool.PooledHandle<>(pool(fair));
    }

    @Override
    public void lock() {
        counter.incrementAndGet();
        super.lock();
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        counter.incrementAndGet();
        try {
            super.lockInterruptibly();
        } catch (InterruptedException e) {
            counter.decrementAndGet();
            throw e;
        }
    }

    @Override
    public boolean tryLock() {
        counter.incrementAndGet();
        boolean success = super.tryLock();
        if (!success) {
            counter.decrementAndGet();
        }
        return success;
    }

    @Override
    public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
        counter.incrementAndGet();
        boolean success = false;
        try {
            return success = super.tryLock(timeout, unit);
        } finally {
            if (!success) counter.decrementAndGet();
        }
    }

    @Override
    public void unlock() {
        super.unlock();
        counter.decrementAndGet();
    }

    @Override
    public Condition newCondition() {
        throw new LocalLockException("LocalLock 类不能使用Condition条件");
    }

    /**
     * 是否空闲
     */
    public boolean isUnlocked() {
        return !super.isLocked();
    }

    /**
     * 是否有线程正在 lock 和 tryLock
     */
    public boolean isUsed() {
        return counter.get() > 0;
    }

    /**
     * 是否空闲
     * 如果所有线程都 unlock 了，那么这个值一定会是0
     */
    public boolean isUnused() {
        return counter.get() == 0;
    }

    /**
     * 实现 Closeable 接口，实现自动解锁
     *      try (LocalLock lock = new LocalLock()) {
     *          lock.lock();
     *      }
     * 以上try块执行结束，会自动调用close()方法实现自动解锁
     */
    @Override
    public void close() {
        if (super.isLocked()) {
            this.unlock();
        }
    }

    @Override
    public ObjectPool.PooledHandle<LocalLock> handle() {
        return this.handle;
    }

    @Override
    public void restore() {
        this.lockKey = null;
        this.counter.set(0);
    }
    
    private static ObjectPool<LocalLock> pool(boolean fair) {
        return fair ? FAIR_POOL : UNFAIR_POOL;
    }
    
    public static LocalLock fromPool(boolean fair) {
        return pool(fair).get();
    }

    public static LocalLock fromPool(boolean fair, String lockKey) {
        LocalLock lock = fromPool(fair);
        lock.setLockKey(lockKey);
        return lock;
    }

    /**
     * @param lockKey 上锁的key
     */
    public static LocalLock fromPool(String lockKey) {
        return fromPool(false, lockKey);
    }

    /**
     * 锁对象池
     */
    static class LocalLockPool extends ObjectPool<LocalLock> {

        private final boolean fair;

        public LocalLockPool(boolean fair) {
            super(ContextUtils.getPropertyInt("nasa.object-pool.local-lock-capacity", 1000));
            this.fair = fair;
        }

        @Override
        public LocalLock newObject() {
            return new LocalLock(fair);
        }

    }
}
