package io.github.nasaruntime.core.base;

import io.github.nasaruntime.core.concurrent.AtomicRingInteger;
import io.github.nasaruntime.core.concurrent.AtomicRingLong;

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

    /**
     * 业务作用：构造实例。字段取默认值。
     *
     * 参数说明: 无。
     * 返回: 构造完成后可直接使用的实例。
     */
    public RingLong() {
        this(Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /**
     * 业务作用：按给定参数构造 RingLong 实例。
     *
     * @param max 上界，取不到
     * 返回: 构造完成后可直接使用的实例。
     */
    public RingLong(long max) {
        this(0, max);
    }

    /**
     * 业务作用: 创建满足 {@code min <= value < max} 的长整数环。
     *
     * @param min 下界，取得到
     * @param max 上界，取不到
     * 返回: 构造完成后当前值为 min；min 不小于 max 时抛出 IllegalArgumentException。
     */
    public RingLong(long min, long max) {
        this.setRing(min, max);
    }

    // ==================== 环形运算核心（O(1)，单一入口） ====================

    /**
     * 业务作用：把任意值折算到 [min, max) 环上，是所有加减运算的唯一回绕入口。
     *
     * @param raw 未回绕的原始值
     * 返回: 落在值域内的等价值。
     */
    private long wrap(long raw) {
        long range = max - min;
        if (range <= 0) return raw;
        return min + Math.floorMod(raw - min, range);
    }

    /**
     * 业务作用：所有写操作的统一收口：更新值 + 触发 When 事件（零装箱）。
     *
     * @param newValue 见上述说明
     * 返回: 无返回值。
     */
    private void update(long newValue) {
        this.value = newValue;
        chain.fire(value);
    }

    // ==================== 配置 ====================

    /**
     * 业务作用：设置数字环的 min 和 max
     *
     * @param min 见上述说明
     * @param max 见上述说明
     * 返回: 无返回值。
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
     * 业务作用：复位到 min
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    public final void restore() {
        update(wrap(min));
    }

    // ==================== 读 ====================

    /**
     * 业务作用：读取当前值。
     *
     * 参数说明: 无。
     * 返回: 当前值，必然落在 [min, max) 内。
     */
    public final long get() {
        return value;
    }

    /**
     * 业务作用：报告值域下界。
     *
     * 参数说明: 无。
     * 返回: 下界，取得到。
     */
    public final long min() {
        return min;
    }

    /**
     * 业务作用：报告值域上界。
     *
     * 参数说明: 无。
     * 返回: 上界，取不到。
     */
    public final long max() {
        return max;
    }

    // ==================== 写（全部通过 wrap + update） ====================

    /**
     * 业务作用：直接设置为指定值。刻意不做回绕而是拒绝越界入参，避免调用方误以为写入成功却被静默改写。
     *
     * @param newValue 目标值，必须落在 [min, max) 内
     * 返回: 无返回值；越界时抛出 IllegalArgumentException 且不改变当前值。
     */
    public final void set(long newValue) {
        if (newValue < min || newValue >= max) {
            throw new IllegalArgumentException("newValue must between " + min + " and " + max);
        }
        update(newValue);
    }

    /**
     * 业务作用：先取旧值再按环形语义累加，供「占位后推进」的分配型场景使用。
     *
     * @param delta 增量
     * 返回: 累加前的旧值。
     */
    public final long getAndAdd(long delta) {
        long old = value;
        update(wrap(value + delta));
        return old;
    }

    /**
     * 业务作用：按环形语义累加后返回新值。
     *
     * @param delta 增量
     * 返回: 回绕后的新值。
     */
    public final long addAndGet(long delta) {
        long v = wrap(value + delta);
        update(v);
        return v;
    }

    /**
     * 业务作用：环形自增并返回旧值。
     *
     * 参数说明: 无。
     * 返回: 自增前的旧值；越过上界时回绕到下界。
     */
    public final long getAndIncrement() {
        return getAndAdd(1);
    }

    /**
     * 业务作用：环形自减并返回旧值。
     *
     * 参数说明: 无。
     * 返回: 自减前的旧值；越过下界时回绕到上界前一位。
     */
    public final long getAndDecrement() {
        return getAndAdd(-1);
    }

    /**
     * 业务作用：环形自增并返回新值。
     *
     * 参数说明: 无。
     * 返回: 回绕后的新值。
     */
    public final long incrementAndGet() {
        return addAndGet(1);
    }

    /**
     * 业务作用：环形自减并返回新值。
     *
     * 参数说明: 无。
     * 返回: 回绕后的新值。
     */
    public final long decrementAndGet() {
        return addAndGet(-1);
    }

    // ==================== 只读预测（不修改状态，不触发事件） ====================

    /**
     * 业务作用：只读预测加上增量后在环上的落点，不改状态也不触发事件，供调用方在真正推进前评估是否会跨越边界。
     *
     * @param delta 增量
     * 返回: 回绕后的预测值。
     */
    public final long expectOnAdd(long delta) {
        return wrap(value + delta);
    }

    /**
     * 业务作用：只读预测从下界起走指定步数后的落点，不改状态也不触发事件。
     *
     * @param delta 增量
     * 返回: 回绕后的预测值。
     */
    public final long expectOnZero(long delta) {
        return wrap(min + delta);
    }

    // ==================== When 事件（long 特化，零装箱） ====================

    /**
     * 业务作用：暴露内部 When 事件链，使条件回调可以挂载到本容器的状态变化上。
     *
     * 参数说明: 无。
     * 返回: 本实例独有的事件链。
     */
    public When.LongWhenChain chain() { return chain; }

    /**
     * 业务作用：注册条件子句，值变化时按条件触发回调。
     *
     * @param condition 见上述说明
     * 返回: 可继续挂载动作的条件子句。
     */
    public When.LongWhenChain.LongClause when(LongPredicate condition) { return chain.when(condition); }

    /**
     * 业务作用：移除全部元素，解除容器对它们的强引用后交给 GC。
     *
     * 返回: 无返回值；并发写入仍在进行时不保证返回后容器为空。
     */
    public void clear() { chain.clear(); }

    /**
     * 业务作用：注册「值回到下界」的条件子句，用于识别一轮轮转的起点。
     *
     * 返回: 可继续挂载动作的条件子句。
     */
    public When.LongWhenChain.LongClause whenMin() { return when(whenMinFunc); }

    /**
     * 业务作用：注册「值到达上界前最后一位」的条件子句，用于在下次回绕前收尾。
     *
     * 返回: 可继续挂载动作的条件子句。
     */
    public When.LongWhenChain.LongClause whenMax() { return when(whenMaxFunc); }

    /**
     * 业务作用：注册命中指定值的条件子句。
     *
     * @param target 触发回调的目标值
     * 返回: 可继续挂载动作的条件子句。
     */
    public When.LongWhenChain.LongClause whenValue(long target) { return when(v -> v == target); }

    // ==================== Number ====================

    /**
     * 业务作用：实现 Number 契约，按 int 返回当前值。
     *
     * 返回: 当前值的 int 表示。
     */
    @Override
    public int intValue() { return (int) get(); }

    /**
     * 业务作用：实现 Number 契约，按 long 返回当前值。
     *
     * 返回: 当前值的 long 表示。
     */
    @Override
    public long longValue() { return get(); }

    /**
     * 业务作用：实现 Number 契约，按 float 返回当前值；大整数会损失精度。
     *
     * 返回: 当前值的 float 近似。
     */
    @Override
    public float floatValue() { return get(); }

    /**
     * 业务作用：实现 Number 契约，按 double 返回当前值。
     *
     * 返回: 当前值的 double 表示。
     */
    @Override
    public double doubleValue() { return get(); }

    /**
     * 业务作用：输出可读的元素快照，仅供诊断。
     *
     * 返回: 形如 [a, b, c] 的字符串；不保证与任何时刻的容器状态一致。
     */
    @Override
    public String toString() { return Long.toString(get()); }

    // ==================== 拷贝 ====================

    /**
     * 业务作用：复制值域与当前值生成独立实例。刻意不复制事件链，避免副本触发原实例注册的回调造成重复副作用。
     *
     * 参数说明: 无。
     * 返回: 值域与当前值相同、事件链为空的新实例。
     */
    public RingLong copy() {
        RingLong ring = new RingLong(min, max);
        ring.set(value);
        return ring;
    }

    /**
     * 业务作用：转换为 int 值域环。
     *
     * 参数说明: 无。
     * 返回: 等值的 RingInteger；超出 int 范围时发生窄化截断。
     */
    public RingInteger copyToRingInteger() {
        RingInteger ring = new RingInteger((int) min, (int) max);
        ring.set((int) value);
        return ring;
    }

    /**
     * 业务作用：转换为线程安全的 long 值域环。
     *
     * 参数说明: 无。
     * 返回: 等值的 AtomicRingLong。
     */
    public AtomicRingLong copyToAtomicRingLong() {
        AtomicRingLong ring = new AtomicRingLong(min, max);
        ring.set(value);
        return ring;
    }

    /**
     * 业务作用：转换为线程安全的 int 值域环。
     *
     * 参数说明: 无。
     * 返回: 等值的 AtomicRingInteger；超出 int 范围时发生窄化截断。
     */
    public AtomicRingInteger copyToAtomicRingInteger() {
        AtomicRingInteger ring = new AtomicRingInteger((int) min, (int) max);
        ring.set((int) value);
        return ring;
    }
}
