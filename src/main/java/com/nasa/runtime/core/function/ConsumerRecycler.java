package com.nasa.runtime.core.function;

import com.nasa.runtime.core.base.ObjectPool;
import com.nasa.runtime.core.utils.ContextUtils;

import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 可池化的 {@code Consumer<T>} 包装器, 零 GC 适配
 * "Collection.forEach / Stream.peek / Stream.forEach / Optional.ifPresent + 捕获外部参数" 场景.
 * <p>
 * 提供 {@link #of(BiConsumer)} / {@link #ofRecycle(BiConsumer)} 对称工厂 + {@code recycleSelf} 开关.
 *   <ul>
 *     <li>forEach / Stream.peek / Stream.forEach — N 次调用, 用 {@code of()} + 手动 {@code recycle()}
 *         (在 accept 里自动归池会让第二个 entry NPE)</li>
 *     <li>{@code Optional.ifPresent} — 0 或 1 次；只有已经确认 Optional 非空时才能用
 *         {@code ofRecycle()}，否则应使用 {@code of()} 并显式回收</li>
 *   </ul>
 * 不确定调用次数就用 {@code of()} 显式管生命周期, 永远不会错.
 *
 * <h2>典型用法 1: list.forEach 过滤 + 写到捕获的输出集合</h2>
 * <pre>{@code
 *   // 替代: orderList.forEach(o -> {
 *   //         if (o.getAmount() > threshold) hits.add(o.getId());
 *   //     });
 *   //     (每次都 new 一个 lambda, capture List<String> 引用 + long threshold 装箱)
 *   //
 *   ConsumerRecycler<Order> c = ConsumerRecycler
 *           .<Order>of((order, ar) -> {
 *               List<String> out = ar.ref(0);
 *               long threshold = ar.longVal(0);
 *               if (order.getAmount() > threshold) out.add(order.getId());
 *           })
 *           .ref(0, hits)
 *           .val(0, 10000L);
 *   try {
 *       orderList.forEach(c);
 *   } finally {
 *       c.recycle();
 *   }
 * }</pre>
 *
 * <h2>典型用法 2: Stream.peek 边遍历边累加</h2>
 * <pre>{@code
 *   AtomicLong total = new AtomicLong();
 *   ConsumerRecycler<Order> c = ConsumerRecycler
 *           .<Order>of((o, ar) -> ar.<AtomicLong>ref(0).addAndGet(o.getAmount()))
 *           .ref(0, total);
 *   try {
 *       List<String> ids = orderStream.peek(c).map(Order::getId).toList();
 *   } finally {
 *       c.recycle();
 *   }
 * }</pre>
 * <h2>典型用法 3: Optional.ifPresent 的零调用风险</h2>
 * <pre>{@code
 *   ConsumerRecycler<User> c = ConsumerRecycler
 *           .<User>ofRecycle((u, ar) -> {
 *               NotifyClient n = ar.ref(0);
 *               int priority = ar.intVal(0);
 *               n.send(u.getId(), priority);
 *           })
 *           .ref(0, notify)
 *           .val(0, 5);
 *   userOpt.ifPresent(c);
 *   // 注意: Optional 为 empty 时 c 一次都不被调用 → 永远不归池, 池漏一格.
 *   //       高频路径请改用 if (opt.isPresent()) { ... try/finally recycle ... } 显式管生命周期.
 * }</pre>
 *
 * <h2>典型用法 4: 嵌套 cascade recycle</h2>
 * <pre>{@code
 *   // ref(1) 是另一个池化的快照对象, iteration 结束后一并回收
 *   RecycleLinkedMap<String, Object> snapshot = AnyHolder.snapshot();
 *   ConsumerRecycler<Order> c = ConsumerRecycler
 *           .<Order>of((o, ar) -> {
 *               Map<String, Object> snap = ar.ref(1);
 *               o.attach(snap);
 *           })
 *           .ref(0, sink)
 *           .refRecycle(1, snapshot);   // ← 标记 cascade, c.recycle() 时一并 snapshot.recycle()
 *   try {
 *       orderList.forEach(c);
 *   } finally {
 *       c.recycle();
 *   }
 * }</pre>
 *
 * <h2>API</h2>
 *   <ul>
 *     <li>{@link #of()} / {@link #of(BiConsumer)} — 借实例 (后者顺带绑 consumer, lambda 签名 {@code (t, ar) -> ...})</li>
 *     <li>{@link #ref(int, Object)} / {@link #refRecycle(int, Object)} — 引用槽位 0~31</li>
 *     <li>{@link #val(int, long)} (+ int/boolean/char 重载) — 原始类型槽位 0~31</li>
 *     <li>{@link #longVal(int)} / {@link #intVal(int)} / {@link #boolVal(int)} / {@link #charVal(int)} — 按类型窄化</li>
 *     <li>显式 {@code c.recycle()} 归池;{@code ofRecycle} 工厂下首次 accept 自动归</li>
 *   </ul>
 *
 * <h2>引用类型槽位</h2>
 * {@link #refs}[32] 随实例池化, {@link #ref(int, Object)} 存(不回收), {@link #refRecycle(int, Object)} 存(标记回收),
 * restore 时按位检查并回收 {@link ObjectPool.Recycler} 实例.
 *
 * <h2>原始类型槽位</h2>
 * {@link #vals}[32] 统一存 long, 零装箱.
 *
 * <h2>池配置</h2>
 * 容量: 默认 2000, 可通过 {@code nasa.object-pool.consumer-recycler-capacity} 调.
 *
 * <h2>常见坑</h2>
 *   <ul>
 *     <li>不要在 accept() 里 recycle(): forEach 还没遍历完, 后续 entry 会拿到空 consumer / null refs, NPE 或串数据.
 *         归池必须在 forEach 出 scope 后, 用 try-with-resources 最稳.</li>
 *     <li>不要把一个 recycler 同时塞给两个 forEach: refs/vals 是共享状态, 两个 iteration 会互相覆盖捕获参数.
 *         并发场景每个调用点借自己的实例.</li>
 *     <li>泛型 hint 必须写在工厂上: {@code ConsumerRecycler.<Order>of(...)} 让编译器推 lambda 参数类型.
 *         不写就只能 lambda 内 {@code (Order o, ConsumerRecycler<Order> ar) -> ...} 自己声明.</li>
 *     <li>refRecycle 的对象不要传出 lambda: 标了 refRecycle 的对象在 owner recycle 时立即归池,
 *         如果你在 accept 内把它传给另一个异步任务/外部作用域, 异步任务读到的就是别人改写后的脏数据.
 *         要异步使用就别标 refRecycle, 业务自己控制生命周期.</li>
 *   </ul>
 */
@SuppressWarnings("unused")
public class ConsumerRecycler<T> implements Consumer<T>, ObjectPool.Recycler<ConsumerRecycler<T>> {

    /* 策略, 接收 (T, recycler) 二元组, 通过 recycler 访问 refs/vals 中的捕获参数 */
    BiConsumer<T, ConsumerRecycler<T>> consumer;
    /* 是否在 accept() 末尾自我回收. 默认 false, ofRecycle 工厂打开. 仅限能保证回调恰好执行一次的场景 */
    boolean recycleSelf;
    /* 引用类型槽位, 随实例池化, 零 GC */
    final Object[] refs = new Object[32];
    /* refs 回收位掩码, bit i = 1 表示 refs[i] 需要在 restore 时回收 */
    long refsRecycle;
    /* 原始类型槽位, 随实例池化, 零 GC */
    final long[] vals = new long[32];

    private ConsumerRecycler() {}

    // 池层面统一当 <Object> 处理, 借出后强转回 <T>
    static final ObjectPool<ConsumerRecycler<Object>> POOL = new ObjectPool<>(
            ContextUtils.getPropertyInt("nasa.object-pool.consumer-recycler-capacity", 2000)) {
        @Override
        public ConsumerRecycler<Object> newObject() {
            return new ConsumerRecycler<>();
        }
    };

    @SuppressWarnings({"unchecked", "rawtypes"})
    private final ObjectPool.PooledHandle<ConsumerRecycler<T>> handle =
            (ObjectPool.PooledHandle) new ObjectPool.PooledHandle<>(POOL);

    /**
     * forEach / peek / ifPresent 入口: 由调用方触发, 每个 T 调一次.
     * <p>
     * 默认 recycleSelf=false, 调用方负责 try-with-resources / 显式 recycle.
     * 如果 borrow 时用了 {@link #ofRecycle}, finally 块在本次 accept 完后自动归池。
     * <b>仅适用于</b>回调恰好执行一次的场景：Optional.ifPresent 在空值时不会调用回调，实例无法归池；
     * forEach/peek 执行多次，会在第二个 entry 上出现 NPE。
     */
    @Override
    public void accept(T t) {
        try {
            this.consumer.accept(t, this);
        } finally {
            if (recycleSelf) this.recycle();
        }
    }

    /**
     * 链式设置 recycleSelf, 业务确认回调会恰好执行一次后才打开
     */
    public ConsumerRecycler<T> recycleSelf(boolean recycleSelf) {
        this.recycleSelf = recycleSelf;
        return this;
    }

    @Override
    public ObjectPool.PooledHandle<ConsumerRecycler<T>> handle() {
        return this.handle;
    }

    @Override
    public void restore() {
        this.consumer = null;
        this.recycleSelf = false;
        Arrays.fill(this.vals, 0);
        for (int i = 0; i < this.refs.length; i++) {
            if ((this.refsRecycle & (1L << i)) != 0 && this.refs[i] instanceof ObjectPool.Recycler<?> r) r.recycle();
            this.refs[i] = null;
        }
        this.refsRecycle = 0;
    }

    // ==================== 工厂 ====================

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> ConsumerRecycler<T> of() {
        return (ConsumerRecycler) POOL.get();
    }

    /**
     * 绑定策略: lambda 签名 {@code (t, ar) -> ...}, ar 即本实例, 可读 refs/vals.
     * recycleSelf=false, 调用方负责生命周期 (forEach/peek 用这个).
     */
    public static <T> ConsumerRecycler<T> of(BiConsumer<T, ConsumerRecycler<T>> consumer) {
        ConsumerRecycler<T> r = of();
        r.consumer = consumer;
        return r;
    }

    /**
     * 绑定策略 + recycleSelf=true: accept() 末尾自动归池.
     * <p>
     * <b>仅限恰好调用一次</b>。{@code Optional.ifPresent} 为空时不会触发 accept，实例无法归池；
     * forEach/peek 执行多次，会在第二个 entry 上出现 NPE。
     */
    public static <T> ConsumerRecycler<T> ofRecycle(BiConsumer<T, ConsumerRecycler<T>> consumer) {
        return of(consumer).recycleSelf(true);
    }

    // ==================== 原始类型槽位: 存 ====================

    public ConsumerRecycler<T> val(int i, long v) {
        this.vals[i] = v;
        return this;
    }

    public ConsumerRecycler<T> val(int i, int v) {
        this.vals[i] = v;
        return this;
    }

    public ConsumerRecycler<T> val(int i, boolean v) {
        this.vals[i] = v ? 1 : 0;
        return this;
    }

    public ConsumerRecycler<T> val(int i, char v) {
        this.vals[i] = v;
        return this;
    }

    // ==================== 原始类型槽位: 取 ====================

    public long longVal(int i) {
        return this.vals[i];
    }

    public int intVal(int i) {
        return (int) this.vals[i];
    }

    public boolean boolVal(int i) {
        return this.vals[i] != 0;
    }

    public char charVal(int i) {
        return (char) this.vals[i];
    }

    // ==================== 引用类型槽位 ====================

    public ConsumerRecycler<T> ref(int i, Object v) {
        this.refs[i] = v;
        return this;
    }

    public ConsumerRecycler<T> refRecycle(int i, Object v) {
        this.refs[i] = v;
        this.refsRecycle |= (1L << i);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <R> R ref(int i) {
        return (R) this.refs[i];
    }
}
