package com.nasa.runtime.core.base;

import com.nasa.runtime.core.concurrent.MPSCLinkedQueue;
import com.nasa.runtime.core.concurrent.NameThreadFactory;
import com.nasa.runtime.core.concurrent.ThreadPoolUtils;
import com.nasa.runtime.core.config.Graceful;
import com.nasa.runtime.core.function.Action;
import com.nasa.runtime.core.function.ActionRecycler;
import com.nasa.runtime.core.utils.MapUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Nasa
 * 面向高吞吐和低 GC 场景的分层时间轮
 * <p>
 * 参照 Netty HashedWheelTimer / Kafka TimingWheel / Caffeine 设计，零锁实现：
 *   offer()  →  MPSCLinkedQueue (XADD 入队，单 consumer 出队)
 * <pre>
 *                       │
 *               ScheduledExecutor 每 tickMs 触发 tick()
 *                       │
 *                       ├── 1. drainSubmitQueue：从队列 poll 出任务，wheel.add() 写入轮槽
 *                       │
 *                       └── 2. 指针推进：逐层检查到期 Slot，flush 后任务降级或执行
 *                               │
 *                               ├── 已到期 → virtualExecutor 执行
 *                               └── 未到期 → wheel.add() 降级到低层轮槽
 *   - MPSC 无锁队列提交任务，零生产者竞争（Netty 思路）
 *   - 分层时间轮，上层粗放存储，到期后 flush 降级到下层精确执行（Kafka 思路）
 *   - ScheduledExecutor 心跳信号 + 指针推进 tick，精准且无空轮询（Netty 思路）
 *   - 惰性取消，volatile cancelled 标记，flush/drain/exec 时跳过回收（Caffeine 思路）
 *   - 单信号线程推进时间轮，全程无锁；任务执行交由 virtualExecutor（虚拟线程池）
 * </pre>
 * <p>
 * 分层结构（以 wheelSize=1000, tickMs=1 为例）：
 * <pre>
 *   Layer1: tickMs=1ms,      interval=1s      → 覆盖 0~1s，精确到 1ms
 *   Layer2: tickMs=1000ms,   interval=1000s   → 覆盖 0~1000s，精确到 1s（按需创建）
 *   Layer3: tickMs=1000000ms, interval=1000000s → 覆盖更远（按需创建）
 * </pre>
 */
@SuppressWarnings("all")
@Slf4j
public class TimingWheel {

    private static final int PARTITION_RUNNING = 0;
    private static final int PARTITION_PARKED = 1;

    /*
     * 时间轮是单例的。
     * volatile 防 DCL 不安全发布: of() 在 createLock 外做 fast-path 读, 缺 volatile 时弱内存模型可能读到
     * 半构造对象 (其他线程见 timingWheel 非 null 但内部字段未发布); 且 stop() 会置 null, 没 volatile
     * 的话其他线程可能持续看到陈旧的非 null 引用. createLock 只保护写, 读侧靠 volatile 拿 happens-before.
     */
    private static volatile TimingWheel timingWheel;
    private static final ReentrantLock createLock = new ReentrantLock();

    private static TimingWheel create(int wheelSize, int tickMs) {
        createLock.lock();
        try {
            if (Objects.nonNull(timingWheel)) return timingWheel;
            return timingWheel = new TimingWheel(wheelSize, tickMs);
        } finally {
            createLock.unlock();
        }
    }

    /**
     * get the Timing Wheel
     *
     * @param wheelSize 每层时间轮槽数
     * @param tickMs    最底层 tick 时间间隔（ms）
     */
    public static TimingWheel of(int wheelSize, int tickMs) {
        return Objects.isNull(timingWheel) ? create(wheelSize, tickMs) : timingWheel;
    }

    /**
     * get the Timing Wheel, wheel size is 1000, tick is 1ms.
     */
    public static TimingWheel of() {
        return of(1000, 1);
    }

    public static boolean isStarted() {
        return of().started;
    }

    /**
     * 健康检查: 已启动且无 partition worker 崩溃. partition worker 因未捕获异常退出后该 slot 永久拒收
     * (同 key 任务不可执行), 此时返回 false, 上层据此熔断 / 重启整个 wheel (框架不静默自愈)。
     */
    public static boolean isHealthy() {
        TimingWheel tw = of();
        return tw.started && tw.deadPartitions.sum() == 0;
    }

    /**
     * 已崩溃的 partition worker 数 (0 = 全部健康)。配合 {@link #isHealthy()} 供监控 / oncall 决策。
     */
    public static int deadPartitionCount() {
        return (int) of().deadPartitions.sum();
    }

    /**
     * 开启时间轮
     */
    public static void startTimingWheel() {
        of().start();
    }

    /**
     * @param action 具体任务
     */
    public static void exec(Action action) {
        exec(0, action);
    }

    /**
     * @param delay  延迟时间
     * @param action 具体任务
     */
    public static void exec(long delay, Action action) {
        exec(delay, null, action);
    }

    /**
     * @param delay  延迟时间
     * @param unique 唯一标识
     * @param action 具体任务
     */
    public static void exec(long delay, String unique, Action action) {
        exec(delay, 0, unique, action);
    }

    /**
     * @param delay  延迟时间
     * @param period 周期时间
     * @param action 具体任务
     */
    public static void exec(long delay, long period, Action action) {
        exec(delay, period, null, action);
    }

    /**
     * @param delay  延迟时间
     * @param period 周期时间，0 表示非周期任务
     * @param unique 唯一标识
     * @param action 具体任务
     */
    public static void exec(long delay, long period, String unique, Action action) {
        of().offer(delay, period, unique, action);
    }

    // ==================== platform: 平台线程执行 (不受虚拟线程 pin 影响) ====================

    /**
     * 立即在平台线程执行
     */
    public static void platform(Action action) {
        platform(0, action);
    }

    /**
     * 延迟后在平台线程执行
     */
    public static void platform(long delay, Action action) {
        platform(delay, 0, null, action);
    }

    /**
     * @param delay  延迟时间
     * @param unique 唯一标识
     * @param action 具体任务
     */
    public static void platform(long delay, String unique, Action action) {
        platform(delay, 0, unique, action);
    }

    /**
     * 周期性在平台线程执行
     */
    public static void platform(long delay, long period, Action action) {
        platform(delay, period, null, action);
    }

    /**
     * 周期性在平台线程执行 (带唯一标识)
     */
    public static void platform(long delay, long period, String unique, Action action) {
        of().offer(delay, period, unique, true, action);
    }

    /**
     * 静态移除任务：O(1) 惰性标记
     *
     * @param unique 任务唯一标识
     */
    public static void cancel(String unique) {
        of().remove(unique);
    }

    /**
     * 静态延期任务
     *
     * @param unique      任务唯一标识
     * @param delayMillis 延期毫秒数
     */
    public static void delay(String unique, long delayMillis) {
        of().postpone(unique, delayMillis);
    }

    // ==================== partition: 同 key 同线程串行消费 (TW.md 设计) ====================

    /**
     * 提交"按 key 串行消费"任务: 同 {@code key} 的所有 action 永远在同一个 virtual worker 线程上按 FIFO 执行,
     * 不同 key 之间最大并发 (落到不同 partition 的 worker 线程).
     * <p>
     * 典型场景: 多个生产者提交同一业务键的状态更新，由 TimingWheel 严格按照提交顺序写入外部存储，
     * 避免并发写入乱序后在恢复阶段读取到错误状态。
     * <p>
     * 语义保证:
     *   <ul>
     *     <li>同 {@code key} (按 {@code key.hashCode()} 散列定 partition slot) 的所有 action 串行,
     *         producer 提交顺序 == consumer 执行顺序</li>
     *     <li>不同 key 完全并发 (落到不同 partition 时), 落到同一 partition 时仍按 FIFO 串行 — 这是哈希碰撞代价,
     *         一般 N=16 足够稀释; 业务有热点 key 时可调大 {@code -Dnasa.timing-wheel.partitions}</li>
     *     <li>跨线程上下文透传: 复用 {@link AnyHolder#snapshot} + {@link ActionRecycler}, 跟 {@link #exec} 一致</li>
     *     <li>worker 用 virtual thread + {@link LockSupport#park}/{@code unpark}, 空闲时 unmount carrier, 不占核</li>
     *   </ul>
     * <p>
     * 注意: 业务侧 lambda 不能在 partition worker 内做长阻塞 (会拖慢同 partition 的所有后续任务).
     * 长任务请走 {@link #exec} 让虚拟线程池调度.
     *
     * @param key    路由 key (按 {@code key.hashCode()} 散列, null 会落到 slot 0)
     * @param action 实际执行的任务
     */
    public static void partition(Object key, Action action) {
        of().offerPartition(key, action);
    }

    /**
     * {@link #partition(Object, Action)} 的 primitive long 版。路由 key 为 long 时优先使用此重载:
     * 走无装箱散列, 避免 long→Long autobox (Long 缓存仅 -128..127, 业务 id 通常超出, 否则每笔 1 次 Long 分配)。
     * 高频路径使用该重载可避免 key 装箱，使 partition 提交保持零 GC。
     *
     * @param key    路由 key (long, 按 {@link Long#hashCode(long)} 散列)
     * @param action 实际执行的任务
     */
    public static void partition(long key, Action action) {
        of().offerPartition(key, action);
    }

    // NOTE ==================== 核心字段 ====================

    /* ActionRecycler 消费策略：将参数快照还原到执行线程的 AnyHolder，再执行业务 Action */
    static final Consumer<ActionRecycler> BICO = ar -> {
        AnyHolder.putAll(ar.ref(1));
        ((Action) ar.ref(0)).action();
    };

    /* task 对象池 */
    private final TaskObjectPool taskPool = new TaskObjectPool(this);

    /* unique → task 索引，O(1) 定位任务 */
    private final ConcurrentHashMap<String, Task> uniqueIndex = new ConcurrentHashMap<>();

    /*
     * MPSC（Multi-Producer Single-Consumer）无锁提交队列
     * MP：任意数量业务线程同时 offer()，仅 CAS 竞争，无阻塞
     * SC：唯一的信号线程在 tick() 中 poll() 消费，因此消费侧全程无锁
     */
    private final MPSCLinkedQueue<Task> submitQueue = new MPSCLinkedQueue<>();
    /* 缓存 this::addOrExecute，避免捕获型方法引用每次 tick 都创建新对象 */
    private final Consumer<Task> addOrExecuteRef = this::addOrExecute;

    /*
     * 高频周期任务快车道（period <= tickMs 的任务，如 1ms 周期任务）
     *
     * 这类任务每个 tick 都要执行，走 submitQueue → drain → wheel.add → flush → execute → re-offer 一圈纯属浪费。
     * 直接放在 ArrayList 里，每次 tick 遍历执行，省掉整条链路。
     * 只被信号线程读写，无需锁。
     */
    private final ArrayList<Task> tickTasks = new ArrayList<>();

    /* 最底层 tick 时间粒度 ms */
    private final int tickMs;
    /* 最底层时间轮 */
    private final WheelLayer wheel;

    /* 时间轮推进信号线程池（1ms 心跳） */
    private ScheduledExecutorService scheduledExecutor;
    /* 时间轮推进信号 */
    private final Action worker;

    // ---- partition: 按 key 串行消费 ----
    /**
     * Partition 数组: 每槽绑定一个 {@link Partition} (worker / queue / signal / 本代 running 标记 / stopped latch).
     * <p>
     * 用专用 {@link Partition} 类替代旧的 raw {@code KV[]} + {@code PartitionSignal[]} 双数组:
     * 去 raw type / unchecked cast / 平行数组索引错配; worker 循环看 <b>本代 {@code running}</b> 而非全局 {@code started},
     * 使 stop→start 的旧 worker 一定退出, 新 start 建新一代, 两代互不复活 (避免孤儿 virtual thread)。
     */
    private volatile Partition[] partitions;

    /**
     * partition mask = partitions.length - 1; spread 后 AND mask 即 slot idx.
     */
    private int partitionMask;
    /*
     * 在途 partition producer 计数: stop 时先关 started, 再等 inflight 清零, 保证 worker drain 时无 in-flight offer (不丢任务).
     * 为何用 LongAdder 而非 AtomicInteger: offerPartition 是热路径, LongAdder 的 increment 无 CAS 竞争, 优于 AtomicInteger.
     * 作为 shutdown 闸门是安全的: 真正的守卫是 producer increment 后对 started 的二次检查 ——
     *   stop 先置 started=false; 与 stop 并发 increment 的 producer 二次检查见 started=false 直接 return 不 offer;
     *   而已通过二次检查、正在 offer 的 producer, 其 increment 早已完成且对随后 sum() 可见 (cell 是 volatile 读),
     *   sum() 必 ≥1 → stop 会等它. 故不存在"sum() 漏掉正在 offer 的 producer"的窗口. sum() 非原子快照在此无害.
     */
    private final LongAdder partitionInflight = new LongAdder();
    /*
     * 在途普通 offer/postpone 计数: stop 时先关 started, 再等 inflight 清零, 保证 scheduler/executor 被 shutdown
     * 与 submitQueue/uniqueIndex/taskPool 被 clear 之前, 已通过 started check 的 producer 全部 offer 完毕,
     * 否则 producer 把 task 塞进已清空的 submitQueue → 任务永远不被 tick 消费 → 静默丢失 (含 uniqueIndex 残留映射).
     * 用 LongAdder 而非 AtomicInteger 同 partitionInflight: offer 是热路径, LongAdder increment 无 CAS 竞争.
     * 闸门同样 sound: producer increment 后 re-check started, 见 false 则 return 不入队;
     * 正在 offer 的 producer 其 increment 对 sum() 可见, sum() 必 ≥1 → stop 会等它.
     */
    private final LongAdder offerInflight = new LongAdder();
    /*
     * 崩溃 partition worker 计数: drainPartition catch 块 (worker 因未捕获 Throwable 退出) 时 increment.
     * 健康信号: 该 slot 此后永久拒收 (防黑洞), 同 key 任务不可执行 → 上层应经 isHealthy()/deadPartitionCount()
     * 感知并熔断/重启整个 wheel (框架不静默自愈, 也不自动重建以免 crash 因素复发进重启循环).
     * 实例级: stop() 末尾置 timingWheel=null, 重启会新建实例计数归零.
     */
    private final LongAdder deadPartitions = new LongAdder();
    /* start/stop 互斥锁, 替代 synchronized (worker) */
    private final ReentrantLock workerLock = new ReentrantLock();
    /* 虚拟线程池 (默认, 业务任务用) */
    @Getter
    private ExecutorService virtualExecutor;
    /* 平台线程池 (关键任务用, 不受虚拟线程 pin 影响) */
    @Getter
    private ExecutorService platformExecutor;
    /* volatile 保证跨线程可见性 */
    private volatile boolean started = false;

    private TimingWheel(int wheelSize, int tickMs) {
        if (wheelSize < 2) {
            throw new IllegalArgumentException("TimingWheel's wheelSize must be greater than or equal to 2.");
        }
        if (tickMs < 1) {
            throw new IllegalArgumentException("TimingWheel's tickMs must be greater than 0.");
        }
        this.tickMs = tickMs;

        // 对齐到 tickMs 的整数倍
        long startMs = System.currentTimeMillis();
        startMs = startMs - (startMs % tickMs);
        this.wheel = new WheelLayer(tickMs, wheelSize, startMs);

        // 信号线程池
        this.scheduledExecutor = Executors.newSingleThreadScheduledExecutor(
                new NameThreadFactory("TimingWheel-scheduled-" + tickMs + "ms", true));
        // 虚拟线程池: 业务任务默认执行器
        this.virtualExecutor = ThreadPoolUtils.newVirtualThreadPool(true, "Virtual-Pool");
        // 平台线程池: 关键定时任务 (如分布式锁看门狗、rebalance), 不受虚拟线程 pin 影响
        this.platformExecutor = ThreadPoolUtils.newThreadPool(true
                , Runtime.getRuntime().availableProcessors() << 1
                , Runtime.getRuntime().availableProcessors() << 2
                , 60_000L
                , 8192
                , "Platform-Pool-"
                , ThreadPoolExecutor.AbortPolicy.class);


        // 时间轮心跳信号：drain 提交队列 + 指针推进 flush 到期 slot
        this.worker = this::tick;

        // 优雅停机
        Graceful.registry(Integer.MAX_VALUE, () -> {
            this.stop();
            ThreadPoolUtils.gracefulShutdown();
        });
    }

    // NOTE ==================== start / stop ====================

    public TimingWheel start() {
        if (started) return this;
        workerLock.lock();
        try {
            if (started) return this;
            if (scheduledExecutor.isShutdown()) {
                this.scheduledExecutor = Executors.newSingleThreadScheduledExecutor(
                        new NameThreadFactory("TimingWheel-scheduled-" + tickMs + "ms", true));
            }
            if (virtualExecutor.isShutdown()) {
                this.virtualExecutor = ThreadPoolUtils.newVirtualThreadPool(true, "Virtual-Pool");
            }
            if (platformExecutor.isShutdown()) {
                this.platformExecutor = ThreadPoolUtils.newThreadPool(true
                        , Runtime.getRuntime().availableProcessors() << 1
                        , Runtime.getRuntime().availableProcessors() << 2
                        , 60_000L
                        , 8192
                        , "Platform-Pool-"
                        , ThreadPoolExecutor.AbortPolicy.class);
            }
            // 先把全部资源 ready: spawn partition worker (循环看本代 running, 不依赖 started) + 调度 tick.
            // 失败则回滚, 不留半启动状态 (started 始终 false).
            try {
                this.startPartitions();
                scheduledExecutor.scheduleAtFixedRate(worker, 0, tickMs, TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                // 回滚已 spawn 的 worker
                this.stopPartitions();
                throw t;
            }
            // 资源全 ready 后最后发布 started: 杜绝"started=true 但 partitions 未就绪"的半启动窗口
            // (此前 offerPartition/offer 一律 reject, 之后 partitions 已 volatile 发布可见).
            started = true;
            // 启动日志走 execute 异步打印, 防止 log appender 慢启动阻塞调用方; REE 时降级同步.
            try {
                scheduledExecutor.execute(() -> log.info("TimingWheel has started."));
            } catch (RejectedExecutionException ignored) {
                log.info("TimingWheel has started.");
            }
        } finally {
            workerLock.unlock();
        }
        return this;
    }

    public void stop() {
        if (!started) return;
        workerLock.lock();
        try {
            if (!started) return;
            // 顺序 (不丢任务): 关 started (阻新 offer) → 等在途 producer 清零 → 停 worker(等其 drain 排空+退出).
            started = false;
            this.awaitOfferInflightDrained();
            this.awaitPartitionInflightDrained();
            this.stopPartitions();
            if (!scheduledExecutor.isShutdown()) {
                /*
                 * 关闭日志走 scheduledExecutor 异步, 保证日志在 shutdown 前最后一条 task 排到队尾。
                 * try-catch 防御: 多线程场景下 isShutdown() 与 execute() 之间可能有 race
                 * (虽然此处持有 workerLock, 但 ScheduledExecutor 内部可能因 OOM / 超过 maxThreads 抛 REE)。
                 * 失败时降级为同步日志, 不影响 shutdown() 主流程。
                 */
                try {
                    scheduledExecutor.execute(() -> log.info("TimingWheel has closed."));
                } catch (RejectedExecutionException ignored) {
                    log.info("TimingWheel has closed.");
                }
                scheduledExecutor.shutdown();
                /*
                 * shutdown() 不阻塞, tick 可能正在 executeTickTasks / drainSubmitQueue 跑;
                 * 不等就 clear uniqueIndex/taskPool/submitQueue/tickTasks 会跟 tick 并发, 状态紊乱
                 * (ArrayList 遍历中清空 → IOBE, taskPool removeAll 中并发 get → 错乱).
                 * 这里 await 给 tick 一个干净退出窗口, 超时只 warn 不抛 (shutdown 主流程必须走完).
                 */
                long tickAwaitMs = Long.getLong("nasa.timing-wheel.tick-await-ms", 2000L);
                try {
                    if (!scheduledExecutor.awaitTermination(tickAwaitMs, TimeUnit.MILLISECONDS)) {
                        log.warn("TimingWheel scheduledExecutor not terminated within {}ms, proceeding with state clear; tick may race with clear", tickAwaitMs);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("TimingWheel stop interrupted while awaiting scheduledExecutor termination", ie);
                }
            }
            if (!virtualExecutor.isShutdown()) {
                virtualExecutor.shutdown();
            }
            if (!platformExecutor.isShutdown()) {
                platformExecutor.shutdown();
            }
            /*
             * 等 virtual/platform 池里已接收的 execAction (含周期任务的 execTask) 跑完再 clear:
             * 否则在途 execTask 会在 submitQueue.clear 之后 re-offer (execTask/addOrExecute 的 started 守卫只缩小窗口,
             * 这里彻底关闭), 且并发 clear 与 task 访问 taskPool/uniqueIndex 会状态紊乱.
             * bounded (可配 nasa.timing-wheel.exec-await-ms), 超时只 warn (停机主流程必须走完).
             */
            long execAwaitMs = Long.getLong("nasa.timing-wheel.exec-await-ms", 2000L);
            this.awaitExecutorTermination(virtualExecutor, "virtualExecutor", execAwaitMs);
            this.awaitExecutorTermination(platformExecutor, "platformExecutor", execAwaitMs);
            ThreadPoolUtils.clearShutdown();
            uniqueIndex.clear();
            taskPool.removeAll();
            submitQueue.clear();
            tickTasks.clear();
            timingWheel = null;
        } finally {
            workerLock.unlock();
        }
    }

    // NOTE ==================== offer / remove / delay（外部调用，无锁） ====================

    /**
     * @param delay  延迟时间
     * @param action 具体任务
     */
    public void offer(long delay, Action action) {
        this.offer(delay, 0, null, action);
    }

    /**
     * @param delay  延迟时间
     * @param unique 唯一标识
     * @param action 具体任务
     */
    public void offer(long delay, String unique, Action action) {
        this.offer(delay, 0, unique, action);
    }

    /**
     * @param delay  延迟时间
     * @param period 周期时间
     * @param action 具体任务
     */
    public void offer(long delay, long period, Action action) {
        this.offer(delay, period, null, action);
    }

    /**
     * @param delay  延迟时间
     * @param period 周期时间，0 表示非周期任务，> 0 表示周期任务
     * @param unique 唯一标识
     * @param action 具体任务
     */
    public void offer(long delay, long period, String unique, Action action) {
        this.offer(delay, period, unique, false, action);
    }

    public void offer(long delay, long period, String unique, boolean platform, Action action) {
        // 未启动/停机短路: 与 offerPartition 一致, 归还 ofRecycle Recycler 防泄漏 (普通 Action no-op)
        if (!started) {
            recycleDropped(action);
            return;
        }
        if (period < 0) {
            throw new IllegalArgumentException("Period must be >= 0.");
        }
        /*
         * inflight 计数: stop 会先关 started 再等 offerInflight 清零, 保证 scheduler/submitQueue/uniqueIndex
         * 被 shutdown/clear 之前, 已通过 started check 的 producer 已完整入队. 否则 producer 把 task 塞进
         * 已 clear 的 submitQueue → tick 永不消费 → 任务静默丢失. 跟 offerPartitionHashed 同款 sound.
         */
        this.offerInflight.increment();
        try {
            // re-check: stop 可能在 increment 后置 started=false; 此时不再入队 (让 wheel 干净 shutdown)
            if (!started) {
                recycleDropped(action);
                return;
            }
            RecycleLinkedMap<String, Object> passthrough = AnyHolder.snapshot();
            // 一次性 immediate (delay<1 && period==0): 不需要 task, 直接 executor 跑, ActionRecycler 携 snapshot
            if (delay < 1 && period == 0) {
                Action exec = action;
                boolean wrapped = false;
                if (MapUtils.isNotEmpty(passthrough)) {
                    ActionRecycler ar = ActionRecycler.ofRecycle(BICO);
                    ar.ref(0, action);
                    ar.refRecycle(1, passthrough);
                    exec = ar;
                    wrapped = true;
                }
                try {
                    this.executor(platform).execute(exec);
                } catch (RejectedExecutionException e) {
                    // 一次性 immediate REE: wrapper.recycle 只 cascade ref(1)=snapshot, ref(0)=action 未标 refRecycle
                    // (正常 fire 路径 action 自行 self-recycle) → 此处 action 未 fire, 须额外 recycleDropped 归还
                    // ofRecycle Recycler (普通 Action no-op). 无 wrapper 时同样兜底原 action. 两者不同对象, 不会重复归池.
                    if (wrapped) ((ActionRecycler) exec).recycle();
                    recycleDropped(action);
                    throw e;
                } catch (Throwable t) {
                    if (wrapped) ((ActionRecycler) exec).recycle();
                    recycleDropped(action);
                    throw TimingWheel.propagate(t);
                }
                return;
            }
            long now = System.currentTimeMillis();
            long timeout;
            if (delay < 1) {
                /*
                 * #2 #6 修复: immediate periodic (delay<1 && period>0) 不再走"裸 execute(action) +
                 * 独立 immSnap + 后入 submitQueue"分叉, 而是创建 task → executor.execute(task.execAction()):
                 * - 复用 execTask 的 RUNNING 守卫 (CAS false→true) — 修 #2 immediate 跑同时 wheel tick 又触发周期
                 *   导致同一 action 并发的 bug.
                 * - task.parameters 持有原 passthrough 跨次复用, REE 时 task.cancelledRecycle() → restore()
                 *   归池 parameters — 修 #6 原代码 REE 只 recycle wrapper / 不 recycle 原 passthrough 的泄漏.
                 * - 取消 immSnap copy 一次 RecycleLinkedMap 分配, 顺手省一次池操作.
                 * timeout = now: execTask 内 do-while (timeout+=period) until > now, 续约后 = now+period,
                 * 再入 submitQueue. 等价于"立刻一次 + 每 period 一次"语义.
                 */
                timeout = now;
            } else {
                timeout = delay + now;
                if (period > 0) while (timeout < now) timeout += period;
            }
            Task task = this.taskPool.get(timeout, period, unique, action, platform);
            // Task 持有快照: 一次性 → task.restore() 回收; 周期 → 跨次复用
            task.setParameters(passthrough);
            this.registerUnique(unique, task);
            if (delay < 1) {
                // immediate periodic: 走 execTask, 续约 + RUNNING 守卫 + re-offer 一气呵成
                try {
                    this.executor(platform).execute(task.execAction());
                } catch (RejectedExecutionException e) {
                    if (unique != null) this.uniqueIndex.remove(unique, task);
                    // action 未 fire 且 task 未入轮: cancelledRecycle 让 ActionRecycler 等正确归池 + task.restore 归池 parameters
                    task.cancelledRecycle();
                    throw e;
                } catch (Throwable t) {
                    if (unique != null) this.uniqueIndex.remove(unique, task);
                    task.cancelledRecycle();
                    throw TimingWheel.propagate(t);
                }
                return;
            }
            // MPSC 无锁入队, Worker 线程消费
            this.offerSubmitTask(task);
        } finally {
            this.offerInflight.decrement();
        }
    }

    /**
     * 移除任务：O(1) 惰性标记
     */
    public void remove(String unique) {
        if (!started) return;
        Task task = this.uniqueIndex.remove(unique);
        if (Objects.nonNull(task)) {
            task.cancelled = true;
        }
    }

    /**
     * 注册 unique 索引。重复 unique 按“后提交覆盖前提交”处理，旧任务只做惰性取消，
     * 由原所在路径 drain / flush / exec 时负责回收。
     */
    private void registerUnique(String unique, Task task) {
        if (unique == null) return;
        Task old = this.uniqueIndex.put(unique, task);
        if (old == null || old == task) return;
        old.cancelled = true;
    }

    /**
     * 提交到 MPSC 队列。若 offer 在发布前失败，任务不会再被 tick 消费，必须撤销索引并回收。
     */
    private void offerSubmitTask(Task task) {
        try {
            this.submitQueue.offer(task);
        } catch (Throwable t) {
            if (task.unique != null) this.uniqueIndex.remove(task.unique, task);
            task.cancelledRecycle();
            throw TimingWheel.propagate(t);
        }
    }

    /**
     * 将任务延期多少毫秒再执行
     *
     * @param unique      任务唯一标识
     * @param delayMillis 延期毫秒数
     */
    public void postpone(String unique, long delayMillis) {
        if (!started) return;
        if (delayMillis < 1) {
            throw new IllegalArgumentException("delayMillis must be greater than 0.");
        }
        /*
         * #1 修复: 跟 offer 一样的 inflight 闸门, 防 stop 并发 clear submitQueue 导致 delayTask 静默丢失.
         * postpone 还多一层灾难性后果: 老 task 已被 cancelled=true, 新 delayTask 若被 clear 吞掉, 该 unique
         * 任务永久消失 (不像 offer 至少 producer 知道入队成功 — postpone 用户以为延期了实际任务被吃掉).
         */
        this.offerInflight.increment();
        try {
            if (!started) return;
            Task task = this.uniqueIndex.remove(unique);
            if (Objects.isNull(task)) return;
            // 给 delayTask 拷一份独立 snapshot, 不共享老 task 的 parameters 引用 — 避免两个 task 各自 recycle 时
            // 重复回收同一个 RecycleLinkedMap (double-recycle, 池数据损坏).
            // 在标记 cancelled 之前先 copy, 缩短和 signal 线程 recycle 老 task 的 race 窗口.
            LinkedHashMap<String, Object> oldParams = task.parameters;
            RecycleLinkedMap<String, Object> newParams = null;
            if (oldParams != null && !oldParams.isEmpty()) {
                newParams = RecycleLinkedMap.of();
                newParams.putAll(oldParams);
            }
            // 标记旧任务取消 (老 task 的 parameters 由它自己 recycle 时回收)
            task.cancelled = true;
            // 创建新任务, 持有独立 snapshot
            Task delayTask = this.taskPool.get(task.timeout + delayMillis, task.period, task.unique, task.action, task.platform);
            delayTask.setParameters(newParams);
            this.registerUnique(delayTask.unique, delayTask);
            this.offerSubmitTask(delayTask);
        } finally {
            this.offerInflight.decrement();
        }
    }

    // NOTE ==================== tick 信号（ScheduledExecutor 每 tickMs 触发一次） ====================

    private void tick() {
        /*
         * 外层 try-catch 兜底, 防止整个时间轮挂掉。
         * 原因: tick() 由 ScheduledExecutorService.scheduleAtFixedRate 调度, 其 API docs 明确规定
         * "If any execution of the task encounters an exception, subsequent executions are
         * suppressed" — 单次 tick 抛任何异常都会导致 ScheduledExecutor 永久停止后续调度,
         * 整个 wheel 死亡, 所有定时/周期任务永久失效。
         * 内层 #2 #3 的精细 try-catch 已经处理了已知 RejectedExecutionException 风险,
         * 此处兜底是双保险, 防御未来代码改动 / 新引入的异常路径 / executor 内部抛 OOM 等极端情况。
         */
        try {
            // 1. 高频周期任务快车道：直接遍历执行，无 submitQueue/wheel 开销
            if (!tickTasks.isEmpty()) {
                this.executeTickTasks();
            }

            // 2. drain 提交队列，将任务放入时间轮
            this.drainSubmitQueue();

            // 3. 指针推进：逐层检查到期 slot，flush 后任务降级或执行
            long now = System.currentTimeMillis();
            WheelLayer layer = wheel;
            while (layer != null) {
                long aligned = now - (now % layer.tickMs);
                // 补偿：如果信号线程有延迟，逐个 tick 追赶，不遗漏任何 slot
                while (layer.currentTime < aligned) {
                    layer.currentTime += layer.tickMs;
                    int idx = (int) ((layer.currentTime / layer.tickMs) % layer.wheelSize);
                    Slot slot = layer.slots[idx];
                    if (slot.expiration != -1L && slot.expiration <= now) {
                        slot.flush(addOrExecuteRef);
                    }
                }
                layer = layer.overflowWheel;
            }
        } catch (Throwable t) {
            log.error("TimingWheel tick failed, continuing next round to keep wheel alive", t);
        }
    }

    /**
     * 执行高频周期任务（period <= tickMs），惰性清理已取消的任务
     */
    private void executeTickTasks() {
        for (int i = this.tickTasks.size() - 1; i >= 0; i--) {
            Task task = this.tickTasks.get(i);
            if (task.cancelled) {
                if (task.running) continue;
                this.tickTasks.remove(i);
                if (Objects.nonNull(task.unique)) this.uniqueIndex.remove(task.unique, task);
                task.cancelledRecycle();
                continue;
            }
            if (!Task.RUNNING.compareAndSet(task, false, true)) continue;
            /*
             * 防 RejectedExecutionException 拖死 wheel。
             * 触发场景:
             *   1) executor 已 shutdown (例如 stop() 期间 race window) → executor.execute 抛 REE
             *   2) 业务通过 setVirtualExecutor / 或 platformExecutor 注入有界池 + AbortPolicy, 队列满抛 REE
             *   3) 默认 platformExecutor 用 CallerRunsPolicy 静默丢弃, 不抛; 但业务自定义可能换策略
             * 不 catch 的后果: 异常逃逸到 ScheduledExecutorService.scheduleAtFixedRate, 后者 API docs 明确
             * 规定一次异常即终止后续所有调度, 整个时间轮永久挂死。
             * 这里 catch 后仅丢这次执行, tickTasks 中的任务下次 tick 重试 (高频任务本来就很快有下一轮)。
             */
            try {
                this.executor(task.platform).execute(task.tickAction());
            } catch (RejectedExecutionException e) {
                Task.RUNNING.setRelease(task, false);
                log.warn("TimingWheel tick task rejected, will retry next tick: unique={}", task.unique, e);
            } catch (Throwable t) {
                Task.RUNNING.setRelease(task, false);
                log.error("TimingWheel tick task execute failed before dispatch, will retry next tick: unique={}", task.unique, t);
            }
        }
    }

    /**
     * 批量 drain 提交队列到时间轮（单线程调用，无需锁）
     */
    void drainSubmitQueue() {
        Task task;
        while ((task = this.submitQueue.poll()) != null) {
            if (task.cancelled) {
                task.cancelledRecycle();
                continue;
            }
            // period <= tickMs 的高频周期任务，分流到 tickTasks 快车道
            if (task.period > 0 && task.period <= tickMs) {
                this.tickTasks.add(task);
                continue;
            }
            this.addOrExecute(task);
        }
    }

    /**
     * 根据 task.platform 选择执行器
     */
    private ExecutorService executor(boolean platform) {
        return platform ? platformExecutor : virtualExecutor;
    }

    /**
     * 尝试将任务放入时间轮，已到期则直接执行
     */
    private void addOrExecute(Task task) {
        if (task.cancelled) {
            task.cancelledRecycle();
            return;
        }
        // 尝试放入时间轮
        if (wheel.add(task)) return;
        /*
         已到期，提交到工作线程池执行
         使用 task.execAction() 而非 () -> execTask(task)，复用 Task 实例上缓存的 Action，零 GC
         防 RejectedExecutionException 拖死 wheel, 跟 executeTickTasks 同理。
         路径不同: 这里是 slot.flush 或 drainSubmitQueue 走过来的"已到期任务直接执行"分支。
         触发场景跟 executeTickTasks 一致 (executor shutdown / 有界池 + AbortPolicy)。
         不 catch 的后果: 异常逃逸到 tick() → 外层 try 接住, 不影响 wheel; 但单次 tick 后续步骤
         (drainSubmitQueue / 指针推进) 会被中断, 任务积压在 submitQueue。
         这里精细 catch 让单 task 失败不影响同 tick 的其他任务。
         任务处理策略:
           - 一次性: 从 uniqueIndex 移除 + recycle, 任务永久丢失 (业务 REE 时本来就该丢)
           - 周期性: 重新 offer, 等下个周期重试 (跟 execTask 末尾的 re-offer 等价)
         */
        try {
            this.executor(task.platform).execute(task.execAction());
        } catch (RejectedExecutionException e) {
            log.warn("TimingWheel addOrExecute rejected: unique={} period={}", task.unique, task.period, e);
            if (task.period == 0) {
                if (task.unique != null) this.uniqueIndex.remove(task.unique, task);
                // action 未被 fire (executor reject), 走 cancelledRecycle 让 ActionRecycler 等正确归池
                task.cancelledRecycle();
            } else {
                // reject 来自 stop/shutdown (executor 已关) 时不再续约: 续进随后被 clear 的 submitQueue 会泄漏 task+context. 回收.
                // reject 来自正常运行期 (有界池 + AbortPolicy 过载) 时 started=true, 仍走续约重试 (原行为).
                if (!started) {
                    if (task.unique != null) this.uniqueIndex.remove(task.unique, task);
                    task.cancelledRecycle();
                    return;
                }
                long now = System.currentTimeMillis();
                do {
                    task.timeout += task.period;
                } while (task.timeout <= now);
                this.offerSubmitTask(task);
            }
        } catch (Throwable t) {
            log.error("TimingWheel addOrExecute execute failed before dispatch: unique={} period={}", task.unique, task.period, t);
            if (task.period == 0) {
                if (task.unique != null) this.uniqueIndex.remove(task.unique, task);
                task.cancelledRecycle();
                return;
            }
            if (!started) {
                if (task.unique != null) this.uniqueIndex.remove(task.unique, task);
                task.cancelledRecycle();
                return;
            }
            long now = System.currentTimeMillis();
            do {
                task.timeout += task.period;
            } while (task.timeout <= now);
            this.offerSubmitTask(task);
        }
    }

    /**
     * 执行任务
     */
    void execTask(Task task) {
        if (task.cancelled) {
            task.cancelledRecycle();
            return;
        }
        long period = task.period;

        if (period > 0) {
            /*
             自我并发守卫 (防御性, renew-after 下正常不会重叠): 上一周期仍标 RUNNING 则跳过本次, 由持有方负责续约
             执行后续约 (fixed-delay): 先跑 action, 完成后再把同一 task 重投队列。
             防止 use-after-recycle: 必须先执行再续约；如果执行期间 task 已进入队列或时间轮，此时 cancel(unique)
             会让 tick 线程 cancelledRecycle 回收并复用该 Task 对象, 而本次执行的 finally 仍 RUNNING.setRelease
             写到已复用对象 → 破坏新 task 的并发守卫。改为执行期间 task 不在任何队列 → 无并发回收方, race 消除。
             续约放 finally: 业务异常由 Action.run() 记录并吞掉, 周期不因单次异常中断。
             代价: 下次触发 = 本次完成 + period (fixed-delay); action 远小于 period 时与 fixed-rate 无差异。
             */
            if (!Task.RUNNING.compareAndSet(task, false, true)) return;
            try {
                // 进入执行前再判一次取消 (cancel 可能在出队后到达); 取消则不跑 action, 由 finally 统一回收
                if (task.cancelled) return;
                AnyHolder.putAll(task.parameters);
                task.action.run();
            } finally {
                Task.RUNNING.setRelease(task, false);
                // 此刻 task 不在队列/轮中且 RUNNING 已 false → 续约 / 回收均无并发回收方, 安全
                long now = System.currentTimeMillis();
                do {
                    task.timeout += period;
                } while (task.timeout <= now);
                task.slot = null;
                // 取消 / 停机: 不再续约, 回收 task + context (停机时 submitQueue 已 clear 且无 tick 消费, 续进去会滞留)
                if (task.cancelled || !started) {
                    if (task.unique != null) this.uniqueIndex.remove(task.unique, task);
                    task.cancelledRecycle();
                } else {
                    this.offerSubmitTask(task);
                }
            }
            return;
        }

        // 一次性任务
        AnyHolder.putAll(task.parameters);
        task.action.run();
        // 使用 remove(key, value) 确保只移除自己，避免误删同 unique 的后续任务续约
        if (Objects.nonNull(task.unique)) this.uniqueIndex.remove(task.unique, task);
        task.recycle();
    }

    // NOTE ==================== partition 实现 ====================

    /**
     * 启动 partition 集群: 按系统属性 {@code nasa.timing-wheel.partitions} (默认 = 2×CPU) 分配 N 个 {@link Partition},
     * N 向上取 2 的幂 (mask trick); 每槽 1 个 MPSC 队列 + 1 个 virtual worker.
     * <p>
     * 协议: 构造 {@code Partition[]} → spawn worker(handshake 拿线程引用, 循环看本代 {@code running}) → 发布 {@code partitions};
     * start() 随后才发布 {@code started=true}. 失败回滚已 spawn 的 worker (不留孤儿).
     * Worker 用 virtual thread: 空闲时 {@link LockSupport#park} unmount carrier, 不占 platform 线程.
     */
    private void startPartitions() {
        int n = Integer.getInteger("nasa.timing-wheel.partitions", Runtime.getRuntime().availableProcessors() << 1);
        // 向上取 2 的幂. (1 → 1, 2 → 2, 3 → 4, ..., 16 → 16, 17 → 32)
        n = Math.max(1, n);
        if (n > (1 << 30)) {
            throw new IllegalArgumentException("nasa.timing-wheel.partitions must be <= " + (1 << 30));
        }
        // n=1 时 highestOneBit((n-1)<<1) 会得到 0，必须单独保留一个有效分区。
        if (n > 1) {
            n = Integer.highestOneBit(n - 1) << 1;
        }
        this.partitionMask = n - 1;
        Partition[] parts = new Partition[n];
        int spawned = 0;
        // 每槽 1 个 MPSC 队列 + 1 个 virtual worker. worker 循环看本代 partition.running (不看全局 started),
        // 故 stop→start 时旧 worker 一定退出、新 start 建新一代, 两代互不复活 (无孤儿 virtual thread).
        // CompletableFuture handshake: worker 启动先 complete(currentThread), 这里 join 拿引用存进 partition 供 unpark.
        try {
            for (int i = 0; i < n; i++) {
                MPSCLinkedQueue<Action> queue = new MPSCLinkedQueue<>();
                Partition p = new Partition(i, queue);
                // 发布给 worker 前置 true (execute 建立 happens-before)
                p.running = true;
                CompletableFuture<Thread> handshake = new CompletableFuture<>();
                virtualExecutor.execute(() -> {
                    Thread me = Thread.currentThread();
                    me.setName("Virtual-Partition-" + p.slot);
                    handshake.complete(me);
                    this.drainPartition(p);
                });
                // worker 已在 drain; 存线程引用供 offerPartition unpark
                p.worker = handshake.join();
                parts[i] = p;
                spawned = i + 1;
            }
            // 全部 worker running 且 worker 引用就绪后才发布 partitions (start() 随后才置 started=true)
            this.partitions = parts;
        } catch (Throwable t) {
            // start 失败回滚: 停掉已 spawn 的 worker, 不留孤儿 (started 仍为 false)
            for (int i = 0; i < spawned; i++) {
                Partition p = parts[i];
                p.running = false;
                if (p.worker != null) LockSupport.unpark(p.worker);
            }
            throw t;
        }
    }

    /*
     * 等在途普通 offer/postpone producer 清零: 它们 increment 后 re-check started=false 即 return, 故很快归零.
     * bounded 1s 防卡死. 超时记 error: 残留 producer 会把 task 塞进随后被 clear 的 submitQueue → 静默丢失.
     * 与 awaitPartitionInflightDrained 同款 sound.
     */
    private void awaitOfferInflightDrained() {
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (this.offerInflight.sum() > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        long remaining = this.offerInflight.sum();
        if (remaining > 0) {
            log.error("TimingWheel awaitOfferInflightDrained timeout (1s), {} offer/postpone producers still in-flight; their tasks may be lost when submitQueue is cleared", remaining);
        }
    }

    // bounded 等 executor 跑完已接收任务 (stop() clear 前调用, 让在途 execTask 完成, 不再 re-offer 到将被 clear 的 submitQueue).
    // 超时只 warn: 停机主流程必须走完, 残留长任务由其自身 started 守卫保证不会泄漏续约.
    private void awaitExecutorTermination(ExecutorService executor, String name, long awaitMs) {
        try {
            if (!executor.awaitTermination(awaitMs, TimeUnit.MILLISECONDS)) {
                log.warn("TimingWheel {} not terminated within {}ms, proceeding with state clear", name, awaitMs);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("TimingWheel stop interrupted while awaiting {} termination", name, ie);
        }
    }

    // 等在途 partition producer 清零: 它们 increment 后 re-check started=false 即 return, 故很快归零. bounded 防卡死.
    private void awaitPartitionInflightDrained() {
        // 1s 上界
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (this.partitionInflight.sum() > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        /*
         * 超时不能静默返回: 仍有 producer 卡在 increment 与 queue.offer 之间, stopPartitions 会让 worker 退出,
         * 它们随后 offer 到没人消费的 queue → 任务永久滞留。这会导致顺序敏感任务丢失，
         * 调用方/oncall 需要被告知 (规模 = 残留 inflight 数), 不能装作"已 drain 完".
         */
        long remaining = this.partitionInflight.sum();
        if (remaining > 0) {
            log.error("TimingWheel awaitPartitionInflightDrained timeout (1s), {} producers still in-flight; some tasks may be lost to dead partition workers", remaining);
        }
    }

    /**
     * 停 partition 集群 (stop() 已先置 {@code started=false} 并等 {@code partitionInflight} 清零):
     * <ol>
     *   <li>本代每个 {@code Partition.running=false} (worker 循环看本代 running, 不看全局 started → 旧代一定退出, 不被新 start 复活)</li>
     *   <li>unpark 每个 worker — park 中的也立即回来看 running=false</li>
     *   <li>worker 把 queue 残余 drain 跑完 (inflight 已清零 → 无 hole, poll 可完全排空) 后 countDown {@code stopped}</li>
     *   <li>{@code await stopped} (bounded, 可配 {@code nasa.timing-wheel.stop-timeout-ms}); 超时记 error</li>
     *   <li>清空 {@code partitions}</li>
     * </ol>
     */
    private void stopPartitions() {
        Partition[] parts = this.partitions;
        if (parts == null) return;
        // 1. 本代所有 running=false (worker 循环看自己的 running, 与全局 started 解耦)
        for (Partition p : parts) p.running = false;
        // 2. unpark: park 中的 worker 立即回来看 running=false → drain remainder + 退出
        for (Partition p : parts) if (p.worker != null) LockSupport.unpark(p.worker);
        // 3. 等 worker drain 残余并退出 (countDown stopped); bounded, 不无限阻塞 shutdown.
        //    超时记 error: 不能静默清空 partitions 却宣称"已 drain 完", 调用方需知有残余任务风险.
        long stopTimeoutMs = Long.getLong("nasa.timing-wheel.stop-timeout-ms", 2000L);
        for (Partition p : parts) {
            try {
                if (!p.stopped.await(stopTimeoutMs, TimeUnit.MILLISECONDS)) {
                    log.error("TimingWheel partition {} stop timeout ({}ms), worker not exited; 残余任务可能未 drain 完", p.slot, stopTimeoutMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        this.partitions = null;
        this.partitionMask = 0;
    }

    /**
     * "action 被丢弃 (既未入队也未 fire)" 兜底归池钩子。partition 路径上多处短路 / 异常分支共用。
     * <p>
     * <b>语义</b>: 一个 action 通过 {@link ObjectPool.Recycler#cancelledRecycle()} 扩展点正确归池,
     * 跟 Task 路径上各 cancelled-skip / REE 分支 (executeTickTasks / drainSubmitQueue / addOrExecute / execTask) 的
     * {@code task.cancelledRecycle()} 调用对齐 ——
     * 都表达"调度框架不会再 fire 这个 action, 业务方也不会再持有它, 因此 ofRecycle 模式 ActionRecycler
     * 必须由框架显式归池, 否则脱池泄漏"。
     * <p>
     * <b>谁调它</b>:
     * <ul>
     *   <li>{@link #offerPartitionHashed} 三处短路 (started=false / re-check started=false / partitions=null)</li>
     *   <li>{@link #offerPartitionHashed} 内 {@code p.queue.offer(wrapped)} 抛异常 (Edge 1)</li>
     *   <li>{@link #drainPartition} worker crash 后 drain queue 残余 (Edge 2)</li>
     * </ul>
     * <p>
     * <b>对哪些 action 生效</b>: 仅 {@code action instanceof ObjectPool.Recycler}; 普通 lambda /
     * {@code ActionRecycler.of(...)} (recycleSelf=false, 业务自管生命周期) 路径都是 no-op
     * (前者无池化, 后者 {@link ActionRecycler#cancelledRecycle()} 内会 check recycleSelf 后 short-circuit)。
     * <p>
     * <b>为什么 swallow + log</b>: {@code cancelledRecycle} 实现走 {@code restore()} →
     * cascade recycle {@code refsRecycle} 位掩码标记的子对象, 子对象自身 restore 出错会向上抛.
     * 本 helper 是兜底路径, 单笔抛错不能阻塞后续的归池调用 —— 尤其 Edge 1 (offer 抛异常时分两次归 wrapped+action),
     * 第一次抛错绝不能掩盖第二次的归池机会; Edge 2 (worker crash 后 drain queue) 第一笔抛错也不能跳过剩余 N 笔。
     * 异常只 log 不上抛, 由 ObjectPool 容量监控发现长期泄漏。
     */
    private static void recycleDropped(Action action) {
        if (action instanceof ObjectPool.Recycler<?> r) {
            try {
                r.cancelledRecycle();
            } catch (Throwable t) {
                log.error("TimingWheel recycleDropped cancelledRecycle failed", t);
            }
        }
    }

    private static RuntimeException propagate(Throwable t) {
        if (t instanceof RuntimeException e) return e;
        if (t instanceof Error e) throw e;
        return new RuntimeException(t);
    }

    /**
     * Producer 入口: 把 action 路由到 hash(key) 对应的 partition queue, 必要时唤醒 worker.
     * <p>
     * 上下文透传: 如果当前线程 AnyHolder 有数据, 用 {@link ActionRecycler#ofRecycle} 包一层把 snapshot 带到 worker 线程,
     * worker 跑 action 之前先 putAll 还原, 跟 {@link #exec} 行为一致.
     */
    public void offerPartition(Object key, Action action) {
        // Object key: 经 key.hashCode() 散列. null key 落 slot 0.
        this.offerPartitionHashed(key == null ? 0 : key.hashCode(), action);
    }

    /**
     * {@link #offerPartition(Object, Action)} 的 primitive long 版: 路由 key 为 long 时使用,
     * 走 {@link Long#hashCode(long)} 无装箱散列, 避免每次 partition 调用 1 次 Long autobox 分配 (彻底零 GC)。
     */
    public void offerPartition(long key, Action action) {
        // long key: Long.hashCode(key) = (int)(key ^ (key>>>32)), 无装箱.
        this.offerPartitionHashed(Long.hashCode(key), action);
    }

    // 共用入队逻辑: 入参为已算好的 key 散列值, 由两个 offerPartition 重载分别从 Object/long 计算后传入.
    private void offerPartitionHashed(int keyHash, Action action) {
        // shutdown/未启动短路: action 未入队也未执行, 若是 ofRecycle Recycler 须归池, 否则泄漏 (普通 Action no-op)
        if (!started) {
            recycleDropped(action);
            return;
        }
        // inflight 计数: stop 会先关 started 再等 inflight 清零, 保证 worker drain 时不会有 in-flight offer 漏掉 (不丢任务)
        this.partitionInflight.increment();
        try {
            // re-check: stop 可能在 increment 后置 started=false; 此时不再入队 (让 worker 干净排空已发布任务后退出)
            if (!started) {
                recycleDropped(action);
                return;
            }
            Partition[] parts = this.partitions;
            // started=true ⇒ partitions 已发布 (start 中先发布后置 started); 防御性
            if (parts == null) {
                recycleDropped(action);
                return;
            }
            int slot = spread(keyHash) & this.partitionMask;
            Partition p = parts[slot];
            /*
             * Edge 3: worker crash 后该 partition 永久无消费者, 必须拒收防黑洞.
             * drainPartition catch 块已置 p.running=false; 此处 check 拒收 + 告警, 否则该 slot 持续吃任务
             * 既不消费也不丢，按分区键串行的关键任务会全部滞留。
             * 不切换路由 (会破坏"同 key 同 worker"串行语义), 由上层感知告警后决策.
             */
            if (!p.running) {
                log.error("TimingWheel partition {} worker has died, rejecting offer to avoid black hole", slot);
                recycleDropped(action);
                return;
            }

            // 上下文快照透传: 跟 TimingWheel.exec 复用同一套 ActionRecycler + BICO 策略, snapshot 由 ar 持有.
            RecycleLinkedMap<String, Object> ps = AnyHolder.snapshot();
            Action wrapped = action;
            if (MapUtils.isNotEmpty(ps)) {
                ActionRecycler ar = ActionRecycler.ofRecycle(BICO);
                ar.ref(0, action);
                ar.refRecycle(1, ps);
                wrapped = ar;
            }
            // Edge 1: offer 抛异常时分别归池 wrapped 和原 action, 防 ofRecycle 模式实例脱池泄漏.
            //
            // 触发场景: MPSCLinkedQueue.offer 内 producerChunkFor 申请新 chunk 时堆 OOM 抛 Throwable.
            //   (offer 已 getAndAdd 占了位, 异常路径写 POISON 哨兵让消费者跳过; 但本帧 wrapped 实例没归池.)
            // 归池策略:
            //   - 包装层 wrapped (有 snapshot 时): ofRecycle ActionRecycler with BICO consumer, ref(1)=ps 标 refRecycle,
            //     cancelledRecycle 时 cascade recycle ps. 但 ref(0)=action 没标 refRecycle —— 因为正常 fire 路径
            //     BICO 体内 `((Action) ref(0)).action()` 调用后, ofRecycle 模式的原 action 会自己 finally self-recycle;
            //     若 ref(0) 也标 refRecycle, wrapped restore 时会 cascade recycle 已经 self-recycle 的原 action → double recycle.
            //     权衡之下 ref(0) 不标 refRecycle, fire 路径正常, 但 Edge 1 这种 "fire 没发生" 路径必须显式归原 action.
            //   - 原 action: 用户侧传进来的可能是 ofRecycle ActionRecycler / 普通 lambda / of 模式 ActionRecycler,
            //     recycleDropped 内部 instanceof 判断后只对 Recycler 触发 cancelledRecycle, 其余 no-op.
            // wrapped == action 的退化情况 (没 snapshot, 没 BICO 包装): 只调一次 recycleDropped(action), 避免重复归池.
            // throw 上抛: 让调用方感知 offer 失败并自行重试或记录日志，finally 仍会递减 inflight。
            try {
                p.queue.offer(wrapped);
            } catch (Throwable t) {
                if (wrapped != action) recycleDropped(wrapped);
                recycleDropped(action);
                throw t;
            }
            // 只有 worker 已声明 PARKED 时才唤醒; 高吞吐热路径避免每次 offer 都 unpark。
            if (p.signal.wakeIfParked()) {
                LockSupport.unpark(p.worker);
            }
        } finally {
            this.partitionInflight.decrement();
        }
    }

    /**
     * Partition worker 主循环: 持续 poll queue 跑 action; queue 空时发布 PARKED 状态, 二次检查后 park.
     * <p>
     * <b>Race-safety</b> (PARKED 状态位 + unpark permit 累积):
     * <ul>
     *   <li>A: producer 在 PARKED 前已发布元素 → worker 二次检查看到任务, 不 park</li>
     *   <li>B: producer 在 PARKED 后发布元素 → CAS PARKED->RUNNING, unpark worker</li>
     *   <li>C: producer unpark 早于 worker park → permit 累积, park 立即返回</li>
     * </ul>
     * 都不会饿死. producer 热路径只有在 worker 已声明 PARKED 时才 unpark, 避免每次 offer 都系统调用。
     * <p>
     * <b>正常 shutdown 路径</b>: stopPartitions 先置 running=false + unpark, worker 退出 while 循环后
     * 跑 try 块尾的 "shutdown drain remainder" (catch 之前) 把已入队但未消费的 action 全部 fire (调 runPartitionAction).
     * 此时 stop 已等 inflight 清零 → 无 in-flight hole → poll 可完全排空, 不丢任务. 这条路径下 ofRecycle
     * ActionRecycler 都正常 self-recycle, 不需要 cancelledRecycle 兜底.
     * <p>
     * <b>异常 crash 路径 (Edge 2)</b>: 正常 fire / shutdown drain 任一阶段抛出未捕获 Throwable 进 catch 块.
     * runPartitionAction 内部已 swallow + log 单 task 异常, 进入此 catch 的只剩 queue.poll
     * 自身的 Throwable (堆 OOM 等极端) 或 framework bug. queue 内残留 action 不会再被 fire ——
     * 必须显式 drain 并 cancelledRecycle 让 ofRecycle 模式正确归池, 否则池泄漏 (规模 = queue 残余笔数).
     * 单笔 cancelledRecycle 异常已由 {@link #recycleDropped} 内部 swallow + log, 不会中断 drain.
     * 外层 try 包整段 drain: queue.poll 自身 OOM 也兜住, 避免 finally 段 countDown 漏跑导致 stopPartitions.await 卡死.
     * <p>
     * <b>单 task 异常隔离</b>: runPartitionAction 内 swallow + log + AnyHolder.clear, 不杀 worker.
     */
    private void drainPartition(Partition p) {
        PartitionSignal signal = p.signal;
        MPSCLinkedQueue<Action> queue = p.queue;
        try {
            // 循环看本代 running (非全局 started): stop 置 running=false 使本 worker 退出, 不受后续 start 复活
            while (p.running) {
                Action a;
                while ((a = queue.poll()) != null) {
                    this.runPartitionAction(p.slot, a, false);
                }
                if (queue.peek() == null) {
                    /*
                     * 先发布 PARKED, 再二次检查队列:
                     * - producer 在这之前已发布元素: 二次检查能看到, 不 park
                     * - producer 在这之后发布元素: producer CAS PARKED->RUNNING 并 unpark
                     * - producer unpark 早于 park: permit 累积, park 立即返回
                     */
                    signal.parked();
                    if (queue.peek() == null && p.running) {
                        LockSupport.park();
                    }
                    signal.running();
                }
            }
            // shutdown: drain 残余. stop 已等 inflight 清零 → 无 in-flight hole, poll 可完全排空 (POISON 自动跳过), 不丢任务.
            Action a;
            while ((a = queue.poll()) != null) {
                this.runPartitionAction(p.slot, a, true);
            }
        } catch (Throwable ex) {
            log.error("partition worker {} crashed", p.slot, ex);
            /*
             * Edge 3 配合 offerPartitionHashed: 立刻把本代 running 置 false, 让后续 offerPartitionHashed
             * check 出该 partition 已死并拒收 + 告警, 否则任务持续涌入无消费者的 queue → 黑洞.
             * 注意只置本代 Partition.running, 不动全局 started: 其它 partition 仍正常工作, 不让一笔 worker
             * crash 拖垮整个 wheel.
             */
            p.running = false;
            // 健康信号: 计入崩溃数, 供 isHealthy()/deadPartitionCount() 暴露给上层熔断/重启 (该 slot 同 key 任务已不可执行).
            this.deadPartitions.increment();
            // Edge 2: worker crash 后归池 queue 残余 action, 防 ofRecycle ActionRecycler 脱池泄漏.
            //
            // 跟正常 shutdown drain (try 块尾, 进 catch 之前) 的区别:
            //   - 正常 shutdown: runPartitionAction 调 action.action() → consumer.accept 完后 finally self-recycle, 正确归池.
            //   - crash 后: 已经在 catch 内, queue 内未消费的 action 不会再被 fire → 必须显式 cancelledRecycle.
            // 内层 try-catch 不是必需 (recycleDropped 内已 swallow + log), 但兜住 queue.poll 自身 Throwable
            // (堆 OOM 时 lvElement/setRelease 内存操作可能抛) 让 finally 段 countDown 必跑;
            // 否则 stopPartitions.await(stopTimeoutMs) 等不到 countDown 会超时 + 走 error log 路径.
            try {
                Action drained;
                while ((drained = queue.poll()) != null) {
                    recycleDropped(drained);
                }
            } catch (Throwable t) {
                log.error("partition worker {} crash-drain failed", p.slot, t);
            }
        } finally {
            // 通知 stopPartitions 本 worker 已退出 (即便崩溃也 countDown, 避免 stop 卡等)
            p.stopped.countDown();
        }
    }

    private void runPartitionAction(int slot, Action action, boolean shutdown) {
        try {
            action.action();
        } catch (Throwable t) {
            if (shutdown) {
                log.error("partition {} shutdown-drain task failed", slot, t);
            } else {
                log.error("partition {} task failed", slot, t);
            }
        } finally {
            AnyHolder.clear();
        }
    }

    /**
     * 哈希散列扩散: 仿 HashMap, 低位异或高位 + 强制非负, 防 hashCode 低位重复导致 partition 热点.
     */
    private static int spread(int h) {
        return (h ^ (h >>> 16)) & 0x7fffffff;
    }

    // NOTE ==================== 分层时间轮（Kafka 思路） ====================

    /**
     * 一层时间轮
     * <p>
     * 单信号线程访问，无需任何锁
     */
    static class WheelLayer {

        // tickMs 用 long: 溢出层 tickMs = 下层 interval, 多层叠加后会超 int (delay > ~24 天即溢出),
        // interval 必须保持 long；强转为 int 会让超长延迟任务发生溢出并调度错乱。
        final long tickMs;
        final int wheelSize;
        // tickMs * wheelSize
        final long interval;
        final Slot[] slots;
        long currentTime;
        /* 溢出层（按需创建） */
        WheelLayer overflowWheel;

        WheelLayer(long tickMs, int wheelSize, long currentTime) {
            this.tickMs = tickMs;
            this.wheelSize = wheelSize;
            this.interval = tickMs * wheelSize;
            this.slots = new Slot[wheelSize];
            for (int i = 0; i < wheelSize; i++) {
                slots[i] = new Slot();
            }
            this.currentTime = currentTime - (currentTime % tickMs);
        }

        /**
         * 添加任务到时间轮
         *
         * @return true 已添加；false 已到期，需要直接执行
         */
        boolean add(Task task) {
            long timeout = task.timeout;
            if (timeout < currentTime + tickMs) {
                return false;
            }
            if (timeout < currentTime + interval) {
                int idx = (int) ((timeout / tickMs) % wheelSize);
                Slot slot = slots[idx];
                slot.add(task);
                slot.setExpiration((timeout / tickMs) * tickMs);
                return true;
            }
            return overflowWheel().add(task);
        }

        /**
         * 按需创建溢出层（上层 tickMs = 本层 interval）
         */
        WheelLayer overflowWheel() {
            if (overflowWheel == null) {
                overflowWheel = new WheelLayer(interval, wheelSize, currentTime);
            }
            return overflowWheel;
        }
    }

    // NOTE ==================== Slot（纯数据容器，无锁） ====================

    /**
     * 时间轮槽，纯数据容器
     * <p>
     * 只被信号线程读写，无需锁
     */
    static class Slot {

        /* 到期时间，-1 表示未使用 */
        long expiration = -1L;
        /* 槽内任务列表，按需从对象池获取，flush 后归还 */
        RecycleLinkedList<Task> tasks;

        void add(Task task) {
            if (tasks == null) tasks = RecycleLinkedList.of();
            tasks.add(task);
            task.slot = this;
        }

        /**
         * flush 所有任务：到期执行或降级
         * 使用 clearRHead() + Node 链遍历，避免创建 Iterator 对象
         */
        void flush(Consumer<Task> consumer) {
            RecycleLinkedList<Task> t = this.tasks;
            if (t == null) return;
            // clearRHead() 返回头节点并清空 list，Node.next 链仍保留
            RecycleLinked.Node<Task, Void> node = t.clearRHead();
            t.recycle();
            this.tasks = null;
            expiration = -1L;
            // 手动遍历 Node 链，零 Iterator 分配
            while (node != null) {
                RecycleLinked.Node<Task, Void> next = node.next;
                Task task = node.key;
                node.recycle();
                task.slot = null;
                consumer.accept(task);
                node = next;
            }
        }

        void setExpiration(long expiration) {
            this.expiration = expiration;
        }
    }

    // NOTE ==================== Task ====================

    /**
     * 时间轮任务
     */
    @Getter
    public static class Task implements ObjectPool.Recycler<Task> {

        private final TimingWheel timingWheel;
        /* 跨线程透传参数 */
        @Setter
        private LinkedHashMap<String, Object> parameters;
        /* 到期时间，ms */
        long timeout;
        /* 全局唯一 */
        private String unique;
        /* 具体任务 */
        private Action action;
        /* 0 表示非周期任务，> 0 表示周期任务 */
        long period;
        /* true = 平台线程执行, false = 虚拟线程执行 (默认) */
        boolean platform;
        /* 惰性取消标记 */
        volatile boolean cancelled;
        /*
         * 自我并发守卫: 周期/高频任务采用 fixed-rate (先续约再执行), 若单次 action 耗时 > period,
         * 下一周期会在前一次还没跑完时触发 → 同一任务并发执行 (与 JDK scheduleAtFixedRate 串行语义不同).
         * CAS running false→true 守卫, 重入的那次直接跳过, 保证同一任务任意时刻至多一个 action 在跑.
         */
        volatile boolean running;
        static final VarHandle RUNNING;

        static {
            try {
                RUNNING = MethodHandles.lookup().findVarHandle(Task.class, "running", boolean.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        /* 当前所在的 slot（用于调试） */
        Slot slot;
        /*
         * 缓存 execTask 的 Action，低 GC 核心技巧：
         *
         * lambda 捕获的是 this（Task 实例的对象地址），而非 Task 的字段值。
         * Task 被对象池回收再复用时，实例地址不变，字段值（timeout/action/period）会被重新赋值，
         * 但 execAction 捕获的 this 引用始终指向同一个 Task 实例，所以 Action 可以一直复用。
         *
         * 生命周期：
         *   第1次从池取出 → execAction == null → 创建 Action（捕获 this）→ 执行 → recycle 回池（execAction 保留不清）
         *   第2次从池取出 → execAction != null → 直接复用（捕获的 this 地址没变）→ 执行 → recycle 回池
         *   第N次：同上，永远复用同一个 Action 对象，零 GC
         *
         * 对比：如果写成 (Action) () -> execTask(task)，task 是局部变量，每次都会 new 一个新的 lambda 对象。
         * restore() 中不清理此字段。
         */
        private Action execAction;

        private final ObjectPool.PooledHandle<Task> handle;

        private Task(TimingWheel timingWheel) {
            this.timingWheel = timingWheel;
            this.handle = new ObjectPool.PooledHandle<>(timingWheel.taskPool);
        }

        Action execAction() {
            Action a = this.execAction;
            if (a == null) {
                this.execAction = a = () -> this.timingWheel.execTask(this);
            }
            return a;
        }

        /*
         * 高频周期任务（tickTasks）专用的 Action，同样利用 this 捕获缓存技巧。
         * 与 execAction 的区别：不走 execTask 的 re-offer 逻辑，
         * 因为 tickTasks 中的任务常驻 ArrayList，每个 tick 直接执行，无需重新入队/入槽。
         * RUNNING 由信号线程提交 executor 前认领，覆盖“已提交但尚未执行”的取消回收窗口。
         */
        private Action tickAction;

        Action tickAction() {
            Action a = this.tickAction;
            if (a == null) {
                this.tickAction = a = () -> {
                    try {
                        if (!this.cancelled) {
                            AnyHolder.putAll(this.parameters);
                            this.action.run();
                        }
                    } finally {
                        RUNNING.setRelease(this, false);
                    }
                };
            }
            return a;
        }

        @Override
        public ObjectPool.PooledHandle<Task> handle() {
            return this.handle;
        }

        @Override
        public void restore() {
            // 回收快照 (一次性任务在 execTask 末尾 task.recycle() → 这里回收;
            //  周期任务永不到达此处, 快照随 task 对象一起 GC, 不进池)
            if (this.parameters instanceof RecycleLinkedMap rm) rm.recycle();
            this.parameters = null;
            this.timeout = 0;
            this.unique = null;
            this.action = null;
            this.period = 0;
            this.platform = false;
            this.cancelled = false;
            this.running = false;
            this.slot = null;
        }

        /**
         * cancelled 路径的 Task 回收: 先通知 action 走 cancelledRecycle 扩展点 (若为 Recycler), 再 recycle 自身.
         * <p>
         * <b>为什么需要这个 helper</b>: cancel(unique) → task.cancelled=true 标记, fire 时 detect cancelled
         * 直接 task.recycle() 不调 action.run(). 普通 Action 没影响, 但 ActionRecycler 的 self-recycle
         * 依赖 action() 内 finally 触发 — cancel 路径下 action() 不执行 → ActionRecycler 实例
         * 既没归池也没被业务方主动 recycle, 只能等 GC, 池化失效.
         * <p>
         * 通过 {@link ObjectPool.Recycler#cancelledRecycle()} 扩展点统一处理:
         * {@code ofRecycle} 模式 override 实现归池; {@code of} 模式默认 no-op 由业务方自管. 框架与具体 Recycler 类型解耦.
         * <p>
         * 调用时机: 所有 cancelled-skip 分支 (executeTickTasks / drainSubmitQueue / addOrExecute / execTask)
         * 用 {@code cancelledRecycle()} 代替 {@code recycle()}.
         */
        @Override
        public void cancelledRecycle() {
            // action 是 Recycler 时调用扩展点; ActionRecycler.ofRecycle 模式会主动归池, of 模式 no-op
            if (this.action instanceof ObjectPool.Recycler<?> r) {
                r.cancelledRecycle();
            }
            this.recycle();
        }
    }

    // NOTE ==================== 对象池 ====================

    static class TaskObjectPool extends ObjectPool<Task> {

        private final TimingWheel owner;

        TaskObjectPool(TimingWheel owner) {
            super(Integer.parseInt(System.getProperty("nasa.object-pool.timing-wheel-task-capacity", "10000")));
            this.owner = owner;
        }

        Task get(long timeout, long period, String unique, Action action, boolean platform) {
            Task task = super.get();
            task.timeout = timeout;
            task.period = period;
            task.unique = unique;
            task.action = action;
            task.platform = platform;
            task.cancelled = false;
            return task;
        }

        @Override
        public Task newObject() {
            return new Task(this.owner);
        }
    }

    // NOTE ==================== MPSC 分区消费 ====================

    /**
     * 一个 partition slot: 绑定 worker / queue / signal / 本代 running 标记 / stopped latch.
     * <p>
     * 替代旧的 raw {@code KV[]} + {@code PartitionSignal[]} 双数组, 消除 raw type / unchecked cast / 平行数组索引错配,
     * 并把生命周期协议 (running / stopped) 内聚到一起:
     * <ul>
     *   <li>{@code running}: <b>本代</b>运行标记. worker 主循环看它而非全局 {@code started}, 故 stop 一定让本代 worker 退出,
     *       新 start 创建新一代 Partition, 两代互不复活 (避免孤儿 virtual thread)。</li>
     *   <li>{@code stopped}: worker 退出 (drain 残余完毕) 时 countDown, stopPartitions 据此等待 worker 真正退出。</li>
     *   <li>{@code worker}: handshake 后由 startPartitions 写入, 供 offerPartition unpark。volatile, partitions 发布后可见。</li>
     * </ul>
     */
    static final class Partition {

        final int slot;
        volatile Thread worker;
        final MPSCLinkedQueue<Action> queue;
        final PartitionSignal signal;
        volatile boolean running;
        final CountDownLatch stopped = new CountDownLatch(1);

        Partition(int slot, MPSCLinkedQueue<Action> queue) {
            this.slot = slot;
            this.queue = queue;
            this.signal = new PartitionSignal();
        }
    }

    static final class PartitionSignal {

        long p00, p01, p02, p03, p04, p05, p06;
        volatile int state;
        long p10, p11, p12, p13, p14, p15, p16;

        private static final VarHandle STATE;

        static {
            try {
                STATE = MethodHandles.lookup().findVarHandle(PartitionSignal.class, "state", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        boolean wakeIfParked() {
            return state == PARTITION_PARKED && STATE.compareAndSet(this, PARTITION_PARKED, PARTITION_RUNNING);
        }

        void parked() {
            STATE.setRelease(this, PARTITION_PARKED);
        }

        void running() {
            STATE.setRelease(this, PARTITION_RUNNING);
        }
    }
}
