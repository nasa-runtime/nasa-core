package com.nasa.runtime.core.concurrent;

import com.nasa.runtime.core.base.RingInteger;
import com.nasa.runtime.core.base.RingLong;
import com.nasa.runtime.core.base.When;

import java.io.Serial;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Nasa 原子数字环
 * 值域 [min, max)，到达边界自动回绕。所有运算 O(1)（基于 {@link Math#floorMod}）。
 */
@SuppressWarnings("unused")
public class AtomicRingInteger extends Number implements When<AtomicInteger> {

    @Serial
    private static final long serialVersionUID = 7803619373942444505L;

    private final Lock lock = new ReentrantLock();
    private final AtomicInteger value = new AtomicInteger();
    private final WhenChain<AtomicInteger> chain = WhenChain.of();

    /* value >= min */
    private int min;
    /* value < max */
    private int max;
    private Predicate<AtomicInteger> whenMinFunc;
    private Predicate<AtomicInteger> whenMaxFunc;

    public AtomicRingInteger() {
        this(Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public AtomicRingInteger(int max) {
        this(0, max);
    }

    /**
     * 业务作用: 创建满足 {@code min <= value < max} 的原子整数环。
     */
    public AtomicRingInteger(int min, int max) {
        this.setRing(min, max);
    }

    // ==================== 环形运算核心（O(1)，单一入口） ====================

    private int wrap(int raw) {
        long range = (long) max - min;
        return (int) (min + Math.floorMod((long) raw - min, range));
    }

    /**
     * 所有写操作的统一收口：更新值 + 触发 When 事件。
     * 调用方必须持有 lock。
     */
    private void update(int newValue) {
        value.set(newValue);
        chain.fire(value);
    }

    // ==================== 配置 ====================

    public final void setRing(int min, int max) {
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

    public final int get() {
        return value.get();
    }

    // ==================== 写（lock 保证值变更 + When 事件的原子性） ====================

    public final void set(int newValue) {
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

    public final boolean compareAndSet(int expectedValue, int newValue) {
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

    public final int getAndAdd(int delta) {
        lock.lock();
        try {
            int old = value.get();
            update(wrap(old + delta));
            return old;
        } finally {
            lock.unlock();
        }
    }

    public final int addAndGet(int delta) {
        lock.lock();
        try {
            int v = wrap(value.get() + delta);
            update(v);
            return v;
        } finally {
            lock.unlock();
        }
    }

    public final int getAndIncrement() {
        return getAndAdd(1);
    }

    public final int getAndDecrement() {
        return getAndAdd(-1);
    }

    public final int incrementAndGet() {
        return addAndGet(1);
    }

    public final int decrementAndGet() {
        return addAndGet(-1);
    }

    // ==================== 只读预测（不修改状态，不触发事件） ====================

    /**
     * 预测：当前值加 expected 后在环上的位置
     */
    public final int expectOnAdd(int expected) {
        return wrap(get() + expected);
    }

    /**
     * 预测：从 min 位开始加 expected 后在环上的位置
     */
    public final int expectOnZero(int expected) {
        return wrap(min + expected);
    }

    // ==================== When 事件 ====================

    @Override
    public WhenChain<AtomicInteger> chain() { return chain; }

    public WhenChain.Clause<AtomicInteger> whenMin() { return when(whenMinFunc); }

    public WhenChain.Clause<AtomicInteger> whenMax() { return when(whenMaxFunc); }

    public WhenChain.Clause<AtomicInteger> whenValue(int target) { return when(n -> n.get() == target); }

    // ==================== Number ====================

    @Override
    public int intValue() { return get(); }

    @Override
    public long longValue() { return get(); }

    @Override
    public float floatValue() { return get(); }

    @Override
    public double doubleValue() { return get(); }

    @Override
    public String toString() { return Integer.toString(get()); }

    // ==================== 拷贝 ====================

    public AtomicRingInteger copy() {
        AtomicRingInteger ring = new AtomicRingInteger(min, max);
        ring.set(value.get());
        return ring;
    }

    public RingInteger copyToRingInteger() {
        RingInteger ring = new RingInteger(min, max);
        ring.set(value.get());
        return ring;
    }

    public RingLong copyToRingLong() {
        RingLong ring = new RingLong(min, max);
        ring.set(value.get());
        return ring;
    }

    public AtomicRingLong copyToAtomicRingLong() {
        AtomicRingLong ring = new AtomicRingLong(min, max);
        ring.set(value.get());
        return ring;
    }
}
