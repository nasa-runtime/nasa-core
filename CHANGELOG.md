# 变更记录

本项目遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## 1.0.2 - 2026-08-14

### 新增能力

- 新增纯 JDK `JdkSnowflake`，负责雪花 ID 的位布局、并发生成和时钟回拨处理；本类接收已经分配完成的 `workerId`，不引入 Redis 或容器依赖。

## 1.0.1 - 2026-08-09

### 调度一致性

- 排队任务取消、拒绝或冻结时，终态在业务所有权与逻辑计数发布完成后才对外可见；终态发布期间通过不可执行中间态关闭并发执行和回收窗口。
- 队列发布失败与严格存量迁移异常路径只递减一次逻辑计数，并保留故障证据和任务终态。
- 严格任务在本地追赶阶段并发取消时只结束对应任务，不会冻结整个任务类型。
- 每轮集中观察为严格候选保留有界、轮转的目标尝试机会，避免严格热点长期被非严格候选压制。

### 调度与生命周期

- 分区空闲 worker 改为由业务提交和控制交接直接唤醒，移除每个 worker 的周期性空闲探测。
- 分区控制统一由一条 1ms `TimingWheel` platform 周期任务推进；迁移审计只唤醒责任 worker，集中热点扫描在同一任务内按 1s 固定周期节流。
- `Submission.status()` 与 `cancel()` 在终态发布期间等待完整所有权结果；同一句柄的并发释放通过借出代次和临时回收 hold 隔离，不会访问复用后的任务条目。
- 补充 Spring `@Bean` 注册 `Graceful.Shutdown` 实现时关闭销毁方法推断的说明，避免停机动作被容器重复调用。

## 1.0.0 - 2026-08-08

首个公开版本。从 Nasa Runtime 拆分为独立的纯 Java 基础库，不依赖容器或框架，不继承外部 parent POM。

坐标 `io.github.nasa-runtime:nasa-core`。

### 核心能力

- **`Partition`** —— 按业务 key 路由的分区任务执行器，带任务窃取。每个分区一条 worker 独占消费自己的 MPSC 队列；严格任务在「原始分区 + taskType」边界内维持 FIFO，非严格任务经租约式盗洞并行分发。提供 `submit`（返回可查询、可取消的稳定句柄）与 `exec`（fire-and-forget，零句柄分配）两类入口，以及基于 `TimingWheel` 的延迟入队。
- **`TimingWheel`** —— 分层时间轮。MPSC 无锁队列提交、分层轮槽降级、单信号线程推进、惰性取消；支持一次性与周期任务、按唯一标识取消与改期、虚拟线程与平台线程两种执行语义。
- **`ObjectPool`** —— 有界堆内对象池。底层为 Vyukov 风格 MPMC 环形队列，`PooledHandle` 提供重复归池的 CAS 防御，另有 `cancelledRecycle` 钩子处理「被持有但从未使用即丢弃」的场景。
- **无锁并发容器** —— `MPSCLinkedQueue`、`MPMCLinkedQueue`、`ConcurrentRingQueue`、`ConcurrentLinkedMap`/`ConcurrentLinkedList`、`ConcurrentRingLinkedMap`、`AtomicRingInteger`/`AtomicRingLong`、`SyncLock`/`LocalLock`、`ThreadPoolUtils`。
- **可回收集合与环形结构** —— `RecycleLinkedMap`/`RecycleLinkedList`/`RecycleLinkedSet`（节点与容器均池化）、`RingList`、`RingArrayList`、`RingLinkedMap`、`RingInteger`、`RingLong`。
- **协议编解码** —— `Protocol`（基于 `Object[]`）与 `ProtocolBytes`（基于 `byte[]`，含 VARINT_TLV / BITMAP / BITPACK_TLV / FAST_FIXED 等模式）。线路兼容性约束见 README。
- **通用工具** —— 日期时间、字符串、数值、集合、反射、JavaBeans、JSON、Trie、线程上下文持有器等。
- **GraalVM native-image** —— `NasaNativeFeature` 提供可选的序列化类型注册入口。

### 运行要求

- JDK 21 或更高版本（使用虚拟线程与 Java 21 语法），Maven 3.6.3 或更高版本。
- 编译产物为 Java 21 字节码，低于 JDK 21 的项目无法加载。
- 日志门面只依赖 `slf4j-api`，日志实现由使用方选择；Jackson、Guava、Fastjson2 与 Commons Codec 等功能依赖由 Maven 坐标传递管理。

### 许可

采用 `Apache-2.0 OR MIT` 双许可证，使用方可任选其一。

### 从 Nasa Runtime 内嵌版本迁移

以下是相对于拆分前内嵌版本的破坏性变化，仅对原本在 Nasa Runtime 中使用这些类的调用方有意义：

- **包名**由 `com.nasa.runtime.core.*` 变为 `io.github.nasaruntime.core.*`，`Automatic-Module-Name` 同步为 `io.github.nasaruntime.core`。
- **分区能力从 `TimingWheel` 移出**，独立为 `Partition`。`TimingWheel` 只保留定时职责，原有的分区入口、worker、状态与健康指标已删除。
- **`ReflectException` 不再在构造时打印错误日志与调用栈**。异常只负责抛出，是否记录由捕获方决定；原先依赖这些日志的调用方需要在自己的捕获点补上。
- **`IpUtils` 的归属地能力改为可选依赖**。实现类按系统属性 `nasa.ip-area.class` 优先、其次内置候选的顺序解析一次；实现缺失时静默降级为返回原 IP，不再打印异常堆栈。
- **容器生命周期、事务管理器适配与自动配置元数据不再包含在核心库内**，由应用集成层负责。`ContextUtils` 改用进程内实例注册表，可选组件需通过 `registerSingleton` 显式装配；`@ThreadPool` 字段需由 `ThreadPoolInitializer.initialize(object)` 显式初始化；`VirtualThreadProperties.apply()` 需在首个虚拟线程创建前调用。
