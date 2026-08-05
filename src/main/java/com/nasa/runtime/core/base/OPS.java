package com.nasa.runtime.core.base;

import com.nasa.runtime.core.function.Action;
import com.nasa.runtime.core.utils.StringUtils;

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

    public void startSafely() {
        startSafely(10000);
    }

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

    public void remove() {
        this.removeSafely(null);
    }

    /**
     * 移除已存在的计数，有scene的才执行Action
     */
    public void removeSafely(Action success) {
        if (OPS_CACHE.remove(this.scene()) != null && success != null) success.action();
    }

    public abstract String scene();

    // ============================== signal ==============================

    /**
     * OPS + 1
     */
    public double signal() {
        return signal(false);
    }

    /**
     * OPS + 1
     * @param cvrSec true:每秒OPS false:窗口OPS
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
     * OPS + n
     */
    public double signal(int n) {
        return signal(false, n);
    }

    /**
     * OPS + n
     * @param cvrSec true:每秒OPS false:窗口OPS
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
     * 获取本场景OPS
     */
    public double ops() {
        return ops(false);
    }

    /**
     * 获取本场景OPS
     * @param cvrSec true:每秒OPS false:窗口OPS
     */
    public double ops(boolean cvrSec) {
        return cvrSec(cvrSec, total.get());
    }

    /**
     * 获取多个场景总共的OPS
     */
    public double ops(Collection<String> scenes) {
        return ops(false, scenes);
    }

    /**
     * 获取多个场景总共的OPS
     * @param cvrSec true:每秒OPS false:窗口OPS
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

    /** 绝对时间 → 绝对槽号 */
    private static long toSlot(long timeMs) {
        return (timeMs - BASE_TIME) / SLOT_MS;
    }

    /** 绝对时间 → ring 索引 */
    private int slotIndex(long timeMs) {
        return (int) (toSlot(timeMs) % slots);
    }

    private double cvrSec(boolean cvrSec, long opsVal) {
        return cvrSec ? opsVal / ((double) window / 1000) : opsVal;
    }

    /**
     * tick: 清零已过期的槽位, 从 total 中减去.
     * 只由全局 TimingWheel 1ms 周期任务调用, 单线程无竞争.
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
