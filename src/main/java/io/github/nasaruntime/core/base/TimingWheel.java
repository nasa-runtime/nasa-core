package io.github.nasaruntime.core.base;

import io.github.nasaruntime.core.concurrent.MPSCLinkedQueue;
import io.github.nasaruntime.core.concurrent.NameThreadFactory;
import io.github.nasaruntime.core.concurrent.ThreadPoolUtils;
import io.github.nasaruntime.core.config.Graceful;
import io.github.nasaruntime.core.function.Action;
import io.github.nasaruntime.core.function.ActionRecycler;
import io.github.nasaruntime.core.utils.MapUtils;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 面向高吞吐和低 GC 场景的分层时间轮。
 * <p>
 * 数据面使用 MPSC 无锁队列接收提交，由单 consumer 进入轮槽：
 * offer()  →  MPSCLinkedQueue (XADD 入队，单 consumer 出队)
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
 *   - MPSC 队列使生产者之间不争用同一互斥锁
 *   - 分层轮槽粗放存储长延迟任务，到期 flush 后降级到更细刻度层
 *   - ScheduledExecutor 只发布心跳信号，唯一信号线程按 tick 推进指针且不空轮询
 *   - 取消使用 volatile cancelled 标记，在 flush、drain 或 exec 时跳过并回收
 *   - 任务执行交由 virtualExecutor 或有界 platformExecutor，不阻塞时间轮信号线程
 * </pre>
 * 启动、停机、层级创建和执行器生命周期属于控制面，使用显式锁和原子状态保证发布顺序；
 * 无锁特性只适用于 MPSC 提交与单 consumer 消费的稳态数据面。每个稳定 Runner 名称对应独立的任务索引、
 * 提交队列、轮槽、信号线程与执行器，取消、改期、背压和停机不跨 Runner 传播。
 * <p>
 * 分层结构（以 wheelSize=1000, tickMs=1 为例）：
 * <pre>
 *   Layer1: tickMs=1ms,      interval=1s      → 覆盖 0~1s，最细刻度 1ms
 *   Layer2: tickMs=1000ms,   interval=1000s   → 覆盖 0~1000s，最细刻度 1s（按需创建）
 *   Layer3: tickMs=1000000ms, interval=1000000s → 覆盖更远（按需创建）
 * </pre>
 * tickMs 是轮槽检查粒度，不是执行时刻 SLA。实际派发受信号线程调度、GC、系统时钟变化、执行器负载
 * 和任务积压影响，可能相对业务测量的目标时刻提前或滞后；要求绝不提前的业务必须在动作执行前复验
 * 自身权威截止时间。
 */
@SuppressWarnings("all")
@Slf4j
public class TimingWheel {

    /**
     * 静态兼容入口统一使用的保留 Runner 名称。
     */
    public static final String DEFAULT_RUNNER = "default";
    private static final int DEFAULT_WHEEL_SIZE = 1000;
    private static final int DEFAULT_TICK_MILLIS = 1;
    /**
     * Runner 注册表只保存应用级稳定执行域，同名并发创建由 ConcurrentHashMap 线性化。
     */
    private static final ConcurrentHashMap<String, TimingWheelRunner> RUNNERS = new ConcurrentHashMap<>();

    static {
        // 时间轮是分区延迟与观察控制的被依赖方，必须排在全部 Partition Runner 之后关闭。
        Graceful.registry(Integer.MAX_VALUE, TimingWheel::shutdownAll);
    }

    /**
     * 业务作用：按名称取得相互隔离的时间轮 Runner，同名调用始终共享同一任务域和生命周期。
     *
     * @param runnerName 应用级稳定 Runner 名称
     *                   返回: 已存在或按默认参数创建的时间轮 Runner。
     */
    public static TimingWheelRunner of(String runnerName) {
        String validatedName = validateRunnerName(runnerName);
        return RUNNERS.computeIfAbsent(
                validatedName,
                name -> new TimingWheelRunner(name, DEFAULT_WHEEL_SIZE, DEFAULT_TICK_MILLIS)
        );
    }

    /**
     * 业务作用：按名称和时间轮参数取得隔离 Runner，并拒绝同名执行域在运行期改变时间刻度。
     *
     * @param runnerName 应用级稳定 Runner 名称
     * @param wheelSize  每层槽位数
     * @param tickMs     最底层时间粒度
     *                   返回: 已存在或本次原子创建的时间轮 Runner；同名参数不一致时抛出 IllegalStateException。
     */
    public static TimingWheelRunner of(String runnerName, int wheelSize, int tickMs) {
        String validatedName = validateRunnerName(runnerName);
        return RUNNERS.compute(validatedName, (name, current) -> {
            if (current == null) return new TimingWheelRunner(name, wheelSize, tickMs);
            current.requireConfiguration(wheelSize, tickMs);
            return current;
        });
    }

    /**
     * 业务作用：按指定容量取得默认时间轮实现；参数只在 default Runner 首次创建时生效。
     *
     * @param wheelSize 每层时间轮槽数
     * @param tickMs    最底层 tick 时间间隔（ms）
     *                  返回: default Runner 绑定的时间轮实现。
     */
    public static TimingWheel of(int wheelSize, int tickMs) {
        return RUNNERS.computeIfAbsent(
                DEFAULT_RUNNER,
                name -> new TimingWheelRunner(name, wheelSize, tickMs)
        ).timingWheel();
    }

    /**
     * 业务作用：以默认 1000 槽、1ms tick 取得 default Runner 的时间轮实现。
     * <p>
     * 参数说明: 无。
     * 返回: default Runner 绑定的时间轮实现。
     */
    public static TimingWheel of() {
        return of(DEFAULT_RUNNER).timingWheel();
    }

    /**
     * 业务作用：判断普通定时任务入口是否已经开放。
     * <p>
     * 参数说明: 无。
     * 返回: tick 调度已启动且尚未进入停机时返回 true。
     */
    public static boolean isStarted() {
        TimingWheelRunner current = RUNNERS.get(DEFAULT_RUNNER);
        return current != null && current.isStarted();
    }

    /**
     * 业务作用：启动 default Runner 的 tick 调度与业务执行器。
     * <p>
     * 参数说明: 无。
     * 返回: 无返回值；重复启动保持幂等。
     */
    public static void startTimingWheel() {
        of(DEFAULT_RUNNER).start();
    }

    /**
     * 业务作用：把一次性任务立即提交给默认虚拟线程执行器。
     *
     * @param action 具体任务
     *               返回: 无返回值；未启动时任务被明确丢弃并触发取消回收。
     */
    public static void exec(Action action) {
        of(DEFAULT_RUNNER).exec(action);
    }

    /**
     * 业务作用：把一次性任务按毫秒延迟提交给默认虚拟线程执行器。
     *
     * @param delay  延迟时间
     * @param action 具体任务
     *               返回: 无返回值；delay 小于 1 时按立即执行处理。
     */
    public static void exec(long delay, Action action) {
        of(DEFAULT_RUNNER).exec(delay, action);
    }

    /**
     * 业务作用：登记带唯一标识的一次性虚拟线程任务，支持后续取消或延期。
     *
     * @param delay  延迟时间
     * @param unique 唯一标识
     * @param action 具体任务
     *               返回: 无返回值；相同 unique 的旧任务被惰性取消。
     */
    public static void exec(long delay, String unique, Action action) {
        of(DEFAULT_RUNNER).exec(delay, unique, action);
    }

    /**
     * 业务作用：登记无唯一标识的周期虚拟线程任务。
     *
     * @param delay  延迟时间
     * @param period 周期时间
     * @param action 具体任务
     *               返回: 无返回值；period 为 0 时退化为一次性任务。
     */
    public static void exec(long delay, long period, Action action) {
        of(DEFAULT_RUNNER).exec(delay, period, action);
    }

    /**
     * 业务作用：登记完整参数的虚拟线程定时任务并委托实例入口维护生命周期。
     *
     * @param delay  延迟时间
     * @param period 周期时间，0 表示非周期任务
     * @param unique 唯一标识
     * @param action 具体任务
     *               返回: 无返回值；未启动或停机时不会受理。
     */
    public static void exec(long delay, long period, String unique, Action action) {
        of(DEFAULT_RUNNER).exec(delay, period, unique, action);
    }

    // ==================== platform: 平台线程执行 (不受虚拟线程 pin 影响) ====================

    /**
     * 业务作用：把一次性任务立即提交给平台线程池，避免关键任务受虚拟线程 pin 影响。
     *
     * @param action 具体任务
     *               返回: 无返回值。
     */
    public static void platform(Action action) {
        of(DEFAULT_RUNNER).platform(action);
    }

    /**
     * 业务作用：把一次性任务按毫秒延迟提交给平台线程池。
     *
     * @param delay  延迟毫秒数
     * @param action 具体任务
     *               返回: 无返回值。
     */
    public static void platform(long delay, Action action) {
        of(DEFAULT_RUNNER).platform(delay, action);
    }

    /**
     * 业务作用：登记带唯一标识的一次性平台线程任务，支持取消和延期。
     *
     * @param delay  延迟时间
     * @param unique 唯一标识
     * @param action 具体任务
     *               返回: 无返回值。
     */
    public static void platform(long delay, String unique, Action action) {
        of(DEFAULT_RUNNER).platform(delay, unique, action);
    }

    /**
     * 业务作用：登记无唯一标识的周期平台线程任务。
     *
     * @param delay  首次延迟毫秒数
     * @param period 周期毫秒数
     * @param action 具体任务
     *               返回: 无返回值。
     */
    public static void platform(long delay, long period, Action action) {
        of(DEFAULT_RUNNER).platform(delay, period, action);
    }

    /**
     * 业务作用：登记完整参数的平台线程定时任务，供关键定时流程选择非虚拟执行器。
     *
     * @param delay  首次延迟毫秒数
     * @param period 周期毫秒数
     * @param unique 唯一标识；可为 null
     * @param action 具体任务
     *               返回: 无返回值。
     */
    public static void platform(long delay, long period, String unique, Action action) {
        of(DEFAULT_RUNNER).platform(delay, period, unique, action);
    }

    /**
     * 业务作用：按唯一标识惰性取消 default Runner 中的当前任务。
     *
     * @param unique 任务唯一标识
     *               返回: 无返回值；未找到或未启动时保持幂等。
     */
    public static void cancel(String unique) {
        of(DEFAULT_RUNNER).cancel(unique);
    }

    /**
     * 业务作用：按唯一标识把当前定时任务整体向后延期。
     *
     * @param unique      任务唯一标识
     * @param delayMillis 延期毫秒数
     *                    返回: 无返回值；未找到任务时保持幂等。
     */
    public static void delay(String unique, long delayMillis) {
        of(DEFAULT_RUNNER).delay(unique, delayMillis);
    }

    /**
     * 业务作用：校验 Runner 名称可稳定用作注册键、线程名和诊断标签，防止隐式折叠到错误执行域。
     *
     * @param runnerName 待校验的 Runner 名称
     *                   返回: 原样名称；null、空白、首尾空白或控制字符会抛出 IllegalArgumentException。
     */
    static String validateRunnerName(String runnerName) {
        if (runnerName == null || runnerName.isBlank()) {
            throw new IllegalArgumentException("runnerName must not be blank");
        }
        if (!runnerName.equals(runnerName.trim())) {
            throw new IllegalArgumentException("runnerName must not contain leading or trailing whitespace");
        }
        if (runnerName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("runnerName must not contain control characters");
        }
        return runnerName;
    }

    /**
     * 业务作用：在进程整体停机时关闭全部时间轮 Runner，再收口仍由 ThreadPoolUtils 管理的共享线程池。
     * <p>
     * 参数说明: 无。
     * 返回: 无返回值；单个 Runner 停机异常被隔离，不能阻断其它执行域释放资源。
     */
    private static void shutdownAll() {
        RUNNERS.forEach((name, runner) -> {
            try {
                runner.stop();
            } catch (Throwable failure) {
                log.error("TimingWheel Runner {} shutdown failed", name, failure);
            }
        });
        ThreadPoolUtils.gracefulShutdown();
    }

    // NOTE ==================== 核心字段 ====================

    /* ActionRecycler 消费策略：将参数快照还原到执行线程的 AnyHolder，再执行业务 Action */
    static final Consumer<ActionRecycler> BICO = ar -> {
        AnyHolder.putAll(ar.ref(1));
        ((Action) ar.ref(0)).action();
    };

    /**
     * 当前时间轮所属的稳定 Runner 名称。
     */
    private final String runnerName;
    /**
     * 当前实例创建时确定的槽位数，同名 Runner 不允许运行期改变。
     */
    private final int wheelSize;
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

    /*
     * 在途普通 offer/postpone 计数: stop 时先关 started, 再等 inflight 清零, 保证 scheduler/executor 被 shutdown
     * 与 submitQueue/uniqueIndex/taskPool 被 clear 之前, 已通过 started check 的 producer 全部 offer 完毕,
     * 否则 producer 把 task 塞进已清空的 submitQueue → 任务永远不被 tick 消费 → 静默丢失 (含 uniqueIndex 残留映射).
     * 用 LongAdder 避免普通定时任务提交热路径上的单点 CAS 竞争。
     * 闸门同样 sound: producer increment 后 re-check started, 见 false 则 return 不入队;
     * 正在 offer 的 producer 其 increment 对 sum() 可见, sum() 必 ≥1 → stop 会等它.
     */
    private final LongAdder offerInflight = new LongAdder();
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
    /**
     * 每次成功启动递增，供绑定组件识别停机清理后已经失效的周期控制任务。
     */
    private volatile long lifecycleEpoch;

    /**
     * 业务作用：构造分层时间轮、tick 调度器及两类执行器，但在 start 前不接受业务任务。
     *
     * @param runnerName 所属 Runner 的稳定注册名称
     * @param wheelSize  每层槽位数，必须至少为 2
     * @param tickMs     最底层时间粒度，必须为正数
     *                   返回: 构造完成后实例处于未启动状态，并由类级统一停机钩子管理。
     */
    private TimingWheel(String runnerName, int wheelSize, int tickMs) {
        if (wheelSize < 2) {
            throw new IllegalArgumentException("TimingWheel's wheelSize must be greater than or equal to 2.");
        }
        if (tickMs < 1) {
            throw new IllegalArgumentException("TimingWheel's tickMs must be greater than 0.");
        }
        this.runnerName = validateRunnerName(runnerName);
        this.wheelSize = wheelSize;
        this.tickMs = tickMs;

        // 对齐到 tickMs 的整数倍
        long startMs = System.currentTimeMillis();
        startMs = startMs - (startMs % tickMs);
        this.wheel = new WheelLayer(tickMs, wheelSize, startMs);

        // 信号线程池
        this.scheduledExecutor = this.newScheduledExecutor();
        // 虚拟线程池: 业务任务默认执行器
        this.virtualExecutor = this.newVirtualExecutor();
        // 平台线程池: 关键定时任务 (如分布式锁看门狗、rebalance), 不受虚拟线程 pin 影响
        this.platformExecutor = this.newPlatformExecutor();


        // 时间轮心跳信号：drain 提交队列 + 指针推进 flush 到期 slot
        this.worker = this::tick;

    }

    // NOTE ==================== start / stop ====================

    /**
     * 业务作用：创建当前 Runner 独占的 tick 信号执行器，并在名称中暴露资源归属。
     * <p>
     * 参数说明: 无。
     * 返回: 尚未启动任务的单线程调度器。
     */
    private ScheduledExecutorService newScheduledExecutor() {
        return Executors.newSingleThreadScheduledExecutor(
                new NameThreadFactory("TimingWheel-" + this.runnerName + "-scheduled-" + this.tickMs + "ms", true)
        );
    }

    /**
     * 业务作用：创建当前 Runner 独占的虚拟线程执行器，停止本场景时不影响其它场景。
     * <p>
     * 参数说明: 无。
     * 返回: 以 Runner 名称标识且不进入全局单例池的虚拟线程执行器。
     */
    private ExecutorService newVirtualExecutor() {
        return ThreadPoolUtils.newVirtualThreadPool(false, "Virtual-TimingWheel-" + this.runnerName + '-');
    }

    /**
     * 业务作用：创建当前 Runner 独占的平台线程池，为关键任务提供与其它业务场景隔离的背压边界。
     * <p>
     * 参数说明: 无。
     * 返回: 线程名包含 Runner 的有界平台线程池；队列和线程均饱和时由 AbortPolicy 明确拒绝。
     */
    private ExecutorService newPlatformExecutor() {
        return ThreadPoolUtils.newThreadPool(false
                , Runtime.getRuntime().availableProcessors() << 1
                , Runtime.getRuntime().availableProcessors() << 2
                , 60_000L
                , 8192
                , "Platform-TimingWheel-" + this.runnerName + '-'
                , ThreadPoolExecutor.AbortPolicy.class);
    }

    /**
     * 业务作用：复验同名 Runner 的时间轮参数保持不变，避免调用方误以为运行中配置已经切换。
     *
     * @param wheelSize 期望槽位数
     * @param tickMs    期望最底层时间粒度
     *                  返回: 参数一致时无返回；不一致时抛出 IllegalStateException。
     */
    private void requireConfiguration(int wheelSize, int tickMs) {
        if (this.wheelSize != wheelSize || this.tickMs != tickMs) {
            throw new IllegalStateException("TimingWheel Runner '" + this.runnerName
                    + "' already exists with wheelSize=" + this.wheelSize + ", tickMs=" + this.tickMs);
        }
    }

    /**
     * 业务作用：向所属 Runner 暴露当前实例是否已经开放定时任务入口。
     * <p>
     * 参数说明: 无。
     * 返回: tick 调度已启动且尚未进入停机时返回 true。
     */
    private boolean runnerStarted() {
        return this.started;
    }

    /**
     * 业务作用：返回当前成功启动代次，供同包内绑定组件判断其周期任务是否仍属于现役时间轮。
     * <p>
     * 参数说明: 无。
     * 返回: 每次从停止状态成功启动时递增的代次值；重复 start 不改变该值。
     */
    private long runnerLifecycleEpoch() {
        return this.lifecycleEpoch;
    }

    /**
     * 业务作用：在生命周期锁内重建已关闭执行器并启动唯一 tick 信号，成功后才开放任务入口。
     * <p>
     * 参数说明: 无。
     * 返回: 当前时间轮实例；重复调用保持幂等。
     */
    public TimingWheel start() {
        if (started) return this;
        workerLock.lock();
        try {
            if (started) return this;
            if (scheduledExecutor.isShutdown()) {
                this.scheduledExecutor = this.newScheduledExecutor();
            }
            if (virtualExecutor.isShutdown()) {
                this.virtualExecutor = this.newVirtualExecutor();
            }
            if (platformExecutor.isShutdown()) {
                this.platformExecutor = this.newPlatformExecutor();
            }
            // tick 调度成功后才开放普通定时任务入口，防止 started=true 的半启动窗口。
            scheduledExecutor.scheduleAtFixedRate(worker, 0, tickMs, TimeUnit.MILLISECONDS);
            lifecycleEpoch++;
            started = true;
            // 启动日志走 execute 异步打印, 防止 log appender 慢启动阻塞调用方; REE 时降级同步.
            try {
                scheduledExecutor.execute(() -> log.info("TimingWheel Runner {} has started.", this.runnerName));
            } catch (RejectedExecutionException ignored) {
                log.info("TimingWheel Runner {} has started.", this.runnerName);
            }
        } finally {
            workerLock.unlock();
        }
        return this;
    }

    /**
     * 业务作用：关闭新定时提交，等待 producer、tick 和已接收业务执行收口后清理全部定时状态。
     * <p>
     * 参数说明: 无。
     * 返回: 无返回值；各等待阶段有界，超时会告警后继续完成停机。
     */
    public void stop() {
        if (!started) return;
        workerLock.lock();
        try {
            if (!started) return;
            // 先关闭定时任务入口，再等待已登记 producer 收口，避免清理 submitQueue 时出现迟到发布。
            started = false;
            this.awaitOfferInflightDrained();
            if (!scheduledExecutor.isShutdown()) {
                /*
                 * 关闭日志走 scheduledExecutor 异步, 保证日志在 shutdown 前最后一条 task 排到队尾。
                 * try-catch 防御: 多线程场景下 isShutdown() 与 execute() 之间可能有 race
                 * (虽然此处持有 workerLock, 但 ScheduledExecutor 内部可能因 OOM / 超过 maxThreads 抛 REE)。
                 * 失败时降级为同步日志, 不影响 shutdown() 主流程。
                 */
                try {
                    scheduledExecutor.execute(() -> log.info("TimingWheel Runner {} has closed.", this.runnerName));
                } catch (RejectedExecutionException ignored) {
                    log.info("TimingWheel Runner {} has closed.", this.runnerName);
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
            // Runner 身份会跨 stop/start 保留，必须先物理摘除旧代任务，禁止重启后执行停机前的迟到任务。
            this.clearScheduledTasks();
            uniqueIndex.clear();
            taskPool.removeAll();
        } finally {
            workerLock.unlock();
        }
    }

    /**
     * 业务作用：停机时摘除提交队列、快车道和全部时间轮槽位中的旧代任务，为同一 Runner 重启建立空状态。
     * <p>
     * 参数说明: 无。
     * 返回: 无返回值；每个物理容器都由停机线程独占清理，任务取消回收异常不会阻断其它任务收口。
     */
    private void clearScheduledTasks() {
        Task task;
        while ((task = this.submitQueue.poll()) != null) this.discardStoppedTask(task);
        for (int i = this.tickTasks.size() - 1; i >= 0; i--) {
            this.discardStoppedTask(this.tickTasks.get(i));
        }
        this.tickTasks.clear();
        this.wheel.clear(System.currentTimeMillis(), this::discardStoppedTask);
        this.submitQueue.clear();
    }

    /**
     * 业务作用：把已经从停机容器摘除的任务发布为取消并释放其业务回收钩子和池化载体。
     *
     * @param task 已经不再属于任何提交队列或时间轮槽位的任务
     *             返回: 无返回值；执行器超时且任务仍在运行时只标记取消，由在途执行路径自行收口。
     */
    private void discardStoppedTask(Task task) {
        task.cancelled = true;
        if (task.unique != null) this.uniqueIndex.remove(task.unique, task);
        if (task.running) return;
        try {
            task.cancelledRecycle();
        } catch (Throwable failure) {
            // 单个业务取消钩子异常不能让后续槽位残留；PooledHandle 保证兜底归还至多成功一次。
            try {
                task.recycle();
            } catch (Throwable recycleFailure) {
                failure.addSuppressed(recycleFailure);
            }
            log.error("TimingWheel Runner {} failed to recycle stopped task", this.runnerName, failure);
        }
    }

    // NOTE ==================== offer / remove / delay（外部调用，无锁） ====================

    /**
     * 业务作用：通过实例入口提交一次性默认虚拟线程任务。
     *
     * @param delay  延迟时间
     * @param action 具体任务
     *               返回: 无返回值。
     */
    public void offer(long delay, Action action) {
        this.offer(delay, 0, null, action);
    }

    /**
     * 业务作用：通过实例入口提交带唯一标识的一次性默认虚拟线程任务。
     *
     * @param delay  延迟时间
     * @param unique 唯一标识
     * @param action 具体任务
     *               返回: 无返回值。
     */
    public void offer(long delay, String unique, Action action) {
        this.offer(delay, 0, unique, action);
    }

    /**
     * 业务作用：通过实例入口提交无唯一标识的默认虚拟线程周期任务。
     *
     * @param delay  延迟时间
     * @param period 周期时间
     * @param action 具体任务
     *               返回: 无返回值。
     */
    public void offer(long delay, long period, Action action) {
        this.offer(delay, period, null, action);
    }

    /**
     * 业务作用：通过实例入口提交完整参数的默认虚拟线程定时任务。
     *
     * @param delay  延迟时间
     * @param period 周期时间，0 表示非周期任务，> 0 表示周期任务
     * @param unique 唯一标识
     * @param action 具体任务
     *               返回: 无返回值。
     */
    public void offer(long delay, long period, String unique, Action action) {
        this.offer(delay, period, unique, false, action);
    }

    /**
     * 业务作用：在停机代次门禁内受理普通定时任务，捕获上下文并选择立即执行、tick 快车道或分层时间轮。
     *
     * @param delay    首次延迟毫秒数
     * @param period   周期毫秒数，不能为负数
     * @param unique   唯一标识；可为 null
     * @param platform 是否使用平台线程执行器
     * @param action   具体业务任务
     *                 返回: 无返回值；发布失败时撤销索引并正确归还对象池资源。
     */
    public void offer(long delay, long period, String unique, boolean platform, Action action) {
        // 未启动或停机后不再接受定时任务；显式归还 ofRecycle Recycler，避免任务从对象池脱离后泄漏。
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
         * 已 clear 的 submitQueue → tick 永不消费 → 任务静默丢失。
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
                 * immediate periodic (delay<1 && period>0) 统一创建 task 并提交 task.execAction():
                 * - 复用 execTask 的 RUNNING 守卫，阻止首次立即执行与后续 tick 并发执行同一 action；
                 * - task.parameters 持有原 passthrough 跨次复用，执行器拒绝时由 cancelledRecycle 完整归还；
                 * - 不额外复制一次 RecycleLinkedMap，避免重复快照和回收责任分叉。
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
     * 业务作用：移除 unique 索引并惰性标记任务取消，由其当前物理容器的唯一消费路径完成回收。
     *
     * @param unique 任务唯一标识
     *               返回: 无返回值；未启动或未找到时保持幂等。
     */
    public void remove(String unique) {
        if (!started) return;
        Task task = this.uniqueIndex.remove(unique);
        if (Objects.nonNull(task)) {
            task.cancelled = true;
        }
    }

    /**
     * 业务作用：注册 unique 索引；重复 unique 按“后提交覆盖前提交”处理，旧任务只做惰性取消，
     * 由原所在路径 drain / flush / exec 时负责回收。
     *
     * @param unique 唯一标识；为 null 时不登记
     * @param task   新提交的稳定任务对象
     *               返回: 无返回值；被覆盖旧任务不会在此提前物理回收。
     */
    private void registerUnique(String unique, Task task) {
        if (unique == null) return;
        Task old = this.uniqueIndex.put(unique, task);
        if (old == null || old == task) return;
        old.cancelled = true;
    }

    /**
     * 业务作用：把任务发布到 tick 唯一 consumer 的 MPSC 提交队列，失败时撤销索引并回收。
     *
     * @param task 已完整初始化的定时任务
     *             返回: 无返回值；发布异常转换为运行时异常向调用方传播。
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
     * 业务作用：以新任务替换 unique 当前任务并向后移动超时时间，避免原物理槽位原地修改。
     *
     * @param unique      任务唯一标识
     * @param delayMillis 延期毫秒数
     *                    返回: 无返回值；未启动或 unique 不存在时保持幂等。
     */
    public void postpone(String unique, long delayMillis) {
        if (!started) return;
        if (delayMillis < 1) {
            throw new IllegalArgumentException("delayMillis must be greater than 0.");
        }
        /*
         * postpone 与 offer 共用 inflight 闸门，防止 stop 并发清空 submitQueue 时吞掉新的 delayTask。
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

    /**
     * 业务作用：由唯一信号线程推进快车道、提交队列和各级时间轮，并隔离单轮异常以保持后续 tick 存活。
     * <p>
     * 参数说明: 无。
     * 返回: 无返回值；信号线程延迟时逐 tick 追赶，不跳过到期槽位。
     */
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
     * 业务作用：调度 period 小于等于 tickMs 的快车道任务，并在任务不运行时惰性清理取消项。
     * <p>
     * 参数说明: 无。
     * 返回: 无返回值；执行器拒绝只跳过本轮，下个 tick 可重试。
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
             * 触发场景：
             *   1) stop() 并发窗口内执行器已经 shutdown；
             *   2) 默认 platformExecutor 的线程和 8192 有界队列均饱和，AbortPolicy 明确拒绝。
             * 默认虚拟线程执行器没有容量拒绝边界，只会在 shutdown 后拒绝。
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
     * 业务作用：由 tick 唯一 consumer 批量摘除提交任务，分流到快车道、时间轮或直接执行路径。
     * <p>
     * 参数说明: 无。
     * 返回: 无返回值；取消任务在物理摘除后完成回收。
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
     * 业务作用：按任务 pin 风险选择平台线程池或默认虚拟线程池。
     *
     * @param platform 是否要求平台线程执行
     *                 返回: 当前对应的业务执行器。
     */
    private ExecutorService executor(boolean platform) {
        return platform ? platformExecutor : virtualExecutor;
    }

    /**
     * 业务作用：把未到期任务加入合适层级，已到期任务提交执行器并按一次性/周期语义处理拒绝。
     *
     * @param task 已从提交队列或槽位摘除的任务
     *             返回: 无返回值；每条失败路径负责索引与对象池收口。
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
                // 正常运行期的容量拒绝不能取消整个周期；Runner 仍开放时推进截止时间并保留后续执行机会。
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
     * 业务作用：恢复提交上下文并执行到期任务；周期任务使用 RUNNING 门禁按 fixed-delay 安全续约。
     *
     * @param task 已取得执行调度权的任务
     *             返回: 无返回值；一次性任务执行后回收，周期任务在 finally 中续约或因取消/停机回收。
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

    /**
     * 业务作用：停机时有界等待已经登记的 offer/postpone producer 退出，保护随后 submitQueue 清理边界。
     * <p>
     * 参数说明: 无。
     * 返回: 无返回值；1 秒内未归零时记录可能丢失风险并由停机流程继续处理。
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

    /**
     * 业务作用：停机清理前有界等待业务执行器完成已接收任务，避免在途周期任务向已清空队列续约。
     *
     * @param executor 已经 shutdown 的业务执行器
     * @param name     日志使用的稳定执行器名称
     * @param awaitMs  最长等待毫秒数
     *                 返回: 无返回值；超时或中断只告警，停机主流程继续收口。
     */
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

    /**
     * 业务作用：在普通定时任务确定不会提交或执行时调用取消回收钩子，避免 ofRecycle 对象脱池泄漏。
     *
     * @param action 已被定时入口明确丢弃的业务动作
     *               返回: 无返回值；普通 action 不处理，单笔回收异常只记录而不破坏停机收口。
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

    /**
     * 业务作用：把内部异常转换为调用方可感知的运行时异常，同时保持 Error 原样传播。
     *
     * @param t 待传播异常
     *          返回: 原 RuntimeException 或包装后的 RuntimeException；Error 会直接抛出。
     */
    private static RuntimeException propagate(Throwable t) {
        if (t instanceof RuntimeException e) return e;
        if (t instanceof Error e) throw e;
        return new RuntimeException(t);
    }

    // 下列层级只由唯一信号线程推进，轮槽内部不需要跨线程互斥。

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

        /**
         * 业务作用：创建一个按给定时间粒度覆盖固定区间的时间轮层，并初始化全部空槽位。
         *
         * @param tickMs      本层时间粒度
         * @param wheelSize   本层槽位数
         * @param currentTime 创建时基准时间
         *                    返回: 构造完成后 currentTime 对齐到本层 tick 边界。
         */
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
         * 业务作用：把未到期任务放入本层槽位或递归降到更粗溢出层。
         *
         * @param task 待调度任务
         *             返回: 已加入某层返回 true；相对当前层已经到期、需要直接执行时返回 false。
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
         * 业务作用：按需创建覆盖更长延迟的上层时间轮，粒度等于本层完整区间。
         * <p>
         * 参数说明: 无。
         * 返回: 已存在或本次创建的唯一溢出层。
         */
        WheelLayer overflowWheel() {
            if (overflowWheel == null) {
                overflowWheel = new WheelLayer(interval, wheelSize, currentTime);
            }
            return overflowWheel;
        }

        /**
         * 业务作用：停机时递归摘除本层和溢出层任务，并把根层时基重新对齐到当前时间供同实例重启。
         *
         * @param now      重启前使用的当前毫秒时间
         * @param consumer 每笔被摘除任务的取消回收处理器
         *                 返回: 无返回值；完成后本层无活动槽位，旧溢出层被释放。
         */
        void clear(long now, Consumer<Task> consumer) {
            for (Slot slot : this.slots) slot.flush(consumer);
            WheelLayer overflow = this.overflowWheel;
            if (overflow != null) overflow.clear(now, consumer);
            this.overflowWheel = null;
            this.currentTime = now - (now % this.tickMs);
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

        /**
         * 业务作用：把任务追加到本槽私有链表并登记物理槽位，保持同槽任务的提交顺序。
         *
         * @param task 待加入本槽的任务
         *             返回: 无返回值；链表首次使用时才从对象池获取。
         */
        void add(Task task) {
            if (tasks == null) tasks = RecycleLinkedList.of();
            tasks.add(task);
            task.slot = this;
        }

        /**
         * 业务作用：一次性摘除本槽全部任务并交给调用方执行或重新分层，同时归还链表节点。
         *
         * @param consumer 每笔摘除任务的后续处理器
         *                 返回: 无返回值；空槽保持幂等。
         *                 <p>
         *                 使用 clearRHead() + Node 链遍历，避免创建 Iterator 对象
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

        /**
         * 业务作用：登记本槽对应的对齐到期边界，供 tick 判断是否需要 flush。
         *
         * @param expiration 对齐后的到期毫秒时间
         *                   返回: 无返回值。
         */
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
        /* Runner 内唯一 */
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

        /* 当前所在的 slot（用于取消与状态诊断） */
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

        /**
         * 业务作用：创建绑定所属时间轮对象池的稳定任务载体，并初始化防重复回收 handle。
         *
         * @param timingWheel 所属时间轮实例
         *                    返回: 构造完成后业务字段为空，等待对象池 get 初始化。
         */
        private Task(TimingWheel timingWheel) {
            this.timingWheel = timingWheel;
            this.handle = new ObjectPool.PooledHandle<>(timingWheel.taskPool);
        }

        /**
         * 业务作用：惰性创建并复用调用所属时间轮 execTask 的捕获动作，避免每次调度分配 lambda。
         * <p>
         * 参数说明: 无。
         * 返回: 绑定当前 Task 实例且可跨对象池复用的执行动作。
         */
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

        /**
         * 业务作用：惰性创建快车道执行动作，恢复上下文并确保 RUNNING 在 finally 中释放。
         * <p>
         * 参数说明: 无。
         * 返回: 绑定当前 Task 实例的快车道动作。
         */
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

        /**
         * 业务作用：暴露本任务的防重复回收 handle，确保对象池归还至多成功一次。
         * <p>
         * 参数说明: 无。
         * 返回: 创建时绑定所属任务池的稳定 handle。
         */
        @Override
        public ObjectPool.PooledHandle<Task> handle() {
            return this.handle;
        }

        /**
         * 业务作用：任务归池前回收上下文并清空所有可变业务字段，同时保留可安全复用的缓存动作。
         * <p>
         * 参数说明: 无。
         * 返回: 无返回值；执行后对象恢复为可再次初始化状态。
         */
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
         * 业务作用：取消路径先通知业务 action 执行取消回收扩展点，再归还 Task 自身和上下文。
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
         * <p>
         * 参数说明: 无。
         * 返回: 无返回值；普通 action 只回收 Task，自回收 action 同时归还自身。
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

        /**
         * 业务作用：创建绑定时间轮实例的 Task 对象池，并读取可配置容量上限。
         *
         * @param owner 新 Task 回调所属时间轮
         *              返回: 构造完成后对象池为空并可按需创建 Task。
         */
        TaskObjectPool(TimingWheel owner) {
            super(Integer.parseInt(System.getProperty("nasa.object-pool.timing-wheel-task-capacity", "10000")));
            this.owner = owner;
        }

        /**
         * 业务作用：从对象池取得任务并完整初始化本次调度的业务字段。
         *
         * @param timeout  绝对到期毫秒时间
         * @param period   周期毫秒数
         * @param unique   唯一标识；可为 null
         * @param action   业务动作
         * @param platform 是否使用平台线程执行
         *                 返回: 已初始化且取消标记清零的 Task。
         */
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

        /**
         * 业务作用：对象池为空时创建绑定同一时间轮的新任务载体。
         * <p>
         * 参数说明: 无。
         * 返回: 尚未初始化业务字段的新 Task。
         */
        @Override
        public Task newObject() {
            return new Task(this.owner);
        }
    }

    /**
     * 按业务执行域隔离的时间轮入口。
     *
     * <p>每个 Runner 独占任务索引、提交队列、时间轮槽位、调度线程和业务执行器。同名 Runner 由
     * {@link TimingWheel#of(String)} 统一注册并复用，不同 Runner 可以安全使用相同的任务 unique。</p>
     */
    public static final class TimingWheelRunner {

        private final String runnerName;
        private final TimingWheel timingWheel;

        /**
         * 业务作用：创建绑定稳定名称和独占时间轮实现的 Runner，只有 TimingWheel 注册表可以调用。
         *
         * @param runnerName 应用级稳定 Runner 名称
         * @param wheelSize  每层槽位数
         * @param tickMs     最底层时间粒度
         *                   返回: 构造完成后 Runner 尚未启动，不接受定时任务。
         */
        private TimingWheelRunner(String runnerName, int wheelSize, int tickMs) {
            this.runnerName = runnerName;
            this.timingWheel = new TimingWheel(runnerName, wheelSize, tickMs);
        }

        /**
         * 业务作用：返回 Runner 的稳定注册名称，供日志、指标和业务资源归属诊断使用。
         * <p>
         * 参数说明: 无。
         * 返回: 创建时经过校验且不会变化的 Runner 名称。
         */
        public String getRunnerName() {
            return this.runnerName;
        }

        /**
         * 业务作用：启动本 Runner 独占的 tick 调度和业务执行器，成功后开放定时任务入口。
         * <p>
         * 参数说明: 无。
         * 返回: 当前 Runner；重复启动保持幂等。
         */
        public TimingWheelRunner start() {
            this.timingWheel.start();
            return this;
        }

        /**
         * 业务作用：关闭本 Runner 的新提交并有界收口其独占资源，不影响任何其它 Runner。
         * <p>
         * 参数说明: 无。
         * 返回: 无返回值；停止后的同一 Runner 可以再次启动。
         */
        public void stop() {
            this.timingWheel.stop();
        }

        /**
         * 业务作用：判断本 Runner 是否已经开放定时任务入口。
         * <p>
         * 参数说明: 无。
         * 返回: tick 调度已经启动且尚未进入停机时返回 true。
         */
        public boolean isStarted() {
            return this.timingWheel.runnerStarted();
        }

        /**
         * 业务作用：向同场景基础组件暴露时间轮启动代次，用于识别停机时已被清除的周期控制任务。
         * <p>
         * 参数说明: 无。
         * 返回: 当前时间轮成功启动代次；仅供同包内生命周期协作使用。
         */
        long lifecycleEpoch() {
            return this.timingWheel.runnerLifecycleEpoch();
        }

        /**
         * 业务作用：把一次性任务立即提交给本 Runner 的虚拟线程执行器。
         *
         * @param action 具体任务
         *               返回: 无返回值；Runner 未启动时任务按时间轮既有契约拒绝并执行取消回收。
         */
        public void exec(Action action) {
            this.exec(0L, action);
        }

        /**
         * 业务作用：把一次性任务按毫秒延迟提交给本 Runner 的虚拟线程执行器。
         *
         * @param delay  延迟毫秒数
         * @param action 具体任务
         *               返回: 无返回值；delay 小于 1 时按立即执行处理。
         */
        public void exec(long delay, Action action) {
            this.exec(delay, null, action);
        }

        /**
         * 业务作用：登记带唯一标识的一次性虚拟线程任务，唯一性只在本 Runner 内生效。
         *
         * @param delay  延迟毫秒数
         * @param unique 本 Runner 内的任务唯一标识
         * @param action 具体任务
         *               返回: 无返回值；同 Runner 相同 unique 的旧任务被惰性取消。
         */
        public void exec(long delay, String unique, Action action) {
            this.exec(delay, 0L, unique, action);
        }

        /**
         * 业务作用：登记无唯一标识的周期虚拟线程任务。
         *
         * @param delay  首次延迟毫秒数
         * @param period 周期毫秒数
         * @param action 具体任务
         *               返回: 无返回值；period 为 0 时退化为一次性任务。
         */
        public void exec(long delay, long period, Action action) {
            this.exec(delay, period, null, action);
        }

        /**
         * 业务作用：按完整参数把虚拟线程定时任务登记到本 Runner 的独立任务域。
         *
         * @param delay  首次延迟毫秒数
         * @param period 周期毫秒数，0 表示一次性任务
         * @param unique 本 Runner 内的唯一标识；可为 null
         * @param action 具体任务
         *               返回: 无返回值；任务受理、执行和回收沿用时间轮既有语义。
         */
        public void exec(long delay, long period, String unique, Action action) {
            this.timingWheel.offer(delay, period, unique, action);
        }

        /**
         * 业务作用：把一次性任务立即提交给本 Runner 的平台线程池。
         *
         * @param action 具体任务
         *               返回: 无返回值。
         */
        public void platform(Action action) {
            this.platform(0L, action);
        }

        /**
         * 业务作用：把一次性任务按毫秒延迟提交给本 Runner 的平台线程池。
         *
         * @param delay  延迟毫秒数
         * @param action 具体任务
         *               返回: 无返回值。
         */
        public void platform(long delay, Action action) {
            this.platform(delay, 0L, null, action);
        }

        /**
         * 业务作用：登记带唯一标识的一次性平台线程任务，唯一性只在本 Runner 内生效。
         *
         * @param delay  延迟毫秒数
         * @param unique 本 Runner 内的任务唯一标识
         * @param action 具体任务
         *               返回: 无返回值。
         */
        public void platform(long delay, String unique, Action action) {
            this.platform(delay, 0L, unique, action);
        }

        /**
         * 业务作用：登记无唯一标识的周期平台线程任务。
         *
         * @param delay  首次延迟毫秒数
         * @param period 周期毫秒数
         * @param action 具体任务
         *               返回: 无返回值。
         */
        public void platform(long delay, long period, Action action) {
            this.platform(delay, period, null, action);
        }

        /**
         * 业务作用：按完整参数把平台线程定时任务登记到本 Runner 的独立任务域。
         *
         * @param delay  首次延迟毫秒数
         * @param period 周期毫秒数，0 表示一次性任务
         * @param unique 本 Runner 内的唯一标识；可为 null
         * @param action 具体任务
         *               返回: 无返回值。
         */
        public void platform(long delay, long period, String unique, Action action) {
            this.timingWheel.offer(delay, period, unique, true, action);
        }

        /**
         * 业务作用：按唯一标识惰性取消本 Runner 当前任务，不触碰其它 Runner 的同名任务。
         *
         * @param unique 本 Runner 内的任务唯一标识
         *               返回: 无返回值；未找到或未启动时保持幂等。
         */
        public void cancel(String unique) {
            this.timingWheel.remove(unique);
        }

        /**
         * 业务作用：按唯一标识把本 Runner 当前任务整体向后延期。
         *
         * @param unique      本 Runner 内的任务唯一标识
         * @param delayMillis 延期毫秒数
         *                    返回: 无返回值；未找到任务时保持幂等。
         */
        public void delay(String unique, long delayMillis) {
            this.timingWheel.postpone(unique, delayMillis);
        }

        /**
         * 业务作用：向 TimingWheel 注册表复验本 Runner 的不可变时间轮参数。
         *
         * @param wheelSize 期望槽位数
         * @param tickMs    期望最底层时间粒度
         *                  返回: 参数一致时无返回；不一致时抛出 IllegalStateException。
         */
        private void requireConfiguration(int wheelSize, int tickMs) {
            this.timingWheel.requireConfiguration(wheelSize, tickMs);
        }

        /**
         * 业务作用：向同类兼容入口提供默认 Runner 的底层时间轮，保持既有 of() 返回类型。
         * <p>
         * 参数说明: 无。
         * 返回: 本 Runner 唯一绑定的时间轮实现。
         */
        private TimingWheel timingWheel() {
            return this.timingWheel;
        }
    }

}
