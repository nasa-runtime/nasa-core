# nasa-core

面向高吞吐、低 GC 场景的纯 Java 运行时基础库。核心是三块自研的运行时设施——**分区任务窃取执行器 `Partition`**、**分层时间轮 `TimingWheel`** 和**堆内对象池 `ObjectPool`**——以及围绕它们的无锁并发容器、可回收集合与协议编解码能力。

不依赖任何容器或框架，不继承外部 parent POM，日志只依赖 `slf4j-api`。

```xml
<dependency>
    <groupId>io.github.nasa-runtime</groupId>
    <artifactId>nasa-core</artifactId>
    <version>1.0.1</version>
</dependency>
```

要求 JDK 21+（使用虚拟线程与 Java 21 语法），Maven 3.6.3+。

---

## 核心能力

### Partition —— 按 key 路由的分区任务执行器，带任务窃取

把任务按业务 key 哈希到固定原始分区，每个分区一条 worker 独占消费自己的 MPSC 队列。严格任务在“原始分区 + taskType”边界内维持 FIFO；非严格任务允许经多个盗洞并行分发和任务粒度重排，因此不能把“同 key”一概理解为串行执行。空闲 worker 由任务发布直接唤醒，不做周期性全局扫描；唯一的 TimingWheel 1ms 分区观察任务持续调度活动迁移审计并定向唤醒责任 worker，其中每秒执行一次集中热点扫描并安装“盗洞”。

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
while (!Partition.stop()) {
    // stop 超时返回 false 时仍处于 STOPPING；再次调用会继续等待本轮收口。
}
TimingWheel.of().stop();
```

每次提交都必须使用独立的 `Task` 实例；任务对象承载本次提交的所有权状态，入队后不得并发复用或再次提交。
`taskType()` 必须在任务生命周期内保持稳定，并且同一 `taskType` 不能混用不同的 `strictOrder()` 值；
`getOwner()`、`setOwner()` 和 `compareAndSetOwner()` 必须提供线程安全的所有权读写与 CAS，所有权字段由框架管理，业务代码不得并发修改。
这些所有权方法位于调度与终态发布热路径，必须有界且无阻塞，不得执行 I/O、访问外部服务或等待业务锁。

业务任务实现 `Partition.Task`，其中 `strictOrder()` 决定该任务类型是否要求严格保序：

- **严格保序类型**在被窃取、归还的全过程中维持 FIFO。归还走 `RETURN_PREPARE → RETURNING → LOCAL_CATCHUP` 的状态机，配合 `tunnelBoundary`/`localBoundary`/`stagingBoundary` 三个边界保证不倒挂、不重复。
- **非严格类型**使用租约式盗洞，到期自动失效，窃取路径更短。

窃取哪个任务类别由**被窃取方的 worker** 依据自己队列各类别的统计数决定，而不是由窃取方猜测。每轮集中观察为严格候选保留一次独立机会，并在固定数量的轮转目标间接力尝试，防止非严格背景流量或固定目标拒绝使严格热点长期得不到分担；其余请求优先安装非严格盗洞，只有没有合格候选时才迁移严格类型，以限制严格 FIFO 执行权的搬迁频率。

`Submission` 实现 `AutoCloseable`。它是不池化的轻量句柄，与内部池化条目按借出代次绑定：句柄释放后再访问会 `IllegalStateException` 快速失败，而不会读到复用后另一笔任务的状态。`status()` 和 `cancel()` 不是无阻塞 API；并发终态仍在发布业务所有权时会等待完整收口，超过迁移总时限会冻结所属类型并记录故障，但不会返回猜测状态。

可调系统属性：`nasa.partition.partitions`（分区数，默认为 CPU 核数的 2 倍再向上取到 2 的幂）、`nasa.partition.idle-task-threshold`、`nasa.partition.max-inbound-tunnels`、`nasa.partition.return-observations`、`nasa.partition.stop-timeout-ms`、`nasa.partition.transition-timeout-ms`。
分区观察任务的 1ms 调度周期与其中热点扫描的 1s 节流周期是固定架构参数，不提供系统属性调整。

### TimingWheel —— 分层时间轮，稳态热路径无锁提交

参照 Netty `HashedWheelTimer`、Kafka `TimingWheel` 与 Caffeine 的设计取长：MPSC 无锁队列提交（生产者之间不争用同一互斥锁）、分层轮槽（上层粗放存储，到期 flush 降级到下层精确执行）、单信号线程推进指针（无空轮询）、惰性取消（`volatile` 标记，在 flush/drain/exec 时顺带回收）。启动、停机和层级创建仍使用控制面锁，不宣称整个实现无锁。

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

以 `wheelSize=1000, tickMs=1` 为例的分层结构：第一层 1ms 粒度覆盖 0~1s，第二层 1s 粒度覆盖 0~1000s，更高层按需创建。默认走虚拟线程执行（`exec`），需要平台线程语义时用 `platform` 系列。

可调系统属性：`nasa.timing-wheel.tick-await-ms`、`nasa.timing-wheel.exec-await-ms`。

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

## 文档与发布

- 贡献方式见 [CONTRIBUTING.md](CONTRIBUTING.md)。
- 安全问题报告方式见 [SECURITY.md](SECURITY.md)。
- Central Portal 和 GitHub 的发布流程见 [RELEASING.md](RELEASING.md)。
- 版本变化见 [CHANGELOG.md](CHANGELOG.md)。

## 许可证

本项目采用 `Apache-2.0 OR MIT` 双许可证，使用方可任选其一。详见 [LICENSE-APACHE](LICENSE-APACHE) 和 [LICENSE-MIT](LICENSE-MIT)。
