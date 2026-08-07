package io.github.nasaruntime.core.base;

import io.github.nasaruntime.core.concurrent.MPSCLinkedQueue;
import io.github.nasaruntime.core.function.ActionRecycler;
import lombok.extern.slf4j.Slf4j;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 独立分区消费子系统。
 *
 * <p>本类拥有固定分区路由、MPSC 队列、唯一 consumer worker、任务类型策略、精确逻辑计数、
 * 取消和故障门禁。路由 key 只决定原始分区；严格顺序边界是“原始分区 + 任务类型”。
 * 非严格任务可经多盗洞分发，严格任务通过代次门禁完成整体迁移和有界归还；延迟入口只负责到期后
 * 重新进入当前路由，不预占任何分区负载。</p>
 */
@Slf4j
public final class Partition {

    /**
     * 分区消费任务。
     *
     * <p>路由 key 只决定原始分区；严格顺序边界由“原始分区 + {@link #taskType()}”共同确定。
     * 任务进入分区架构后，类型和保序属性都不得改变。所有权方法必须提供跨线程原子可见性，
     * 推荐使用 {@code VarHandle} 或 {@code AtomicInteger} 实现。</p>
     */
    public interface Task {

        /**
         * 业务作用：返回稳定的业务任务类型，作为严格顺序、类型统计和盗洞路由的索引。
         *
         * 参数说明: 无。
         * 返回: 任务进入分区架构后保持不变的类型标识。
         */
        int taskType();

        /**
         * 业务作用：声明该类型是否要求在“原始分区 + 任务类型”范围内严格 FIFO。
         *
         * 参数说明: 无。
         * 返回: 要求严格保序时返回 true；允许任务粒度重排和多盗洞分发时返回 false。
         */
        boolean strictOrder();

        /**
         * 业务作用：以 acquire 语义读取任务当前逻辑所有者，供执行、取消和迁移竞争时复验权威。
         *
         * 参数说明: 无。
         * 返回: 当前所有权分区号或框架定义的不可执行终态哨兵。
         */
        int getOwner();

        /**
         * 业务作用：在任务初始化或框架已经独占状态迁移权时发布所有者，禁止业务代码并发改写。
         *
         * @param owner 新所有权分区号或框架终态哨兵
         * 返回: 无返回值；实现必须以 release 或更强语义发布。
         */
        void setOwner(int owner);

        /**
         * 业务作用：原子转移任务所有权，使执行、取消和跨队列迁移至多只有一方取得权威。
         *
         * @param expectedOwner 期望的当前所有者
         * @param newOwner 竞争成功后发布的新所有者
         * 返回: CAS 成功时返回 true；所有权已经被其他路径改变时返回 false。
         */
        boolean compareAndSetOwner(int expectedOwner, int newOwner);

        /**
         * 业务作用：执行已经取得唯一执行权的业务任务；框架负责在 finally 中发布终态和减少逻辑计数。
         *
         * 参数说明: 无。
         * 返回: 无返回值；业务异常由分区 worker 隔离并记录，不得杀死 worker。
         */
        void exec();
    }

    /**
     * 分区任务的稳定提交句柄。
     *
     * <p>句柄只保存不可变借出代次，状态仍读取框架内部任务条目的权威原子状态。
     * 句柄本身不进入对象池，因此即使内部条目已经被另一笔任务复用，旧引用也只会 fail-fast，
     * 绝不会观察或取消新任务。调用方使用完必须执行 {@link #recycle()} 或 {@link #close()}，
     * 否则对应内部条目不能归池；不需要查询或取消能力的 fire-and-forget 任务应使用 {@link #exec(Object, Task)}。
     * 句柄释放后不得再访问旧引用。</p>
     */
    public interface Submission extends AutoCloseable {

        /**
         * 提交生命周期的公开状态。
         */
        enum Status {
            /** 任务只登记在 TimingWheel，尚未取得任何分区所有权。 */
            DELAYED,
            /** 框架正在完成初始化或路由，尚未形成对外受理结果。 */
            ENQUEUEING,
            /** 任务已经进入唯一队列并等待执行。 */
            QUEUED,
            /** 任务已经取得执行权。 */
            RUNNING,
            /** 任务已经执行结束。 */
            COMPLETED,
            /** 任务在开始执行前被取消。 */
            CANCELLED,
            /** 任务未被队列受理或因故障/停机被明确拒绝。 */
            REJECTED,
            /** 任务已经受理，但所属类型或分区失去安全推进条件，任务作为未执行证据保留。 */
            FAILED,
            /** 任务正在两个逻辑所有者之间迁移。 */
            MOVING
        }

        /**
         * 业务作用：读取提交当前生命周期，供调用方判断任务是否仍可能执行。
         *
         * 参数说明: 无。
         * 返回: 当前原子状态对应的公开枚举值。
         */
        Status status();

        /**
         * 业务作用：在任务开始执行前竞争取消权；成功者负责发布终态并只减少一次逻辑计数。
         *
         * 参数说明: 无。
         * 返回: 本次调用成功把未执行任务取消时返回 true；任务已运行、完成、拒绝或已被取消时返回 false。
         */
        boolean cancel();

        /**
         * 业务作用：返回拒绝或冻结原因，帮助调用方区分停机、类型策略冲突、分区故障和发布异常。
         *
         * 参数说明: 无。
         * 返回: 状态为 REJECTED/FAILED 时返回稳定原因；其他状态通常返回 null。
         */
        String rejectionReason();

        /**
         * 业务作用：声明调用方不再访问本提交句柄，使内部条目在物理脱离全部容器后可以安全归池。
         *
         * 参数说明: 无。
         * 返回: 无返回值；可在任务执行前调用，实际归池会延迟到终态、队列摘除和定时包装释放全部完成；
         * 忘记调用会使本笔内部条目失去对象池复用机会。
         */
        void recycle();

        /**
         * 业务作用：以 try-with-resources 语义释放稳定提交句柄，统一进入对象池延迟回收协议。
         *
         * 参数说明: 无。
         * 返回: 无返回值；调用后不得继续读取状态、取消或拒绝原因。
         */
        @Override
        default void close() {
            this.recycle();
        }
    }

    /** 任务已经取消，不再属于任何可执行分区。 */
    public static final int OWNER_CANCELLED = -1;
    /** 任务已经执行完成，不再属于任何可执行分区。 */
    public static final int OWNER_COMPLETED = -2;
    /** 任务未被框架受理。 */
    public static final int OWNER_REJECTED = -3;
    /** 归还暂存任务使用的不可执行所有权哨兵。 */
    public static final int OWNER_RETURN_STAGING = -4;
    /** 已受理任务因控制面故障冻结并保留证据。 */
    public static final int OWNER_FAILED = -5;

    private static final int DEFAULT_DRAIN_BATCH = 1_024;
    private static final long DEFAULT_STOP_TIMEOUT_MILLIS = 5_000L;
    private static final long DEFAULT_TUNNEL_LEASE_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);
    private static final long STEAL_RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(2L);
    /** 停机排空期间无进度时的 park 时长：停机路径不会有新任务到达，忙循环只会空烧 CPU。 */
    private static final long STOP_DRAIN_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(1L);
    private static final Object GATE_IDLE = new Object();
    private static final Object GATE_RUNNING = new Object();
    private static final Partition INSTANCE = new Partition();
    /** 延迟到期动作使用无捕获策略，逐笔参数放入池化 ActionRecycler 槽位。 */
    private static final Consumer<ActionRecycler> DELAYED_EXPIRY_ACTION = Partition::runDelayedExpiryAction;

    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final AtomicLong lifecycleEpoch = new AtomicLong();
    private final AtomicReference<Lifecycle> lifecycle = new AtomicReference<>(Lifecycle.stopped(0L));
    private final AtomicInteger failedPartitionCount = new AtomicInteger();
    private final AtomicLong delayedSequence = new AtomicLong();
    private final ConcurrentHashMap<TaskEntry, Boolean> delayedRegistry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TaskEntry, Boolean> movingRegistry = new ConcurrentHashMap<>();
    /** 迁移审计读侧计数与回收门禁共同提供一次轻量 grace period，阻止弱一致迭代器访问已复用条目。 */
    private final AtomicInteger movingAuditors = new AtomicInteger();
    private final AtomicBoolean movingRecycleGate = new AtomicBoolean();
    private final TaskEntryPool taskEntryPool = new TaskEntryPool();
    private final DelayedReferencePool delayedReferencePool = new DelayedReferencePool();

    /**
     * 业务作用：构造全局分区子系统实例；实际队列与 worker 只在 {@link #start()} 时创建。
     *
     * 参数说明: 无。
     * 返回: 构造完成后处于 STOPPED，不接受任务。
     */
    private Partition() {
    }

    /**
     * 业务作用：启动独立分区集群，完整创建所有队列和 worker 后一次性开放提交入口。
     *
     * 参数说明: 无。
     * 返回: 无返回值；重复启动保持幂等，停机尚未收口时拒绝重新启动。
     */
    public static void start() {
        INSTANCE.startInternal();
    }

    /**
     * 业务作用：关闭新提交，等待已登记 producer 收口，排空健康队列并停止全部分区 worker。
     *
     * 参数说明: 无。
     * 返回: 完整收口并发布 STOPPED 时返回 true；任一阶段超时返回 false 并保留 STOPPING 状态。
     * <p>
     * <b>返回 false 的处置契约</b>：本方法<b>没有时间上界</b>，返回 false 不代表"再等一会儿就好"，
     * 循环重试也不改变收敛条件。两条超时路径的语义完全不同，调用方必须区分：
     * <ul>
     *   <li><b>全局 producer 未归零</b>——有线程长期停在 {@code submit}/{@code exec} 内部。
     *       重试通常有效，因为这些线程会自行退出。</li>
     *   <li><b>worker 未排空</b>——某个分区仍持有无法推进的入站严格盗洞：源分区已经无法继续
     *       交付该盗洞的任务，而目标 worker 必须等盗洞清空才能退出。此时没有任何后台机制
     *       会打破该状态，重试只是重新等一轮同样的超时。</li>
     * </ul>
     * 因此持续返回 false 属于<b>需要人工介入的故障信号</b>，不是可以无限重试的正常路径。
     * 调用方应当在有限次重试后停止重试并上报，用 {@link #isHealthy()} 与
     * {@link #failedPartitionCount()} 判定是否已有分区进入 FAILED，再决定是否放弃优雅停机。
     * <p>
     * 设计取舍说明：早期实现带有超时后强制冻结卡死盗洞的 backstop，可保证停机有界，
     * 代价是把在途任务冻结成失败证据（有损）。当前实现移除了该 backstop，改为依赖
     * 各条卡死根因被逐个修复，因此把"停机可能不收敛"显式暴露给调用方，而不是静默兜底。
     */
    public static boolean stop() {
        return INSTANCE.stopInternal();
    }

    /**
     * 业务作用：判断分区子系统是否已经完整开放提交。
     *
     * 参数说明: 无。
     * 返回: 当前生命周期为 ACCEPTING 时返回 true。
     */
    public static boolean isStarted() {
        return INSTANCE.lifecycle.get().phase == LifecyclePhase.ACCEPTING;
    }

    /**
     * 业务作用：判断分区子系统是否可接收任务且没有分区 worker/共享原队列故障。
     *
     * 参数说明: 无。
     * 返回: 已启动且失败分区数为零时返回 true。
     */
    public static boolean isHealthy() {
        return isStarted() && INSTANCE.failedPartitionCount.get() == 0;
    }

    /**
     * 业务作用：暴露已经失败关闭的原始分区数量，供上层熔断和运维恢复决策使用。
     *
     * 参数说明: 无。
     * 返回: 当前代已发布分区级 FAILED 的槽位数。
     */
    public static int failedPartitionCount() {
        return INSTANCE.failedPartitionCount.get();
    }

    /**
     * 业务作用：返回当前代实际分区数，供容量监控和路由诊断使用。
     *
     * 参数说明: 无。
     * 返回: 已启动或停机收口中的槽位数；完全停止时为 0。
     */
    public static int partitionCount() {
        return INSTANCE.lifecycle.get().slots.length;
    }

    /**
     * 业务作用：按对象 key 的稳定 hash 路由并立即提交 typed 分区任务。
     *
     * @param key 路由 key；null 固定落到 hash 0
     * @param task 业务任务，类型和保序属性进入框架后不得改变
     * 返回: 与内部任务状态共用权威的稳定提交句柄；未启动或故障时返回 REJECTED，使用完应 recycle/close。
     */
    public static Submission submit(Object key, Task task) {
        return INSTANCE.stableSubmission(
                INSTANCE.submitHashed(key == null ? 0 : key.hashCode(), task, false)
        );
    }

    /**
     * 业务作用：按 primitive long key 无装箱路由并立即提交 typed 分区任务。
     *
     * @param key long 路由 key
     * @param task 业务任务，类型和保序属性进入框架后不得改变
     * 返回: 与内部任务状态共用权威的稳定提交句柄；未启动或故障时返回 REJECTED，使用完应 recycle/close。
     */
    public static Submission submit(long key, Task task) {
        return INSTANCE.stableSubmission(INSTANCE.submitHashed(Long.hashCode(key), task, false));
    }

    /**
     * 业务作用：登记对象 key 的延迟分区任务，到期时才读取当时路由并增加对应逻辑计数。
     *
     * @param key 路由 key；null 固定落到 hash 0
     * @param delayMillis 延迟毫秒数；0 按立即提交处理，不能为负数
     * @param task 业务任务
     * 返回: 可在到期前取消的稳定提交句柄；TimingWheel 不可用时返回 REJECTED，使用完应 recycle/close。
     */
    public static Submission submit(Object key, long delayMillis, Task task) {
        return INSTANCE.stableSubmission(
                INSTANCE.submitDelayedHashed(key == null ? 0 : key.hashCode(), delayMillis, task, false)
        );
    }

    /**
     * 业务作用：以 primitive long key 无装箱登记延迟分区任务，到期时再执行完整路由协议。
     *
     * @param key long 路由 key
     * @param delayMillis 延迟毫秒数；0 按立即提交处理，不能为负数
     * @param task 业务任务
     * 返回: 可在到期前取消的稳定提交句柄；TimingWheel 不可用时返回 REJECTED，使用完应 recycle/close。
     */
    public static Submission submit(long key, long delayMillis, Task task) {
        return INSTANCE.stableSubmission(
                INSTANCE.submitDelayedHashed(Long.hashCode(key), delayMillis, task, false)
        );
    }

    /**
     * 业务作用：按对象 key 立即提交无需取消或状态查询的任务，并在物理完成后自动归还内部条目。
     *
     * @param key 路由 key；null 固定落到 hash 0
     * @param task 业务任务
     * 返回: 无返回值；拒绝原因不对调用方保留，适用于 fire-and-forget 零 GC 路径。
     */
    public static void exec(Object key, Task task) {
        INSTANCE.submitHashed(key == null ? 0 : key.hashCode(), task, true);
    }

    /**
     * 业务作用：按 primitive long key 立即提交无需句柄的任务，避免 key 装箱并自动回收内部条目。
     *
     * @param key long 路由 key
     * @param task 业务任务
     * 返回: 无返回值；任务终态且物理摘除后归还对象池。
     */
    public static void exec(long key, Task task) {
        INSTANCE.submitHashed(Long.hashCode(key), task, true);
    }

    /**
     * 业务作用：按对象 key 登记无需取消句柄的延迟任务，复用池化到期动作并在完整收口后自动归池。
     *
     * @param key 路由 key；null 固定落到 hash 0
     * @param delayMillis 延迟毫秒数；0 按立即提交处理，不能为负数
     * @param task 业务任务
     * 返回: 无返回值；调用方不持有 Submission，停机拒绝仍由内部状态机安全处理。
     */
    public static void exec(Object key, long delayMillis, Task task) {
        INSTANCE.submitDelayedHashed(key == null ? 0 : key.hashCode(), delayMillis, task, true);
    }

    /**
     * 业务作用：以 primitive long key 登记 fire-and-forget 延迟任务，避免装箱并自动回收全部内部载体。
     *
     * @param key long 路由 key
     * @param delayMillis 延迟毫秒数；0 按立即提交处理，不能为负数
     * @param task 业务任务
     * 返回: 无返回值；定时包装释放前条目保持回收 hold，禁止迟到回调访问复用对象。
     */
    public static void exec(long key, long delayMillis, Task task) {
        INSTANCE.submitDelayedHashed(Long.hashCode(key), delayMillis, task, true);
    }

    /**
     * 业务作用：在生命周期锁内创建新一代分区槽位，防止半启动状态对 producer 可见。
     *
     * 参数说明: 无。
     * 返回: 无返回值；所有 worker 就绪后才发布 ACCEPTING 快照。
     */
    private void startInternal() {
        this.lifecycleLock.lock();
        try {
            Lifecycle current = this.lifecycle.get();
            if (current.phase == LifecyclePhase.ACCEPTING) return;
            if (current.phase == LifecyclePhase.STOPPING) {
                throw new IllegalStateException("Partition is still stopping");
            }
            if (!TimingWheel.isStarted()) {
                // 延迟入口依赖时间轮的取消与到期驱动，启动顺序错误时不能开放一个能力不完整的分区代次。
                throw new IllegalStateException("TimingWheel must be started before Partition");
            }

            int requested = Integer.getInteger(
                    "nasa.partition.partitions",
                    Runtime.getRuntime().availableProcessors() << 1
            );
            int partitionCount = normalizePartitionCount(requested);
            long epoch = this.lifecycleEpoch.incrementAndGet();
            PartitionSlot[] slots = new PartitionSlot[partitionCount];
            int started = 0;
            try {
                for (int i = 0; i < partitionCount; i++) {
                    PartitionSlot slot = new PartitionSlot(this, epoch, i);
                    slot.startWorker();
                    slots[i] = slot;
                    started++;
                }
            } catch (Throwable failure) {
                // 启动未完整发布时先停掉已创建 worker，避免孤儿 consumer 在后台继续运行。
                for (int i = 0; i < started; i++) slots[i].requestStop();
                for (int i = 0; i < started; i++) slots[i].awaitStopped(DEFAULT_STOP_TIMEOUT_MILLIS);
                throw failure;
            }

            this.failedPartitionCount.set(0);
            this.lifecycle.set(new Lifecycle(epoch, LifecyclePhase.ACCEPTING, slots, partitionCount - 1, null));
        } finally {
            this.lifecycleLock.unlock();
        }
    }

    /**
     * 业务作用：分阶段关闭当前代，只有 producer 和 worker 都已证明收口后才发布 STOPPED。
     *
     * 参数说明: 无。
     * 返回: 已经或本轮完成 STOPPED 发布时返回 true；任何阶段超时返回 false 并保留 STOPPING 快照。
     */
    private boolean stopInternal() {
        this.lifecycleLock.lock();
        try {
            Lifecycle current = this.lifecycle.get();
            if (current.phase == LifecyclePhase.STOPPED) return true;

            Lifecycle stopping = current;
            if (current.phase == LifecyclePhase.ACCEPTING) {
                stopping = new Lifecycle(
                        current.epoch,
                        LifecyclePhase.STOPPING,
                        current.slots,
                        current.mask,
                        current
                );
                // 先关闭全局入口，迟到 producer 登记旧代后复验失败，只会退出而不会再发布任务。
                this.lifecycle.set(stopping);
            }

            this.rejectDelayedEntries("Partition 停机取消尚未到期任务");

            Lifecycle accepting = stopping.predecessor;
            if (accepting != null && !awaitZero(accepting.inflight, stopTimeoutMillis())) {
                log.error("Partition stop timed out waiting for {} global producers", accepting.inflight.get());
                return false;
            }
            // 捕获关闭入口前已登记、但第一次扫描时尚未进入注册表的迟到延迟提交。
            this.rejectDelayedEntries("Partition 停机取消迟到延迟任务");

            for (PartitionSlot slot : stopping.slots) slot.closeSubmissionGate();
            for (PartitionSlot slot : stopping.slots) slot.requestStop();

            boolean allStopped = true;
            long timeoutMillis = stopTimeoutMillis();
            for (PartitionSlot slot : stopping.slots) {
                if (!slot.awaitStopped(timeoutMillis)) allStopped = false;
            }
            if (!allStopped) {
                log.error("Partition stop timed out waiting for one or more workers; lifecycle remains STOPPING");
                return false;
            }

            this.lifecycle.set(Lifecycle.stopped(this.lifecycleEpoch.incrementAndGet()));
            return true;
        } finally {
            this.lifecycleLock.unlock();
        }
    }

    /**
     * 业务作用：在全局与分区两级代次门禁内完成立即提交，防止停机或分区失败后迟到发布。
     *
     * @param keyHash 已计算的业务 key hash
     * @param task 待提交的 typed 任务
     * @param autoRecycle 调用方不接收句柄、允许物理收口后自动归池时为 true
     * 返回: 稳定任务条目；受理成功后可执行/取消，失败时包含明确拒绝原因。
     */
    private TaskEntry submitHashed(int keyHash, Task task, boolean autoRecycle) {
        Objects.requireNonNull(task, "task");
        TaskEntry entry = this.taskEntryPool.get(task, autoRecycle);
        long submissionGeneration = entry.retainRecycleHold();
        try {
            if (!entry.captureContext()) return entry;
            this.routeExisting(keyHash, entry);
            return entry;
        } finally {
            // fire-and-forget 的拒绝路径可能已释放框架 hold；必须等公开入口完全退出后才能复用条目。
            entry.releaseRecycleHold(submissionGeneration);
        }
    }

    /**
     * 业务作用：为需要状态查询或取消能力的提交创建稳定外部句柄，隔离池化条目的跨代 ABA。
     *
     * @param entry 已完成本次提交初始化的内部任务条目
     * 返回: 不参与对象池复用的轻量句柄；调用方释放前持有条目本代的唯一外部回收权。
     */
    private Submission stableSubmission(TaskEntry entry) {
        long generation = entry.recycleGeneration();
        try {
            return new SubmissionHandle(entry, generation);
        } catch (Throwable failure) {
            // 句柄分配失败时调用方不可能再释放条目；立即撤销外部持有，避免错误路径永久占住池容量。
            entry.releaseSubmission(generation);
            throw failure;
        }
    }

    /**
     * 业务作用：登记尚无分区所有权的延迟任务，并把轻量到期回调交给 TimingWheel。
     *
     * @param keyHash 已计算的路由 hash
     * @param delayMillis 延迟毫秒数
     * @param task 业务任务
     * @param autoRecycle 调用方不保留句柄、允许完整收口后自动归池时为 true
     * 返回: DELAYED、REJECTED 或立即提交后的稳定句柄。
     */
    private TaskEntry submitDelayedHashed(
            int keyHash,
            long delayMillis,
            Task task,
            boolean autoRecycle
    ) {
        Objects.requireNonNull(task, "task");
        if (delayMillis < 0L) throw new IllegalArgumentException("delayMillis must not be negative");
        if (delayMillis == 0L) return this.submitHashed(keyHash, task, autoRecycle);

        TaskEntry entry = this.taskEntryPool.get(task, autoRecycle);
        long submissionGeneration = entry.retainRecycleHold();
        try {
            if (!entry.captureContext()) return entry;
            Lifecycle global = this.lifecycle.get();
            if (global.phase != LifecyclePhase.ACCEPTING) {
                entry.reject("Partition 未启动或正在停机");
                return entry;
            }
            global.inflight.incrementAndGet();
            try {
                if (this.lifecycle.get() != global || global.phase != LifecyclePhase.ACCEPTING) {
                    entry.reject("Partition 延迟提交代次已经关闭");
                    return entry;
                }
                if (!TimingWheel.isStarted()) {
                    entry.reject("TimingWheel 尚未启动，无法登记延迟任务");
                    return entry;
                }

                // fire-and-forget 没有单笔取消能力，不创建唯一字符串；稳定 Submission 才为 cancel 安装索引键。
                String unique = autoRecycle
                        ? null
                        : "nasa-partition-delay-" + global.epoch + '-' + this.delayedSequence.incrementAndGet();
                DelayedReference delayedReference = null;
                ActionRecycler expiryAction;
                try {
                    delayedReference = this.delayedReferencePool.get(entry);
                    expiryAction = ActionRecycler.ofRecycle(DELAYED_EXPIRY_ACTION)
                            .refRecycle(0, delayedReference);
                } catch (Throwable failure) {
                    // 包装尚未发布给 TimingWheel，当前线程仍是唯一所有者；初始化失败必须撤销 hold 并明确拒绝。
                    if (delayedReference != null) delayedReference.recycle();
                    entry.reject("初始化延迟池化包装失败: " + failure.getClass().getSimpleName());
                    return entry;
                }
                // 先建立定时包装 hold，再把 DELAYED 暴露给停机/取消线程；否则 fire-and-forget 条目
                // 可能在注册表发布与 hold 增加之间被拒绝并归池，迟到包装会引用复用后的另一笔任务。
                entry.publishDelayed(keyHash, unique);
                this.delayedRegistry.put(entry, Boolean.TRUE);
                try {
                    // ActionRecycler 在正常到期和 TimingWheel 惰性取消路径都会归池，并级联释放条目回调 hold。
                    TimingWheel.exec(delayMillis, unique, expiryAction);
                } catch (Throwable failure) {
                    this.delayedRegistry.remove(entry);
                    // TimingWheel 在参数初始化异常时可能尚未接管 Action；PooledHandle 让该补偿可安全重复。
                    expiryAction.cancelledRecycle();
                    entry.rejectDelayed("TimingWheel 延迟登记失败: " + failure.getClass().getSimpleName());
                }
                return entry;
            } finally {
                global.inflight.decrementAndGet();
            }
        } finally {
            // 延迟包装 hold 与提交调用 hold 相互独立，入口退出不能提前释放尚未触发的定时引用。
            entry.releaseRecycleHold(submissionGeneration);
        }
    }

    /**
     * 业务作用：执行池化延迟动作槽位中的到期引用，把运行期参数从捕获 lambda 改为 Recycler 引用槽。
     *
     * @param action TimingWheel 调用的池化 ActionRecycler
     * 返回: 无返回值；ActionRecycler 的 finally 负责归池并级联释放 DelayedReference。
     */
    private static void runDelayedExpiryAction(ActionRecycler action) {
        DelayedReference reference = action.ref(0);
        INSTANCE.expireDelayed(reference.entry());
    }

    /**
     * 业务作用：到期时竞争 DELAYED 到 ENQUEUEING，并按当前路由提交；取消或停机胜出后回调只回收自身。
     *
     * @param entry TimingWheel 回调持有的稳定提交条目
     * 返回: 无返回值；所有异常在回调内转为 REJECTED，绝不抛回时间轮执行链。
     */
    private void expireDelayed(TaskEntry entry) {
        try {
            if (!entry.beginExpiry()) return;
            this.delayedRegistry.remove(entry);
            this.routeExisting(entry.keyHash, entry);
            entry.applyPendingCancel();
        } catch (Throwable failure) {
            entry.reject("延迟任务到期路由失败: " + failure.getClass().getSimpleName());
            log.error("Partition delayed expiry failed", failure);
        }
    }

    /**
     * 业务作用：停机时原子拒绝注册表中仍为 DELAYED 的任务，不等待最长 delay 自然到期。
     *
     * @param reason 稳定停机拒绝原因
     * 返回: 无返回值；TimingWheel 中迟到轻量回调只会观察终态，不再持有业务任务。
     */
    private void rejectDelayedEntries(String reason) {
        for (TaskEntry entry : this.delayedRegistry.keySet()) {
            if (entry.rejectDelayed(reason)) {
                this.delayedRegistry.remove(entry);
                String unique = entry.delayUnique;
                if (unique != null && TimingWheel.isStarted()) TimingWheel.cancel(unique);
            }
        }
    }

    /**
     * 业务作用：由转移两侧任一健康 worker 帮助提交并发取消，并把长期停留在 MOVING 的任务冻结为可追踪证据。
     *
     * @param observer 本轮执行审计的分区 worker
     * 返回: 本轮至少帮助取消或发布一笔转移故障时返回 true。
     */
    private boolean auditMovingEntries(PartitionSlot observer) {
        if (this.movingRegistry.isEmpty() || this.movingRecycleGate.get()) return false;
        this.movingAuditors.incrementAndGet();
        if (this.movingRecycleGate.get()) {
            this.movingAuditors.decrementAndGet();
            return false;
        }
        boolean progressed = false;
        try {
            int budget = Math.max(1, DEFAULT_DRAIN_BATCH / 32);
            long now = System.nanoTime();
            for (TaskEntry entry : this.movingRegistry.keySet()) {
                if (budget-- <= 0) break;
                long moveStarted = entry.moveStartedNanos;
                if (moveStarted == 0L || !this.movingRegistry.containsKey(entry)) continue;
                if (entry.moveSource != observer && entry.moveTarget != observer) continue;
                if (entry.moveStartedNanos != moveStarted) continue;
                if (entry.cancelRequested && entry.state() == TaskEntry.QUEUED) {
                    // 描述符仍登记时只帮助线性化取消，不提前回收；物理发布方或 consumer 负责最终摘除。
                    progressed |= entry.cancelQueued();
                }
                if (now - moveStarted > transitionTimeoutNanos()
                        && entry.moveStartedNanos == moveStarted
                        && this.movingRegistry.containsKey(entry)) {
                    progressed |= entry.failMove("任务转移超过总时限");
                }
            }
            return progressed;
        } finally {
            this.movingAuditors.decrementAndGet();
        }
    }

    /**
     * 业务作用：在迁移条目撤出注册表后等待所有旧弱一致迭代器退出，建立对象池复用前的 grace period。
     *
     * 参数说明: 无。
     * 返回: 无返回值；返回时后续审计者不可能再取得已撤出的旧代条目引用。
     */
    private void awaitMovingAuditGrace() {
        while (!this.movingRecycleGate.compareAndSet(false, true)) {
            LockSupport.parkNanos(1_000L);
        }
        try {
            while (this.movingAuditors.get() != 0) {
                Thread.onSpinWait();
            }
        } finally {
            this.movingRecycleGate.set(false);
        }
    }

    /**
     * 业务作用：在全局与分区两级代次门禁内路由一个已完成上下文捕获的任务条目。
     *
     * @param keyHash 已计算的业务 key hash
     * @param entry 状态为 ENQUEUEING 的稳定条目
     * 返回: 无返回值；成功后进入唯一队列，失败时发布明确 REJECTED。
     */
    private void routeExisting(int keyHash, TaskEntry entry) {

        Lifecycle global = this.lifecycle.get();
        if (global.phase != LifecyclePhase.ACCEPTING) {
            entry.reject("Partition 未启动或正在停机");
            return;
        }

        global.inflight.incrementAndGet();
        try {
            if (this.lifecycle.get() != global || global.phase != LifecyclePhase.ACCEPTING) {
                entry.reject("Partition 提交代次已经关闭");
                return;
            }

            int slotIndex = spread(keyHash) & global.mask;
            PartitionSlot slot = global.slots[slotIndex];
            SlotLifecycle slotLifecycle = slot.lifecycle.get();
            if (slotLifecycle.phase != SlotPhase.ACCEPTING) {
                entry.reject(slot.failureReason("原始分区不可接收任务"));
                return;
            }

            slotLifecycle.inflight.incrementAndGet();
            try {
                if (slot.lifecycle.get() != slotLifecycle || slotLifecycle.phase != SlotPhase.ACCEPTING) {
                    entry.reject(slot.failureReason("原始分区提交代次已经关闭"));
                    return;
                }
                slot.enqueue(entry);
            } finally {
                slotLifecycle.inflight.decrementAndGet();
            }
        } finally {
            global.inflight.decrementAndGet();
        }
    }

    /**
     * 业务作用：把配置的任意正整数分区数向上归一化为 2 的幂，保证 mask 路由不会产生越界或偏斜。
     *
     * @param requested 配置请求的分区数
     * 返回: 1 到 2^30 之间的 2 的幂。
     */
    private static int normalizePartitionCount(int requested) {
        int count = Math.max(1, requested);
        if (count > (1 << 30)) {
            throw new IllegalArgumentException("nasa.partition.partitions must be <= " + (1 << 30));
        }
        if (count == 1) return 1;
        return Integer.highestOneBit(count - 1) << 1;
    }

    /**
     * 业务作用：扩散业务 hash 的高低位并清除符号位，降低低位重复造成的热点分区。
     *
     * @param hash 原始业务 hash
     * 返回: 可安全与非负 mask 做按位与的扩散值。
     */
    private static int spread(int hash) {
        return (hash ^ (hash >>> 16)) & 0x7fffffff;
    }

    /**
     * 业务作用：以短 park 分轮等待精确在途计数归零，避免控制线程长时间占用 CPU 自旋。
     *
     * @param counter 只接受旧代 producer 退出的精确计数
     * @param timeoutMillis 总等待上限
     * 返回: 上限内观察到零返回 true；超时返回 false，调用方不得继续破坏性清理。
     */
    private static boolean awaitZero(AtomicInteger counter, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (counter.get() != 0) {
            if (System.nanoTime() >= deadline) return false;
            LockSupport.parkNanos(100_000L);
        }
        return true;
    }

    /**
     * 业务作用：读取统一停机时限，约束 producer 收口和 worker 排空的最长单阶段等待。
     *
     * 参数说明: 无。
     * 返回: 大于等于 1ms 的停机阶段时限。
     */
    private static long stopTimeoutMillis() {
        return Math.max(1L, Long.getLong("nasa.partition.stop-timeout-ms", DEFAULT_STOP_TIMEOUT_MILLIS));
    }

    /**
     * 业务作用：读取分区绝对空闲阈值，统一盗洞续租、窃取候选和后续归还判断口径。
     *
     * 参数说明: 无。
     * 返回: 大于等于 0 的逻辑任务数量阈值。
     */
    private static int idleTaskThreshold() {
        return Math.max(0, Integer.getInteger("nasa.partition.idle-task-threshold", 1));
    }

    /**
     * 业务作用：读取迁移、归还和 catch-up 单阶段总时限，超时后必须失败关闭而不能永久悬挂。
     *
     * 参数说明: 无。
     * 返回: 大于等于 1ms 的单阶段纳秒时限。
     */
    private static long transitionTimeoutNanos() {
        long millis = Math.max(1L, Long.getLong("nasa.partition.transition-timeout-ms", 30_000L));
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    /**
     * 业务作用：安全调用被拒绝/取消任务的对象池回收钩子，避免框架不再执行时出现脱池泄漏。
     *
     * @param task 已确定不会再执行的业务任务
     * 返回: 无返回值；单笔回收异常只记录，不得破坏后续故障收口。
     */
    @SuppressWarnings("rawtypes")
    private static void recycleDropped(Task task) {
        if (task instanceof ObjectPool.Recycler recycler) {
            try {
                recycler.cancelledRecycle();
            } catch (Throwable failure) {
                log.error("Partition cancelledRecycle failed", failure);
            }
        }
    }

    private enum LifecyclePhase {
        STOPPED,
        ACCEPTING,
        STOPPING
    }

    private enum SlotPhase {
        ACCEPTING,
        STOPPING,
        FAILED
    }

    private enum StrictRouteState {
        LOCAL,
        MIGRATING,
        STOLEN,
        RETURN_PREPARE,
        RETURNING,
        LOCAL_CATCHUP,
        STOLEN_CATCHUP,
        FAILED
    }

    /**
     * 全局不可变生命周期快照；inflight 只属于本代，predecessor 仅在 STOPPING 时指向被关闭的 ACCEPTING 代。
     */
    private static final class Lifecycle {

        final long epoch;
        final LifecyclePhase phase;
        final PartitionSlot[] slots;
        final int mask;
        final AtomicInteger inflight = new AtomicInteger();
        final Lifecycle predecessor;

        /**
         * 业务作用：创建一次性发布的全局生命周期快照，隔离新旧代 producer 计数。
         *
         * @param epoch 生命周期代次
         * @param phase 当前阶段
         * @param slots 本代稳定槽位数组
         * @param mask 路由掩码
         * @param predecessor 停机时被关闭的前一代；其他阶段为 null
         * 返回: 构造完成后字段不可变，inflight 仅由持有本快照的 producer 使用。
         */
        Lifecycle(long epoch, LifecyclePhase phase, PartitionSlot[] slots, int mask, Lifecycle predecessor) {
            this.epoch = epoch;
            this.phase = phase;
            this.slots = slots;
            this.mask = mask;
            this.predecessor = predecessor;
        }

        /**
         * 业务作用：创建不持有任何队列和 worker 的 STOPPED 快照，防止旧槽位被新提交重新引用。
         *
         * @param epoch 停止状态代次
         * 返回: 空槽位、mask 为 0 的 STOPPED 快照。
         */
        static Lifecycle stopped(long epoch) {
            return new Lifecycle(epoch, LifecyclePhase.STOPPED, new PartitionSlot[0], 0, null);
        }
    }

    /**
     * 分区级不可变生命周期快照；共享原队列或 worker 故障后通过替换快照关闭新提交。
     */
    private static final class SlotLifecycle {

        final long epoch;
        final SlotPhase phase;
        final AtomicInteger inflight = new AtomicInteger();

        /**
         * 业务作用：创建分区级提交代次，使关闭方只等待已经关闭的旧 producer 集合。
         *
         * @param epoch 分区生命周期代次
         * @param phase 分区提交阶段
         * 返回: 构造完成后阶段不可变，inflight 独立于其他代次。
         */
        SlotLifecycle(long epoch, SlotPhase phase) {
            this.epoch = epoch;
            this.phase = phase;
        }
    }

    /**
     * 严格类型的不可变路由快照；直投同一盗洞的代次共享 producer 门禁，其他状态按代隔离。
     */
    private static final class StrictRoute {

        final long epoch;
        final StrictRouteState state;
        final StrictTunnel tunnel;
        final StrictReturnContext returnContext;
        final AtomicInteger inflight;
        final AtomicReference<Object> executionGate = new AtomicReference<>(GATE_IDLE);

        /**
         * 业务作用：创建严格类型路由代次，把状态、正确作用域的 producer 门禁和执行门禁绑定为稳定快照。
         *
         * @param epoch 路由代次
         * @param state 路由控制状态
         * @param tunnel 非 LOCAL 状态绑定的严格盗洞；LOCAL/FAILED 可为 null
         * @param returnContext 归还相关状态绑定的类型级上下文；其他状态为 null
         * 返回: 构造完成后路由字段不可变。
         */
        StrictRoute(
                long epoch,
                StrictRouteState state,
                StrictTunnel tunnel,
                StrictReturnContext returnContext
        ) {
            this.epoch = epoch;
            this.state = state;
            this.tunnel = tunnel;
            this.returnContext = returnContext;
            boolean directTunnelRoute = state == StrictRouteState.MIGRATING
                    || state == StrictRouteState.STOLEN
                    || state == StrictRouteState.STOLEN_CATCHUP;
            // 所有直投代次共用同一门禁，归还切到 RETURN_PREPARE 后新流量改用独立门禁并落到 staging；
            // 因此旧直投 producer 可完整归零，又不会被持续到达的 staging producer 饿死。
            this.inflight = directTunnelRoute && tunnel != null
                    ? tunnel.producerInflight
                    : new AtomicInteger();
        }
    }

    /**
     * 可帮助完成的严格迁移声明；发布到旧 LOCAL executionGate 前已包含完整新路由。
     */
    private static final class MigrationClaim {

        final StrictRoute expectedRoute;
        final StrictRoute migratingRoute;

        /**
         * 业务作用：冻结严格盗洞安装所需的全部信息，避免申请线程取得门禁后暂停造成永久阻塞。
         *
         * @param expectedRoute 被替换的 LOCAL 路由
         * @param migratingRoute 已完整构造的 MIGRATING 路由
         * 返回: 构造完成后字段不可变，任意帮助者都可执行同一组 CAS。
         */
        MigrationClaim(StrictRoute expectedRoute, StrictRoute migratingRoute) {
            this.expectedRoute = expectedRoute;
            this.migratingRoute = migratingRoute;
        }
    }

    /**
     * 单个原始分区内的任务类型策略和精确逻辑数量。
     */
    private interface LogicalCounter {

        /**
         * 业务作用：在任务取得本逻辑所有者之前增加精确计数，防止控制面观察到漏计窗口。
         *
         * 参数说明: 无。
         * 返回: 增加后的精确逻辑数量。
         */
        int increment();

        /**
         * 业务作用：在执行、取消或迁出完成后减少本逻辑所有者计数，且每个任务只能成功一次。
         *
         * 参数说明: 无。
         * 返回: 减少后的精确逻辑数量。
         */
        int decrement();
    }

    private static final class TypeState implements LogicalCounter {

        final int taskType;
        final boolean strictOrder;
        final PartitionSlot source;
        final AtomicInteger logicalCount = new AtomicInteger();
        final AtomicReference<StrictRoute> strictRoute;
        final NonStrictTunnelGroup nonStrictGroup;
        final AtomicBoolean failed = new AtomicBoolean();

        /**
         * 业务作用：原子注册后固定任务类型的保序策略，避免同一类型混用两套路由协议。
         *
         * @param taskType 稳定任务类型
         * @param strictOrder 是否严格 FIFO
         * @param source 固定原始分区
         * 返回: 严格类型同时创建首个 LOCAL 路由，非严格类型不创建严格门禁。
         */
        TypeState(int taskType, boolean strictOrder, PartitionSlot source) {
            this.taskType = taskType;
            this.strictOrder = strictOrder;
            this.source = source;
            this.strictRoute = strictOrder
                    ? new AtomicReference<>(new StrictRoute(0L, StrictRouteState.LOCAL, null, null))
                    : null;
            this.nonStrictGroup = strictOrder ? null : new NonStrictTunnelGroup(source, this);
        }

        /**
         * 业务作用：在任务初次获得逻辑所有权前精确增加本类型数量。
         *
         * 参数说明: 无。
         * 返回: 增加后的精确逻辑数量。
         */
        @Override
        public int increment() {
            this.source.localTaskCount.incrementAndGet();
            return this.logicalCount.incrementAndGet();
        }

        /**
         * 业务作用：在执行完成、取消或发布回滚的唯一终态路径精确减少本类型数量。
         *
         * 参数说明: 无。
         * 返回: 减少后的精确数量；出现负数表示计数不变量被破坏。
         */
        @Override
        public int decrement() {
            this.source.localTaskCount.decrementAndGet();
            return this.logicalCount.decrementAndGet();
        }
    }

    /**
     * 非严格盗洞的不可变活动快照；producer 只读数组，新增和摘除通过 CAS 发布新实例。
     */
    private static final class NonStrictRoute {

        final long epoch;
        final NonStrictTunnel[] tunnels;

        /**
         * 业务作用：创建一代非严格盗洞活动数组，防止 producer 观察到原地修改的半成品。
         *
         * @param epoch 路由代次
         * @param tunnels 本代完整活动盗洞数组
         * 返回: 构造完成后数组引用不再修改。
         */
        NonStrictRoute(long epoch, NonStrictTunnel[] tunnels) {
            this.epoch = epoch;
            this.tunnels = tunnels;
        }
    }

    /**
     * 非严格盗洞组；同一原始分区和任务类型可同时向多个目标分区开放盗洞。
     */
    private static final class NonStrictTunnelGroup {

        final PartitionSlot source;
        final TypeState typeState;
        final AtomicReference<NonStrictRoute> route =
                new AtomicReference<>(new NonStrictRoute(0L, new NonStrictTunnel[0]));
        final AtomicInteger producerCursor = new AtomicInteger();
        int sourceDispatchCursor;

        /**
         * 业务作用：创建空盗洞组，新任务在首个活动盗洞发布前继续进入原始队列。
         *
         * @param source 固定原始分区
         * @param typeState 非严格类型状态
         * 返回: 初始活动快照为空。
         */
        NonStrictTunnelGroup(PartitionSlot source, TypeState typeState) {
            this.source = source;
            this.typeState = typeState;
        }

        /**
         * 业务作用：为 MP 新任务轮询选择并登记一个有效盗洞，过期或关闭候选会被协助摘除。
         *
         * 参数说明: 无。
         * 返回: 已增加 producer 在途计数的盗洞；没有可用候选时返回 null。
         */
        NonStrictTunnel selectForProducer() {
            NonStrictRoute snapshot = this.route.get();
            NonStrictTunnel[] tunnels = snapshot.tunnels;
            int length = tunnels.length;
            if (length == 0) return null;
            int start = Integer.remainderUnsigned(this.producerCursor.getAndIncrement(), length);
            long now = System.nanoTime();
            for (int i = 0; i < length; i++) {
                NonStrictTunnel tunnel = tunnels[(start + i) % length];
                if (tunnel.tryEnterProducer(now)) return tunnel;
                if (tunnel.isExpired(now)) tunnel.expireAndDetach();
            }
            return null;
        }

        /**
         * 业务作用：由原分区 worker 在自己与活动盗洞之间轮转存量任务，保留原分区执行份额。
         *
         * 参数说明: 无。
         * 返回: 已登记 producer 在途计数的目标盗洞；本轮轮到原分区或无候选时返回 null。
         */
        NonStrictTunnel selectForSourceDispatch() {
            NonStrictRoute snapshot = this.route.get();
            NonStrictTunnel[] tunnels = snapshot.tunnels;
            if (tunnels.length == 0) return null;
            int choice = Integer.remainderUnsigned(this.sourceDispatchCursor++, tunnels.length + 1);
            if (choice == 0) return null;
            long now = System.nanoTime();
            for (int i = 0; i < tunnels.length; i++) {
                NonStrictTunnel tunnel = tunnels[(choice - 1 + i) % tunnels.length];
                if (tunnel.tryEnterProducer(now)) return tunnel;
                if (tunnel.isExpired(now)) tunnel.expireAndDetach();
            }
            return null;
        }

        /**
         * 业务作用：CAS 发布新增盗洞，避免与并发过期摘除互相覆盖。
         *
         * @param tunnel 已在目标分区登记、尚未对 producer 可见的盗洞
         * 返回: 本次成功加入返回 true；同一实例已存在时返回 false。
         */
        boolean add(NonStrictTunnel tunnel) {
            while (true) {
                NonStrictRoute current = this.route.get();
                for (NonStrictTunnel existing : current.tunnels) {
                    if (existing == tunnel) return false;
                    if (existing.target == tunnel.target && existing.isOpen()) return false;
                }
                NonStrictTunnel[] updated = new NonStrictTunnel[current.tunnels.length + 1];
                System.arraycopy(current.tunnels, 0, updated, 0, current.tunnels.length);
                updated[current.tunnels.length] = tunnel;
                if (this.route.compareAndSet(current, new NonStrictRoute(current.epoch + 1, updated))) return true;
            }
        }

        /**
         * 业务作用：幂等地从最新活动快照摘除过期或故障盗洞，不覆盖并发新增的其他目标。
         *
         * @param tunnel 不再接收新任务的盗洞
         * 返回: 盗洞已经不在活动快照或本次成功摘除时返回 true。
         */
        boolean remove(NonStrictTunnel tunnel) {
            while (true) {
                NonStrictRoute current = this.route.get();
                int found = -1;
                for (int i = 0; i < current.tunnels.length; i++) {
                    if (current.tunnels[i] == tunnel) {
                        found = i;
                        break;
                    }
                }
                if (found < 0) return true;
                NonStrictTunnel[] updated = new NonStrictTunnel[current.tunnels.length - 1];
                System.arraycopy(current.tunnels, 0, updated, 0, found);
                System.arraycopy(current.tunnels, found + 1, updated, found, updated.length - found);
                if (this.route.compareAndSet(current, new NonStrictRoute(current.epoch + 1, updated))) return true;
            }
        }

        /**
         * 业务作用：判断当前活动快照是否已经把本类型连接到指定目标，供源 worker 跳过重复配对并继续选择其他非严格类型。
         *
         * @param target 待接收任务的目标分区
         * 返回: 已存在指向该目标的 OPEN 盗洞时返回 true；否则返回 false。
         */
        boolean hasOpenTarget(PartitionSlot target) {
            NonStrictRoute current = this.route.get();
            for (NonStrictTunnel tunnel : current.tunnels) {
                if (tunnel.target == target && tunnel.isOpen()) return true;
            }
            return false;
        }
    }

    /**
     * 非严格盗洞租约；对象不可变，续租和过期标记只对实际读取的旧对象做 CAS。
     */
    private static final class TunnelLease {

        static final TunnelLease EXPIRED = new TunnelLease(Long.MIN_VALUE, -1L, true);

        final long deadlineNanos;
        final long epoch;
        final boolean expired;

        /**
         * 业务作用：创建单调时钟租约，防止墙上时间跳变影响盗洞生存期。
         *
         * @param deadlineNanos 单调时钟截止点
         * @param epoch 租约代次
         * @param expired 是否为不可续租过期标记
         * 返回: 构造完成后字段不可变。
         */
        TunnelLease(long deadlineNanos, long epoch, boolean expired) {
            this.deadlineNanos = deadlineNanos;
            this.epoch = epoch;
            this.expired = expired;
        }
    }

    /**
     * 单个非严格盗洞；多 producer 写入，只有目标分区 worker 消费。
     */
    private static final class NonStrictTunnel implements LogicalCounter {

        static final int OPEN = 0;
        static final int DRAINING = 1;
        static final int CLOSED = 2;
        static final int FAILED = 3;

        final NonStrictTunnelGroup group;
        final PartitionSlot source;
        final PartitionSlot target;
        final TypeState typeState;
        final MPSCLinkedQueue<TaskEntry> queue = new MPSCLinkedQueue<>();
        final MPSCLinkedQueue.ConsumerCursor consumerCursor = new MPSCLinkedQueue.ConsumerCursor();
        final AtomicInteger state = new AtomicInteger(OPEN);
        final AtomicInteger inflight = new AtomicInteger();
        final AtomicInteger logicalCount = new AtomicInteger();
        final AtomicReference<TunnelLease> lease;
        final AtomicBoolean failureRecorded = new AtomicBoolean();

        /**
         * 业务作用：创建从原分区直达空闲目标分区的非严格任务通道，尚未加入活动快照前不接收任务。
         *
         * @param group 所属原分区与类型的盗洞组
         * @param target 唯一消费本盗洞的目标分区
         * 返回: 初始状态 OPEN，并持有首个单调时钟租约。
         */
        NonStrictTunnel(NonStrictTunnelGroup group, PartitionSlot target) {
            this.group = group;
            this.source = group.source;
            this.target = target;
            this.typeState = group.typeState;
            long now = System.nanoTime();
            this.lease = new AtomicReference<>(new TunnelLease(now + DEFAULT_TUNNEL_LEASE_NANOS, 0L, false));
        }

        /**
         * 业务作用：登记 producer 后复验状态和租约，覆盖旧快照读者与摘除并发的发布窗口。
         *
         * @param now 当前单调时钟值
         * 返回: 本次 producer 可以发布时返回 true；调用方结束后必须调用 {@link #exitProducer()}。
         */
        boolean tryEnterProducer(long now) {
            TunnelLease currentLease = this.lease.get();
            if (this.state.get() != OPEN || currentLease.expired || now >= currentLease.deadlineNanos) return false;
            this.inflight.incrementAndGet();
            currentLease = this.lease.get();
            if (this.state.get() == OPEN && !currentLease.expired && now < currentLease.deadlineNanos) return true;
            this.inflight.decrementAndGet();
            return false;
        }

        /**
         * 业务作用：在队列 offer 完成后以原子写退出盗洞 producer 临界区，使关闭方可安全观察归零。
         *
         * 参数说明: 无。
         * 返回: 无返回值；必须与成功的 tryEnterProducer 一一配对。
         */
        void exitProducer() {
            this.inflight.decrementAndGet();
        }

        /**
         * 业务作用：判断当前实际租约是否已经过期，供 producer 和目标 worker 协助关闭。
         *
         * @param now 当前单调时钟值
         * 返回: 已发布过期标记或截止时间已到时返回 true。
         */
        boolean isExpired(long now) {
            TunnelLease current = this.lease.get();
            return current.expired || now >= current.deadlineNanos;
        }

        /**
         * 业务作用：按实际读取租约 CAS 不可续租标记，并幂等推进 DRAINING 与活动快照摘除。
         *
         * 参数说明: 无。
         * 返回: 无返回值；已被其他线程续租时不会误删新租约。
         */
        void expireAndDetach() {
            TunnelLease observed = this.lease.get();
            long now = System.nanoTime();
            if (!observed.expired && now < observed.deadlineNanos) return;
            if (!observed.expired && !this.lease.compareAndSet(observed, TunnelLease.EXPIRED)) return;
            this.state.compareAndSet(OPEN, DRAINING);
            this.group.remove(this);
            this.target.wakeWorker();
        }

        /**
         * 业务作用：由仍空闲、健康且确认盗洞仍在使用的目标 worker CAS 发布新租约；过期标记一旦出现就不得抢回 OPEN。
         *
         * @param now 当前单调时钟值
         * 返回: 成功续租返回 true；盗洞已过期或不再 OPEN 时返回 false。
         */
        boolean renew(long now) {
            if (this.state.get() != OPEN) return false;
            TunnelLease observed = this.lease.get();
            if (observed.expired) return false;
            TunnelLease renewed = new TunnelLease(now + DEFAULT_TUNNEL_LEASE_NANOS, observed.epoch + 1, false);
            return this.lease.compareAndSet(observed, renewed);
        }

        /**
         * 业务作用：标记专属盗洞队列故障并从活动路由摘除，保留队列中的未执行任务证据。
         *
         * @param reason 故障原因
         * @param failure 原始异常；没有时为 null
         * 返回: 无返回值；不会扩大为原始分区共享队列故障。
         */
        void fail(String reason, Throwable failure) {
            this.state.set(FAILED);
            this.lease.set(TunnelLease.EXPIRED);
            this.group.remove(this);
            if (this.failureRecorded.compareAndSet(false, true)) {
                this.target.failedNonStrictTunnels.offer(this);
                this.target.inboundNonStrictTunnels.remove(this);
            }
            if (failure == null) {
                log.error("Non-strict tunnel {} -> {} taskType {} FAILED: {}",
                        this.source.slot, this.target.slot, this.typeState.taskType, reason);
            } else {
                log.error("Non-strict tunnel {} -> {} taskType {} FAILED: {}",
                        this.source.slot, this.target.slot, this.typeState.taskType, reason, failure);
            }
        }

        /**
         * 业务作用：判断盗洞是否仍可出现在活动快照中。
         *
         * 参数说明: 无。
         * 返回: 状态为 OPEN 时返回 true。
         */
        boolean isOpen() {
            return this.state.get() == OPEN;
        }

        /**
         * 业务作用：在任务进入盗洞前增加目标分区和本盗洞精确逻辑计数。
         *
         * 参数说明: 无。
         * 返回: 增加后的盗洞逻辑数量。
         */
        @Override
        public int increment() {
            this.target.tunnelTaskCount.incrementAndGet();
            return this.logicalCount.incrementAndGet();
        }

        /**
         * 业务作用：在盗洞任务完成、取消或发布回滚时减少目标分区和本盗洞精确逻辑计数。
         *
         * 参数说明: 无。
         * 返回: 减少后的盗洞逻辑数量。
         */
        @Override
        public int decrement() {
            this.target.tunnelTaskCount.decrementAndGet();
            return this.logicalCount.decrementAndGet();
        }
    }

    /**
     * 严格类型盗洞；存量和增量使用独立 FIFO，目标 worker 在迁移完成前只能消费存量前缀。
     */
    private static final class StrictTunnel implements LogicalCounter {

        /** 当前负载不要求归还严格执行权。 */
        static final int RETURN_NOT_REQUIRED = 0;
        /** 目标存在足够的其他竞争负载，需要归还严格执行权。 */
        static final int RETURN_FOR_PRESSURE = 1;
        /** 当前盗洞持续为空，需要释放临时执行权和通道资源。 */
        static final int RETURN_FOR_EMPTY = 2;

        final PartitionSlot source;
        final PartitionSlot target;
        final TypeState typeState;
        final StrictRoute oldLocalRoute;
        final MPSCLinkedQueue<TaskEntry> stockQueue = new MPSCLinkedQueue<>();
        final MPSCLinkedQueue<TaskEntry> incrementalQueue = new MPSCLinkedQueue<>();
        final MPSCLinkedQueue.ConsumerCursor stockCursor = new MPSCLinkedQueue.ConsumerCursor();
        final MPSCLinkedQueue.ConsumerCursor incrementalCursor = new MPSCLinkedQueue.ConsumerCursor();
        /** 仅统计跨代直投本盗洞的 producer；改投 staging 后不再增加，保证归还边界可收敛。 */
        final AtomicInteger producerInflight = new AtomicInteger();
        final AtomicInteger logicalCount = new AtomicInteger();
        /** 每次严格任务进入盗洞前递增，用于识别两次空闲观察之间发生过的短任务。 */
        final AtomicLong activityEpoch = new AtomicLong();
        final AtomicBoolean failed = new AtomicBoolean();

        volatile long sourceBoundary = -1L;
        volatile boolean migrationComplete;
        /** 首次关闭严格盗洞的稳定原因，日志后端缺失时仍可由诊断和恢复流程读取。 */
        volatile String failureReason;
        int returnObservationMode;
        int returnObservationCount;
        long returnObservedActivityEpoch = -1L;
        volatile long transitionStartedNanos = System.nanoTime();

        /**
         * 业务作用：创建严格类型的唯一目标通道，保存旧 LOCAL 代次以封住存量 producer 边界。
         *
         * @param source 原始分区
         * @param target 取得临时执行权的目标分区
         * @param typeState 严格类型状态
         * @param oldLocalRoute 被关闭的 LOCAL 路由快照
         * 返回: 初始边界未知且迁移尚未完成，目标只能等待存量队列发布。
         */
        StrictTunnel(
                PartitionSlot source,
                PartitionSlot target,
                TypeState typeState,
                StrictRoute oldLocalRoute
        ) {
            this.source = source;
            this.target = target;
            this.typeState = typeState;
            this.oldLocalRoute = oldLocalRoute;
        }

        /**
         * 业务作用：在严格任务进入存量或增量队列前记录活动代次，并增加目标分区和本盗洞精确计数。
         *
         * 参数说明: 无。
         * 返回: 增加后的严格盗洞逻辑数量；活动代次同步前进一次，用于重置空闲归还观察。
         */
        @Override
        public int increment() {
            // 先记录一次真实提交尝试，使空盗洞归还观察不会跨过并发到达又快速完成的任务。
            this.activityEpoch.incrementAndGet();
            this.target.tunnelTaskCount.incrementAndGet();
            return this.logicalCount.incrementAndGet();
        }

        /**
         * 业务作用：在严格盗洞任务完成、取消或发布回滚时减少目标分区和本盗洞精确计数。
         *
         * 参数说明: 无。
         * 返回: 减少后的严格盗洞逻辑数量。
         */
        @Override
        public int decrement() {
            this.target.tunnelTaskCount.decrementAndGet();
            return this.logicalCount.decrementAndGet();
        }

        /**
         * 业务作用：冻结严格类型两侧执行权并保留两个 FIFO，任何发布或边界故障都不得回退 LOCAL。
         *
         * @param reason 故障原因
         * @param evidence 已离开公共队列的任务；没有时为 null
         * 返回: 无返回值；首次故障把盗洞移出目标正常调度并登记恢复证据。
         */
        void fail(String reason, TaskEntry evidence) {
            if (!this.failed.compareAndSet(false, true)) return;
            this.failureReason = reason;
            this.source.failType(this.typeState, reason, evidence);
            this.target.inboundStrictTunnels.remove(this);
            this.target.failedStrictTunnels.offer(this);
            this.source.wakeWorker();
            this.target.wakeWorker();
        }
    }

    /**
     * 单个严格类型独占的归还上下文；暂存队列和两个私有 FIFO 不与其他类型共享。
     */
    private static final class StrictReturnContext implements LogicalCounter {

        final StrictTunnel tunnel;
        final TypeState typeState;
        final StrictRoute stolenRoute;
        final int triggerMode;
        final long triggerActivityEpoch;
        final MPSCLinkedQueue<TaskEntry> stagingQueue = new MPSCLinkedQueue<>();
        final MPSCLinkedQueue.ConsumerCursor stagingCursor = new MPSCLinkedQueue.ConsumerCursor();
        final AtomicInteger stagingCount = new AtomicInteger();
        final ArrayDeque<TaskEntry> returnPending = new ArrayDeque<>();
        final ArrayDeque<TaskEntry> postReturnPending = new ArrayDeque<>();

        volatile StrictRoute prepareRoute;
        volatile StrictRoute returningRoute;
        volatile long tunnelBoundary = -1L;
        volatile long localBoundary = -1L;
        volatile long stagingBoundary = -1L;
        volatile long phaseStartedNanos = System.nanoTime();

        /**
         * 业务作用：为一次严格归还创建专属第三落点和私有 FIFO，分支确定前 staging 没有 consumer。
         *
         * @param tunnel 当前持有严格执行权的盗洞
         * @param stolenRoute 被 RETURN_PREPARE 关闭的 STOLEN 路由代次
         * @param triggerMode 发起归还时满足的负载或空闲触发模式
         * @param triggerActivityEpoch 发起归还时盗洞最后一次任务活动代次
         * 返回: 三个排他边界初始未知，暂存计数为零。
         */
        StrictReturnContext(
                StrictTunnel tunnel,
                StrictRoute stolenRoute,
                int triggerMode,
                long triggerActivityEpoch
        ) {
            this.tunnel = tunnel;
            this.typeState = tunnel.typeState;
            this.stolenRoute = stolenRoute;
            this.triggerMode = triggerMode;
            this.triggerActivityEpoch = triggerActivityEpoch;
        }

        /**
         * 业务作用：在归还窗口任务进入 staging 前增加独立精确计数，不提前归属原分区或目标分区。
         *
         * 参数说明: 无。
         * 返回: 增加后的暂存任务数。
         */
        @Override
        public int increment() {
            return this.stagingCount.incrementAndGet();
        }

        /**
         * 业务作用：暂存任务被最终 consumer 接管、取消或发布回滚时只减少一次独立计数。
         *
         * 参数说明: 无。
         * 返回: 减少后的暂存任务数。
         */
        @Override
        public int decrement() {
            return this.stagingCount.decrementAndGet();
        }
    }

    /**
     * 目标分区复用的窃取请求；pending 防止同一空闲 worker 向多个源分区并发重复申请。
     */
    private static final class StealRequest {

        final PartitionSlot target;
        final AtomicBoolean pending = new AtomicBoolean();

        /**
         * 业务作用：创建绑定空闲目标分区的可复用控制请求，避免每轮空闲探测分配新对象。
         *
         * @param target 希望接管任务的空闲分区
         * 返回: 初始未挂入任何源分区控制队列。
         */
        StealRequest(PartitionSlot target) {
            this.target = target;
        }
    }

    /**
     * 单个原始分区槽位；主 MPSC 队列始终只由本槽位 worker 消费。
     */
    private static final class PartitionSlot {

        private final Partition partition;
        private final long generation;
        private final int slot;
        private final MPSCLinkedQueue<TaskEntry> queue = new MPSCLinkedQueue<>();
        private final MPSCLinkedQueue.ConsumerCursor consumerCursor = new MPSCLinkedQueue.ConsumerCursor();
        private final MPSCLinkedQueue<StealRequest> controlQueue = new MPSCLinkedQueue<>();
        private final ConcurrentHashMap<Integer, TypeState> typeStates = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<NonStrictTunnel, Boolean> inboundNonStrictTunnels = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<StrictTunnel, Boolean> inboundStrictTunnels = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<NonStrictTunnel> failedNonStrictTunnels = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<StrictTunnel> failedStrictTunnels = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<TaskEntry> failedEvidence = new ConcurrentLinkedQueue<>();
        private final AtomicInteger localTaskCount = new AtomicInteger();
        private final AtomicInteger tunnelTaskCount = new AtomicInteger();
        private final AtomicReference<SlotLifecycle> lifecycle;
        private final AtomicLong lifecycleEpoch = new AtomicLong();
        private final AtomicInteger signal = new AtomicInteger();
        private final AtomicBoolean failureCounted = new AtomicBoolean();
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final StealRequest stealRequest;

        private volatile boolean running;
        private volatile Thread worker;
        private volatile String failureReason;
        private long nextStealAttemptNanos;
        /**
         * 业务作用：创建一代分区槽位及其唯一主队列，尚未启动 worker 前不对 producer 发布。
         *
         * @param partition 所属分区子系统
         * @param generation 全局启动代次
         * @param slot 固定分区号
         * 返回: 初始提交状态为 ACCEPTING，worker 尚未启动。
         */
        PartitionSlot(Partition partition, long generation, int slot) {
            this.partition = partition;
            this.generation = generation;
            this.slot = slot;
            this.lifecycle = new AtomicReference<>(new SlotLifecycle(0L, SlotPhase.ACCEPTING));
            this.stealRequest = new StealRequest(this);
        }

        /**
         * 业务作用：启动绑定本槽位的唯一虚拟线程 consumer，并在返回前保存可唤醒线程引用。
         *
         * 参数说明: 无。
         * 返回: 无返回值；worker 启动后持续遵守单 consumer 约束。
         */
        void startWorker() {
            this.running = true;
            this.worker = Thread.ofVirtual()
                    .name("Virtual-Partition-" + this.generation + '-' + this.slot)
                    .start(this::runWorker);
        }

        /**
         * 业务作用：在类型策略和路由代次门禁内把任务发布到本槽位主队列。
         *
         * @param entry 已通过全局和分区级提交复验的稳定任务条目
         * 返回: 无返回值；成功后条目为 QUEUED，失败则发布 REJECTED 并完整回滚。
         */
        void enqueue(TaskEntry entry) {
            Task task = entry.task();
            int taskType;
            boolean strictOrder;
            try {
                taskType = task.taskType();
                strictOrder = task.strictOrder();
            } catch (Throwable failure) {
                entry.reject("读取任务类型策略失败: " + failure.getClass().getSimpleName());
                return;
            }

            TypeState typeState = this.typeStates.get(taskType);
            if (typeState == null) {
                TypeState candidate = new TypeState(taskType, strictOrder, this);
                TypeState raced = this.typeStates.putIfAbsent(taskType, candidate);
                typeState = raced == null ? candidate : raced;
            }
            if (typeState.strictOrder != strictOrder) {
                entry.reject("同一 taskType 不能混用 strictOrder: " + taskType);
                return;
            }
            if (typeState.failed.get()) {
                entry.reject("任务类型已经 FAILED: " + taskType);
                return;
            }

            if (!strictOrder) {
                NonStrictTunnel tunnel = typeState.nonStrictGroup.selectForProducer();
                if (tunnel != null) {
                    try {
                        this.enqueueDirectTunnel(entry, task, typeState, tunnel);
                    } finally {
                        tunnel.exitProducer();
                    }
                    return;
                }
            }

            StrictRoute route = null;
            if (strictOrder) {
                while (true) {
                    route = typeState.strictRoute.get();
                    if (route.state == StrictRouteState.LOCAL
                            && route.executionGate.get() instanceof MigrationClaim) {
                        // producer 也帮助发布完整迁移声明，避免源 worker 在持有门禁时暂停导致该类型永久阻塞。
                        this.helpMigrationClaim(typeState, route);
                        continue;
                    }
                    route.inflight.incrementAndGet();
                    if (typeState.strictRoute.get() == route) break;
                    route.inflight.decrementAndGet();
                }
                if (route.state == StrictRouteState.MIGRATING
                        || route.state == StrictRouteState.STOLEN
                        || route.state == StrictRouteState.STOLEN_CATCHUP) {
                    StrictTunnel directTunnel = route.tunnel;
                    if (directTunnel == null) {
                        route.inflight.decrementAndGet();
                        entry.reject("严格直投路由缺少盗洞: " + route.state);
                        return;
                    }
                    try {
                        this.enqueueDirectStrictTunnel(entry, task, typeState, directTunnel);
                    } finally {
                        route.inflight.decrementAndGet();
                    }
                    return;
                }
                if (route.state == StrictRouteState.RETURN_PREPARE
                        || route.state == StrictRouteState.RETURNING) {
                    try {
                        this.enqueueReturnStaging(entry, task, typeState, route.returnContext);
                    } finally {
                        route.inflight.decrementAndGet();
                    }
                    return;
                }
                if (route.state != StrictRouteState.LOCAL
                        && route.state != StrictRouteState.LOCAL_CATCHUP) {
                    route.inflight.decrementAndGet();
                    entry.reject("严格任务路由不可接收新任务: " + route.state);
                    return;
                }
            }

            boolean counted = false;
            try {
                if (!this.prepareEntry(entry, task, typeState, typeState, this.slot, null, null)) return;
                counted = true;
                try {
                    this.queue.offer(entry);
                } catch (Throwable failure) {
                    if (counted && entry.state() != TaskEntry.CANCELLED) typeState.decrement();
                    entry.rejectQueued("主队列发布失败: " + failure.getClass().getSimpleName());
                    if (entry.state() == TaskEntry.CANCELLED) entry.releaseDroppedTask();
                    if (this.queue.isFailed()) {
                        // 共享原队列永久死槽会阻塞所有类型，必须先关闭整个槽位而不是只冻结当前类型。
                        this.failSlot("主队列永久死槽: " + this.queue.failureIndex(), failure);
                    }
                    return;
                }
                this.wakeWorker();
            } finally {
                if (route != null) route.inflight.decrementAndGet();
            }
        }

        /**
         * 业务作用：在 RETURN_PREPARE/RETURNING 期间把新严格任务发布到无 consumer 的专属 staging 队列。
         *
         * @param entry 稳定任务条目
         * @param task 业务任务
         * @param typeState 原始分区严格类型状态
         * @param context 当前路由原子绑定的归还上下文
         * 返回: 无返回值；任务使用不可执行 OWNER_RETURN_STAGING，发布失败不会改投其他队列。
         */
        void enqueueReturnStaging(
                TaskEntry entry,
                Task task,
                TypeState typeState,
                StrictReturnContext context
        ) {
            if (context == null) {
                entry.reject("归还路由缺少 returnStaging 上下文");
                return;
            }
            if (!this.prepareEntry(
                    entry,
                    task,
                    typeState,
                    context,
                    OWNER_RETURN_STAGING,
                    null,
                    null
            )) return;
            entry.returnContext = context;
            try {
                context.stagingQueue.offer(entry);
            } catch (Throwable failure) {
                if (entry.state() != TaskEntry.CANCELLED) context.decrement();
                entry.rejectQueued("returnStaging 发布失败: " + failure.getClass().getSimpleName());
                if (entry.state() == TaskEntry.CANCELLED) entry.releaseDroppedTask();
                if (context.stagingQueue.isFailed()) {
                    context.tunnel.fail(
                            "returnStaging 永久死槽: " + context.stagingQueue.failureIndex(),
                            null
                    );
                }
            }
        }

        /**
         * 业务作用：在 MIGRATING/STOLEN 期间把严格增量任务直接发布到唯一盗洞增量 FIFO。
         *
         * @param entry 稳定任务条目
         * @param task 业务任务
         * @param typeState 原始分区严格类型状态
         * @param tunnel 当前路由原子绑定的严格盗洞
         * 返回: 无返回值；发布失败明确拒绝，永久死槽冻结整个严格类型。
         */
        void enqueueDirectStrictTunnel(
                TaskEntry entry,
                Task task,
                TypeState typeState,
                StrictTunnel tunnel
        ) {
            if (tunnel == null || tunnel.failed.get()) {
                entry.reject("严格盗洞不可用");
                return;
            }
            if (!this.prepareEntry(entry, task, typeState, tunnel, tunnel.target.slot, null, tunnel)) return;
            try {
                tunnel.incrementalQueue.offer(entry);
            } catch (Throwable failure) {
                if (entry.state() != TaskEntry.CANCELLED) tunnel.decrement();
                entry.rejectQueued("严格增量队列发布失败: " + failure.getClass().getSimpleName());
                if (entry.state() == TaskEntry.CANCELLED) entry.releaseDroppedTask();
                if (tunnel.incrementalQueue.isFailed()) {
                    tunnel.fail(
                            "严格增量队列永久死槽: " + tunnel.incrementalQueue.failureIndex(),
                            null
                    );
                }
                return;
            }
            tunnel.target.wakeWorker();
        }

        /**
         * 业务作用：把安装盗洞后的非严格新任务直接发布到目标队列，不再增加原始分区积压。
         *
         * @param entry 稳定任务条目
         * @param task 业务任务
         * @param typeState 原始分区类型策略
         * @param tunnel 已登记 producer 临界区的活动盗洞
         * 返回: 无返回值；发布失败完整回滚目标计数，专属死槽只关闭本盗洞。
         */
        void enqueueDirectTunnel(TaskEntry entry, Task task, TypeState typeState, NonStrictTunnel tunnel) {
            if (!this.prepareEntry(entry, task, typeState, tunnel, tunnel.target.slot, tunnel, null)) return;
            try {
                tunnel.queue.offer(entry);
            } catch (Throwable failure) {
                if (entry.state() != TaskEntry.CANCELLED) tunnel.decrement();
                entry.rejectQueued("盗洞队列发布失败: " + failure.getClass().getSimpleName());
                if (entry.state() == TaskEntry.CANCELLED) entry.releaseDroppedTask();
                if (tunnel.queue.isFailed()) {
                    tunnel.fail("POISON 补写二次失败，sequence=" + tunnel.queue.failureIndex(), failure);
                }
                return;
            }
            tunnel.target.wakeWorker();
        }

        /**
         * 业务作用：在任何物理队列发布前初始化所有权、上下文和接收方计数，并最后发布 QUEUED。
         *
         * @param entry 稳定任务条目
         * @param task 业务任务
         * @param typeState 原始分区类型策略
         * @param counter 当前逻辑所有者计数器
         * @param owner 当前所有权分区号
         * @param tunnel 当前物理落点是盗洞时的引用；原始队列为 null
         * @param strictTunnel 当前物理落点是严格盗洞时的引用；其他队列为 null
         * 返回: 初始化成功返回 true；任一步失败都会发布 REJECTED 并返回 false。
         */
        boolean prepareEntry(
                TaskEntry entry,
                Task task,
                TypeState typeState,
                LogicalCounter counter,
                int owner,
                NonStrictTunnel tunnel,
                StrictTunnel strictTunnel
        ) {
            RecycleLinkedMap<String, Object> context = entry.context;
            try {
                task.setOwner(owner);
            } catch (Throwable failure) {
                entry.reject("初始化任务所有权失败: " + failure.getClass().getSimpleName());
                return false;
            }

            entry.bind(this, typeState, context, counter, tunnel, strictTunnel);
            counter.increment();
            entry.publishQueued();
            if (entry.applyPendingCancel()) {
                // 尚未发布到任何物理队列，提交线程就是最终摘除方，可立即执行取消回收。
                entry.releaseDroppedTask();
                return false;
            }
            return true;
        }

        /**
         * 业务作用：串行消费本槽位主队列，隔离单任务异常，并在停机时排空全部已受理任务。
         *
         * 参数说明: 无。
         * 返回: 无返回值；退出前始终通知 stopped，异常时先发布分区级 FAILED。
         */
        void runWorker() {
            try {
                while (true) {
                    boolean progressed = this.partition.auditMovingEntries(this);
                    progressed |= this.drainControlRequests();
                    progressed |= this.advanceStrictMigrations();
                    progressed |= this.advanceLocalCatchups();
                    progressed |= this.drainBatch();
                    progressed |= this.advanceStrictMigrations();
                    progressed |= this.advanceLocalCatchups();
                    progressed |= this.drainInboundNonStrictTunnels();
                    progressed |= this.drainInboundStrictTunnels();
                    MPSCLinkedQueue.ConsumerHeadState headState = this.queue.consumerHeadState();
                    if (headState == MPSCLinkedQueue.ConsumerHeadState.FAILED) {
                        this.failSlot("主队列 consumer 到达永久死槽: " + this.queue.failureIndex(), null);
                        break;
                    }

                    if (!this.running) {
                        if (headState == MPSCLinkedQueue.ConsumerHeadState.EMPTY
                                && this.inboundNonStrictTunnels.isEmpty()
                                && this.inboundStrictTunnels.isEmpty()
                                && !this.hasActiveOutboundStrictTunnels()
                                && this.controlQueue.consumerHeadState()
                                == MPSCLinkedQueue.ConsumerHeadState.EMPTY) break;
                        if (headState == MPSCLinkedQueue.ConsumerHeadState.RESERVED) {
                            // 停机前 producer 已经归零，仍有 RESERVED 说明边界证据不完整，不能伪装成排空成功。
                            this.failSlot("停机排空遇到未发布的保留槽位: " + this.queue.consumerBoundary(), null);
                            break;
                        }
                        // 停机路径同样不得忙循环：正常路径靠 parkUntilWork 退避，这里没有任何新任务会到达，
                        // 无进度时必须短时 park，否则 worker 会以 100% CPU 空转直到退出条件满足。
                        // 注意这里没有退出上界：若入站严格盗洞的源分区已无法继续推进，本 worker 会一直
                        // 停在这个分支，awaitStopped 必然超时且 stop() 持续返回 false。这是刻意选择——
                        // 强制关闭卡死盗洞会把在途任务冻结成失败证据（有损），当前实现宁可把不收敛
                        // 暴露给调用方，也不静默丢任务。stop() 的返回值契约已说明该情形需人工介入。
                        if (!progressed) LockSupport.parkNanos(this, STOP_DRAIN_PARK_NANOS);
                        continue;
                    }

                    if (!progressed) {
                        this.maybeRequestSteal();
                        this.parkUntilWork();
                    }
                }
            } catch (Throwable failure) {
                this.failSlot("分区 worker 异常退出", failure);
            } finally {
                this.running = false;
                this.stopped.countDown();
            }
        }

        /**
         * 业务作用：按批次消费主队列，限制单轮工作量并为后续控制请求和盗洞调度保留公平性。
         *
         * 参数说明: 无。
         * 返回: 本轮至少物理取得一个真实任务时返回 true；队头暂不可推进时返回 false。
         */
        boolean drainBatch() {
            boolean progressed = false;
            for (int i = 0; i < DEFAULT_DRAIN_BATCH; i++) {
                if (i != 0 && (i & 31) == 0) {
                    // 长批次中穿插控制请求，避免繁忙源分区执行完整批次后才看到空闲分区的盗洞申请。
                    this.drainControlRequests();
                    if (!this.inboundNonStrictTunnels.isEmpty() || !this.inboundStrictTunnels.isEmpty()) break;
                }
                TaskEntry entry = this.queue.poll(this.consumerCursor);
                if (entry == null) break;
                progressed = true;
                this.executeEntry(entry, this.consumerCursor.sequence(), null, null);
            }
            return progressed;
        }

        /**
         * 业务作用：由原分区 worker 串行处理其他空闲分区提交的盗洞申请，避免并发修改类型控制状态。
         *
         * 参数说明: 无。
         * 返回: 本轮至少处理一个控制请求时返回 true。
         */
        boolean drainControlRequests() {
            boolean progressed = false;
            for (int i = 0; i < Math.max(1, DEFAULT_DRAIN_BATCH / 16); i++) {
                StealRequest request = this.controlQueue.poll();
                if (request == null) break;
                progressed = true;
                try {
                    if (!this.installNonStrictTunnel(request.target)) {
                        // 只有不存在可安装的非严格候选时才考虑严格类型，减少 FIFO 执行权迁移频率。
                        this.installStrictTunnelFor(request.target);
                    }
                } finally {
                    request.pending.set(false);
                }
            }
            if (this.controlQueue.consumerHeadState() == MPSCLinkedQueue.ConsumerHeadState.FAILED) {
                this.failSlot("控制队列永久死槽: " + this.controlQueue.failureIndex(), null);
            }
            return progressed;
        }

        /**
         * 业务作用：为空闲目标分区选择积压最多的非严格类型并安装一个独立盗洞。
         *
         * @param target 发起申请且将成为唯一盗洞 consumer 的目标分区
         * 返回: 安装成功返回 true；跨线程调用、没有候选、目标已忙或状态变化时返回 false。
         */
        boolean installNonStrictTunnel(PartitionSlot target) {
            if (Thread.currentThread() != this.worker) {
                // 候选统计与盗洞路由发布属于源 worker 权威；拒绝跨线程调用，避免未来重构绕过该控制边界。
                log.error("Partition slot {} rejected non-strict tunnel installation outside its source worker", this.slot);
                return false;
            }
            if (target == this || !this.running || !target.running) return false;
            if (this.hasInboundFrom(target)) return false;
            if (this.lifecycle.get().phase != SlotPhase.ACCEPTING
                    || target.lifecycle.get().phase != SlotPhase.ACCEPTING) return false;
            if (target.localTaskCount.get() > idleTaskThreshold()) return false;

            TypeState candidate = null;
            int candidateCount = idleTaskThreshold();
            for (TypeState typeState : this.typeStates.values()) {
                if (typeState.strictOrder || typeState.failed.get()) continue;
                // 同类型已连接当前目标时继续考察其他非严格类型，不能直接降级为严格迁移。
                if (typeState.nonStrictGroup.hasOpenTarget(target)) continue;
                int count = typeState.logicalCount.get();
                if (count > candidateCount) {
                    candidate = typeState;
                    candidateCount = count;
                }
            }
            if (candidate == null) return false;

            NonStrictTunnel tunnel = new NonStrictTunnel(candidate.nonStrictGroup, target);
            target.inboundNonStrictTunnels.put(tunnel, Boolean.TRUE);
            if (!candidate.nonStrictGroup.add(tunnel)) {
                target.inboundNonStrictTunnels.remove(tunnel);
                return false;
            }
            // 盗洞活动快照已经发布，显式唤醒两侧 worker 开始存量分发和目标消费。
            this.wakeWorker();
            target.wakeWorker();
            return true;
        }

        /**
         * 业务作用：由源分区唯一 worker 选择最繁忙 LOCAL 严格类型，并为目标发布完整迁移声明和唯一盗洞。
         *
         * @param target 发起申请且将成为严格盗洞唯一 consumer 的目标分区
         * 返回: MIGRATING 路由成功发布时返回 true；跨线程调用、无候选或门禁竞争失败时返回 false。
         */
        boolean installStrictTunnelFor(PartitionSlot target) {
            if (Thread.currentThread() != this.worker) {
                // 候选统计与首次门禁发布属于源 worker 权威；拒绝跨线程调用，避免未来重构绕过该控制边界。
                log.error("Partition slot {} rejected strict tunnel installation outside its source worker", this.slot);
                return false;
            }
            if (this == target || !this.running || !target.running) return false;
            if (this.hasInboundFrom(target)) return false;
            if (this.lifecycle.get().phase != SlotPhase.ACCEPTING
                    || target.lifecycle.get().phase != SlotPhase.ACCEPTING) return false;
            if (target.localTaskCount.get() > idleTaskThreshold()) return false;

            TypeState candidate = null;
            int candidateCount = idleTaskThreshold();
            for (TypeState typeState : this.typeStates.values()) {
                if (!typeState.strictOrder || typeState.failed.get()) continue;
                StrictRoute route = typeState.strictRoute.get();
                if (route.state != StrictRouteState.LOCAL
                        || route.executionGate.get() != GATE_IDLE) continue;
                int count = typeState.logicalCount.get();
                if (count > candidateCount) {
                    candidate = typeState;
                    candidateCount = count;
                }
            }
            if (candidate == null) return false;

            StrictRoute local = candidate.strictRoute.get();
            if (local.state != StrictRouteState.LOCAL) return false;
            StrictTunnel tunnel = new StrictTunnel(this, target, candidate, local);
            StrictRoute migrating = new StrictRoute(local.epoch + 1, StrictRouteState.MIGRATING, tunnel, null);
            MigrationClaim claim = new MigrationClaim(local, migrating);

            if (!local.executionGate.compareAndSet(GATE_IDLE, claim)) return false;
            // 先关闭旧 LOCAL 执行权，再由可帮助流程登记目标并发布 MIGRATING；目标绝不能在 claim
            // 尚未成为 producer 权威时把一条看似无主的入站登记提前摘除。
            return this.helpMigrationClaim(candidate, local);
        }

        /**
         * 业务作用：帮助完成已经占住旧 LOCAL executionGate 的严格迁移声明，消除声明线程暂停造成的控制权失联。
         *
         * @param typeState 声明所属严格类型
         * @param local 当前观测到的旧 LOCAL 路由
         * 返回: 迁移路由已经由本线程或其他线程发布时返回 true；声明失效并安全撤销时返回 false。
         */
        boolean helpMigrationClaim(TypeState typeState, StrictRoute local) {
            Object gate = local.executionGate.get();
            if (!(gate instanceof MigrationClaim claim) || claim.expectedRoute != local) return false;

            StrictRoute migrating = claim.migratingRoute;
            StrictTunnel tunnel = migrating.tunnel;
            StrictRoute current = typeState.strictRoute.get();
            if (current == local) {
                // 目标登记必须先于路由发布：producer 一旦观察 MIGRATING，就必须已有唯一 consumer
                // 能看见对应盗洞；executionGate 已关闭旧执行权，所以此窗口不会发生本地执行。
                tunnel.target.inboundStrictTunnels.putIfAbsent(tunnel, Boolean.TRUE);
                if (typeState.strictRoute.compareAndSet(local, migrating)) {
                    tunnel.source.wakeWorker();
                    tunnel.target.wakeWorker();
                    return true;
                }
                current = typeState.strictRoute.get();
            }

            if ((current == migrating || current.tunnel == tunnel)
                    && current.state != StrictRouteState.FAILED
                    && !tunnel.failed.get()) {
                // 同一盗洞可能已被目标推进到后继状态；补齐幂等登记，禁止摘掉仍在服务的唯一 consumer。
                tunnel.target.inboundStrictTunnels.putIfAbsent(tunnel, Boolean.TRUE);
                return true;
            }
            if (current.state == StrictRouteState.FAILED || tunnel.failed.get()) {
                // FAILED 已永久撤销目标执行权；迟到帮助者只能清理旧登记，不能把冻结盗洞重新暴露给调度。
                tunnel.target.inboundStrictTunnels.remove(tunnel);
                return false;
            }
            if (current != local
                    && current.epoch >= migrating.epoch) {
                // 迟到帮助者观察到更晚且已脱离本盗洞的代次，说明声明历史上已经完成并归还；
                // 不得把旧盗洞重新登记到目标，否则会与新路由上下文发生代际交叉。
                tunnel.target.inboundStrictTunnels.remove(tunnel);
                return true;
            }

            // 只有当前仍停在旧 LOCAL 且声明从未发布时才重新开放门禁并撤销登记，避免留下无主盗洞。
            if (local.executionGate.compareAndSet(claim, GATE_IDLE)) {
                tunnel.target.inboundStrictTunnels.remove(tunnel);
            }
            return false;
        }

        /**
         * 业务作用：阻止两个分区同时建立反向盗洞，避免目标因本地变忙时又把任务交回原分区形成控制振荡。
         *
         * @param other 申请成为新目标的分区
         * 返回: 当前分区已经消费来自 other 的任一活动盗洞时返回 true。
         */
        boolean hasInboundFrom(PartitionSlot other) {
            for (NonStrictTunnel tunnel : this.inboundNonStrictTunnels.keySet()) {
                if (tunnel.source == other && tunnel.state.get() != NonStrictTunnel.CLOSED) return true;
            }
            for (StrictTunnel tunnel : this.inboundStrictTunnels.keySet()) {
                if (tunnel.source == other && !tunnel.failed.get()) return true;
            }
            return false;
        }

        /**
         * 业务作用：空闲时选择本代最繁忙健康分区并提交可复用窃取请求，不直接消费对方主队列。
         *
         * 参数说明: 无。
         * 返回: 无返回值；每个目标同一时刻最多挂起一个申请，并受全局旧代 inflight 保护。
         */
        void maybeRequestSteal() {
            if (this.localTaskCount.get() > idleTaskThreshold()
                    || this.tunnelTaskCount.get() > idleTaskThreshold()) return;
            if (this.inboundNonStrictTunnels.size()
                    >= Math.max(1, Integer.getInteger("nasa.partition.max-inbound-tunnels", 4))) return;
            long now = System.nanoTime();
            if (now < this.nextStealAttemptNanos) return;
            this.nextStealAttemptNanos = now + STEAL_RETRY_NANOS;

            Lifecycle global = this.partition.lifecycle.get();
            if (global.phase != LifecyclePhase.ACCEPTING) return;
            global.inflight.incrementAndGet();
            try {
                if (this.partition.lifecycle.get() != global || global.phase != LifecyclePhase.ACCEPTING) return;
                PartitionSlot source = null;
                int highest = idleTaskThreshold();
                for (PartitionSlot candidate : global.slots) {
                    if (candidate == this || candidate.lifecycle.get().phase != SlotPhase.ACCEPTING) continue;
                    int count = candidate.localTaskCount.get();
                    if (count > highest) {
                        highest = count;
                        source = candidate;
                    }
                }
                if (source == null || !this.stealRequest.pending.compareAndSet(false, true)) return;
                try {
                    source.controlQueue.offer(this.stealRequest);
                } catch (Throwable failure) {
                    this.stealRequest.pending.set(false);
                    if (source.controlQueue.isFailed()) {
                        source.failSlot("控制队列永久死槽: " + source.controlQueue.failureIndex(), failure);
                    }
                    return;
                }
                source.wakeWorker();
            } finally {
                global.inflight.decrementAndGet();
            }
        }

        /**
         * 业务作用：由目标分区唯一消费所有入站非严格盗洞，按真实使用续租并在空闲过期后安全关闭资源。
         *
         * 参数说明: 无。
         * 返回: 本轮至少取得一个真实盗洞任务时返回 true。
         */
        boolean drainInboundNonStrictTunnels() {
            boolean progressed = false;
            long now = System.nanoTime();
            for (NonStrictTunnel tunnel : this.inboundNonStrictTunnels.keySet()) {
                if (tunnel.state.get() == NonStrictTunnel.OPEN) {
                    boolean inUse = tunnel.logicalCount.get() > 0 || tunnel.inflight.get() > 0;
                    boolean renewed = inUse
                            && this.localTaskCount.get() <= idleTaskThreshold()
                            && tunnel.renew(now);
                    if (!renewed && tunnel.isExpired(now)) {
                        // 空盗洞不能因目标仍空闲而永久续租；主动摘除可恢复反向配对和窃取名额。
                        tunnel.expireAndDetach();
                    }
                }

                for (int i = 0; i < Math.max(1, DEFAULT_DRAIN_BATCH / 8); i++) {
                    TaskEntry entry = tunnel.queue.poll(tunnel.consumerCursor);
                    if (entry == null) break;
                    progressed = true;
                    this.executeEntry(entry, tunnel.consumerCursor.sequence(), tunnel, null);
                }

                MPSCLinkedQueue.ConsumerHeadState state = tunnel.queue.consumerHeadState();
                if (state == MPSCLinkedQueue.ConsumerHeadState.FAILED) {
                    tunnel.fail("consumer 到达永久死槽，sequence=" + tunnel.queue.failureIndex(), null);
                    continue;
                }
                if (tunnel.state.get() == NonStrictTunnel.DRAINING
                        && tunnel.inflight.get() == 0
                        && tunnel.logicalCount.get() == 0
                        && state == MPSCLinkedQueue.ConsumerHeadState.EMPTY) {
                    tunnel.state.compareAndSet(NonStrictTunnel.DRAINING, NonStrictTunnel.CLOSED);
                    this.inboundNonStrictTunnels.remove(tunnel);
                }
            }
            return progressed;
        }

        /**
         * 业务作用：异步封住旧 LOCAL producer 边界，并在主队列唯一 consumer 到达边界后发布严格存量迁移完成。
         *
         * 参数说明: 无。
         * 返回: 本轮至少记录一个边界或完成一个迁移时返回 true。
         */
        boolean advanceStrictMigrations() {
            boolean progressed = false;
            for (TypeState typeState : this.typeStates.values()) {
                if (!typeState.strictOrder || typeState.failed.get()) continue;
                StrictRoute route = typeState.strictRoute.get();
                if (route.state == StrictRouteState.LOCAL
                        && route.executionGate.get() instanceof MigrationClaim) {
                    progressed |= this.helpMigrationClaim(typeState, route);
                    route = typeState.strictRoute.get();
                }
                if (route.state != StrictRouteState.MIGRATING || route.tunnel == null) continue;
                StrictTunnel tunnel = route.tunnel;
                if (tunnel.source != this || tunnel.failed.get()) continue;

                if (!tunnel.migrationComplete
                        && System.nanoTime() - tunnel.transitionStartedNanos > transitionTimeoutNanos()) {
                    // 严格迁移不能仅依赖槽位停滞检测；总时限兜住 producer 永不归零等无法形成边界的异常。
                    tunnel.fail("严格存量迁移超过总时限", null);
                    progressed = true;
                    continue;
                }

                if (tunnel.sourceBoundary < 0L && tunnel.oldLocalRoute.inflight.get() == 0) {
                    // acquire 观察旧代归零后再读 producer 排他边界，保证此前 offer 的元素对 consumer 可见。
                    tunnel.sourceBoundary = this.queue.producerBoundary();
                    progressed = true;
                }
                long boundary = tunnel.sourceBoundary;
                if (boundary >= 0L
                        && !tunnel.migrationComplete
                        && this.queue.consumerBoundary() >= boundary) {
                    tunnel.migrationComplete = true;
                    tunnel.transitionStartedNanos = System.nanoTime();
                    progressed = true;
                    tunnel.target.wakeWorker();
                }
            }
            return progressed;
        }

        /**
         * 业务作用：由目标 worker 先消费严格存量 FIFO，确认迁移完成且存量排空后再切到 STOLEN 并消费增量 FIFO。
         *
         * 参数说明: 无。
         * 返回: 本轮至少取得一个严格任务或完成一次 MIGRATING 到 STOLEN 切换时返回 true。
         */
        boolean drainInboundStrictTunnels() {
            boolean progressed = false;
            for (StrictTunnel tunnel : this.inboundStrictTunnels.keySet()) {
                if (tunnel.failed.get()) continue;
                StrictRoute current = tunnel.typeState.strictRoute.get();
                if (current == tunnel.oldLocalRoute
                        && current.executionGate.get() instanceof MigrationClaim) {
                    progressed |= tunnel.source.helpMigrationClaim(tunnel.typeState, current);
                    current = tunnel.typeState.strictRoute.get();
                }
                if (current.tunnel != tunnel) {
                    boolean drained = tunnel.oldLocalRoute.inflight.get() == 0
                            && tunnel.producerInflight.get() == 0
                            && tunnel.logicalCount.get() == 0
                            && tunnel.stockQueue.consumerHeadState()
                            == MPSCLinkedQueue.ConsumerHeadState.EMPTY
                            && tunnel.incrementalQueue.consumerHeadState()
                            == MPSCLinkedQueue.ConsumerHeadState.EMPTY;
                    if (drained) {
                        // LOCAL 或下一代路由发布后，只允许摘除已由唯一 consumer 完整排空的历史登记。
                        this.inboundStrictTunnels.remove(tunnel);
                    } else {
                        // 非空旧洞已经失去路由身份时无法证明 FIFO 归属；冻结比拿新上下文继续消费更安全。
                        tunnel.fail(
                                "目标入站严格盗洞已脱离当前路由: current="
                                        + current.state + ", epoch=" + current.epoch,
                                null
                        );
                    }
                    continue;
                }

                for (int i = 0; i < Math.max(1, DEFAULT_DRAIN_BATCH / 8); i++) {
                    TaskEntry entry = tunnel.stockQueue.poll(tunnel.stockCursor);
                    if (entry == null) break;
                    progressed = true;
                    this.executeEntry(entry, tunnel.stockCursor.sequence(), null, tunnel);
                }
                MPSCLinkedQueue.ConsumerHeadState stockState = tunnel.stockQueue.consumerHeadState();
                if (stockState == MPSCLinkedQueue.ConsumerHeadState.FAILED) {
                    tunnel.fail("严格存量队列永久死槽: " + tunnel.stockQueue.failureIndex(), null);
                    continue;
                }

                if (tunnel.migrationComplete && stockState == MPSCLinkedQueue.ConsumerHeadState.EMPTY) {
                    StrictRoute route = tunnel.typeState.strictRoute.get();
                    if (route.state == StrictRouteState.MIGRATING && route.tunnel == tunnel) {
                        StrictRoute stolen = new StrictRoute(route.epoch + 1, StrictRouteState.STOLEN, tunnel, null);
                        if (tunnel.typeState.strictRoute.compareAndSet(route, stolen)) progressed = true;
                    }
                    route = tunnel.typeState.strictRoute.get();
                    if (route.tunnel != tunnel) {
                        // 路由可能在本轮存量消费后完成归还或进入下一代；留给下一轮统一复验和摘除。
                        continue;
                    }
                    if (route.state == StrictRouteState.RETURN_PREPARE
                            || route.state == StrictRouteState.RETURNING) {
                        progressed |= this.advanceStrictReturnOnTarget(tunnel, route);
                        continue;
                    }
                    if (route.state == StrictRouteState.STOLEN_CATCHUP) {
                        progressed |= this.advanceStolenCatchupOnTarget(tunnel, route);
                        continue;
                    }
                    if (route.state == StrictRouteState.LOCAL_CATCHUP) {
                        continue;
                    }
                    if (route.state == StrictRouteState.STOLEN) {
                        for (int i = 0; i < Math.max(1, DEFAULT_DRAIN_BATCH / 8); i++) {
                            TaskEntry entry = tunnel.incrementalQueue.poll(tunnel.incrementalCursor);
                            if (entry == null) break;
                            progressed = true;
                            this.executeEntry(entry, tunnel.incrementalCursor.sequence(), null, tunnel);
                        }
                        if (tunnel.incrementalQueue.consumerHeadState()
                                == MPSCLinkedQueue.ConsumerHeadState.FAILED) {
                            tunnel.fail(
                                    "严格增量队列永久死槽: " + tunnel.incrementalQueue.failureIndex(),
                                    null
                            );
                        }
                        if (!this.running
                                && tunnel.producerInflight.get() == 0
                                && tunnel.logicalCount.get() == 0
                                && tunnel.incrementalQueue.consumerHeadState()
                                == MPSCLinkedQueue.ConsumerHeadState.EMPTY) {
                            this.inboundStrictTunnels.remove(tunnel);
                        }
                        if (this.running && this.maybeStartStrictReturn(tunnel, route)) progressed = true;
                    }
                }
            }
            return progressed;
        }

        /**
         * 业务作用：由原分区 worker 按 returnPending、主队列边界、staging、postReturnPending 顺序完成 LOCAL_CATCHUP。
         *
         * 参数说明: 无。
         * 返回: 本轮至少执行/转换一笔任务、记录 staging 边界或发布 LOCAL 时返回 true。
         */
        boolean advanceLocalCatchups() {
            boolean progressed = false;
            for (TypeState typeState : this.typeStates.values()) {
                if (!typeState.strictOrder || typeState.failed.get()) continue;
                StrictRoute route = typeState.strictRoute.get();
                if (route.state != StrictRouteState.LOCAL_CATCHUP) continue;
                StrictReturnContext context = route.returnContext;
                if (context == null) {
                    this.failType(typeState, "LOCAL_CATCHUP 缺少 StrictReturnContext", null);
                    continue;
                }
                if (System.nanoTime() - context.phaseStartedNanos > transitionTimeoutNanos()) {
                    // LOCAL_CATCHUP 已产生跨队列物理副作用，超时只能冻结类型，不能回退后破坏严格顺序。
                    context.tunnel.fail("LOCAL_CATCHUP 推进超过总时限", null);
                    progressed = true;
                    continue;
                }

                if (context.stagingBoundary < 0L) {
                    StrictRoute prepare = context.prepareRoute;
                    StrictRoute returning = context.returningRoute;
                    if ((prepare != null && prepare.inflight.get() != 0)
                            || (returning != null && returning.inflight.get() != 0)) continue;
                    context.stagingBoundary = context.stagingQueue.producerBoundary();
                    progressed = true;
                }

                TaskEntry pending;
                int budget = Math.max(1, DEFAULT_DRAIN_BATCH / 8);
                while (budget-- > 0 && (pending = context.returnPending.pollFirst()) != null) {
                    progressed = true;
                    this.executeCatchupEntry(pending, context, "returnPending");
                }
                if (!context.returnPending.isEmpty()) continue;
                if (this.queue.consumerBoundary() < context.localBoundary) continue;

                budget = Math.max(1, DEFAULT_DRAIN_BATCH / 8);
                while (budget-- > 0
                        && context.stagingQueue.consumerBoundary() < context.stagingBoundary) {
                    TaskEntry staged = context.stagingQueue.poll(context.stagingCursor);
                    if (staged == null) break;
                    progressed = true;
                    if (staged.state() == TaskEntry.CANCELLED || staged.state() == TaskEntry.REJECTED) {
                        staged.releaseDroppedTask();
                        continue;
                    }
                    if (!this.takeStagedEntry(staged, context, context.stagingCursor.sequence())) continue;
                    this.executeCatchupEntry(staged, context, "returnStaging");
                }
                if (context.stagingQueue.consumerBoundary() < context.stagingBoundary) continue;

                budget = Math.max(1, DEFAULT_DRAIN_BATCH / 8);
                while (budget-- > 0 && (pending = context.postReturnPending.pollFirst()) != null) {
                    progressed = true;
                    this.executeCatchupEntry(pending, context, "postReturnPending");
                }
                if (!context.postReturnPending.isEmpty()) continue;
                if (context.stagingCount.get() != 0
                        || context.tunnel.logicalCount.get() != 0
                        || context.tunnel.incrementalQueue.consumerHeadState()
                        != MPSCLinkedQueue.ConsumerHeadState.EMPTY) continue;

                StrictRoute local = new StrictRoute(route.epoch + 1, StrictRouteState.LOCAL, null, null);
                if (typeState.strictRoute.compareAndSet(route, local)) {
                    progressed = true;
                    this.wakeWorker();
                    context.tunnel.target.wakeWorker();
                }
            }
            return progressed;
        }

        /**
         * 业务作用：把 staging 哨兵所有权转为原分区，先增加接收方再减少暂存计数，并帮助完成并发取消。
         *
         * @param entry staging 唯一 consumer 取得的任务
         * @param context 当前类型归还上下文
         * @param sequence staging 队列槽位序号
         * 返回: 成功取得原分区所有权且仍需执行时返回 true；取消或故障时返回 false。
         */
        boolean takeStagedEntry(TaskEntry entry, StrictReturnContext context, long sequence) {
            context.typeState.increment();
            if (!entry.beginMove("RETURN_STAGING_TO_SOURCE", this, context.tunnel.target, sequence)) {
                context.typeState.decrement();
                if (entry.state() == TaskEntry.CANCELLED) entry.releaseDroppedTask();
                return false;
            }
            try {
                Task task = entry.task();
                if (task == null || !task.compareAndSetOwner(OWNER_RETURN_STAGING, this.slot)) {
                    context.typeState.decrement();
                    TaskEntry.STATE.setRelease(entry, TaskEntry.QUEUED);
                    context.tunnel.fail("接管 staging 所有权失败，sequence=" + sequence, entry);
                    return false;
                }
                entry.logicalCounter = context.typeState;
                // staging 已被本轮唯一 consumer 摘除，后续不再通过主队列 RETURNING 身份复验。
                entry.returnContext = null;
                TaskEntry.STATE.setRelease(entry, TaskEntry.QUEUED);
                context.decrement();
                if (entry.applyPendingCancel()) {
                    // staging 已由唯一 consumer 物理摘除，取消终态现在可以安全释放业务任务。
                    entry.releaseDroppedTask();
                    return false;
                }
                return true;
            } finally {
                entry.finishMove();
            }
        }

        /**
         * 业务作用：在 LOCAL_CATCHUP 独占执行门禁下执行已经按三个边界排好顺序的任务。
         *
         * @param entry 已从私有 FIFO 或 staging 取得的稳定本地任务
         * @param context 当前类型归还上下文
         * @param stage 诊断用阶段名
         * 返回: 无返回值；无法取得执行权时冻结类型，不能把任务重新排到主队列尾部。
         */
        void executeCatchupEntry(TaskEntry entry, StrictReturnContext context, String stage) {
            if (entry.state() == TaskEntry.CANCELLED || entry.state() == TaskEntry.REJECTED) {
                // 私有 FIFO 的唯一 consumer 已完成物理摘除，取消对象不再参与任何后续边界。
                entry.releaseDroppedTask();
                return;
            }
            if (!entry.tryStart(this.slot)) {
                context.tunnel.fail("LOCAL_CATCHUP 在 " + stage + " 无法取得任务执行权", entry);
                return;
            }
            try {
                entry.runTask();
            } finally {
                // catch-up 调用点不再读取条目后才释放框架 hold，避免 runTask 返回前发生池化复用。
                if (entry.state() == TaskEntry.COMPLETED) entry.releaseFrameworkOwnership();
            }
        }

        /**
         * 业务作用：在目标 worker 的任务边界按文档负载条件发起严格归还，并先把新任务改投 staging。
         *
         * @param tunnel 当前稳定 STOLEN 的严格盗洞
         * @param stolen 当前 STOLEN 路由快照
         * 返回: 本次成功发布 RETURN_PREPARE 时返回 true。
         */
        boolean maybeStartStrictReturn(StrictTunnel tunnel, StrictRoute stolen) {
            if (stolen.state != StrictRouteState.STOLEN || stolen.tunnel != tunnel) return false;
            if (tunnel.source.lifecycle.get().phase != SlotPhase.ACCEPTING) return false;

            int returnMode = this.strictReturnMode(tunnel);
            if (returnMode == StrictTunnel.RETURN_NOT_REQUIRED) {
                tunnel.returnObservationMode = StrictTunnel.RETURN_NOT_REQUIRED;
                tunnel.returnObservationCount = 0;
                return false;
            }

            long activityEpoch = tunnel.activityEpoch.get();
            if (tunnel.returnObservationMode != returnMode
                    || (returnMode == StrictTunnel.RETURN_FOR_EMPTY
                    && tunnel.returnObservedActivityEpoch != activityEpoch)) {
                // 触发模式变化或出现过新任务时重新累计，防止把不连续的空闲采样拼成归还证据。
                tunnel.returnObservationMode = returnMode;
                tunnel.returnObservationCount = 0;
                tunnel.returnObservedActivityEpoch = activityEpoch;
            }
            int required = Math.max(1, Integer.getInteger("nasa.partition.return-observations", 3));
            if (++tunnel.returnObservationCount < required) return false;
            tunnel.returnObservationMode = StrictTunnel.RETURN_NOT_REQUIRED;
            tunnel.returnObservationCount = 0;

            StrictReturnContext context = new StrictReturnContext(
                    tunnel,
                    stolen,
                    returnMode,
                    activityEpoch
            );
            StrictRoute prepare = new StrictRoute(
                    stolen.epoch + 1,
                    StrictRouteState.RETURN_PREPARE,
                    tunnel,
                    context
            );
            context.prepareRoute = prepare;
            if (!tunnel.typeState.strictRoute.compareAndSet(stolen, prepare)) return false;
            // 新 producer 已改投 staging，目标 worker 本轮起停止执行该类型并异步等待旧 STOLEN producer。
            this.wakeWorker();
            return true;
        }

        /**
         * 业务作用：按目标竞争负载和空盗洞安静状态判断严格执行权的归还触发模式。
         *
         * @param tunnel 当前由本目标分区消费的严格盗洞
         * 返回: 目标存在足够竞争负载时返回 RETURN_FOR_PRESSURE；盗洞已空且原分区空闲时返回 RETURN_FOR_EMPTY；否则返回 RETURN_NOT_REQUIRED。
         */
        int strictReturnMode(StrictTunnel tunnel) {
            long targetLocal = this.localTaskCount.get();
            long targetTunnelTotal = this.tunnelTaskCount.get();
            long tunnelTasks = tunnel.logicalCount.get();
            long source = tunnel.source.localTaskCount.get();
            long idle = idleTaskThreshold();
            // tunnelTaskCount 与单洞计数分步更新，瞬时读偏差只允许把竞争负载保守夹到零。
            long competingTargetLoad = Math.max(0L, targetLocal + targetTunnelTotal - tunnelTasks);
            boolean conditionA = tunnelTasks > 0
                    && competingTargetLoad * 100L > tunnelTasks * 80L
                    && (source * 100L < tunnelTasks * 50L || source <= idle);
            if (conditionA) return StrictTunnel.RETURN_FOR_PRESSURE;
            if (tunnelTasks == 0 && source <= idle) return StrictTunnel.RETURN_FOR_EMPTY;
            return StrictTunnel.RETURN_NOT_REQUIRED;
        }

        /**
         * 业务作用：在目标 worker 上异步推进 RETURN_PREPARE 和 RETURNING，不占住 worker 等待 producer。
         *
         * @param tunnel 正在归还的严格盗洞
         * @param route 当前观测到的归还路由
         * 返回: 本轮至少推进状态、转移任务或发布边界时返回 true。
         */
        boolean advanceStrictReturnOnTarget(StrictTunnel tunnel, StrictRoute route) {
            if (route.tunnel != tunnel) {
                // 调用前后的路由可能跨代变化；绝不能把另一代归还上下文用于当前入站 FIFO。
                tunnel.fail("严格归还路由与目标入站盗洞不一致", null);
                return false;
            }
            StrictReturnContext context = route.returnContext;
            if (context == null) {
                tunnel.fail("归还路由缺少 StrictReturnContext", null);
                return false;
            }

            if (route.state == StrictRouteState.RETURN_PREPARE) {
                if (System.nanoTime() - context.phaseStartedNanos > transitionTimeoutNanos()) {
                    // RETURN_PREPARE 尚未搬运任务，但 producer 失联同样必须形成明确故障证据，避免永久半状态。
                    tunnel.fail("RETURN_PREPARE 推进超过总时限", null);
                    return true;
                }
                if (context.stolenRoute.inflight.get() != 0) {
                    // RETURN_PREPARE 已让新任务改投 staging；这里只等待所有历史直投代次退出，
                    // 防止 MIGRATING/STOLEN_CATCHUP 的迟到 producer 跨过盗洞边界发布。
                    return false;
                }
                if (context.tunnelBoundary < 0L) {
                    context.tunnelBoundary = tunnel.incrementalQueue.producerBoundary();
                }
                int returnMode = this.strictReturnMode(tunnel);
                boolean emptyEvidenceInvalidated = context.triggerMode == StrictTunnel.RETURN_FOR_EMPTY
                        && returnMode != StrictTunnel.RETURN_FOR_PRESSURE
                        && tunnel.activityEpoch.get() != context.triggerActivityEpoch;
                if (tunnel.source.lifecycle.get().phase == SlotPhase.FAILED
                        || returnMode == StrictTunnel.RETURN_NOT_REQUIRED
                        || emptyEvidenceInvalidated) {
                    // 可逆准备期发现负载条件消失或空闲证据被新任务打断时，按旧边界安全撤销归还。
                    StrictRoute catchup = new StrictRoute(
                            route.epoch + 1,
                            StrictRouteState.STOLEN_CATCHUP,
                            tunnel,
                            context
                    );
                    if (tunnel.typeState.strictRoute.compareAndSet(route, catchup)) {
                        context.phaseStartedNanos = System.nanoTime();
                        this.wakeWorker();
                        return true;
                    }
                    return false;
                }
                StrictRoute returning = new StrictRoute(
                        route.epoch + 1,
                        StrictRouteState.RETURNING,
                        tunnel,
                        context
                );
                context.returningRoute = returning;
                if (tunnel.typeState.strictRoute.compareAndSet(route, returning)) {
                    context.phaseStartedNanos = System.nanoTime();
                    return true;
                }
                return false;
            }

            if (route.state != StrictRouteState.RETURNING) return false;
            if (System.nanoTime() - context.phaseStartedNanos > transitionTimeoutNanos()) {
                // RETURNING 已把部分任务回写原分区，超时后禁止回退目标侧，避免新旧 FIFO 交叉执行。
                tunnel.fail("RETURNING 推进超过总时限", null);
                return true;
            }
            if (tunnel.source.lifecycle.get().phase == SlotPhase.FAILED) {
                // 已进入不可逆物理归还后不能再切回目标，否则较新盗洞任务会越过已回写原队列的旧任务。
                tunnel.fail("RETURNING 期间原分区失效，禁止回退 STOLEN", null);
                return true;
            }
            boolean progressed = false;
            long boundary = context.tunnelBoundary;
            for (int i = 0; i < Math.max(1, DEFAULT_DRAIN_BATCH / 8)
                    && tunnel.incrementalQueue.consumerBoundary() < boundary; i++) {
                TaskEntry entry = tunnel.incrementalQueue.poll(tunnel.incrementalCursor);
                if (entry == null) break;
                progressed = true;
                if (entry.state() == TaskEntry.CANCELLED || entry.state() == TaskEntry.REJECTED) {
                    entry.releaseDroppedTask();
                    continue;
                }
                if (!this.moveStrictReturnEntry(entry, context, tunnel.incrementalCursor.sequence())) return true;
            }

            if (tunnel.incrementalQueue.consumerHeadState() == MPSCLinkedQueue.ConsumerHeadState.FAILED) {
                tunnel.fail("严格归还遇到增量队列永久死槽", null);
                return true;
            }
            if (tunnel.incrementalQueue.consumerBoundary() < boundary) return progressed;

            if (tunnel.producerInflight.get() != 0) return progressed;
            long verifiedBoundary = tunnel.incrementalQueue.producerBoundary();
            if (verifiedBoundary != boundary) {
                if (verifiedBoundary < boundary) {
                    tunnel.fail("严格归还复验发现 producer 边界回退", null);
                    return true;
                }
                // 直投 producer 的登记和物理发布分属两个原子域；在不可逆切到 LOCAL_CATCHUP 前
                // 再次扩展边界，确保任何已完成的迟到发布仍排在 staging 与本地主队列任务之前。
                context.tunnelBoundary = verifiedBoundary;
                return true;
            }

            context.localBoundary = tunnel.source.queue.producerBoundary();
            StrictRoute catchup = new StrictRoute(
                    route.epoch + 1,
                    StrictRouteState.LOCAL_CATCHUP,
                    tunnel,
                    context
            );
            if (tunnel.typeState.strictRoute.compareAndSet(route, catchup)) {
                context.phaseStartedNanos = System.nanoTime();
                tunnel.source.wakeWorker();
                return true;
            }
            return progressed;
        }

        /**
         * 业务作用：原分区在 RETURN_PREPARE 阶段失效时，按旧盗洞边界、staging、新盗洞顺序安全撤销归还。
         *
         * @param tunnel 保持目标执行能力的严格盗洞
         * @param route 当前 STOLEN_CATCHUP 路由
         * 返回: 本轮至少执行/转换任务、记录 staging 边界或恢复 STOLEN 时返回 true。
         */
        boolean advanceStolenCatchupOnTarget(StrictTunnel tunnel, StrictRoute route) {
            if (route.tunnel != tunnel) {
                // 撤销归还同样必须绑定原盗洞；跨代继续会把 staging 插进错误的严格 FIFO。
                tunnel.fail("STOLEN_CATCHUP 路由与目标入站盗洞不一致", null);
                return false;
            }
            StrictReturnContext context = route.returnContext;
            if (context == null) {
                tunnel.fail("STOLEN_CATCHUP 缺少 StrictReturnContext", null);
                return false;
            }
            if (System.nanoTime() - context.phaseStartedNanos > transitionTimeoutNanos()) {
                // 撤销归还仍受总时限约束；超时冻结比跨越旧盗洞或 staging 边界继续执行更安全。
                tunnel.fail("STOLEN_CATCHUP 推进超过总时限", null);
                return true;
            }
            if (context.prepareRoute != null && context.prepareRoute.inflight.get() != 0) return false;
            if (context.stagingBoundary < 0L) {
                context.stagingBoundary = context.stagingQueue.producerBoundary();
            }

            boolean progressed = false;
            int budget = Math.max(1, DEFAULT_DRAIN_BATCH / 8);
            while (budget-- > 0
                    && tunnel.incrementalQueue.consumerBoundary() < context.tunnelBoundary) {
                TaskEntry entry = tunnel.incrementalQueue.poll(tunnel.incrementalCursor);
                if (entry == null) break;
                progressed = true;
                this.executeEntry(entry, tunnel.incrementalCursor.sequence(), null, tunnel);
            }
            if (tunnel.incrementalQueue.consumerBoundary() < context.tunnelBoundary) return progressed;

            budget = Math.max(1, DEFAULT_DRAIN_BATCH / 8);
            while (budget-- > 0
                    && context.stagingQueue.consumerBoundary() < context.stagingBoundary) {
                TaskEntry entry = context.stagingQueue.poll(context.stagingCursor);
                if (entry == null) break;
                progressed = true;
                if (entry.state() == TaskEntry.CANCELLED || entry.state() == TaskEntry.REJECTED) {
                    entry.releaseDroppedTask();
                    continue;
                }
                if (!this.takeStagedEntryForTarget(entry, context, context.stagingCursor.sequence())) continue;
                this.executeEntry(entry, context.stagingCursor.sequence(), null, tunnel);
            }
            if (context.stagingQueue.consumerBoundary() < context.stagingBoundary) return progressed;
            if (context.stagingCount.get() != 0) return progressed;

            StrictRoute stolen = new StrictRoute(route.epoch + 1, StrictRouteState.STOLEN, tunnel, null);
            if (tunnel.typeState.strictRoute.compareAndSet(route, stolen)) return true;
            return progressed;
        }

        /**
         * 业务作用：安全撤销归还时把 staging 所有权转回目标盗洞，先增目标计数再减暂存计数。
         *
         * @param entry staging 唯一 consumer 取得的任务
         * @param context 本次撤销归还上下文
         * @param sequence staging 槽位序号
         * 返回: 成功接管且仍需执行时返回 true；取消或故障时返回 false。
         */
        boolean takeStagedEntryForTarget(TaskEntry entry, StrictReturnContext context, long sequence) {
            StrictTunnel tunnel = context.tunnel;
            tunnel.increment();
            if (!entry.beginMove("RETURN_STAGING_TO_TARGET", this, tunnel.source, sequence)) {
                tunnel.decrement();
                if (entry.state() == TaskEntry.CANCELLED) entry.releaseDroppedTask();
                return false;
            }
            try {
                Task task = entry.task();
                if (task == null || !task.compareAndSetOwner(OWNER_RETURN_STAGING, this.slot)) {
                    tunnel.decrement();
                    TaskEntry.STATE.setRelease(entry, TaskEntry.QUEUED);
                    tunnel.fail("撤销归还接管 staging 所有权失败，sequence=" + sequence, entry);
                    return false;
                }
                entry.logicalCounter = tunnel;
                entry.strictTunnel = tunnel;
                // 撤销归还后任务重新成为普通盗洞任务，旧上下文不得污染下一轮归还身份判断。
                entry.returnContext = null;
                TaskEntry.STATE.setRelease(entry, TaskEntry.QUEUED);
                context.decrement();
                if (entry.applyPendingCancel()) {
                    // staging 已被目标唯一 consumer 摘除，取消任务此时不再被任何物理队列持有。
                    entry.releaseDroppedTask();
                    return false;
                }
                return true;
            } finally {
                entry.finishMove();
            }
        }

        /**
         * 业务作用：按盗洞 FIFO 把归还边界内旧任务发布到原始主队列，先增源计数、发布成功后再减目标计数。
         *
         * @param entry 目标 worker 从盗洞取得的旧严格任务
         * @param context 本类型独占归还上下文
         * @param sequence 盗洞增量队列槽位序号
         * 返回: 转移成功或任务已取消时返回 true；不可恢复发布失败时冻结类型并返回 false。
         */
        boolean moveStrictReturnEntry(TaskEntry entry, StrictReturnContext context, long sequence) {
            TypeState sourceCounter = context.typeState;
            sourceCounter.increment();
            if (!entry.beginMove("STRICT_RETURN", this, context.tunnel.source, sequence)) {
                sourceCounter.decrement();
                if (entry.state() == TaskEntry.CANCELLED) {
                    entry.releaseDroppedTask();
                    return true;
                }
                return false;
            }
            try {
                Task task = entry.task();
                if (task == null || !task.compareAndSetOwner(this.slot, context.tunnel.source.slot)) {
                    TaskEntry.STATE.setRelease(entry, TaskEntry.QUEUED);
                    sourceCounter.decrement();
                    context.tunnel.fail("严格归还所有权 CAS 失败，sequence=" + sequence, entry);
                    return false;
                }

                entry.logicalCounter = sourceCounter;
                entry.strictTunnel = null;
                entry.returnContext = context;
                TaskEntry.STATE.setRelease(entry, TaskEntry.QUEUED);
                if (entry.applyPendingCancel()) {
                    // 原盗洞条目已物理摘除且不再发布到源队列；补减旧所有者后即可安全回收。
                    context.tunnel.decrement();
                    entry.releaseDroppedTask();
                    return true;
                }
                try {
                    context.tunnel.source.queue.offer(entry);
                } catch (Throwable failure) {
                    if (entry.state() == TaskEntry.CANCELLED) {
                        context.tunnel.decrement();
                        entry.releaseDroppedTask();
                    } else {
                        sourceCounter.decrement();
                        entry.logicalCounter = context.tunnel;
                        entry.strictTunnel = context.tunnel;
                        TaskEntry.STATE.setRelease(entry, TaskEntry.QUEUED);
                    }
                    context.tunnel.fail("严格归还发布原队列失败，sequence=" + sequence, entry);
                    if (context.tunnel.source.queue.isFailed()) {
                        context.tunnel.source.failSlot(
                                "归还发布形成主队列永久死槽: "
                                        + context.tunnel.source.queue.failureIndex(),
                                failure
                        );
                    }
                    return false;
                }
                context.tunnel.decrement();
                context.tunnel.source.wakeWorker();
                return true;
            } finally {
                entry.finishMove();
            }
        }

        /**
         * 业务作用：为一笔已离开主队列的任务取得唯一执行权，严格类型还必须占有当前 LOCAL executionGate。
         *
         * @param entry 已由唯一 consumer 物理摘除的任务条目
         * @param sequence 该任务所在物理队列的槽位序号
         * @param tunnel 非严格盗洞任务的物理来源；主队列任务为 null
         * @param strictTunnel 严格盗洞任务的物理来源；其他队列为 null
         * 返回: 无返回值；取消条目直接跳过，失去门禁的条目登记为 FAILED 证据。
         */
        void executeEntry(
                TaskEntry entry,
                long sequence,
                NonStrictTunnel tunnel,
                StrictTunnel strictTunnel
        ) {
            if (entry.state() == TaskEntry.CANCELLED || entry.state() == TaskEntry.REJECTED) {
                // 当前线程已从对应物理队列摘除终态条目，至此才允许归还业务任务对象。
                entry.releaseDroppedTask();
                return;
            }
            if (entry.state() == TaskEntry.FAILED) return;
            if (entry.nonStrictTunnel != tunnel || entry.strictTunnel != strictTunnel) {
                this.freezeEntry(entry, "任务物理队列与盗洞登记不一致，sequence=" + sequence);
                return;
            }

            TypeState typeState = entry.typeState;
            if (typeState.failed.get()) {
                // 类型门禁已经关闭，主队列中迟到摘除的任务只能登记证据，继续执行会越过已冻结的控制边界。
                if (entry.publishFailed("任务类型已经 FAILED")) this.failedEvidence.offer(entry);
                return;
            }
            if (!typeState.strictOrder && tunnel == null) {
                NonStrictTunnel selected = typeState.nonStrictGroup.selectForSourceDispatch();
                if (selected != null) {
                    try {
                        if (this.moveNonStrictEntry(entry, selected, sequence)) return;
                    } finally {
                        selected.exitProducer();
                    }
                }
            }

            StrictRoute route = null;
            if (typeState.strictOrder) {
                route = typeState.strictRoute.get();
                if (route.state == StrictRouteState.LOCAL
                        && route.executionGate.get() instanceof MigrationClaim
                        && this.helpMigrationClaim(typeState, route)) {
                    route = typeState.strictRoute.get();
                }
                if (strictTunnel == null) {
                    if (route.state == StrictRouteState.RETURNING) {
                        StrictReturnContext context = route.returnContext;
                        if (context == null || entry.returnContext != context) {
                            this.freezeEntry(entry, "RETURNING 取得未登记归还任务，sequence=" + sequence);
                            return;
                        }
                        context.returnPending.addLast(entry);
                        return;
                    }
                    if (route.state == StrictRouteState.LOCAL_CATCHUP) {
                        StrictReturnContext context = route.returnContext;
                        if (context == null) {
                            this.freezeEntry(entry, "LOCAL_CATCHUP 缺少归还上下文，sequence=" + sequence);
                            return;
                        }
                        if (sequence >= context.localBoundary) {
                            context.postReturnPending.addLast(entry);
                        } else if (!context.returnPending.isEmpty()) {
                            context.returnPending.addLast(entry);
                        } else {
                            this.executeCatchupEntry(entry, context, "localBoundary");
                        }
                        return;
                    }
                    if (route.state == StrictRouteState.MIGRATING && route.tunnel != null) {
                        this.moveStrictEntry(entry, route.tunnel, sequence);
                        return;
                    }
                    if (route.state != StrictRouteState.LOCAL
                            || !route.executionGate.compareAndSet(GATE_IDLE, GATE_RUNNING)) {
                        // 任务已经离开共享队列，不能重新 offer 到队尾；保留条目并冻结本类型等待受控恢复。
                        this.freezeEntry(entry, "严格类型未取得 LOCAL executionGate，sequence=" + sequence);
                        return;
                    }
                } else if (route.tunnel != strictTunnel
                        || (route.state != StrictRouteState.MIGRATING
                        && route.state != StrictRouteState.STOLEN
                        && route.state != StrictRouteState.STOLEN_CATCHUP)) {
                    strictTunnel.fail("目标执行时严格路由与盗洞不一致，sequence=" + sequence, entry);
                    return;
                }
            }

            boolean started = false;
            try {
                if (!entry.tryStart(this.slot)) {
                    int current = entry.state();
                    if (current == TaskEntry.CANCELLED || current == TaskEntry.REJECTED) {
                        // consumer 摘除与取消 CAS 可以并发；取消已减少逻辑计数，这里只负责最后的物理回收。
                        entry.releaseDroppedTask();
                        return;
                    }
                    if (current == TaskEntry.FAILED) return;
                    // 任务已经永久离开唯一物理队列，任何非终态启动失败都不能静默丢弃，否则逻辑计数
                    // 会永远保留而任务又失去全部容器引用；冻结类型并登记条目是唯一可审计的失败结果。
                    this.freezeEntry(
                            entry,
                            "consumer 摘除后无法取得执行权，state=" + current + ", sequence=" + sequence
                    );
                    return;
                }
                started = true;
                entry.runTask();
            } finally {
                try {
                    if (route != null
                            && strictTunnel == null
                            && route.state == StrictRouteState.LOCAL
                            && !route.executionGate.compareAndSet(GATE_RUNNING, GATE_IDLE)) {
                        this.failType(typeState, "严格类型 executionGate 释放失败", entry);
                    }
                } finally {
                    // executionGate 故障诊断仍会引用 entry；完成该诊断后才允许 fire-and-forget 条目归池。
                    if (started && entry.state() == TaskEntry.COMPLETED) {
                        entry.releaseFrameworkOwnership();
                    }
                }
            }
        }

        /**
         * 业务作用：把原分区唯一 consumer 已取出的非严格任务转移到目标盗洞，取消和发布失败均保持任务可追踪。
         *
         * @param entry 已离开原始主队列、当前仍为 QUEUED(source) 的任务
         * @param tunnel 已登记 producer 临界区的目标盗洞
         * @param sequence 原始主队列槽位序号
         * 返回: 任务已进入盗洞、已取消或已冻结时返回 true；发布失败且安全恢复本地执行时返回 false。
         */
        boolean moveNonStrictEntry(TaskEntry entry, NonStrictTunnel tunnel, long sequence) {
            tunnel.increment();
            if (!entry.beginMove("NON_STRICT_TO_TUNNEL", this, tunnel.target, sequence)) {
                tunnel.decrement();
                int current = entry.state();
                if (current == TaskEntry.CANCELLED) {
                    entry.releaseDroppedTask();
                    return true;
                }
                return current != TaskEntry.QUEUED;
            }

            try {
                Task task = entry.task();
                if (task == null || !task.compareAndSetOwner(this.slot, tunnel.target.slot)) {
                    TaskEntry.STATE.setRelease(entry, TaskEntry.QUEUED);
                    tunnel.decrement();
                    if (entry.applyPendingCancel()) {
                        entry.releaseDroppedTask();
                        return true;
                    }
                    this.freezeEntry(entry, "非严格迁移所有权 CAS 失败，sequence=" + sequence);
                    return true;
                }

                // 接收方计数已经增加；先发布新的逻辑计数器和盗洞引用，再开放 QUEUED 取消竞争。
                entry.logicalCounter = tunnel;
                entry.nonStrictTunnel = tunnel;
                TaskEntry.STATE.setRelease(entry, TaskEntry.QUEUED);
                if (entry.applyPendingCancel()) {
                    // 源队列已摘除且任务未进入目标队列；取消方减目标计数，迁移方补减源计数。
                    entry.typeState.decrement();
                    entry.releaseDroppedTask();
                    return true;
                }

                try {
                    tunnel.queue.offer(entry);
                } catch (Throwable failure) {
                    if (tunnel.queue.isFailed()) {
                        tunnel.fail("迁移发布形成永久死槽，sequence=" + tunnel.queue.failureIndex(), failure);
                    }
                    if (TaskEntry.STATE.compareAndSet(entry, TaskEntry.QUEUED, TaskEntry.MOVING)) {
                        Task currentTask = entry.task();
                        boolean ownerRestored = currentTask != null
                                && currentTask.compareAndSetOwner(tunnel.target.slot, this.slot);
                        entry.logicalCounter = entry.typeState;
                        entry.nonStrictTunnel = null;
                        tunnel.decrement();
                        TaskEntry.STATE.setRelease(entry, TaskEntry.QUEUED);
                        if (entry.applyPendingCancel()) {
                            entry.releaseDroppedTask();
                            return true;
                        }
                        if (!ownerRestored) {
                            this.freezeEntry(entry, "非严格迁移发布失败后无法恢复所有权，sequence=" + sequence);
                            return true;
                        }
                        // 任务从未进入目标队列，原分区计数也尚未减少，可以保持当前位置继续本地执行。
                        return false;
                    }

                    if (entry.state() == TaskEntry.CANCELLED) {
                        // 取消方已经减少接收方计数；任务不会进入任何队列，迁移方负责移除仍保留的源计数。
                        entry.typeState.decrement();
                        entry.releaseDroppedTask();
                        return true;
                    }
                    this.freezeEntry(entry, "非严格迁移发布失败时状态不可恢复，sequence=" + sequence);
                    return true;
                }

                // 物理发布成功后才能减少交出方，短暂双计只会保守地推迟控制动作。
                entry.typeState.decrement();
                tunnel.target.wakeWorker();
                return true;
            } finally {
                entry.finishMove();
            }
        }

        /**
         * 业务作用：按原始主队列 FIFO 把严格存量任务发布到唯一盗洞存量队列，发布失败后冻结而不回队尾。
         *
         * @param entry 原分区唯一 consumer 已取得的严格任务
         * @param tunnel 当前 MIGRATING 路由绑定的严格盗洞
         * @param sequence 原始主队列槽位序号
         * 返回: 无返回值；成功后接收方取得逻辑所有权，失败后任务保留为类型级故障证据。
         */
        void moveStrictEntry(TaskEntry entry, StrictTunnel tunnel, long sequence) {
            tunnel.increment();
            if (!entry.beginMove("STRICT_STOCK_TO_TUNNEL", this, tunnel.target, sequence)) {
                tunnel.decrement();
                if (entry.state() == TaskEntry.CANCELLED) entry.releaseDroppedTask();
                return;
            }

            try {
                Task task = entry.task();
                if (task == null || !task.compareAndSetOwner(this.slot, tunnel.target.slot)) {
                    TaskEntry.STATE.setRelease(entry, TaskEntry.QUEUED);
                    tunnel.decrement();
                    if (entry.applyPendingCancel()) {
                        entry.releaseDroppedTask();
                        return;
                    }
                    tunnel.fail("严格存量迁移所有权 CAS 失败，sequence=" + sequence, entry);
                    return;
                }

                entry.logicalCounter = tunnel;
                entry.strictTunnel = tunnel;
                // 新盗洞建立了新的执行权代次，清除历史归还身份以免未来 RETURNING 误判物理来源。
                entry.returnContext = null;
                TaskEntry.STATE.setRelease(entry, TaskEntry.QUEUED);
                if (entry.applyPendingCancel()) {
                    // 严格源条目已摘除且未写入 stockQueue，按接收后源顺序补齐两侧计数并回收。
                    entry.typeState.decrement();
                    entry.releaseDroppedTask();
                    return;
                }

                try {
                    tunnel.stockQueue.offer(entry);
                } catch (Throwable failure) {
                    if (entry.state() == TaskEntry.CANCELLED) {
                        // 取消方已经减少目标计数，任务也未进入目标队列；迁移方补减仍保留的源计数。
                        entry.typeState.decrement();
                        entry.releaseDroppedTask();
                    } else {
                        tunnel.decrement();
                        entry.logicalCounter = entry.typeState;
                        entry.strictTunnel = null;
                        TaskEntry.STATE.setRelease(entry, TaskEntry.QUEUED);
                        entry.publishOwner(OWNER_FAILED);
                    }
                    tunnel.fail(
                            "严格存量队列发布失败，sourceSequence=" + sequence
                                    + ", failedSequence=" + tunnel.stockQueue.failureIndex(),
                            entry
                    );
                    return;
                }

                // 接收队列发布完成后再减少源计数，控制面最多观察到保守双计，不会提前归零。
                entry.typeState.decrement();
                tunnel.target.wakeWorker();
            } finally {
                entry.finishMove();
            }
        }

        /**
         * 业务作用：把已经离开公共队列但无法安全执行的任务登记为未执行证据，禁止静默丢失或尾部重排。
         *
         * @param entry 无法继续推进的任务条目
         * @param reason 冻结原因
         * 返回: 无返回值；严格类型同时发布类型级 FAILED。
         */
        void freezeEntry(TaskEntry entry, String reason) {
            this.failType(entry.typeState, reason, entry);
        }

        /**
         * 业务作用：关闭单个任务类型的执行与新入队，并保留已脱离公共队列的任务引用作为恢复证据。
         *
         * @param typeState 失去安全推进条件的类型状态
         * @param reason 故障原因
         * @param evidence 已脱离队列的任务；没有时可为 null
         * 返回: 无返回值；其他不依赖本类型专属状态的类型仍可继续。
         */
        void failType(TypeState typeState, String reason, TaskEntry evidence) {
            typeState.failed.set(true);
            if (typeState.strictOrder) {
                StrictTunnel failedTunnel = null;
                while (true) {
                    StrictRoute current = typeState.strictRoute.get();
                    if (current.state == StrictRouteState.FAILED) {
                        failedTunnel = current.tunnel;
                        break;
                    }
                    StrictRoute failed = new StrictRoute(
                            current.epoch + 1,
                            StrictRouteState.FAILED,
                            current.tunnel,
                            current.returnContext
                    );
                    if (typeState.strictRoute.compareAndSet(current, failed)) {
                        failedTunnel = current.tunnel;
                        break;
                    }
                }
                if (failedTunnel != null && failedTunnel.failed.compareAndSet(false, true)) {
                    failedTunnel.failureReason = reason;
                    // 类型路由冻结后同步撤销目标执行权，保留两个严格 FIFO 作为恢复证据。
                    failedTunnel.target.inboundStrictTunnels.remove(failedTunnel);
                    failedTunnel.target.failedStrictTunnels.offer(failedTunnel);
                    failedTunnel.target.wakeWorker();
                }
            } else {
                NonStrictRoute route = typeState.nonStrictGroup.route.get();
                for (NonStrictTunnel tunnel : route.tunnels) {
                    // 非严格类型不再安全时关闭全部活动盗洞，不能只拒绝原队列的新任务。
                    tunnel.fail("任务类型 FAILED: " + reason, null);
                }
            }
            if (evidence != null && evidence.publishFailed(reason)) {
                this.failedEvidence.offer(evidence);
            }
            log.error("Partition slot {} taskType {} FAILED: {}", this.slot, typeState.taskType, reason);
        }

        /**
         * 业务作用：发布共享原队列/worker 的分区级故障门禁，立即拒绝该原始分区的全部新提交。
         *
         * @param reason 稳定故障原因
         * @param failure 原始异常；没有异常对象时为 null
         * 返回: 无返回值；首次失败计入全局失败分区数，后续调用只补充日志。
         */
        void failSlot(String reason, Throwable failure) {
            this.failureReason = reason;
            boolean newlyFailed = false;
            while (true) {
                SlotLifecycle current = this.lifecycle.get();
                if (current.phase == SlotPhase.FAILED) break;
                SlotLifecycle failed = new SlotLifecycle(this.lifecycleEpoch.incrementAndGet(), SlotPhase.FAILED);
                if (this.lifecycle.compareAndSet(current, failed)) {
                    newlyFailed = true;
                    break;
                }
            }
            if (this.failureCounted.compareAndSet(false, true)) {
                this.partition.failedPartitionCount.incrementAndGet();
            }
            if (newlyFailed) {
                // 本 worker 是所有入站队列的唯一 consumer；失效后先关闭通道，禁止源分区继续越权发布。
                for (NonStrictTunnel tunnel : this.inboundNonStrictTunnels.keySet()) {
                    tunnel.fail("目标分区 worker/共享队列失效: " + reason, failure);
                }
                for (StrictTunnel tunnel : this.inboundStrictTunnels.keySet()) {
                    tunnel.fail("目标分区 worker/共享队列失效: " + reason, null);
                }
            }
            if (failure == null) {
                log.error("Partition slot {} FAILED: {}", this.slot, reason);
            } else {
                log.error("Partition slot {} FAILED: {}", this.slot, reason, failure);
            }
        }

        /**
         * 业务作用：停机时先替换分区提交代次，确保迟到 producer 复验失败后不再写入即将停止的队列。
         *
         * 参数说明: 无。
         * 返回: 无返回值；FAILED 槽位保持 FAILED，不覆盖故障证据。
         */
        void closeSubmissionGate() {
            while (true) {
                SlotLifecycle current = this.lifecycle.get();
                if (current.phase != SlotPhase.ACCEPTING) return;
                SlotLifecycle stopping = new SlotLifecycle(this.lifecycleEpoch.incrementAndGet(), SlotPhase.STOPPING);
                if (this.lifecycle.compareAndSet(current, stopping)) {
                    if (!awaitZero(current.inflight, stopTimeoutMillis())) {
                        this.failSlot("停机等待分区 producer 超时: " + current.inflight.get(), null);
                    }
                    return;
                }
            }
        }

        /**
         * 业务作用：请求唯一 consumer 在排空健康队列后退出，并显式唤醒可能 park 的 worker。
         *
         * 参数说明: 无。
         * 返回: 无返回值；重复请求保持幂等。
         */
        void requestStop() {
            this.running = false;
            for (NonStrictTunnel tunnel : this.inboundNonStrictTunnels.keySet()) {
                tunnel.lease.set(TunnelLease.EXPIRED);
                tunnel.state.compareAndSet(NonStrictTunnel.OPEN, NonStrictTunnel.DRAINING);
                tunnel.group.remove(tunnel);
            }
            this.wakeWorker();
        }

        /**
         * 业务作用：判断本槽位作为源分区的严格盗洞是否仍依赖源 worker 推进，防止停机时源先于目标退出。
         *
         * 参数说明: 无。
         * 返回: 任一未失败严格盗洞仍登记在目标入站集合时返回 true；目标已经排空并摘除后返回 false。
         */
        boolean hasActiveOutboundStrictTunnels() {
            for (TypeState typeState : this.typeStates.values()) {
                if (!typeState.strictOrder) continue;
                StrictRoute route = typeState.strictRoute.get();
                StrictTunnel tunnel = route.tunnel;
                if (tunnel != null
                        && !tunnel.failed.get()
                        && tunnel.target.inboundStrictTunnels.containsKey(tunnel)) return true;
            }
            return false;
        }

        /**
         * 业务作用：等待本槽位 worker 完成排空或故障退出，给全局生命周期提供停止证明。
         *
         * @param timeoutMillis 最长等待毫秒数
         * 返回: worker 已退出返回 true；超时或线程被中断返回 false。
         */
        boolean awaitStopped(long timeoutMillis) {
            try {
                return this.stopped.await(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        /**
         * 业务作用：发布 PARKED 后二次检查队头，消除 producer 在 park 边界发布任务造成的丢唤醒窗口。
         *
         * 参数说明: 无。
         * 返回: 无返回值；任何新任务、故障或停机唤醒都会使 worker 重新检查控制状态。
         */
        void parkUntilWork() {
            this.signal.set(1);
            MPSCLinkedQueue.ConsumerHeadState state = this.queue.consumerHeadState();
            MPSCLinkedQueue.ConsumerHeadState controlState = this.controlQueue.consumerHeadState();
            if ((state != MPSCLinkedQueue.ConsumerHeadState.EMPTY
                    && state != MPSCLinkedQueue.ConsumerHeadState.RESERVED)
                    || this.hasReadyInboundTunnel()
                    || this.hasReadyInboundStrictTunnel()
                    || (controlState != MPSCLinkedQueue.ConsumerHeadState.EMPTY
                    && controlState != MPSCLinkedQueue.ConsumerHeadState.RESERVED)) {
                this.signal.set(0);
                return;
            }
            if (!this.running) {
                this.signal.set(0);
                return;
            }
            if (this.inboundNonStrictTunnels.isEmpty() && this.inboundStrictTunnels.isEmpty()) {
                // 空闲分区需要周期性醒来重新观察全局负载，否则源分区稍后变忙时不会有业务信号唤醒窃取方。
                LockSupport.parkNanos(this, STEAL_RETRY_NANOS);
            } else {
                // 活动租约需要目标 worker 定期醒来续租；定时 park 不依赖新业务任务偶然唤醒。
                LockSupport.parkNanos(this, Math.max(1L, DEFAULT_TUNNEL_LEASE_NANOS / 4L));
            }
            this.signal.set(0);
        }

        /**
         * 业务作用：二次检查任一入站盗洞是否已有可消费或故障槽位，避免 worker 在盗洞任务已到达时误 park。
         *
         * 参数说明: 无。
         * 返回: 至少一个盗洞需要 consumer 推进时返回 true。
         */
        boolean hasReadyInboundTunnel() {
            for (NonStrictTunnel tunnel : this.inboundNonStrictTunnels.keySet()) {
                MPSCLinkedQueue.ConsumerHeadState state = tunnel.queue.consumerHeadState();
                if (state != MPSCLinkedQueue.ConsumerHeadState.EMPTY
                        && state != MPSCLinkedQueue.ConsumerHeadState.RESERVED) return true;
            }
            return false;
        }

        /**
         * 业务作用：检查严格盗洞的存量或已开放增量队列，避免目标 worker 在迁移任务已发布时误 park。
         *
         * 参数说明: 无。
         * 返回: 至少一个严格盗洞存在可推进槽位或迁移完成信号时返回 true。
         */
        boolean hasReadyInboundStrictTunnel() {
            for (StrictTunnel tunnel : this.inboundStrictTunnels.keySet()) {
                MPSCLinkedQueue.ConsumerHeadState stock = tunnel.stockQueue.consumerHeadState();
                if (stock != MPSCLinkedQueue.ConsumerHeadState.EMPTY
                        && stock != MPSCLinkedQueue.ConsumerHeadState.RESERVED) return true;
                if (tunnel.migrationComplete) {
                    MPSCLinkedQueue.ConsumerHeadState incremental =
                            tunnel.incrementalQueue.consumerHeadState();
                    if (incremental != MPSCLinkedQueue.ConsumerHeadState.EMPTY
                            && incremental != MPSCLinkedQueue.ConsumerHeadState.RESERVED) return true;
                }
            }
            return false;
        }

        /**
         * 业务作用：在 producer 发布任务或控制面交接责任后唤醒唯一 consumer，避免依赖偶然业务流量推进。
         *
         * 参数说明: 无。
         * 返回: 无返回值；unpark permit 可安全早于实际 park 到达。
         */
        void wakeWorker() {
            Thread target = this.worker;
            if (target != null && (this.signal.getAndSet(0) == 1 || !this.running)) {
                LockSupport.unpark(target);
            }
        }

        /**
         * 业务作用：把稳定分区故障原因拼接到提交拒绝信息，便于调用方定位原始故障域。
         *
         * @param fallback 尚未记录具体原因时使用的说明
         * 返回: 已记录故障时返回故障原因，否则返回 fallback。
         */
        String failureReason(String fallback) {
            String reason = this.failureReason;
            return reason == null ? fallback : reason;
        }
    }

    /**
     * 不参与对象池复用的公开提交句柄；串行化同一引用上的访问与释放，阻断跨代 ABA。
     */
    private static final class SubmissionHandle implements Submission {

        private TaskEntry entry;
        private final long generation;
        private boolean released;

        /**
         * 业务作用：绑定一笔内部任务条目的不可变借出代次，建立稳定的调用方观察边界。
         *
         * @param entry 本次提交唯一对应的池化条目
         * @param generation 条目本次借出的代次
         * 返回: 构造后句柄保持活动，直到 recycle/close 首次释放。
         */
        SubmissionHandle(TaskEntry entry, long generation) {
            this.entry = entry;
            this.generation = generation;
        }

        /**
         * 业务作用：读取本提交当前生命周期，并保证释放边界不会与本次读取并发穿透到下一代任务。
         *
         * 参数说明: 无。
         * 返回: 本代内部条目的权威公开状态；句柄已经释放或代次失配时抛出 IllegalStateException。
         */
        @Override
        public synchronized Status status() {
            return this.activeEntry().submissionStatus();
        }

        /**
         * 业务作用：只针对本句柄绑定的任务竞争取消权，禁止旧引用影响复用后的新任务。
         *
         * 参数说明: 无。
         * 返回: 本代任务在执行前成功转为 CANCELLED 时返回 true；其他终态返回 false。
         */
        @Override
        public synchronized boolean cancel() {
            return this.activeEntry().cancelSubmission();
        }

        /**
         * 业务作用：读取本提交的拒绝或冻结原因，不允许释放后的旧引用观察新任务诊断字段。
         *
         * 参数说明: 无。
         * 返回: 本代 REJECTED/FAILED 的稳定原因；其他状态通常返回 null，句柄失效时抛异常。
         */
        @Override
        public synchronized String rejectionReason() {
            return this.activeEntry().submissionRejectionReason();
        }

        /**
         * 业务作用：一次性释放调用方对本代条目的外部持有，使物理收口后的内部条目可以安全归池。
         *
         * 参数说明: 无。
         * 返回: 无返回值；重复释放幂等，释放与同一句柄的状态读取/取消互斥。
         */
        @Override
        public synchronized void recycle() {
            if (this.released) return;
            TaskEntry current = this.activeEntry();
            this.released = true;
            this.entry = null;
            // 先使旧句柄永久失效，再开放内部条目归池，避免复用瞬间仍有公开入口可达。
            current.releaseSubmission(this.generation);
        }

        /**
         * 业务作用：复验句柄仍绑定原借出代次，作为所有公开读写操作的统一 ABA 门禁。
         *
         * 参数说明: 无。
         * 返回: 当前代内部条目；句柄已释放或内部代次异常变化时抛出 IllegalStateException。
         */
        private TaskEntry activeEntry() {
            TaskEntry current = this.entry;
            if (this.released || current == null || !current.matchesRecycleGeneration(this.generation)) {
                throw new IllegalStateException("Partition Submission already recycled");
            }
            return current;
        }
    }

    /**
     * 池化任务条目；调用方释放与框架物理脱离使用独立门禁，缺一不可归池。
     */
    private static final class TaskEntry implements ObjectPool.Recycler<TaskEntry> {

        static final int ENQUEUEING = 0;
        static final int QUEUED = 1;
        static final int RUNNING = 2;
        static final int COMPLETED = 3;
        static final int CANCELLED = 4;
        static final int REJECTED = 5;
        static final int MOVING = 6;
        static final int FAILED = 7;
        static final int DELAYED = 8;
        static final int RELEASED = 9;

        private static final VarHandle STATE;
        private static final VarHandle TASK;
        private static final VarHandle CONTEXT;
        private static final VarHandle CANCEL_REQUESTED;
        private static final VarHandle MOVE_FAILURE_RECORDED;
        private static final VarHandle FRAMEWORK_RELEASED;
        private static final VarHandle RECYCLE_HOLDS;
        private static final VarHandle RECYCLE_TOKEN;
        private static final VarHandle RELEASE_REQUESTED;

        static {
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                STATE = lookup.findVarHandle(TaskEntry.class, "state", int.class);
                TASK = lookup.findVarHandle(TaskEntry.class, "task", Task.class);
                CONTEXT = lookup.findVarHandle(TaskEntry.class, "context", RecycleLinkedMap.class);
                CANCEL_REQUESTED = lookup.findVarHandle(TaskEntry.class, "cancelRequested", boolean.class);
                FRAMEWORK_RELEASED = lookup.findVarHandle(
                        TaskEntry.class,
                        "frameworkReleased",
                        boolean.class
                );
                RECYCLE_HOLDS = lookup.findVarHandle(TaskEntry.class, "recycleHolds", int.class);
                RECYCLE_TOKEN = lookup.findVarHandle(TaskEntry.class, "recycleToken", long.class);
                RELEASE_REQUESTED = lookup.findVarHandle(
                        TaskEntry.class,
                        "releaseRequested",
                        boolean.class
                );
                MOVE_FAILURE_RECORDED = lookup.findVarHandle(
                        TaskEntry.class,
                        "moveFailureRecorded",
                        boolean.class
                );
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        private volatile int state = ENQUEUEING;
        private volatile Task task;
        private volatile RecycleLinkedMap<String, Object> context;
        private volatile String rejectionReason;
        private volatile boolean cancelRequested;
        private volatile boolean moveFailureRecorded;
        private volatile boolean frameworkReleased;
        private volatile boolean releaseRequested;
        private volatile int recycleHolds;
        /** 高位为本对象借出代次，低位 1 表示本代已经提交归池，阻止并发双归还跨越下一次借出。 */
        private volatile long recycleToken;
        private volatile int keyHash;
        private volatile String delayUnique;
        private PartitionSlot source;
        private TypeState typeState;
        private volatile LogicalCounter logicalCounter;
        private volatile NonStrictTunnel nonStrictTunnel;
        private volatile StrictTunnel strictTunnel;
        private volatile StrictReturnContext returnContext;
        private volatile PartitionSlot moveSource;
        private volatile PartitionSlot moveTarget;
        private volatile long moveSequence;
        private volatile long moveStartedNanos;
        private volatile String moveStage;
        private volatile long moveRecycleGeneration = -1L;
        private final ObjectPool.PooledHandle<TaskEntry> handle;

        /**
         * 业务作用：创建尚未路由的稳定提交条目，在明确受理结果前由提交线程独占初始化。
         *
         * @param pool 所属 TaskEntry 对象池
         * 返回: 构造完成后仅持有稳定对象池 handle，业务字段由每次借出初始化。
         */
        TaskEntry(ObjectPool<TaskEntry> pool) {
            this.handle = new ObjectPool.PooledHandle<>(pool);
        }

        /**
         * 业务作用：初始化一次对象池借出代次，绑定业务任务和自动释放策略并建立框架基础 hold。
         *
         * @param task 本次提交的业务任务
         * @param autoRecycle fire-and-forget 入口无需稳定句柄时为 true
         * 返回: 无返回值；初始化后状态为 ENQUEUEING，回收代次严格单调增加。
         */
        void initialize(Task task, boolean autoRecycle) {
            long previous = (long) RECYCLE_TOKEN.getAcquire(this);
            long generation = (previous >>> 1) + 1L;
            RECYCLE_TOKEN.setRelease(this, generation << 1);
            this.task = task;
            this.state = ENQUEUEING;
            this.recycleHolds = 1;
            this.frameworkReleased = false;
            this.releaseRequested = autoRecycle;
        }

        /**
         * 业务作用：取得本次借出的稳定代次，供不可池化 SubmissionHandle 建立 ABA 防护。
         *
         * 参数说明: 无。
         * 返回: 当前 recycleToken 的借出代次；不包含低位归池提交标记。
         */
        long recycleGeneration() {
            return ((long) RECYCLE_TOKEN.getAcquire(this)) >>> 1;
        }

        /**
         * 业务作用：复验内部条目仍属于指定提交代次，防止公开旧句柄读写复用后的新任务。
         *
         * @param generation SubmissionHandle 构造时记录的借出代次
         * 返回: 条目仍为该代且尚未提交归池时返回 true。
         */
        boolean matchesRecycleGeneration(long generation) {
            return (long) RECYCLE_TOKEN.getAcquire(this) == generation << 1;
        }

        /**
         * 业务作用：为仍可能回调本条目的外部包装增加回收 hold，禁止业务条目先于包装归池。
         *
         * 参数说明: 无。
         * 返回: 本次条目借出代次，包装释放时必须携带同一代次归还 hold。
         */
        long retainRecycleHold() {
            long token = (long) RECYCLE_TOKEN.getAcquire(this);
            RECYCLE_HOLDS.getAndAdd(this, 1);
            return token >>> 1;
        }

        /**
         * 业务作用：释放定时包装等外部持有者的回收 hold，并在调用方已释放时尝试归池。
         *
         * @param generation 建立 hold 时记录的条目借出代次
         * 返回: 无返回值；代次不匹配的迟到释放被忽略，不能影响已复用的新任务。
         */
        void releaseRecycleHold(long generation) {
            long token = (long) RECYCLE_TOKEN.getAcquire(this);
            if ((token >>> 1) != generation || (token & 1L) != 0L) return;
            int previous = (int) RECYCLE_HOLDS.getAndAdd(this, -1);
            if (previous <= 0) {
                RECYCLE_HOLDS.getAndAdd(this, 1);
                log.error("Partition TaskEntry recycle hold underflow");
                return;
            }
            if (previous == 1) this.tryRecycle(generation);
        }

        /**
         * 业务作用：由唯一物理收口路径释放框架基础 hold，证明条目不再被任何任务队列或注册表使用。
         *
         * 参数说明: 无。
         * 返回: 无返回值；并发或重复收口只允许第一次减少 hold。
         */
        void releaseFrameworkOwnership() {
            long generation = ((long) RECYCLE_TOKEN.getAcquire(this)) >>> 1;
            if (FRAMEWORK_RELEASED.compareAndSet(this, false, true)) {
                this.releaseRecycleHold(generation);
            }
        }

        /**
         * 业务作用：原子提交本代唯一归池动作，代次戳避免并发双归还越过对象池重新借出边界。
         *
         * @param generation 发起释放时观测到的条目借出代次
         * 返回: 无返回值；只有调用方已释放且所有框架 hold 为零时执行实际 ObjectPool.recycle。
         */
        void tryRecycle(long generation) {
            if (!(boolean) RELEASE_REQUESTED.getAcquire(this)
                    || (int) RECYCLE_HOLDS.getAcquire(this) != 0) return;
            long expected = generation << 1;
            if (!RECYCLE_TOKEN.compareAndSet(this, expected, expected | 1L)) return;
            ObjectPool.Recycler.super.recycle();
        }

        /**
         * 业务作用：暴露稳定池化 handle，以 CAS 防止同一借出代次被重复放入 TaskEntry 对象池。
         *
         * 参数说明: 无。
         * 返回: 构造时绑定全局 TaskEntryPool 的 handle。
         */
        @Override
        public ObjectPool.PooledHandle<TaskEntry> handle() {
            return this.handle;
        }

        /**
         * 业务作用：由稳定外部句柄声明调用方已放弃本代观察权，等待框架物理引用全部释放后归池。
         *
         * @param generation 外部句柄创建时记录的条目借出代次
         * 返回: 无返回值；代次失配或本代已经提交归池时静默忽略，不能影响复用后的新任务。
         */
        void releaseSubmission(long generation) {
            long token = (long) RECYCLE_TOKEN.getAcquire(this);
            if (token != generation << 1) return;
            RELEASE_REQUESTED.setRelease(this, true);
            this.tryRecycle(generation);
        }

        /**
         * 业务作用：归池前清空所有任务、路由、上下文和迁移引用，保留 handle 与递增回收代次。
         *
         * 参数说明: 无。
         * 返回: 无返回值；执行后旧句柄进入 RELEASED，直到下一次对象池借出重新初始化。
         */
        @Override
        public void restore() {
            this.state = RELEASED;
            this.task = null;
            this.context = null;
            this.rejectionReason = null;
            this.cancelRequested = false;
            this.moveFailureRecorded = false;
            this.frameworkReleased = false;
            this.releaseRequested = false;
            this.recycleHolds = 0;
            this.keyHash = 0;
            this.delayUnique = null;
            this.source = null;
            this.typeState = null;
            this.logicalCounter = null;
            this.nonStrictTunnel = null;
            this.strictTunnel = null;
            this.returnContext = null;
            this.moveSource = null;
            this.moveTarget = null;
            this.moveSequence = -1L;
            this.moveStartedNanos = 0L;
            this.moveStage = null;
            this.moveRecycleGeneration = -1L;
        }

        /**
         * 业务作用：在提交线程捕获一次跨线程上下文，立即和延迟路径都复用该快照而不读取 worker 上下文。
         *
         * 参数说明: 无。
         * 返回: 捕获成功返回 true；异常时发布 REJECTED、释放任务并返回 false。
         */
        boolean captureContext() {
            try {
                this.context = AnyHolder.snapshot();
                return true;
            } catch (Throwable failure) {
                this.reject("捕获提交上下文失败: " + failure.getClass().getSimpleName());
                return false;
            }
        }

        /**
         * 业务作用：发布尚无分区所有权的 DELAYED 状态，并保存到期时重新计算路由所需的 hash 和定时标识。
         *
         * @param keyHash 到期时使用的原始路由 hash
         * @param unique TimingWheel 惰性取消标识
         * 返回: 无返回值；只允许延迟提交线程在安装定时回调前调用一次。
         */
        void publishDelayed(int keyHash, String unique) {
            this.keyHash = keyHash;
            this.delayUnique = unique;
            STATE.setRelease(this, DELAYED);
        }

        /**
         * 业务作用：由到期回调竞争 DELAYED 到 ENQUEUEING，确保取消、停机和到期至多一个路径取得任务引用。
         *
         * 参数说明: 无。
         * 返回: 本次回调取得到期路由权时返回 true。
         */
        boolean beginExpiry() {
            return STATE.compareAndSet(this, DELAYED, ENQUEUEING);
        }

        /**
         * 业务作用：在延迟任务尚未到期时发布明确拒绝，并只释放一次上下文和业务任务。
         *
         * @param reason 取消登记或停机拒绝原因
         * 返回: 本次成功把 DELAYED 发布为 REJECTED 时返回 true。
         */
        boolean rejectDelayed(String reason) {
            this.rejectionReason = reason;
            if (!STATE.compareAndSet(this, DELAYED, REJECTED)) return false;
            this.publishOwner(OWNER_REJECTED);
            this.releaseDroppedTask();
            return true;
        }

        /**
         * 业务作用：读取尚未释放的业务任务引用，仅允许提交线程或已取得 RUNNING 的 worker 使用。
         *
         * 参数说明: 无。
         * 返回: 当前业务任务；终态释放后可能为 null。
         */
        Task task() {
            return (Task) TASK.getAcquire(this);
        }

        /**
         * 业务作用：在发布 QUEUED 前绑定唯一原始分区、类型状态和跨线程上下文。
         *
         * @param source 原始分区槽位
         * @param typeState 已原子注册的类型策略
         * @param context 提交线程上下文快照；上下文为空时为 null
         * @param logicalCounter 当前逻辑所有者的精确计数器
         * @param nonStrictTunnel 当前落点是非严格盗洞时的引用；原始队列为 null
         * @param strictTunnel 当前落点是严格盗洞时的引用；其他队列为 null
         * 返回: 无返回值；绑定完成后这些控制字段在本次对象池借出生命周期内保持稳定。
         */
        void bind(
                PartitionSlot source,
                TypeState typeState,
                RecycleLinkedMap<String, Object> context,
                LogicalCounter logicalCounter,
                NonStrictTunnel nonStrictTunnel,
                StrictTunnel strictTunnel
        ) {
            this.source = source;
            this.typeState = typeState;
            this.context = context;
            this.logicalCounter = logicalCounter;
            this.nonStrictTunnel = nonStrictTunnel;
            this.strictTunnel = strictTunnel;
        }

        /**
         * 业务作用：以 release 语义发布任务已经拥有逻辑计数并即将进入唯一队列。
         *
         * 参数说明: 无。
         * 返回: 无返回值；只允许提交线程执行一次。
         */
        void publishQueued() {
            STATE.setRelease(this, QUEUED);
        }

        /**
         * 业务作用：读取内部精确状态，供唯一 consumer 在物理摘除后选择执行、跳过或保留证据。
         *
         * 参数说明: 无。
         * 返回: 内部状态整数。
         */
        int state() {
            return (int) STATE.getAcquire(this);
        }

        /**
         * 业务作用：在任务离开源物理队列后发布内嵌转移描述符，再竞争 MOVING，确保暂停线程仍留下可审计证据。
         *
         * @param stage 稳定转移阶段名
         * @param moveSource 当前物理摘除方
         * @param moveTarget 计划接收任务的分区
         * @param sequence 源物理队列槽位序号
         * 返回: 成功取得本次唯一转移权时返回 true；取消或其他终态先到达时撤销描述符并返回 false。
         */
        boolean beginMove(
                String stage,
                PartitionSlot moveSource,
                PartitionSlot moveTarget,
                long sequence
        ) {
            long generation = this.retainRecycleHold();
            this.moveRecycleGeneration = generation;
            try {
                this.moveStage = stage;
                this.moveSource = moveSource;
                this.moveTarget = moveTarget;
                this.moveSequence = sequence;
                this.moveStartedNanos = System.nanoTime();
                this.moveFailureRecorded = false;
                INSTANCE.movingRegistry.put(this, Boolean.TRUE);
                if (STATE.compareAndSet(this, QUEUED, MOVING)) return true;
                INSTANCE.movingRegistry.remove(this);
                // ConcurrentHashMap 弱一致迭代器可能已经取得 key；等待旧审计者退出后才能解除移动 hold。
                INSTANCE.awaitMovingAuditGrace();
                this.clearMoveDescriptor();
                this.moveRecycleGeneration = -1L;
                this.releaseRecycleHold(generation);
                return false;
            } catch (Throwable failure) {
                INSTANCE.movingRegistry.remove(this);
                INSTANCE.awaitMovingAuditGrace();
                this.clearMoveDescriptor();
                this.moveRecycleGeneration = -1L;
                this.releaseRecycleHold(generation);
                throw failure;
            }
        }

        /**
         * 业务作用：在转移计数、所有权和物理发布全部收口后撤销活动描述符，禁止迟到审计误判已完成任务。
         *
         * 参数说明: 无。
         * 返回: 无返回值；重复调用保持幂等。
         */
        void finishMove() {
            long generation = this.moveRecycleGeneration;
            if (this.moveFailureRecorded) {
                while (true) {
                    int current = this.state();
                    if (current != MOVING && current != QUEUED) break;
                    if (STATE.compareAndSet(this, current, FAILED)) {
                        this.publishOwner(OWNER_FAILED);
                        break;
                    }
                }
            }
            this.moveStartedNanos = 0L;
            INSTANCE.movingRegistry.remove(this);
            // 移动 hold 覆盖注册表弱一致读窗口；grace period 后再清字段并开放对象池复用。
            INSTANCE.awaitMovingAuditGrace();
            this.clearMoveDescriptor();
            this.moveRecycleGeneration = -1L;
            if (generation >= 0L) this.releaseRecycleHold(generation);
        }

        /**
         * 业务作用：清除不再活动的转移诊断字段，使稳定提交句柄不会长期持有两侧分区。
         *
         * 参数说明: 无。
         * 返回: 无返回值；只能在 movingRegistry 摘除后调用。
         */
        void clearMoveDescriptor() {
            this.moveSource = null;
            this.moveTarget = null;
            this.moveSequence = -1L;
            this.moveStartedNanos = 0L;
            this.moveStage = null;
        }

        /**
         * 业务作用：对超过总时限的转移只登记一次故障证据，并冻结原始任务类型而不猜测双计窗口。
         *
         * @param reason 转移失败原因
         * 返回: 本次首次记录故障时返回 true；已有审计者完成记录时返回 false。
         */
        boolean failMove(String reason) {
            if (!MOVE_FAILURE_RECORDED.compareAndSet(this, false, true)) return false;
            String detail = reason + ": stage=" + this.moveStage + ", sequence=" + this.moveSequence;
            this.rejectionReason = detail;
            PartitionSlot origin = this.source;
            TypeState type = this.typeState;
            if (origin != null && type != null) {
                origin.failedEvidence.offer(this);
                origin.failType(type, detail, null);
            }
            return true;
        }

        /**
         * 业务作用：由 worker 原子取得本地排队任务的执行权，并复验业务任务所有权没有被外部破坏。
         *
         * @param expectedOwner 当前 worker 的分区号
         * 返回: QUEUED 到 RUNNING 转换成功且所有者匹配时返回 true。
         */
        boolean tryStart(int expectedOwner) {
            Task currentTask = this.task();
            if (currentTask == null || currentTask.getOwner() != expectedOwner) return false;
            return STATE.compareAndSet(this, QUEUED, RUNNING);
        }

        /**
         * 业务作用：在 worker 已取得唯一执行权后恢复提交上下文、隔离业务异常，并发布完成终态。
         *
         * 参数说明: 无。
         * 返回: 无返回值；无论业务成功或抛错都只减少一次逻辑计数，框架 hold 由外层最后引用点释放。
         */
        void runTask() {
            Task currentTask = this.task();
            RecycleLinkedMap<String, Object> currentContext = this.context;
            try {
                AnyHolder.putAll(currentContext);
                currentTask.exec();
            } catch (Throwable failure) {
                log.error("Partition slot {} taskType {} execution failed",
                        this.source.slot,
                        this.typeState.taskType,
                        failure);
            } finally {
                AnyHolder.clear();
                try {
                    currentTask.setOwner(OWNER_COMPLETED);
                } catch (Throwable failure) {
                    log.error("Partition task failed to publish COMPLETED owner", failure);
                }
                int remaining = this.logicalCounter.decrement();
                if (remaining < 0) {
                    this.source.failType(this.typeState, "任务完成后逻辑计数为负数", this);
                }
                STATE.setRelease(this, COMPLETED);
                this.releaseContext();
                TASK.setRelease(this, null);
            }
        }

        /**
         * 业务作用：在任务尚未获得任何队列所有权时发布明确拒绝，并释放上下文和可回收业务任务。
         *
         * @param reason 稳定拒绝原因
         * 返回: 无返回值；重复拒绝不会重复执行回收钩子。
         */
        void reject(String reason) {
            this.rejectionReason = reason;
            if (STATE.compareAndSet(this, ENQUEUEING, REJECTED)) {
                this.publishOwner(OWNER_REJECTED);
                this.releaseDroppedTask();
            }
        }

        /**
         * 业务作用：回滚已经增加逻辑计数但未成功进入主队列的任务，发布拒绝后禁止 consumer 执行。
         *
         * @param reason 队列发布失败原因
         * 返回: 无返回值；只允许 QUEUED 到 REJECTED 的提交异常路径成功。
         */
        void rejectQueued(String reason) {
            this.rejectionReason = reason;
            if (STATE.compareAndSet(this, QUEUED, REJECTED)) {
                this.publishOwner(OWNER_REJECTED);
                this.releaseDroppedTask();
            }
        }

        /**
         * 业务作用：把已受理但失去安全推进条件的任务冻结为 FAILED，保留句柄供故障恢复和人工决策。
         *
         * @param reason 故障原因
         * 返回: 本次成功把 QUEUED 发布为 FAILED 时返回 true。
         */
        boolean publishFailed(String reason) {
            this.rejectionReason = reason;
            if (!STATE.compareAndSet(this, QUEUED, FAILED)) return false;
            this.publishOwner(OWNER_FAILED);
            return true;
        }

        /**
         * 业务作用：迁移窗口内登记一次取消请求，由迁移完成或回滚路径在稳定 QUEUED 所有者上帮助提交终态。
         *
         * 参数说明: 无。
         * 返回: 本次首次登记取消请求时返回 true；已有请求时返回 false。
         */
        boolean requestCancelDuringMove() {
            return CANCEL_REQUESTED.compareAndSet(this, false, true);
        }

        /**
         * 业务作用：在迁移发布新的稳定逻辑所有者后帮助完成已登记取消，避免调用线程等待迁移 producer 恢复。
         *
         * 参数说明: 无。
         * 返回: 本线程或其他帮助者已经把该请求发布为 CANCELLED 时返回 true。
         */
        boolean applyPendingCancel() {
            if (!(boolean) CANCEL_REQUESTED.getAcquire(this)) return false;
            return this.state() == CANCELLED || this.cancelQueued();
        }

        /**
         * 业务作用：在当前稳定逻辑所有者上发布 CANCELLED 并只减少一次计数，业务对象留给物理 consumer 回收。
         *
         * 参数说明: 无。
         * 返回: 本次 CAS 成功并完成逻辑取消时返回 true；此方法不证明队列已经物理摘除。
         */
        boolean cancelQueued() {
            if (!STATE.compareAndSet(this, QUEUED, CANCELLED)) return false;
            this.publishOwner(OWNER_CANCELLED);
            int remaining = this.logicalCounter.decrement();
            if (remaining < 0) {
                this.source.failType(this.typeState, "任务取消后逻辑计数为负数", this);
            }
            return true;
        }

        /**
         * 业务作用：安全发布任务所有权终态；业务任务实现异常不能阻断框架计数和资源收口。
         *
         * @param owner 框架终态哨兵
         * 返回: 无返回值；任务已经释放时直接返回。
         */
        void publishOwner(int owner) {
            Task currentTask = this.task();
            if (currentTask == null) return;
            try {
                currentTask.setOwner(owner);
            } catch (Throwable failure) {
                log.error("Partition task failed to publish owner {}", owner, failure);
            }
        }

        /**
         * 业务作用：原子释放跨线程上下文快照并归还对象池，保证取消、拒绝和完成最多回收一次。
         *
         * 参数说明: 无。
         * 返回: 无返回值；没有上下文或已被其他终态释放时直接返回。
         */
        void releaseContext() {
            RecycleLinkedMap<String, Object> snapshot =
                    (RecycleLinkedMap<String, Object>) CONTEXT.getAndSet(this, null);
            if (snapshot != null) snapshot.recycle();
        }

        /**
         * 业务作用：释放确定不会执行的业务任务和上下文，并调用对象池取消回收钩子。
         *
         * 参数说明: 无。
         * 返回: 无返回值；TASK 的原子 getAndSet 防止重复归池。
         */
        void releaseDroppedTask() {
            this.releaseContext();
            Task dropped = (Task) TASK.getAndSet(this, null);
            if (dropped != null) recycleDropped(dropped);
            // 调用点均位于未入队拒绝或唯一 consumer 物理摘除之后；状态终结本身不能替代这条证明。
            this.releaseFrameworkOwnership();
        }

        /**
         * 业务作用：把内部状态映射为公开提交生命周期，不暴露实现用整数编码。
         *
         * 参数说明: 无。
         * 返回: 当前权威状态对应的公开枚举。
         */
        Submission.Status submissionStatus() {
            return switch (this.state()) {
                case ENQUEUEING -> Submission.Status.ENQUEUEING;
                case QUEUED -> Submission.Status.QUEUED;
                case RUNNING -> Submission.Status.RUNNING;
                case COMPLETED -> Submission.Status.COMPLETED;
                case CANCELLED -> Submission.Status.CANCELLED;
                case REJECTED -> Submission.Status.REJECTED;
                case MOVING -> Submission.Status.MOVING;
                case FAILED -> Submission.Status.FAILED;
                case DELAYED -> Submission.Status.DELAYED;
                case RELEASED -> throw new IllegalStateException("Partition task entry already recycled");
                default -> throw new IllegalStateException("Unknown partition task state");
            };
        }

        /**
         * 业务作用：竞争取消尚未开始执行的排队任务，并由胜出者只减少一次逻辑计数。
         *
         * 参数说明: 无。
         * 返回: 本次成功取消 QUEUED 任务时返回 true；其他状态返回 false。
         */
        boolean cancelSubmission() {
            int current = this.state();
            if (current == DELAYED) {
                if (!STATE.compareAndSet(this, DELAYED, CANCELLED)) return false;
                INSTANCE.delayedRegistry.remove(this);
                String unique = this.delayUnique;
                if (unique != null && TimingWheel.isStarted()) TimingWheel.cancel(unique);
                this.publishOwner(OWNER_CANCELLED);
                this.releaseDroppedTask();
                return true;
            }
            if (current == ENQUEUEING) return this.requestCancelDuringMove();
            if (current == MOVING) return this.requestCancelDuringMove();
            return current == QUEUED && this.cancelQueued();
        }

        /**
         * 业务作用：返回拒绝或冻结原因，帮助调用方区分未受理与已受理后失败。
         *
         * 参数说明: 无。
         * 返回: REJECTED/FAILED 的稳定原因；其他状态通常为 null。
         */
        String submissionRejectionReason() {
            return this.rejectionReason;
        }
    }

    /**
     * 延迟 ActionRecycler 持有的池化间接引用；归池时负责解除 TaskEntry 的回调回收 hold。
     */
    private static final class DelayedReference implements ObjectPool.Recycler<DelayedReference> {

        private TaskEntry entry;
        private long generation;
        private final ObjectPool.PooledHandle<DelayedReference> handle;

        /**
         * 业务作用：创建绑定延迟引用池的稳定载体，避免定时 Action 直接捕获可复用任务条目。
         *
         * @param pool 所属 DelayedReferencePool
         * 返回: 构造完成后只持有对象池 handle，任务引用由每次借出初始化。
         */
        DelayedReference(ObjectPool<DelayedReference> pool) {
            this.handle = new ObjectPool.PooledHandle<>(pool);
        }

        /**
         * 业务作用：绑定本次延迟回调持有的任务条目及其借出代次。
         *
         * @param entry 延迟到期时需要重新路由的条目
         * @param generation 建立回收 hold 时的条目代次
         * 返回: 无返回值；ActionRecycler 归池前引用保持稳定。
         */
        void initialize(TaskEntry entry, long generation) {
            this.entry = entry;
            this.generation = generation;
        }

        /**
         * 业务作用：向无捕获到期策略提供当前任务条目，不把引用复制到临时 lambda。
         *
         * 参数说明: 无。
         * 返回: 当前延迟包装唯一绑定的任务条目。
         */
        TaskEntry entry() {
            return this.entry;
        }

        /**
         * 业务作用：暴露防重复归池 handle，保证一个定时包装最多归还一次间接引用。
         *
         * 参数说明: 无。
         * 返回: 构造时绑定 DelayedReferencePool 的 handle。
         */
        @Override
        public ObjectPool.PooledHandle<DelayedReference> handle() {
            return this.handle;
        }

        /**
         * 业务作用：定时动作正常完成或被取消时解除条目回调 hold，并清除跨生命周期强引用。
         *
         * 参数说明: 无。
         * 返回: 无返回值；带代次释放保证迟到包装不能减少新任务的 hold。
         */
        @Override
        public void restore() {
            TaskEntry current = this.entry;
            long currentGeneration = this.generation;
            this.entry = null;
            this.generation = 0L;
            if (current != null) current.releaseRecycleHold(currentGeneration);
        }
    }

    /**
     * 分区任务条目对象池；稳定 Submission 仅在显式释放后归池，exec 快路径自动释放。
     */
    private static final class TaskEntryPool extends ObjectPool<TaskEntry> {

        /**
         * 业务作用：创建有界任务条目池，容量覆盖常见峰值在途量且允许通过系统属性调节。
         *
         * 参数说明: 无。
         * 返回: 构造完成后池为空，按需创建 TaskEntry。
         */
        TaskEntryPool() {
            super(Math.max(1, Integer.getInteger("nasa.object-pool.partition-task-entry-capacity", 20_000)));
        }

        /**
         * 业务作用：从池中取得条目并初始化本次业务任务、回收代次和句柄策略。
         *
         * @param task 本次提交的业务任务
         * @param autoRecycle fire-and-forget 入口自动释放句柄时为 true
         * 返回: 状态为 ENQUEUEING 且持有一个框架基础 hold 的任务条目。
         */
        TaskEntry get(Task task, boolean autoRecycle) {
            TaskEntry entry = super.get();
            entry.initialize(task, autoRecycle);
            return entry;
        }

        /**
         * 业务作用：池为空时创建只绑定本池 handle 的新任务条目。
         *
         * 参数说明: 无。
         * 返回: 尚未绑定业务任务的 TaskEntry。
         */
        @Override
        public TaskEntry newObject() {
            return new TaskEntry(this);
        }
    }

    /**
     * 延迟间接引用对象池；与 ActionRecycler 级联回收，避免正常到期和取消路径产生包装垃圾。
     */
    private static final class DelayedReferencePool extends ObjectPool<DelayedReference> {

        /**
         * 业务作用：创建有界延迟引用池，容量与 TimingWheel 常见任务池规模保持一致。
         *
         * 参数说明: 无。
         * 返回: 构造完成后池为空，可按需扩容到配置上限。
         */
        DelayedReferencePool() {
            super(Math.max(1, Integer.getInteger("nasa.object-pool.partition-delay-reference-capacity", 10_000)));
        }

        /**
         * 业务作用：先为任务条目增加回调 hold，再把条目和代次绑定到池化间接引用。
         *
         * @param entry 将被 TimingWheel 包装持有的任务条目
         * 返回: 已绑定且由 ActionRecycler refRecycle 槽负责归还的引用载体。
         */
        DelayedReference get(TaskEntry entry) {
            long generation = entry.retainRecycleHold();
            try {
                DelayedReference reference = super.get();
                reference.initialize(entry, generation);
                return reference;
            } catch (Throwable failure) {
                // 对象池自身未能给出引用载体时，没有任何回调会负责释放，必须由提交线程撤销 hold。
                entry.releaseRecycleHold(generation);
                throw failure;
            }
        }

        /**
         * 业务作用：池为空时创建绑定本池 handle 的延迟引用。
         *
         * 参数说明: 无。
         * 返回: 尚未持有任务条目的 DelayedReference。
         */
        @Override
        public DelayedReference newObject() {
            return new DelayedReference(this);
        }
    }
}
