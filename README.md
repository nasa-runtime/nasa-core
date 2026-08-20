# nasa-core

面向高吞吐、低 GC 场景的纯 Java 运行时基础库。核心是三块自研的运行时设施——**分区任务窃取执行器 `Partition`**、**分层时间轮 `TimingWheel`** 和**堆内对象池 `ObjectPool`**——以及围绕它们的无锁并发容器、可回收集合、雪花 ID 与协议编解码能力。`Partition` 与 `TimingWheel` 都支持按稳定 Runner 名称隔离任务状态、执行资源、背压和生命周期；已受理任务失去安全推进条件时会发布可观察终态并主动解除业务强引用，不把失败留存交给 GC 猜测处理。

不依赖任何容器或框架，不继承外部 parent POM，日志只依赖 `slf4j-api`。

```xml
<dependency>
    <groupId>io.github.nasa-runtime</groupId>
    <artifactId>nasa-core</artifactId>
    <version>1.0.3</version>
</dependency>
```

要求 JDK 21+（使用虚拟线程与 Java 21 语法），Maven 3.6.3+。

---

## 运行架构与安全不变量

`TimingWheel` 和 `Partition` 都以应用级稳定 Runner 名称划分执行域。同名
`PartitionRunner` 固定绑定同名 `TimingWheelRunner`；静态 API 使用保留名称 `default`，与显式
`of("default")` 操作同一实例。不同 Runner 独立持有线程、队列、时间轮槽位、任务索引、对象池、
背压状态和生命周期，停止或压满一个执行域不会把状态传播到其它执行域。

```text
立即提交 ──> PartitionRunner ──> 原始分区队列 ──> 严格迁移或非严格盗洞 ──> worker 执行
延迟提交 ──> TimingWheelRunner ──> 到期回调 ─────> PartitionRunner 重新路由
观察控制 ──> TimingWheelRunner ──> 热点扫描与迁移审计 ───────────────> 责任 worker
失败收口 ──> 物理容器交权 ──> 唯一收口者 ──> 发布终态 / 断开引用 / 归还内部条目
```

运行时必须保持以下不变量：

- 先启动 `TimingWheelRunner`，再启动同名 `PartitionRunner`；停机顺序相反。时间轮不会因停止
  Partition 而自动停止，因为同一执行域内可能还有独立定时任务。
- 严格任务只在“原始分区 + `taskType`”边界内承诺 FIFO；非严格任务允许经多个盗洞并行执行和重排。
- 每次提交使用独立 `Task` 实例，任务所有权字段只由框架变更，业务不得并发复用任务或改写所有权。
- `isHealthy()` 表示分区 worker 与观察控制依赖完整，不是存活探针，也不表示返回 `false` 后所有提交
  都已关闭。时间轮被单独重启后，必须停止并重新启动 Partition Runner 才能重建观察任务。
- 队列、处理栈和失败收口队列都必须持有明确的物理所有权。只有任务、上下文、路由与逻辑计数
  全部收口且条目脱离最后一个容器后，内部条目才能归还对象池。
- Runner 注册表不会自动逐出名称。名称必须来自数量有界的应用场景，不能使用租户、订单等无界值。

## 核心能力

### Partition —— 按 key 路由的分区任务执行器，带任务窃取

把任务按业务 key 哈希到固定原始分区，每个分区一条 worker 独占消费自己的 MPSC 队列。严格任务在“原始分区 + taskType”边界内维持 FIFO；非严格任务允许经多个盗洞并行分发和任务粒度重排，因此不能把“同 key”一概理解为串行执行。空闲 worker 由任务发布直接唤醒，不做周期性全局扫描；每个 `PartitionRunner` 各有一条绑定同名 `TimingWheelRunner` 的 1ms 观察任务，持续调度本执行域的活动迁移审计并定向唤醒责任 worker，其中每秒执行一次集中热点扫描并安装“盗洞”。

```java
// Partition 的延迟提交与归还收口依赖 TimingWheel，必须先启动时间轮。
TimingWheel.startTimingWheel();
Partition.start();

// fire-and-forget：不需要句柄，零句柄分配
Partition.exec(orderId, fireAndForgetTask);

// 需要状态查询或取消能力时用 submit，返回稳定句柄
try (Partition.Submission s = Partition.submit(orderId, cancellableTask)) {
    if (s.status() == Partition.Submission.Status.QUEUED) s.cancel();
}

// 延迟入队：延迟部分交给 TimingWheel，到期后才进入分区队列
Partition.exec(orderId, 500L, delayedTask);

// 必须先等 Partition 完全收口，再停止 TimingWheel，避免延迟任务被截断。
if (!Partition.stop()) {
    // 不要无限重试或提前停止时间轮；记录健康状态并进入运维故障处置。
    throw new IllegalStateException("Partition did not converge during shutdown");
}
TimingWheel.of().stop();
```

现有静态入口全部委托给保留名称 `default`，因此 `Partition.exec(...)` 与
`Partition.of("default").exec(...)` 操作同一个执行域。需要隔离业务场景时，显式取得同名 Runner：

```java
TimingWheel.TimingWheelRunner wheel = TimingWheel.of("settlement").start();
Partition.PartitionRunner partition = Partition.of("settlement").start();

partition.exec(orderId, task);
try (Partition.Submission submission = partition.submit(orderId, cancellableTask)) {
    // 按业务需要查询或取消
}

if (!partition.stop()) {
    throw new IllegalStateException("Partition Runner did not converge during shutdown");
}
wheel.stop();
```

`Partition.of(name)` 固定绑定 `TimingWheel.of(name)`；启动时要求同名时间轮已经启动。停止 Partition 不会自动停止时间轮，因为同一业务执行域中的其它定时任务仍可能使用它。不同 Runner 的分区队列、worker、迁移状态、延迟注册表、健康状态和失败计数完全独立；分区数量等系统属性仍是进程级配置，各 Runner 使用相同配置值。

Runner 名称用于应用级、数量有界的稳定执行域，不应使用租户号、订单号等无界业务值动态创建。每个 Runner 都拥有独立 worker 和时间轮执行资源，Runner 数量会直接增加线程、队列和对象池容量。

每次提交都必须使用独立的 `Task` 实例；任务对象承载本次提交的所有权状态，入队后不得并发复用或再次提交。
`taskType()` 必须在任务生命周期内保持稳定，并且同一 `taskType` 不能混用不同的 `strictOrder()` 值；
`getOwner()`、`setOwner()` 和 `compareAndSetOwner()` 必须提供线程安全的所有权读写与 CAS，所有权字段由框架管理，业务代码不得并发修改。
这些所有权方法位于调度与终态发布热路径，必须有界且无阻塞，不得执行 I/O、访问外部服务或等待业务锁。

业务任务实现 `Partition.Task`，其中 `strictOrder()` 决定该任务类型是否要求严格保序：

- **严格保序类型**在被窃取、归还的全过程中维持 FIFO。归还走 `RETURN_PREPARE → RETURNING → LOCAL_CATCHUP` 的状态机，配合 `tunnelBoundary`/`localBoundary`/`stagingBoundary` 三个边界保证不倒挂、不重复。
- **非严格类型**使用租约式盗洞，到期自动失效，窃取路径更短。

窃取哪个任务类别由**被窃取方的 worker** 依据自己队列各类别的统计数决定，而不是由窃取方猜测。每轮集中观察为严格候选保留一次独立机会，并在固定数量的轮转目标间接力尝试，防止非严格背景流量或固定目标拒绝使严格热点长期得不到分担；其余请求优先安装非严格盗洞，只有没有合格候选时才迁移严格类型，以限制严格 FIFO 执行权的搬迁频率。

`Submission` 实现 `AutoCloseable`。它是不池化的轻量句柄，与内部池化条目按借出代次绑定：句柄释放后再访问会 `IllegalStateException` 快速失败，而不会读到复用后另一笔任务的状态。`status()` 和 `cancel()` 不是无阻塞 API；并发终态仍在发布业务所有权时会等待完整收口，超过迁移总时限会冻结所属类型并记录故障，但不会返回猜测状态。

#### 故障隔离、可观察终态与资源收口

`Partition` 区分任务类型故障和原始分区故障。任务类型失去严格路由、迁移边界或计数不变量后，只冻结该
`taskType`；原始分区的共享 worker 或主队列失去推进能力时，该分区关闭新提交并计入
`failedPartitionCount()`。其它任务类型、其它分区和其它 Runner 不因此共享失败状态。

已受理任务不会因为控制面失效而静默消失：仍然具备唯一执行权和完整顺序证据的任务可以继续完成；无法安全推进且
尚未开始执行的任务发布 `Submission.Status.FAILED` 和稳定 `rejectionReason()`，业务任务不再执行。正在执行的任务由已取得执行权的 worker 完成，不伪造未执行终态。

失败收口不依赖队列被 GC 连带释放。队列、处理栈和收口队列之间显式交接物理所有权；唯一收口者等待可能的迟到
producer 退出，再发布终态、结清逻辑计数、断开任务/上下文/盗洞/路由强引用，最后才把无外部句柄的内部条目归还对象池。归池门禁同时检查调用方是否释放句柄、框架 hold 是否归零以及条目是否脱离所有物理容器，避免已归池对象被迟到清理者访问。停机成功前会再次排空全部失败收口责任。

`FAILED` 是只读终态，不会自动重试业务任务。需要逐笔知道结果的调用方应使用 `submit()` 并在使用完后关闭 `Submission`；`exec()` 没有句柄，故障只能通过运行状态和日志观测。类型级冻结不增加 `failedPartitionCount()`，也不单独把 `isHealthy()` 置为 `false`，应用必须对任务类型故障日志配置告警。

`stop()` 返回 `false` 时 Runner 仍处于 `STOPPING`。如果原因是迟到 producer 尚未退出，有限次重试可能收敛；如果严格盗洞已失去源端推进能力，没有后台机制会自动打破该状态。持续 `false` 是运维故障信号，应结合 `isHealthy()`、`failedPartitionCount()` 和故障日志决定告警、人工介入或进程级处置，不应无限重试，也不能在 Partition 收口前先停止绑定时间轮。

可调系统属性：`nasa.partition.partitions`（分区数，默认为 CPU 核数的 2 倍再向上取到 2 的幂）、`nasa.partition.idle-task-threshold`、`nasa.partition.max-inbound-tunnels`、`nasa.partition.return-observations`、`nasa.partition.stop-timeout-ms`、`nasa.partition.transition-timeout-ms`。
分区观察任务的 1ms 调度周期与其中热点扫描的 1s 节流周期是固定架构参数，不提供系统属性调整。

### TimingWheel —— 分层时间轮，稳态热路径无锁提交

定时提交通过 MPSC 无锁队列进入唯一 consumer，生产者之间不争用同一互斥锁；上层轮槽粗放存储长延迟任务，到期 flush 后降级到更细刻度层；单信号线程推进指针，不执行空轮询；取消使用 `volatile` 标记，在 flush、drain 或 exec 阶段顺带回收。这些是数据面热路径特性，启动、停机和层级创建仍使用控制面锁。

```java
TimingWheel.startTimingWheel();

TimingWheel.exec(1000L, () -> { ... });                    // 1 秒后执行（虚拟线程）
TimingWheel.exec(1000L, 5000L, () -> { ... });             // 1 秒后首次，此后每 5 秒
TimingWheel.exec(1000L, "order:" + id, () -> { ... });     // 带唯一标识，可取消/改期
TimingWheel.platform(1000L, () -> { ... });                // 需要平台线程时

TimingWheel.delay("order:" + id, 3000L);                   // 改期
TimingWheel.cancel("order:" + id);                         // 取消

// ...应用继续运行，到停机阶段再关闭时间轮...
TimingWheel.of().stop();                                   // 应用停机时调用
```

静态入口都委托给 `default` Runner，所以下列两种写法操作同一个时间轮：

```java
TimingWheel.exec(1000L, action);
TimingWheel.of("default").exec(1000L, action);
```

命名 Runner 独占任务索引、提交队列、轮槽、tick 调度器、虚拟线程执行器和平台线程池；不同 Runner 可以安全使用相同的 `unique`，取消、改期、停止和背压不会跨执行域传播：

```java
TimingWheel.TimingWheelRunner order = TimingWheel.of("order").start();
TimingWheel.TimingWheelRunner settlement = TimingWheel.of("settlement").start();

order.exec(1000L, "refresh", orderAction);
settlement.exec(1000L, "refresh", settlementAction);

order.cancel("refresh");       // 不会取消 settlement 的同名任务
order.stop();                  // settlement 继续运行
```

同一名称并发 `of` 始终返回同一个内部 Runner。`stop()`清理当前代全部定时状态并关闭本 Runner 执行器，同一对象随后可以重新 `start()`；停机前尚未触发的任务不会跨重启代次执行。进程整体停机时先关闭全部 Partition Runner，再关闭全部 TimingWheel Runner。若业务反向停止绑定时间轮，Partition 会立即报告不健康；该状态表示观察与迁移控制能力不完整，不等同于立即或延迟提交一定被拒绝，也不应直接作为存活探针。单独重启时间轮不会恢复停机时已清除的 Partition 观察任务，必须停止并重新启动该 Partition Runner 才能重新建立完整控制能力。

以 `wheelSize=1000, tickMs=1` 为例的分层结构：第一层 1ms 粒度覆盖 0~1s，第二层 1s 粒度覆盖 0~1000s，更高层按需创建。这里的粒度是轮槽检查刻度，不是执行时刻 SLA；实际派发受信号线程调度、GC、系统时钟变化、执行器负载和任务积压影响，可能相对业务测量的目标时刻提前或滞后。依赖“绝不提前”的租约、限流窗口等业务必须在动作执行前复验自身权威截止时间。

默认 `exec` 使用按任务创建虚拟线程的执行器；`platform` 使用最大线程数为处理器数四倍、队列容量为 8192 的有界平台线程池，饱和时由 `AbortPolicy` 拒绝。`platform` 的立即一次性任务会向调用方抛出 `RejectedExecutionException`；已经进入时间轮的一次性任务在到期派发被拒后会移除并回收，周期任务则在后续刻度或周期重试。拒绝会记录告警且不会终止时间轮信号线程，但时间轮不提供“过载下必达”保证，要求必达的业务应使用持久化任务源和业务重试。

可调系统属性：`nasa.timing-wheel.tick-await-ms`、`nasa.timing-wheel.exec-await-ms`。

## 能力边界与观测

- 所有队列、时间轮任务和提交状态都只在进程内存中保存。本库不是持久化任务系统，不提供跨进程接管、
  至少一次投递或进程崩溃后的任务恢复；要求必达的业务需要持久化任务源和幂等重试。
- `Partition` 不提供全局顺序，也不让同一 key 的非严格任务自动串行；严格顺序范围由原始分区和
  `taskType` 共同确定。
- `TimingWheel` 的 `tickMs` 是检查刻度，不是执行时刻 SLA，也不承诺绝不提前。租约、限流窗口等业务
  必须在动作执行前复验自己的权威截止时间。
- 平台执行器使用有界队列并在饱和时拒绝，虚拟线程执行器也会在停机后拒绝。时间轮会保护信号线程继续
  推进，但不把内存调度转换成过载下必达语义。
- `PartitionRunner` 暴露 `isStarted()`、`isHealthy()`、`failedPartitionCount()` 和 `partitionCount()`；
  `TimingWheelRunner` 暴露 `isStarted()`。静态方法读取 `default` 执行域。
- `failedPartitionCount()` 只统计原始分区级故障，不统计单个 `taskType` 的冻结。逐笔结果使用
  `Submission`；无句柄任务和类型级故障必须通过日志告警补足观测。
- Runner 名称会进入 worker、时间轮和执行器线程名；拒绝、分区故障与停机超时会记录所属 Runner。
  本库不内建指标导出器，应用应把上述状态接入自己的监控系统，并对拒绝与故障日志配置告警。

### ObjectPool —— 有界堆内对象池，防重复归池

底层是 `ConcurrentRingQueue`（Vyukov 风格有界 MPMC 环形队列，offer/poll 全程 lock-free，稳态零包装节点分配）。目的不是降低堆占用——**对象池不改变活对象规模，它降低的是分配率**，让相同业务量触发更少的 Young GC。

```java
final class MyObj implements ObjectPool.Recycler<MyObj> {
    static final ObjectPool<MyObj> POOL = new ObjectPool<>(8192) {
        @Override public MyObj newObject() { return new MyObj(); }
    };
    private final ObjectPool.PooledHandle<MyObj> handle = new ObjectPool.PooledHandle<>(POOL);

    @Override public ObjectPool.PooledHandle<MyObj> handle() { return this.handle; }
    @Override public void restore() { /* 字段复位 */ }
}
```

`Recycler` 实现有两条路径，二选一：

- 覆写 `handle()` 持有 `PooledHandle`——自带 CAS 防御，同一对象重复 `recycle()` 只有第一次生效，池满时状态自动回滚、对象交给 GC，不会留下卡死的孤儿引用；
- 覆写 `objectPool()`——老路径，直接归池，无重复归池防御，供已有代码零侵入接入。

另有 `cancelledRecycle()` 钩子，处理"调度框架持有了对象但因取消而从未调用业务逻辑"的场景，避免对象脱离调用闭环后池化失效。

`PooledHandle` 标注了 `@JsonIgnoreType`，Jackson 序列化时按类型跳过，实现类无需逐个加 `@JsonIgnore`（不跳过会因 `handle → pool → 池内其它对象 → handle` 递归而撞 Jackson 嵌套深度上限）。

各内建池容量均可通过 `nasa.object-pool.*` 系统属性调节，例如 `nasa.object-pool.partition-task-entry-capacity`、`nasa.object-pool.timing-wheel-task-capacity`、`nasa.object-pool.recycle-linked-map-capacity`。

### JdkSnowflake —— 纯 JDK 雪花 ID 生成内核

`JdkSnowflake` 把雪花算法与节点协调解耦：core 只负责相对时间戳、workerId 和毫秒内序列号的位布局与并发生成，调用方可以直接使用已经分配好的 workerId，不需要为 ID 热路径引入 Spring 或 Redis。

```java
long baseTime = 1704038400000L; // 2024-01-01 00:00:00 UTC
JdkSnowflake ids = new JdkSnowflake(
        3,        // 当前实例独占的 workerId
        baseTime,
        6,        // 最多 64 个 workerId
        6         // 每个相对毫秒最多 64 个 ID
);

long id = ids.generate();
long[] batch = ids.generate(1_000); // 一次加锁完成批量生成
```

全局唯一性的安全边界是：同一数据域中的并行实例必须使用互不相同的 workerId，并保持一致的 `baseTime`、`workerIdBits` 和 `seqBits`。实例内结果严格递增；跨实例只大致有序，不能据此判定分布式事件的真实先后。时钟回拨或当前毫秒序列耗尽时会向未来借用时间戳，不等待墙上时钟追平。

本类不分配、续租或回收 workerId，也不提供集群成员发现；这些职责应由 Redis 等集成层承担。构造参数越界会立即抛出 `IllegalArgumentException`。生成器没有后台线程或内建指标，运行方应观测 workerId 分配系统，并保留最终存储的唯一约束作为冲突门禁。

---

## 其余能力

- **无锁并发容器**：`MPSCLinkedQueue`（XADD 入队、单 consumer 出队，带槽位保留状态机）、`MPMCLinkedQueue`、`ConcurrentRingQueue`、`ConcurrentLinkedMap`/`ConcurrentLinkedList`、`ConcurrentRingLinkedMap`、`AtomicRingInteger`/`AtomicRingLong`、`SyncLock`/`LocalLock`、`ThreadPoolUtils`。
- **可回收集合**：`RecycleLinkedMap`/`RecycleLinkedList`/`RecycleLinkedSet`，节点与容器本身都走对象池。
- **环形结构**：`RingList`、`RingArrayList`、`RingLinkedMap`、`RingInteger`、`RingLong`。
- **协议编解码**：`Protocol`（基于 `Object[]`）与 `ProtocolBytes`（基于 `byte[]`，含 VARINT_TLV / BITMAP / BITPACK_TLV / FAST_FIXED 等模式）。
- **通用工具**：`DateUtils`、`StringUtils`、`ColUtils`、`MapUtils`、`Numeric`、`ReflectUtils`、`ObjMprUtils`、`Trie`、`AnyHolder`、`When`、`SimpleCache`。
- **GraalVM native-image**：`NasaNativeFeature` 提供可选类型注册入口。

## 协议兼容性

- `Protocol` 和 `ProtocolBytes` 只处理标有 `@Protocols` 的字段；没有标注字段时编码结果为空。
- `@Protocols.value()` 是稳定线路标识。**已发布后不要复用或修改现有字段的值。**
- `Protocol.Mode.DENSE` 是默认格式；切换模式会改变线路结构，收发两端必须同步。
- `ProtocolBytes.Mode.VARINT_TLV` 使用 protobuf 风格的 wire type 和 tag 编码，但集合等扩展布局属于本项目协议，**不能直接替代由 `.proto` 生成的消息类型**。
- `ProtocolBytes` 的二进制模式按枚举 `ordinal` 编码；枚举常量一经发布只能在末尾追加，不能插入、删除或重排。
- `BITMAP`、`BITPACK_TLV` 和 `FAST_FIXED` 依赖稳定字段布局，修改 schema 前必须评估历史消息和旧消费者。

## 原生镜像

`NasaNativeFeature` 可通过系统属性指定需要自动注册序列化类型的基础包：

```text
-Dnasa.native.serialization.package=com.example.app
```

构建 native-image 时还需显式启用 Feature：

```text
--features=io.github.nasaruntime.core.feature.NasaNativeFeature
```

未配置扫描包时只注册 `nasa-core` 内建的基础类型。

## 独立化边界

- `ContextUtils` 使用进程内实例注册表；可通过 `registerSingleton` 显式装配协议转换器等可选组件。
- `ThreadPoolInitializer.initialize(object)` 显式初始化 `@ThreadPool` 标注的字段和 setter。
- `VirtualThreadProperties.apply()` 在首个虚拟线程创建前发布 JDK 调度器参数。
- 容器生命周期、事务管理器适配以及自动配置元数据由应用集成层负责，不进入核心库。

## 构建

```bash
mvn -B -ntp clean verify
```

编译产物是 Java 21 字节码，低于 JDK 21 的项目无法加载本库。

## 参与和安全

- 贡献方式见 [CONTRIBUTING.md](CONTRIBUTING.md)。
- 安全问题报告方式见 [SECURITY.md](SECURITY.md)。

## 许可证

本项目采用 `Apache-2.0 OR MIT` 双许可证，使用方可任选其一。详见 [LICENSE-APACHE](LICENSE-APACHE) 和 [LICENSE-MIT](LICENSE-MIT)。
