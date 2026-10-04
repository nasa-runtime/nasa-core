# nasa-core

[中文](README.md) | [English](README.en.md)

A pure Java runtime library for applications that need high throughput and low allocation rates, running on **JDK 21 or later**.
Its core facilities are the **`Partition` executor with partitioned work stealing**, the **hierarchical `TimingWheel`**,
and the **on-heap `ObjectPool`**, supported by concurrent containers, recyclable collections, Snowflake IDs, and protocol codecs.
`Partition` and `TimingWheel` isolate task state, execution resources, backpressure, and lifecycle by stable Runner name.
When an accepted task can no longer progress safely, the runtime publishes an observable terminal state and releases
strong references to business objects.

The library has no container or framework dependency and no external parent POM. Logging depends only on `slf4j-api`.

```xml
<dependency>
    <groupId>io.github.nasa-runtime</groupId>
    <artifactId>nasa-core</artifactId>
    <version>1.0.4</version>
</dependency>
```

Building and running require JDK 21 or later; builds require Maven 3.6.3 or later. The Maven JDK range is `[21,)`, with no upper bound.
`release=21` retains the Java 21 API and bytecode baseline when building on a newer JDK.
Source builds also require Lombok to support the selected JDK compiler. The build uses Lombok `1.18.42`; JDK 21 or 25 can be used to build it.

## Architecture and safety invariants

Stable application-level Runner names define execution domains. A named `PartitionRunner` always binds to the
`TimingWheelRunner` with the same name. Static APIs use the reserved `default` name, sharing the instance returned by
`of("default")`. Runners independently own threads, queues, wheel slots, task indexes, pools, backpressure, and lifecycle;
stopping or saturating one does not propagate its state into another.

```text
Immediate submission -> PartitionRunner -> original queue -> strict transfer / relaxed stealing -> worker
Delayed submission   -> TimingWheelRunner -> expiry callback -> PartitionRunner routing
Observation          -> TimingWheelRunner -> hotspot scan / transfer audit -> responsible worker
Failure completion   -> container ownership handoff -> sole finalizer -> terminal state / release references / recycle
```

The runtime requires these invariants:

- Start the `TimingWheelRunner` before its `PartitionRunner`; stop them in reverse order. Stopping Partition does not
  stop the wheel because independent scheduled tasks may still use it.
- Strict tasks guarantee FIFO only within an original partition and `taskType`. Non-strict tasks may run concurrently
  through multiple stealing tunnels and may be reordered.
- Use a separate `Task` instance for every submission. The framework controls ownership fields; business code must not
  reuse a submitted task concurrently or change its ownership.
- `isHealthy()` means worker and observation dependencies are complete. It is neither a liveness probe nor proof that
  every submission is rejected when false. After independently restarting a wheel, stop and restart its Partition Runner
  to restore observation tasks.
- Queues, processing stacks, and failure-finalization queues explicitly own entries. Recycling requires completed task,
  context, routing, and logical-accounting cleanup, with the entry detached from its last physical container.
- Runner registries do not evict names automatically. Use a bounded set of application names, not tenant or order IDs.

## Core facilities

### Partition: key routing and work stealing

Tasks hash to a fixed original partition whose worker exclusively consumes its MPSC queue. Strict tasks preserve FIFO
within original partition + `taskType`. Non-strict work can be distributed concurrently through several tunnels, so a
shared key alone does not guarantee serial execution. Publishing work wakes idle workers directly rather than polling
all workers. Each Runner has a 1 ms observation task on its matching wheel: it audits active transfers and wakes their
responsible workers, with a centralized hotspot scan once per second to install stealing tunnels.

Message adapters can expose routing through `PartitionedEventListener<T, TS>`. `partitionKey(T)` returns the business
ordering key; null means no key-order requirement. `partition()` returns an explicit `PartitionRunner` or null for an
adapter-provided default. This interface declares capabilities only. It does not implement acknowledgments, contiguous
offset commits, consumer-group fencing, backpressure, or Runner lifecycle. Redis, Kafka, and other adapters must implement
their own delivery protocol and explicitly distinguish ordinary and Partition listeners instead of inferring mode from defaults.

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

All static entry points delegate to `default`. For a separate business domain, obtain matching named Runners:

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

`Partition.of(name)` binds permanently to `TimingWheel.of(name)`, which must already be started. Each domain has its own
queues, workers, migration state, delayed registrations, health, and failure counts. System properties such as the partition
count remain process-wide, so every Runner uses the same configured values. Each additional Runner adds workers, queues,
wheel resources, and pool capacity; keep names bounded.

Every submission requires its own `Task`. `taskType()` must remain stable throughout its lifetime, and one type must not
mix `strictOrder()` values. `getOwner()`, `setOwner()`, and `compareAndSetOwner()` must provide thread-safe reads, writes,
and CAS. These hot-path operations must be bounded and nonblocking: no I/O, remote service access, or business-lock waits.

- Strict types retain FIFO while work is stolen and returned. Return follows
  `RETURN_PREPARE → RETURNING → LOCAL_CATCHUP`, with `tunnelBoundary`, `localBoundary`, and `stagingBoundary`
  preventing inversions and duplicates.
- Non-strict types use shorter lease-based tunnels that expire automatically.

The source worker chooses which type can be stolen using its own queue counts. Each centralized observation gives strict
candidates an independent opportunity across a bounded rotating set of targets, preventing non-strict background traffic
or repeated target rejection from indefinitely excluding a strict hotspot. Other requests prefer non-strict tunnels and
move strict types only when no suitable alternative exists, limiting FIFO authority transfers.

`Submission` is a lightweight, nonpooled `AutoCloseable` handle bound to an internal entry's borrow generation. Access
after close throws `IllegalStateException` instead of exposing a later task that reused the entry. `status()` and
`cancel()` are not nonblocking: they wait for concurrent terminal ownership publication to finish. Exceeding the transfer
deadline freezes the type and records failure instead of returning a guessed state.

#### Failure isolation, terminal states, and resource cleanup

A lost strict route, transfer boundary, or counting invariant freezes only the affected `taskType`. Loss of a shared
worker or primary queue closes submissions for that original partition and increments `failedPartitionCount()`.
Other types, partitions, and Runners do not inherit that failure state.

Accepted work does not silently disappear when control fails. Tasks with exclusive execution authority and complete
ordering evidence can finish. Tasks that cannot safely proceed and have not begun publish `Submission.Status.FAILED`
with a stable `rejectionReason()` and do not execute business code. An executing task remains the responsible worker's
completion obligation; the runtime does not claim it never ran.

Cleanup does not depend on garbage collection of a queue. Containers explicitly transfer physical ownership. The sole
finalizer waits for possible late producers, publishes the terminal state, settles logical counts, disconnects strong
task/context/tunnel/route references, then recycles an entry without an external handle. Recycling additionally requires
the caller's handle release, zero framework holds, and detachment from every physical container. Successful shutdown
drains all remaining failure-finalization obligations.

`FAILED` is terminal and does not automatically retry the business task. Use `submit()` and close its handle when an
individual result matters. `exec()` has no handle and relies on runtime state and logs. Type-level freezing neither
increments `failedPartitionCount()` nor independently makes `isHealthy()` false; applications must alert on type-failure logs.

When `stop()` returns false, the Runner remains `STOPPING`. A bounded retry may succeed if a late producer was the cause.
A strict tunnel whose source cannot progress has no automatic background escape. Persistent failure requires an
operational decision using health, failed-partition counts, and logs. Do not retry indefinitely or stop the bound wheel
before Partition finishes cleanup.

System properties: `nasa.partition.partitions` defaults to twice the processor count rounded up to a power of two;
also available are `nasa.partition.idle-task-threshold`, `nasa.partition.max-inbound-tunnels`,
`nasa.partition.return-observations`, `nasa.partition.stop-timeout-ms`, and `nasa.partition.transition-timeout-ms`.
The 1 ms observer period and 1 s hotspot-scan throttle are fixed architecture parameters.

### TimingWheel: hierarchical scheduling with lock-free steady-state submission

Producers submit through a lock-free MPSC queue to a single consumer. Coarse upper slots hold long delays and flush them
into finer levels as they become due. One signal thread advances the wheel without empty polling. Cancellation marks a
volatile flag; flush, drain, or execution later reclaims the task. These properties concern the data path: startup,
shutdown, and level creation still use control locks.

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

Static calls share the `default` Runner:

```java
TimingWheel.exec(1000L, action);
TimingWheel.of("default").exec(1000L, action);
```

Each named Runner owns its task index, submission queue, slots, tick scheduler, virtual-thread executor, and platform pool.
The same `unique` value can safely exist in separate domains; cancellation, rescheduling, shutdown, and backpressure remain local.

```java
TimingWheel.TimingWheelRunner order = TimingWheel.of("order").start();
TimingWheel.TimingWheelRunner settlement = TimingWheel.of("settlement").start();

order.exec(1000L, "refresh", orderAction);
settlement.exec(1000L, "refresh", settlementAction);

order.cancel("refresh");       // 不会取消 settlement 的同名任务
order.stop();                  // settlement 继续运行
```

Concurrent `of` calls for the same name return the same internal Runner. `stop()` clears the current generation's scheduled
state and closes its executors; the same object may subsequently `start()` again. Tasks not fired before shutdown do not
survive restart. Stop every Partition Runner before its wheel. Reversing this order makes Partition unhealthy without
guaranteeing that immediate or delayed submissions are all rejected. Restarting the wheel alone does not recreate
Partition observation tasks: stop and restart that Partition Runner as well.

For `wheelSize=1000, tickMs=1`, the first level covers roughly 0–1 s at 1 ms granularity and the second covers 0–1000 s
at 1 s granularity; further levels are created as needed. Granularity is a slot-check interval, not an execution-time SLA.
Signal scheduling, GC, clock changes, executor load, and backlog can make dispatch early or late relative to business
measurements. Lease and rate-window actions that must never run early must recheck their authoritative deadline.

`exec` uses a virtual thread per task. `platform` uses a bounded pool with at most four times the processor count and
queue capacity 8192, rejecting through `AbortPolicy` when saturated. Immediate one-shot platform work throws
`RejectedExecutionException` to its caller. An already-scheduled one-shot task rejected at expiry is removed and recycled;
periodic work retries at a later tick or period. Rejection logs a warning without killing the signal thread. Delivery
under overload is not guaranteed; use a durable task source and business retries when completion is required.

System properties: `nasa.timing-wheel.tick-await-ms` and `nasa.timing-wheel.exec-await-ms`.

## Boundaries and observation

- All queues, scheduled tasks, and submission state are in memory. The library does not provide durable scheduling,
  cross-process takeover, at-least-once delivery, or crash recovery. Durable sources and idempotent retries belong to the application.
- Partition has no global ordering and does not automatically serialize non-strict tasks by key. Strict order depends on
  both the original partition and `taskType`.
- Wheel ticks are checks, not deadlines or a promise never to execute early. Recheck business authority before acting.
- Platform saturation and executor shutdown can reject work; protecting the signal thread does not guarantee delivery.
- `PartitionRunner` exposes `isStarted()`, `isHealthy()`, `failedPartitionCount()`, and `partitionCount()`;
  `TimingWheelRunner` exposes `isStarted()`. Static APIs observe `default`.
- Failed-partition counts exclude type freezes. Use `Submission` for individual outcomes and alerts for failures without handles.
- Thread names and rejection/failure/shutdown logs identify the Runner. No metrics exporter is built in; integrate state and logs with host monitoring.

### ObjectPool: bounded on-heap reuse with duplicate-return protection

`ConcurrentRingQueue` is a bounded Vyukov-style MPMC ring with lock-free offer/poll and no wrapper-node allocation in
steady state. Pooling reduces allocation rate, not the live-object footprint, allowing the same workload to trigger fewer young collections.

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

Choose one `Recycler` implementation style:

- Override `handle()` and retain a `PooledHandle` for CAS-based protection: only the first repeated `recycle()` succeeds.
  A full pool rolls state back and leaves the object collectible instead of retaining an orphan reference.
- Override `objectPool()` for direct return without duplicate-return protection, allowing existing code to integrate.

`cancelledRecycle()` handles objects held by a scheduler whose business callback never ran because the task was cancelled.
`PooledHandle` carries `@JsonIgnoreType`, preventing Jackson from traversing the handle/pool/object cycle; implementing
classes need not annotate every handle with `@JsonIgnore`.

Built-in pool capacities use `nasa.object-pool.*`, including `partition-task-entry-capacity`,
`timing-wheel-task-capacity`, and `recycle-linked-map-capacity`.

### JdkSnowflake: ID generation using only the JDK

`JdkSnowflake` separates ID generation from node coordination. Core combines a relative timestamp, worker ID, and
within-millisecond sequence. Applications supply an allocated worker ID without introducing Spring or Redis into generation.

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

Concurrent instances in one ID domain must have different worker IDs and identical `baseTime`, `workerIdBits`, and
`seqBits`. One instance generates strictly increasing values; ordering across instances is approximate and does not prove
distributed event order. Clock rollback or an exhausted millisecond sequence borrows a future timestamp rather than waiting.

The class does not allocate, lease, or recycle worker IDs or discover members; an integration such as Redis owns those tasks.
Invalid constructor parameters throw `IllegalArgumentException`. There are no background threads or built-in metrics.
Monitor the allocation system and retain a final storage uniqueness constraint.

## Other facilities

- Concurrent containers: `MPSCLinkedQueue` (XADD enqueue, single-consumer dequeue, slot reservations), `MPMCLinkedQueue`,
  `ConcurrentRingQueue`, `ConcurrentLinkedMap`/`ConcurrentLinkedList`, `ConcurrentRingLinkedMap`,
  `AtomicRingInteger`/`AtomicRingLong`, `SyncLock`/`LocalLock`, and `ThreadPoolUtils`.
- Recyclable collections: `RecycleLinkedMap`, `RecycleLinkedList`, and `RecycleLinkedSet` pool both nodes and containers.
- Ring structures: `RingList`, `RingArrayList`, `RingLinkedMap`, `RingInteger`, and `RingLong`.
- Codecs: `Protocol` uses `Object[]`; `ProtocolBytes` uses `byte[]` with VARINT_TLV, BITMAP, BITPACK_TLV, FAST_FIXED, and other modes.
- Utilities: `DateUtils`, `StringUtils`, `ColUtils`, `MapUtils`, `Numeric`, `ReflectUtils`, `ObjMprUtils`, `Trie`,
  `AnyHolder`, `When`, and `SimpleCache`.
- GraalVM native-image: optional type registration through `NasaNativeFeature`.

## Protocol compatibility

- Only fields annotated with `@Protocols` are encoded; no annotated fields means an empty encoding.
- `@Protocols.value()` is a stable wire identifier. Do not change or reuse published values.
- `Protocol.Mode.DENSE` is the default; changing modes changes the wire layout and requires both endpoints to agree.
- `ProtocolBytes.Mode.VARINT_TLV` uses protobuf-style wire types and tags, but extensions such as collection layouts
  belong to this protocol. It cannot directly replace messages generated from `.proto`.
- Binary `ProtocolBytes` modes encode enums by `ordinal`. Only append constants; never insert, remove, or reorder published ones.
- BITMAP, BITPACK_TLV, and FAST_FIXED depend on stable field layouts. Consider historical messages and older consumers before schema changes.

## Native images

Select a package for automatic serialization-type registration:

```text
-Dnasa.native.serialization.package=com.example.app
```

Explicitly enable the feature when building native-image:

```text
--features=io.github.nasaruntime.core.feature.NasaNativeFeature
```

Without a scan package, only built-in core types are registered.

## Integration boundaries

- `ContextUtils` uses a process-local instance registry. `registerSingleton` can explicitly install optional protocol converters.
- `ThreadPoolInitializer.initialize(object)` initializes fields and setters annotated with `@ThreadPool`.
- `VirtualThreadProperties.apply()` publishes scheduler properties before the first virtual thread is created.
- Container lifecycle, transaction-manager adapters, and automatic configuration metadata belong to the integration layer.

## Build

```bash
mvn -B -ntp clean verify
```

Artifacts use Java 21 bytecode and require JDK 21 or later to load. A newer JDK may build and run them; retain `release=21`
when building the library to preserve that minimum.

## Contributing and security

See [Contributing](CONTRIBUTING.en.md) and [Security](SECURITY.en.md).

## License

Choose either [Apache License 2.0](LICENSE-APACHE) or [MIT License](LICENSE-MIT), expressed as `Apache-2.0 OR MIT`.
