package com.nasa.runtime.core.base;

import com.nasa.runtime.core.concurrent.AtomicRingInteger;
import com.nasa.runtime.core.concurrent.AtomicRingLong;

import java.io.Serial;
import java.util.function.IntPredicate;

/**
 * Nasa 数字环
 * 值域 [min, max)，到达边界自动回绕。所有运算 O(1)（基于 {@link Math#floorMod}）。
 * When 事件：值变更后自动触发已注册的条件链（int 特化，零装箱）。
 * RingInteger ring = new RingInteger(0, 10);
 * ring.when(v -> v == 0).then(v -> onZero())
 *     .when(v -> v == 9).then(v -> beforeWrap());
 * ring.whenMin().then(v -> onMin());
 * ring.whenMax().then(v -> onMax());
 */
@SuppressWarnings("unused")
public class RingInteger extends Number {

    @Serial
    private static final long serialVersionUID = -7538378958417601930L;

    private int value;
    private int min;
    private int max;
    private IntPredicate whenMinFunc;
    private IntPredicate whenMaxFunc;
    private final When.IntWhenChain chain = When.IntWhenChain.of();

    public RingInteger() {
        this(Integer.MAX_VALUE);
    }

    public RingInteger(int max) {
        this(0, max);
    }

    /**
     * 业务作用: 创建满足 {@code min <= value < max} 的整数环。
     */
    public RingInteger(int min, int max) {
        this.setRing(min, max);
    }

    // ==================== 环形运算核心（O(1)，单一入口） ====================

    /**
     * 将任意 int 值映射到 [min, max) 区间。
     * 使用 long 避免 (max - min) 的 int 溢出。
     */
    private int wrap(int raw) {
        long range = (long) max - min;
        return (int) (min + Math.floorMod((long) raw - min, range));
    }

    /**
     * 所有写操作的统一收口：更新值 + 触发 When 事件（零装箱）。
     */
    private void update(int newValue) {
        this.value = newValue;
        chain.fire(value);
    }

    // ==================== 配置 ====================

    /**
     * 设置数字环的 min 和 max
     */
    public final void setRing(int min, int max) {
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

    public final int get() {
        return value;
    }

    public final int min() {
        return min;
    }

    public final int max() {
        return max;
    }

    // ==================== 写（全部通过 wrap + update） ====================

    public final void set(int newValue) {
        if (newValue < min || newValue >= max) {
            throw new IllegalArgumentException("newValue must between " + min + " and " + max);
        }
        update(newValue);
    }

    public final int getAndAdd(int delta) {
        int old = value;
        update(wrap(value + delta));
        return old;
    }

    public final int addAndGet(int delta) {
        int v = wrap(value + delta);
        update(v);
        return v;
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
     * 预测：当前值加 delta 后在环上的位置
     */
    public final int expectOnAdd(int delta) {
        return wrap(value + delta);
    }

    /**
     * 预测：从 min 位开始加 delta 后在环上的位置
     */
    public final int expectOnZero(int delta) {
        return wrap(min + delta);
    }

    // ==================== When 事件（int 特化，零装箱） ====================

    public When.IntWhenChain chain() { return chain; }

    public When.IntWhenChain.IntClause when(IntPredicate condition) { return chain.when(condition); }

    public void clear() { chain.clear(); }

    public When.IntWhenChain.IntClause whenMin() { return when(whenMinFunc); }

    public When.IntWhenChain.IntClause whenMax() { return when(whenMaxFunc); }

    public When.IntWhenChain.IntClause whenValue(int target) { return when(v -> v == target); }

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

    public RingInteger copy() {
        RingInteger ring = new RingInteger(min, max);
        ring.set(value);
        return ring;
    }

    public RingLong copyToRingLong() {
        RingLong ring = new RingLong(min, max);
        ring.set(value);
        return ring;
    }

    public AtomicRingInteger copyToAtomicRingInteger() {
        AtomicRingInteger ring = new AtomicRingInteger(min, max);
        ring.set(value);
        return ring;
    }

    public AtomicRingLong copyToAtomicRingLong() {
        AtomicRingLong ring = new AtomicRingLong(min, max);
        ring.set(value);
        return ring;
    }
}
