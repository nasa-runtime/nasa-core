package io.github.nasaruntime.core.base;

import io.github.nasaruntime.core.function.Action;
import io.github.nasaruntime.core.utils.StringUtils;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Nasa OPS计数
 */
public abstract class OPS {

    static final ConcurrentMap<String, OPS> OPS_CACHE = new ConcurrentHashMap<>();

    /** 每个槽位代表的毫秒数, 越小精度越高, 16ms 在 OPS 统计场景下精度足够且节省内存 (626 slots vs 10000) */
    private static final int SLOT_MS = 16;

    /** 时间基准, 所有实例共享 */
    private static final long BASE_TIME = System.currentTimeMillis();

    /** 全局 tick 是否已启动 */
    private static volatile boolean tickStarted;
    private static final ReentrantLock tickLock = new ReentrantLock();

    /* 时间窗 (ms) */
    private long window;
    /* ring buffer 长度 */
    private int slots;
    /* ring buffer: 每个槽位存放该时间片内的 signal 计数 */
    private AtomicIntegerArray ring;
    /* 上次清零对应的时间槽 (绝对槽号), 只由 tick 线程写 */
    private long clearSlotTime;
    /* 热计数: signal 时 CAS 累加, 过期槽清零时减去, 读 ops() 时直接返回 */
    private final AtomicLong total = new AtomicLong(0);

    /**
     * 业务作用：启动统计并容忍重复调用，已启动时直接返回，避免重复注册定时任务。
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    public void startSafely() {
        startSafely(10000);
    }

    /**
     * 业务作用：启动统计并容忍重复调用，已启动时直接返回，避免重复注册定时任务。
     *
     * @param window 统计窗口长度
     * 返回: 无返回值。
     */
    public void startSafely(long window) {
        /* 场景名 */
        String scene = this.scene();
        if (OPS_CACHE.containsKey(scene)) {
            throw new IllegalArgumentException(StringUtils.format("OPS scene [{}] already exists.", scene));
        }
        this.window = window;
        this.slots = (int) (window / SLOT_MS) + 1;
        this.ring = new AtomicIntegerArray(slots);
        long now = System.currentTimeMillis();
        this.clearSlotTime = toSlot(now) - 1;
        OPS_CACHE.put(scene, this);

        ensureTickStarted();
    }

    /**
     * 业务作用：注销本统计场景，释放其占用的槽位与定时登记。
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    public void remove() {
        this.removeSafely(null);
    }

    /**
     * 业务作用：移除已存在的计数，有scene的才执行Action
     *
     * @param success 见上述说明
     * 返回: 无返回值。
     */
    public void removeSafely(Action success) {
        if (OPS_CACHE.remove(this.scene()) != null && success != null) success.action();
    }

    /**
     * 业务作用：给出本统计的场景名，是不同统计之间相互隔离的依据。
     *
     * 参数说明: 无。
     * 返回: 场景名。
     */
    public abstract String scene();

    // ============================== signal ==============================

    /**
     * 业务作用：记一次或多次事件，并返回当前窗口的统计值。
     *
     * 参数说明: 无。
     * 返回: 当前窗口的统计值。
     */
    public double signal() {
        return signal(false);
    }

    /**
     * 业务作用：记一次或多次事件，并返回当前窗口的统计值。
     *
     * @param cvrSec 为 true 时把结果折算成每秒速率
     * 返回: 当前窗口的统计值。
     */
    public double signal(boolean cvrSec) {
        if (slots == 0) {
            return 0D;
        }
        long now = System.currentTimeMillis();
        int idx = slotIndex(now);
        ring.incrementAndGet(idx);
        long t = total.incrementAndGet();
        return cvrSec(cvrSec, t);
    }

    /**
     * 业务作用：记一次或多次事件，并返回当前窗口的统计值。
     *
     * @param n 计数增量
     * 返回: 当前窗口的统计值。
     */
    public double signal(int n) {
        return signal(false, n);
    }

    /**
     * 业务作用：记一次或多次事件，并返回当前窗口的统计值。
     *
     * @param cvrSec 为 true 时把结果折算成每秒速率
     * @param n 计数增量
     * 返回: 当前窗口的统计值。
     */
    public double signal(boolean cvrSec, int n) {
        if (n == 1) return signal(cvrSec);
        if (slots == 0) {
            return 0D;
        }
        long now = System.currentTimeMillis();
        int idx = slotIndex(now);
        ring.addAndGet(idx, n);
        long t = total.addAndGet(n);
        return cvrSec(cvrSec, t);
    }

    // ============================== ops ==============================

    /**
     * 业务作用：读取当前窗口的统计值，不记事件。
     *
     * 参数说明: 无。
     * 返回: 当前窗口的统计值。
     */
    public double ops() {
        return ops(false);
    }

    /**
     * 业务作用：读取当前窗口的统计值，不记事件。
     *
     * @param cvrSec 为 true 时把结果折算成每秒速率
     * 返回: 当前窗口的统计值。
     */
    public double ops(boolean cvrSec) {
        return cvrSec(cvrSec, total.get());
    }

    /**
     * 业务作用：读取当前窗口的统计值，不记事件。
     *
     * @param scenes 锁场景，不同场景互不影响
     * 返回: 当前窗口的统计值。
     */
    public double ops(Collection<String> scenes) {
        return ops(false, scenes);
    }

    /**
     * 业务作用：读取当前窗口的统计值，不记事件。
     *
     * @param cvrSec 为 true 时把结果折算成每秒速率
     * @param scenes 锁场景，不同场景互不影响
     * 返回: 当前窗口的统计值。
     */
    public double ops(boolean cvrSec, Collection<String> scenes) {
        long sum = 0;
        for (String s : scenes) {
            OPS o = OPS_CACHE.get(s);
            if (Objects.nonNull(o)) sum += o.total.get();
        }
        return cvrSec(cvrSec, sum);
    }

    // ============================== internal ==============================

    /**
     * 业务作用：把毫秒时间戳折算成时间槽序号。
     *
     * @param timeMs 毫秒时间戳
     * 返回: 时间槽序号。
     */
    private static long toSlot(long timeMs) {
        return (timeMs - BASE_TIME) / SLOT_MS;
    }

    /**
     * 业务作用：把时间槽序号映射到环形数组下标。
     *
     * @param timeMs 毫秒时间戳
     * 返回: 环形数组下标。
     */
    private int slotIndex(long timeMs) {
        return (int) (toSlot(timeMs) % slots);
    }

    /**
     * 业务作用：按需把窗口累计量折算成每秒速率，使不同窗口长度的统计可以横向比较。
     *
     * @param cvrSec 为 true 时把结果折算成每秒速率
     * @param opsVal 窗口内的累计量
     * 返回: 折算后的值；不折算时原样返回。
     */
    private double cvrSec(boolean cvrSec, long opsVal) {
        return cvrSec ? opsVal / ((double) window / 1000) : opsVal;
    }

    /**
     * 业务作用：tick: 清零已过期的槽位, 从 total 中减去.
     * 只由全局 TimingWheel 1ms 周期任务调用, 单线程无竞争.
     *
     * @param currentSlot 见上述说明
     * 返回: 无返回值。
     */
    void tick(long currentSlot) {
        // 需要清零 (clearSlotTime, currentSlot - slots] 范围外的已过期槽
        // 即从 clearSlotTime+1 到 currentSlot - slots + 1
        long expireSlot = currentSlot - slots + 1;
        if (expireSlot <= clearSlotTime) return;

        // 限制单次最多清 slots 个 (防止长时间未 tick 后一次性遍历过多)
        long from = clearSlotTime + 1;
        if (expireSlot - from >= slots) from = expireSlot - slots + 1;

        long expired = 0;
        for (long s = from; s <= expireSlot; s++) {
            int idx = (int) (s % slots);
            int val = ring.getAndSet(idx, 0);
            expired += val;
        }
        if (expired > 0) {
            total.addAndGet(-expired);
        }
        clearSlotTime = expireSlot;
    }

    // ============================== global tick ==============================

    /**
     * 业务作用：确保推进时间槽的定时任务已启动，只在首次调用时真正启动。
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    private static void ensureTickStarted() {
        if (tickStarted) return;
        tickLock.lock();
        try {
            if (tickStarted) return;
            tickStarted = true;
            if (!TimingWheel.isStarted()) TimingWheel.startTimingWheel();
            TimingWheel.exec(1, 1, () -> {
                long currentSlot = toSlot(System.currentTimeMillis());
                for (OPS ops : OPS_CACHE.values()) {
                    ops.tick(currentSlot);
                }
            });
        } finally {
            tickLock.unlock();
        }
    }
}
