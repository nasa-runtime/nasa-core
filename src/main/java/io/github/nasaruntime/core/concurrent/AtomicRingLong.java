package io.github.nasaruntime.core.concurrent;

import io.github.nasaruntime.core.base.RingInteger;
import io.github.nasaruntime.core.base.RingLong;
import io.github.nasaruntime.core.base.When;

import java.io.Serial;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Nasa 原子数字环（long 版）
 * 值域 [min, max)，到达边界自动回绕。所有运算均为常数时间，宽值域不会因 long 中间量溢出而失去回绕。
 */
@SuppressWarnings("unused")
public class AtomicRingLong extends Number implements When<AtomicLong> {

    @Serial
    private static final long serialVersionUID = -995072596953680913L;

    private final Lock lock = new ReentrantLock();
    private final AtomicLong value = new AtomicLong();
    private final WhenChain<AtomicLong> chain = WhenChain.of();

    /* value >= min；volatile 让已注册的边界谓词在重配置后读取新下界。 */
    private volatile long min;
    /* value < max；volatile 让已注册的边界谓词在重配置后读取新上界。 */
    private volatile long max;
    /** 边界谓词只创建一次，避免 setRing 重配置产生新 lambda，同时让既有 Clause 跟随新边界。 */
    private final Predicate<AtomicLong> whenMinFunc = n -> n.get() == this.min;
    private final Predicate<AtomicLong> whenMaxFunc = n -> n.get() == this.max - 1;

    /**
     * 业务作用：以默认宽值域 [Long.MIN_VALUE, Long.MAX_VALUE) 构造原子数字环，
     * 供只需要回绕计数而不关心自定义值域的调用方使用。
     *
     * 参数说明: 无。
     * 返回: 构造完成后当前值为 Long.MIN_VALUE。
     */
    public AtomicRingLong() {
        this(Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /**
     * 业务作用：构造下界为 0 的原子数字环，覆盖序列号、槽位轮转等最常见的从零计数场景。
     *
     * @param max 上界，取不到；必须大于 0
     * 返回: 构造完成后当前值为 0；max 不大于 0 时抛出 IllegalArgumentException。
     */
    public AtomicRingLong(long max) {
        this(0, max);
    }

    /**
     * 业务作用：创建满足 {@code min <= value < max} 的原子长整数环，是另外两个构造器的统一收口。
     *
     * @param min 下界，取得到
     * @param max 上界，取不到
     * 返回: 构造完成后当前值为 min；min 不小于 max 时抛出 IllegalArgumentException。
     */
    public AtomicRingLong(long min, long max) {
        this.setRing(min, max);
    }

    // ==================== 环形运算核心（O(1)，单一入口） ====================

    /**
     * 业务作用：把当前合法值与任意 long 增量折算到 [min, max) 环上，是所有加减运算的唯一回绕入口。
     * 窄值域使用无溢出的模加法；超过 Long.MAX_VALUE 宽度的值域利用“单个 long 增量最多跨越一次边界”
     * 的事实直接折返，避免 max-min、current+delta 和 raw-min 的有符号溢出。
     *
     * @param current 当前合法值
     * @param delta 增量，可为任意 long
     * 返回: 落在 [min, max) 内的等价值。
     */
    private long wrapAdd(long current, long delta) {
        long range = max - min;
        if (range > 0) {
            long offset = current - min;
            long normalizedDelta = Math.floorMod(delta, range);
            long distanceToWrap = range - normalizedDelta;
            long normalized = offset >= distanceToWrap
                    ? offset - distanceToWrap
                    : offset + normalizedDelta;
            return min + normalized;
        }

        // 此分支的环宽至少为 2^63；任意 long 增量至多越过一个边界，可直接按边界距离折返。
        if (delta >= 0) {
            long wrapThreshold = max - delta;
            if (current >= wrapThreshold) {
                return min + (delta - (max - current));
            }
            return current + delta;
        }

        long wrapThreshold = min - delta;
        if (current < wrapThreshold) {
            long offset = current - min;
            return max + (delta + offset);
        }
        return current + delta;
    }

    /**
     * 业务作用：所有写操作的统一收口，先落值再触发 When 事件。两步必须在同一把锁内完成，
     * 否则监听方可能观察到“值已变但事件未发”或事件顺序与值变更顺序不一致的中间态。
     *
     * @param newValue 已经过回绕或校验的目标值
     * 返回: 无返回值；调用方必须已持有 lock，本方法不自行加锁。
     */
    private void update(long newValue) {
        value.set(newValue);
        chain.fire(value);
    }

    // ==================== 配置 ====================

    /**
     * 业务作用：重设值域并把当前值复位到下界，同时触发一次 When 事件；已经注册的边界谓词
     * 动态读取新边界，使监听方立刻看到新值域下的判定，而不是继续监听旧边界。
     *
     * @param min 下界，取得到
     * @param max 上界，取不到
     * 返回: 无返回值；min 不小于 max 时抛出 IllegalArgumentException 且不改变原有值域；
     * 重配置、复位和事件触发与其它写操作使用同一把锁线性化。
     */
    public final void setRing(long min, long max) {
        if (min >= max) {
            throw new IllegalArgumentException("min must be less than max");
        }
        lock.lock();
        try {
            // 值域与复位值必须作为一次配置提交发布，禁止并发写按新旧边界交叉校验。
            this.min = min;
            this.max = max;
            update(min);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：在锁保护下把当前值复位到下界并触发 When 事件，供一轮轮转结束后重新开始计数。
     *
     * 参数说明: 无。
     * 返回: 无返回值；复位与事件触发对其它写操作是原子的。
     */
    public final void restore() {
        lock.lock();
        try {
            update(min);
        } finally {
            lock.unlock();
        }
    }

    // ==================== 读（无锁） ====================

    /**
     * 业务作用：无锁读取当前值。读取不参与锁，因此可能与并发写交错，只保证读到某次写入的完整值。
     *
     * 参数说明: 无。
     * 返回: 当前值，必然落在 [min, max) 内。
     */
    public final long get() {
        return value.get();
    }

    // ==================== 写（lock 保证值变更 + When 事件的原子性） ====================

    /**
     * 业务作用：直接把环设置到指定值。刻意不做回绕而是拒绝越界入参，避免调用方误以为写入成功却被静默改写。
     *
     * @param newValue 目标值，必须落在 [min, max) 内
     * 返回: 无返回值；越界时抛出 IllegalArgumentException 且不改变当前值。
     */
    public final void set(long newValue) {
        lock.lock();
        try {
            if (newValue < min || newValue >= max) {
                throw new IllegalArgumentException("newValue must between " + min + " and " + max);
            }
            update(newValue);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：条件更新。在锁内完成比较、写入与 When 事件触发，保证监听方不会观察到
     * “值已变但事件未发”的中间态。
     *
     * @param expectedValue 期望的当前值，必须落在 [min, max) 内
     * @param newValue 目标值，必须落在 [min, max) 内
     * 返回: 当前值等于期望值并完成更新时返回 true；不相等返回 false；任一入参越界抛出 IllegalArgumentException。
     */
    public final boolean compareAndSet(long expectedValue, long newValue) {
        lock.lock();
        try {
            if (expectedValue < min || expectedValue >= max) {
                throw new IllegalArgumentException("expectedValue must between " + min + " and " + max);
            }
            if (newValue < min || newValue >= max) {
                throw new IllegalArgumentException("newValue must between " + min + " and " + max);
            }
            if (value.compareAndSet(expectedValue, newValue)) {
                chain.fire(value);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：先取旧值再按环形语义累加，供需要“占位后推进”的分配型场景使用。
     *
     * @param delta 增量，可为负
     * 返回: 累加前的旧值；新值按 [min, max) 自动回绕。
     */
    public final long getAndAdd(long delta) {
        lock.lock();
        try {
            long old = value.get();
            update(wrapAdd(old, delta));
            return old;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：按环形语义累加后返回新值，供需要立即使用推进结果的场景使用。
     *
     * @param delta 增量，可为负
     * 返回: 回绕后的新值。
     */
    public final long addAndGet(long delta) {
        lock.lock();
        try {
            long v = wrapAdd(value.get(), delta);
            update(v);
            return v;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：环形自增并返回旧值，等价于步长为 1 的 getAndAdd。
     *
     * 参数说明: 无。
     * 返回: 自增前的旧值；越过上界时回绕到下界。
     */
    public final long getAndIncrement() {
        return getAndAdd(1);
    }

    /**
     * 业务作用：环形自减并返回旧值，等价于步长为 -1 的 getAndAdd。
     *
     * 参数说明: 无。
     * 返回: 自减前的旧值；越过下界时回绕到上界之前一位。
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
     * 业务作用：只读预测当前值加上增量后在环上的落点，不修改状态也不触发 When 事件，
     * 供调用方在真正推进前评估是否会跨越边界。
     *
     * @param expected 假设的增量，可为负
     * 返回: 回绕后的预测值；本次调用不改变环的实际状态。
     */
    public final long expectOnAdd(long expected) {
        lock.lock();
        try {
            return wrapAdd(value.get(), expected);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：只读预测从下界起走指定步数后在环上的落点，不修改状态也不触发 When 事件。
     *
     * @param expected 自下界起的步数
     * 返回: 回绕后的预测值；本次调用不改变环的实际状态。
     */
    public final long expectOnZero(long expected) {
        lock.lock();
        try {
            return wrapAdd(min, expected);
        } finally {
            lock.unlock();
        }
    }

    // ==================== When 事件 ====================

    /**
     * 业务作用：暴露内部 When 事件链，使 When 接口的默认方法能挂载条件回调。
     *
     * 参数说明: 无。
     * 返回: 本环独有的事件链实例。
     */
    @Override
    public WhenChain<AtomicLong> chain() { return chain; }

    /**
     * 业务作用：注册“值回到下界”的条件子句，用于识别一轮环形轮转的起点。
     *
     * 返回: 可继续挂载动作的条件子句。
     */
    public WhenChain.Clause<AtomicLong> whenMin() { return when(whenMinFunc); }

    /**
     * 业务作用：注册“值到达上界前最后一位”的条件子句，用于在下一次推进回绕前做收尾。
     *
     * 返回: 可继续挂载动作的条件子句。
     */
    public WhenChain.Clause<AtomicLong> whenMax() { return when(whenMaxFunc); }

    /**
     * 业务作用：注册命中指定值的条件子句，用于环上任意关键位点的回调。
     *
     * @param target 触发回调的目标值
     * 返回: 可继续挂载动作的条件子句。
     */
    public WhenChain.Clause<AtomicLong> whenValue(long target) { return when(n -> n.get() == target); }

    // ==================== Number ====================

    /**
     * 业务作用：实现 Number 契约，按 int 返回当前值；超出 int 值域时发生窄化截断。
     *
     * 返回: 当前值的 int 表示。
     */
    @Override
    public int intValue() { return (int) get(); }

    /**
     * 业务作用：实现 Number 契约，按 long 返回当前值，不会丢失精度。
     *
     * 返回: 当前值。
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
     * 业务作用：实现 Number 契约，按 double 返回当前值；超过 2^53 后损失精度。
     *
     * 返回: 当前值的 double 近似。
     */
    @Override
    public double doubleValue() { return get(); }

    /**
     * 业务作用：输出当前值的十进制文本，供日志与诊断使用，不包含值域信息。
     *
     * 返回: 当前值的字符串形式。
     */
    @Override
    public String toString() { return Long.toString(get()); }

    // ==================== 拷贝 ====================

    /**
     * 业务作用：复制值域与当前值生成独立实例。刻意不复制 When 事件链，
     * 避免副本触发原实例注册的回调造成重复副作用。
     *
     * 参数说明: 无。
     * 返回: 值域和当前值相同、事件链为空的新实例。
     */
    public AtomicRingLong copy() {
        lock.lock();
        try {
            AtomicRingLong ring = new AtomicRingLong(min, max);
            ring.set(value.get());
            return ring;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：在值域和当前值均可由 int 无损表达时，转换为非线程安全的整数环；
     * 禁止静默窄化后生成与原对象数值含义不同的环。
     *
     * 参数说明: 无。
     * 返回: 值域和当前值相同的 RingInteger，不携带事件链；任一数值超出 int 范围时抛出 ArithmeticException。
     */
    public RingInteger copyToRingInteger() {
        lock.lock();
        try {
            int intMin = Math.toIntExact(min);
            int intMax = Math.toIntExact(max);
            int intValue = Math.toIntExact(value.get());
            RingInteger ring = new RingInteger(intMin, intMax);
            ring.set(intValue);
            return ring;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：转换为非线程安全的同值域长整数环，供确认无并发访问、希望省去锁开销的场景使用。
     *
     * 参数说明: 无。
     * 返回: 值域和当前值相同的 RingLong，不携带事件链。
     */
    public RingLong copyToRingLong() {
        lock.lock();
        try {
            RingLong ring = new RingLong(min, max);
            ring.set(value.get());
            return ring;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：在值域和当前值均可由 int 无损表达时，转换为线程安全的整数环；
     * 禁止静默窄化后生成与原对象数值含义不同的环。
     *
     * 参数说明: 无。
     * 返回: 值域和当前值相同的 AtomicRingInteger，不携带事件链；任一数值超出 int 范围时抛出 ArithmeticException。
     */
    public AtomicRingInteger copyToAtomicRingInteger() {
        lock.lock();
        try {
            int intMin = Math.toIntExact(min);
            int intMax = Math.toIntExact(max);
            int intValue = Math.toIntExact(value.get());
            AtomicRingInteger ring = new AtomicRingInteger(intMin, intMax);
            ring.set(intValue);
            return ring;
        } finally {
            lock.unlock();
        }
    }
}
