package com.nasa.runtime.core.concurrent;

import com.nasa.runtime.core.base.RingInteger;
import com.nasa.runtime.core.base.RingLong;
import com.nasa.runtime.core.base.When;

import java.io.Serial;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Nasa 原子数字环（long 版）
 * 值域 [min, max)，到达边界自动回绕。所有运算 O(1)（基于 {@link Math#floorMod}）。
 */
@SuppressWarnings("unused")
public class AtomicRingLong extends Number implements When<AtomicLong> {

    @Serial
    private static final long serialVersionUID = -995072596953680913L;

    private final Lock lock = new ReentrantLock();
    private final AtomicLong value = new AtomicLong();
    private final WhenChain<AtomicLong> chain = WhenChain.of();

    /* value >= min */
    private long min;
    /* value < max */
    private long max;
    private Predicate<AtomicLong> whenMinFunc;
    private Predicate<AtomicLong> whenMaxFunc;

    public AtomicRingLong() {
        this(Long.MIN_VALUE, Long.MAX_VALUE);
    }

    public AtomicRingLong(long max) {
        this(0, max);
    }

    /**
     * 业务作用: 创建满足 {@code min <= value < max} 的原子长整数环。
     */
    public AtomicRingLong(long min, long max) {
        this.setRing(min, max);
    }

    // ==================== 环形运算核心（O(1)，单一入口） ====================

    private long wrap(long raw) {
        long range = max - min;
        if (range <= 0) return raw; // 完整 long 范围，自然溢出即回绕
        return min + Math.floorMod(raw - min, range);
    }

    /**
     * 所有写操作的统一收口：更新值 + 触发 When 事件。
     * 调用方必须持有 lock。
     */
    private void update(long newValue) {
        value.set(newValue);
        chain.fire(value);
    }

    // ==================== 配置 ====================

    public final void setRing(long min, long max) {
        if (min >= max) {
            throw new IllegalArgumentException("min must be less than max");
        }
        this.min = min;
        this.max = max;
        this.whenMinFunc = n -> n.get() == min;
        this.whenMaxFunc = n -> n.get() == max - 1;
        value.set(wrap(min));
        chain.fire(value);
    }

    /**
     * 复位到 min
     */
    public final void restore() {
        lock.lock();
        try {
            update(wrap(min));
        } finally {
            lock.unlock();
        }
    }

    // ==================== 读（无锁） ====================

    public final long get() {
        return value.get();
    }

    // ==================== 写（lock 保证值变更 + When 事件的原子性） ====================

    public final void set(long newValue) {
        if (newValue < min || newValue >= max) {
            throw new IllegalArgumentException("newValue must between " + min + " and " + max);
        }
        lock.lock();
        try {
            update(newValue);
        } finally {
            lock.unlock();
        }
    }

    public final boolean compareAndSet(long expectedValue, long newValue) {
        if (expectedValue < min || expectedValue >= max) {
            throw new IllegalArgumentException("expectedValue must between " + min + " and " + max);
        }
        if (newValue < min || newValue >= max) {
            throw new IllegalArgumentException("newValue must between " + min + " and " + max);
        }
        lock.lock();
        try {
            if (value.compareAndSet(expectedValue, newValue)) {
                chain.fire(value);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    public final long getAndAdd(long delta) {
        lock.lock();
        try {
            long old = value.get();
            update(wrap(old + delta));
            return old;
        } finally {
            lock.unlock();
        }
    }

    public final long addAndGet(long delta) {
        lock.lock();
        try {
            long v = wrap(value.get() + delta);
            update(v);
            return v;
        } finally {
            lock.unlock();
        }
    }

    public final long getAndIncrement() {
        return getAndAdd(1);
    }

    public final long getAndDecrement() {
        return getAndAdd(-1);
    }

    public final long incrementAndGet() {
        return addAndGet(1);
    }

    public final long decrementAndGet() {
        return addAndGet(-1);
    }

    // ==================== 只读预测（不修改状态，不触发事件） ====================

    /**
     * 预测：当前值加 expected 后在环上的位置
     */
    public final long expectOnAdd(long expected) {
        return wrap(get() + expected);
    }

    /**
     * 预测：从 min 位开始加 expected 后在环上的位置
     */
    public final long expectOnZero(long expected) {
        return wrap(min + expected);
    }

    // ==================== When 事件 ====================

    @Override
    public WhenChain<AtomicLong> chain() { return chain; }

    public WhenChain.Clause<AtomicLong> whenMin() { return when(whenMinFunc); }

    public WhenChain.Clause<AtomicLong> whenMax() { return when(whenMaxFunc); }

    public WhenChain.Clause<AtomicLong> whenValue(long target) { return when(n -> n.get() == target); }

    // ==================== Number ====================

    @Override
    public int intValue() { return (int) get(); }

    @Override
    public long longValue() { return get(); }

    @Override
    public float floatValue() { return get(); }

    @Override
    public double doubleValue() { return get(); }

    @Override
    public String toString() { return Long.toString(get()); }

    // ==================== 拷贝 ====================

    public AtomicRingLong copy() {
        AtomicRingLong ring = new AtomicRingLong(min, max);
        ring.set(value.get());
        return ring;
    }

    public RingInteger copyToRingInteger() {
        RingInteger ring = new RingInteger((int) min, (int) max);
        ring.set((int) value.get());
        return ring;
    }

    public RingLong copyToRingLong() {
        RingLong ring = new RingLong(min, max);
        ring.set(value.get());
        return ring;
    }

    public AtomicRingInteger copyToAtomicRingInteger() {
        AtomicRingInteger ring = new AtomicRingInteger((int) min, (int) max);
        ring.set((int) value.get());
        return ring;
    }
}
