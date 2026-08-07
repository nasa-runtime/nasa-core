package io.github.nasaruntime.core.concurrent;

import io.github.nasaruntime.core.base.RingInteger;
import io.github.nasaruntime.core.base.RingLong;
import io.github.nasaruntime.core.base.When;

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

    /* value >= min；volatile 让已注册的边界谓词在重配置后读取新下界。 */
    private volatile int min;
    /* value < max；volatile 让已注册的边界谓词在重配置后读取新上界。 */
    private volatile int max;
    /** 边界谓词只创建一次，避免 setRing 重配置产生新 lambda，同时让既有 Clause 跟随新边界。 */
    private final Predicate<AtomicInteger> whenMinFunc = n -> n.get() == this.min;
    private final Predicate<AtomicInteger> whenMaxFunc = n -> n.get() == this.max - 1;

    /**
     * 业务作用：以默认宽值域 [Integer.MIN_VALUE, Integer.MAX_VALUE) 构造原子数字环，
     * 供只需要回绕计数而不关心自定义值域的调用方使用。
     *
     * 参数说明: 无。
     * 返回: 构造完成后当前值为 Integer.MIN_VALUE。
     */
    public AtomicRingInteger() {
        this(Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * 业务作用：构造下界为 0 的原子数字环，覆盖数组下标、槽位轮转等最常见的从零计数场景。
     *
     * @param max 上界，取不到；必须大于 0
     * 返回: 构造完成后当前值为 0；max 不大于 0 时抛出 IllegalArgumentException。
     */
    public AtomicRingInteger(int max) {
        this(0, max);
    }

    /**
     * 业务作用：创建满足 {@code min <= value < max} 的原子整数环，是另外两个构造器的统一收口。
     *
     * @param min 下界，取得到
     * @param max 上界，取不到
     * 返回: 构造完成后当前值为 min；min 不小于 max 时抛出 IllegalArgumentException。
     */
    public AtomicRingInteger(int min, int max) {
        this.setRing(min, max);
    }

    // ==================== 环形运算核心（O(1)，单一入口） ====================

    /**
     * 业务作用：把任意整数折算到 [min, max) 环上，是所有加减运算的唯一回绕入口。
     * 用 long 中间量计算范围与偏移，避免 max-min 或 raw-min 在极端值域下溢出。
     *
     * @param raw 未回绕的原始值；使用 long 承接 int 加法，避免加法先溢出再回绕到错误位置
     * 返回: 落在 [min, max) 内的等价值。
     */
    private int wrap(long raw) {
        long range = (long) max - min;
        return (int) (min + Math.floorMod(raw - min, range));
    }

    /**
     * 业务作用：所有写操作的统一收口，先落值再触发 When 事件。两步必须在同一把锁内完成，
     * 否则监听方可能观察到“值已变但事件未发”或事件顺序与值变更顺序不一致的中间态。
     *
     * @param newValue 已经过回绕或校验的目标值
     * 返回: 无返回值；调用方必须已持有 lock，本方法不自行加锁。
     */
    private void update(int newValue) {
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
    public final void setRing(int min, int max) {
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
    public final int get() {
        return value.get();
    }

    // ==================== 写（lock 保证值变更 + When 事件的原子性） ====================

    /**
     * 业务作用：直接把环设置到指定值。刻意不做回绕而是拒绝越界入参，避免调用方误以为写入成功却被静默改写。
     *
     * @param newValue 目标值，必须落在 [min, max) 内
     * 返回: 无返回值；越界时抛出 IllegalArgumentException 且不改变当前值。
     */
    public final void set(int newValue) {
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
    public final boolean compareAndSet(int expectedValue, int newValue) {
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
    public final int getAndAdd(int delta) {
        lock.lock();
        try {
            int old = value.get();
            update(wrap((long) old + delta));
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
    public final int addAndGet(int delta) {
        lock.lock();
        try {
            int v = wrap((long) value.get() + delta);
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
    public final int getAndIncrement() {
        return getAndAdd(1);
    }

    /**
     * 业务作用：环形自减并返回旧值，等价于步长为 -1 的 getAndAdd。
     *
     * 参数说明: 无。
     * 返回: 自减前的旧值；越过下界时回绕到上界之前一位。
     */
    public final int getAndDecrement() {
        return getAndAdd(-1);
    }

    /**
     * 业务作用：环形自增并返回新值。
     *
     * 参数说明: 无。
     * 返回: 回绕后的新值。
     */
    public final int incrementAndGet() {
        return addAndGet(1);
    }

    /**
     * 业务作用：环形自减并返回新值。
     *
     * 参数说明: 无。
     * 返回: 回绕后的新值。
     */
    public final int decrementAndGet() {
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
    public final int expectOnAdd(int expected) {
        lock.lock();
        try {
            return wrap((long) value.get() + expected);
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
    public final int expectOnZero(int expected) {
        lock.lock();
        try {
            return wrap((long) min + expected);
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
    public WhenChain<AtomicInteger> chain() { return chain; }

    /**
     * 业务作用：注册“值回到下界”的条件子句，用于识别一轮环形轮转的起点。
     *
     * 返回: 可继续挂载动作的条件子句。
     */
    public WhenChain.Clause<AtomicInteger> whenMin() { return when(whenMinFunc); }

    /**
     * 业务作用：注册“值到达上界前最后一位”的条件子句，用于在下一次推进回绕前做收尾。
     *
     * 返回: 可继续挂载动作的条件子句。
     */
    public WhenChain.Clause<AtomicInteger> whenMax() { return when(whenMaxFunc); }

    /**
     * 业务作用：注册命中指定值的条件子句，用于环上任意关键位点的回调。
     *
     * @param target 触发回调的目标值
     * 返回: 可继续挂载动作的条件子句。
     */
    public WhenChain.Clause<AtomicInteger> whenValue(int target) { return when(n -> n.get() == target); }

    // ==================== Number ====================

    /**
     * 业务作用：实现 Number 契约，按 int 返回当前值。
     *
     * 返回: 当前值。
     */
    @Override
    public int intValue() { return get(); }

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
     * 业务作用：实现 Number 契约，按 double 返回当前值。
     *
     * 返回: 当前值的 double 表示。
     */
    @Override
    public double doubleValue() { return get(); }

    /**
     * 业务作用：输出当前值的十进制文本，供日志与诊断使用，不包含值域信息。
     *
     * 返回: 当前值的字符串形式。
     */
    @Override
    public String toString() { return Integer.toString(get()); }

    // ==================== 拷贝 ====================

    /**
     * 业务作用：复制值域与当前值生成独立实例。刻意不复制 When 事件链，
     * 避免副本触发原实例注册的回调造成重复副作用。
     *
     * 参数说明: 无。
     * 返回: 值域和当前值相同、事件链为空的新实例。
     */
    public AtomicRingInteger copy() {
        lock.lock();
        try {
            AtomicRingInteger ring = new AtomicRingInteger(min, max);
            ring.set(value.get());
            return ring;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：转换为非线程安全的同值域整数环，供确认无并发访问、希望省去锁开销的场景使用。
     *
     * 参数说明: 无。
     * 返回: 值域和当前值相同的 RingInteger，不携带事件链。
     */
    public RingInteger copyToRingInteger() {
        lock.lock();
        try {
            RingInteger ring = new RingInteger(min, max);
            ring.set(value.get());
            return ring;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：转换为非线程安全的 long 值域环，供需要更大值域但无并发访问的场景使用。
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
     * 业务作用：转换为线程安全的 long 值域环，供值域需要突破 int 上限的场景使用。
     *
     * 参数说明: 无。
     * 返回: 值域和当前值相同的 AtomicRingLong，不携带事件链。
     */
    public AtomicRingLong copyToAtomicRingLong() {
        lock.lock();
        try {
            AtomicRingLong ring = new AtomicRingLong(min, max);
            ring.set(value.get());
            return ring;
        } finally {
            lock.unlock();
        }
    }
}
