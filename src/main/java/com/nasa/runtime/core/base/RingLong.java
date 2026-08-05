package com.nasa.runtime.core.base;

import com.nasa.runtime.core.concurrent.AtomicRingInteger;
import com.nasa.runtime.core.concurrent.AtomicRingLong;

import java.io.Serial;
import java.util.function.LongPredicate;

/**
 * Nasa 数字环（long 版）
 * 值域 [min, max)，到达边界自动回绕。所有运算 O(1)（基于 {@link Math#floorMod}）。
 * When 事件：值变更后自动触发已注册的条件链（long 特化，零装箱）。
 * RingLong ring = new RingLong(0, 10);
 * ring.when(v -> v == 0).then(v -> onZero())
 *     .when(v -> v == 9).then(v -> beforeWrap());
 */
@SuppressWarnings("unused")
public class RingLong extends Number {

    @Serial
    private static final long serialVersionUID = -8992276494075367240L;

    private long value;
    private long min;
    private long max;
    private LongPredicate whenMinFunc;
    private LongPredicate whenMaxFunc;
    private final When.LongWhenChain chain = When.LongWhenChain.of();

    public RingLong() {
        this(Long.MIN_VALUE, Long.MAX_VALUE);
    }

    public RingLong(long max) {
        this(0, max);
    }

    /**
     * 业务作用: 创建满足 {@code min <= value < max} 的长整数环。
     */
    public RingLong(long min, long max) {
        this.setRing(min, max);
    }

    // ==================== 环形运算核心（O(1)，单一入口） ====================

    /**
     * 将任意 long 值映射到 [min, max) 区间。
     * 当 range 可能溢出 long 时（完整 long 范围），直接返回原值。
     */
    private long wrap(long raw) {
        long range = max - min;
        if (range <= 0) return raw;
        return min + Math.floorMod(raw - min, range);
    }

    /**
     * 所有写操作的统一收口：更新值 + 触发 When 事件（零装箱）。
     */
    private void update(long newValue) {
        this.value = newValue;
        chain.fire(value);
    }

    // ==================== 配置 ====================

    /**
     * 设置数字环的 min 和 max
     */
    public final void setRing(long min, long max) {
        if (min >= max) {
            throw new IllegalArgumentException("min must be less than max");
        }
        this.min = min;
        this.max = max;
        this.whenMinFunc = m -> m == min;
        this.whenMaxFunc = m -> m == max - 1;
        update(wrap(min));
    }

    /**
     * 复位到 min
     */
    public final void restore() {
        update(wrap(min));
    }

    // ==================== 读 ====================

    public final long get() {
        return value;
    }

    public final long min() {
        return min;
    }

    public final long max() {
        return max;
    }

    // ==================== 写（全部通过 wrap + update） ====================

    public final void set(long newValue) {
        if (newValue < min || newValue >= max) {
            throw new IllegalArgumentException("newValue must between " + min + " and " + max);
        }
        update(newValue);
    }

    public final long getAndAdd(long delta) {
        long old = value;
        update(wrap(value + delta));
        return old;
    }

    public final long addAndGet(long delta) {
        long v = wrap(value + delta);
        update(v);
        return v;
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
     * 预测：当前值加 delta 后在环上的位置
     */
    public final long expectOnAdd(long delta) {
        return wrap(value + delta);
    }

    /**
     * 预测：从 min 位开始加 delta 后在环上的位置
     */
    public final long expectOnZero(long delta) {
        return wrap(min + delta);
    }

    // ==================== When 事件（long 特化，零装箱） ====================

    public When.LongWhenChain chain() { return chain; }

    public When.LongWhenChain.LongClause when(LongPredicate condition) { return chain.when(condition); }

    public void clear() { chain.clear(); }

    public When.LongWhenChain.LongClause whenMin() { return when(whenMinFunc); }

    public When.LongWhenChain.LongClause whenMax() { return when(whenMaxFunc); }

    public When.LongWhenChain.LongClause whenValue(long target) { return when(v -> v == target); }

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

    public RingLong copy() {
        RingLong ring = new RingLong(min, max);
        ring.set(value);
        return ring;
    }

    public RingInteger copyToRingInteger() {
        RingInteger ring = new RingInteger((int) min, (int) max);
        ring.set((int) value);
        return ring;
    }

    public AtomicRingLong copyToAtomicRingLong() {
        AtomicRingLong ring = new AtomicRingLong(min, max);
        ring.set(value);
        return ring;
    }

    public AtomicRingInteger copyToAtomicRingInteger() {
        AtomicRingInteger ring = new AtomicRingInteger((int) min, (int) max);
        ring.set((int) value);
        return ring;
    }
}
