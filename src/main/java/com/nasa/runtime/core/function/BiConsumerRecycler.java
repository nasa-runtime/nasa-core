package com.nasa.runtime.core.function;

import com.nasa.runtime.core.base.ObjectPool;
import com.nasa.runtime.core.utils.ContextUtils;

import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * 可池化的 {@code BiConsumer<T, U>} 包装器, 零 GC 适配
 * "map.forEach / 任意 BiConsumer 入参 + 捕获外部参数" 场景.
 * <p>
 * 提供 {@link #of(Consumer3)} / {@link #ofRecycle(Consumer3)} 对称工厂 + {@code recycleSelf} 开关.
 *   <ul>
 *     <li>{@code map.forEach} — N 次调用, 用 {@code of()} + 手动 {@code recycle()}
 *         (在 accept 里自动归池会让第二个 entry NPE)</li>
 *     <li>你能保证 BiConsumer 只被调一次的场景 — 用 {@code ofRecycle()} 单次后自动归池</li>
 *   </ul>
 * 不确定调用次数就用 {@code of()} 显式管生命周期, 永远不会错.
 *
 * <h2>典型用法 1: map.forEach 写入捕获的目标 map</h2>
 * <pre>{@code
 *   // 替代: sourceMap.forEach((k, v) -> outMap.put(k, v * mul));
 *   //       (每次 new lambda, capture outMap + Long mul 装箱)
 *   //
 *   BiConsumerRecycler<String, Long> bc = BiConsumerRecycler
 *           .<String, Long>of((k, v, ar) -> {
 *               Map<String, Long> out = ar.ref(0);
 *               long mul = ar.longVal(0);
 *               out.put(k, v * mul);
 *           })
 *           .ref(0, outMap)
 *           .val(0, 100L);
 *   try {
 *       sourceMap.forEach(bc);
 *   } finally {
 *       bc.recycle();
 *   }
 * }</pre>
 *
 * <h2>典型用法 2: map.forEach 累加 / 过滤命中</h2>
 * <pre>{@code
 *   AtomicLong gmv = new AtomicLong();
 *   List<String> highValueKeys = new ArrayList<>();
 *   BiConsumerRecycler<String, Order> bc = BiConsumerRecycler
 *           .<String, Order>of((k, o, ar) -> {
 *               AtomicLong g = ar.ref(0);
 *               List<String> hits = ar.ref(1);
 *               long threshold = ar.longVal(0);
 *               g.addAndGet(o.getAmount());
 *               if (o.getAmount() > threshold) hits.add(k);
 *           })
 *           .ref(0, gmv)
 *           .ref(1, highValueKeys)
 *           .val(0, 10000L);
 *   try {
 *       orderMap.forEach(bc);
 *   } finally {
 *       bc.recycle();
 *   }
 * }</pre>
 *
 * <h2>典型用法 3: 嵌套 cascade 回收 (refRecycle)</h2>
 * <pre>{@code
 *   // 用一个池化的 RecycleLinkedMap 作为按 key 分桶的中间结构, forEach 结束一并回收
 *   RecycleLinkedMap<String, List<Long>> bucket = RecycleLinkedMap.get();
 *   BiConsumerRecycler<String, Long> bc = BiConsumerRecycler
 *           .<String, Long>of((k, v, ar) -> {
 *               Map<String, List<Long>> b = ar.ref(0);
 *               b.computeIfAbsent(category(k), x -> new ArrayList<>()).add(v);
 *           })
 *           .refRecycle(0, bucket);   // ← bucket 在 bc.recycle() 时被 cascade recycle
 *   try {
 *       sourceMap.forEach(bc);
 *       process(bucket);              // 注意: 必须在 bc.recycle() 之前用完 bucket
 *   } finally {
 *       bc.recycle();                 // ← bucket 此时归池, 业务方不要再持有 bucket 引用
 *   }
 * }</pre>
 *
 * <h2>API</h2>
 *   <ul>
 *     <li>{@link #of()} / {@link #of(Consumer3)} — 借实例 (后者顺带绑 strategy, lambda 签名 {@code (t, u, ar) -> ...})</li>
 *     <li>{@link #ref(int, Object)} / {@link #refRecycle(int, Object)} — 引用槽位 0~31</li>
 *     <li>{@link #val(int, long)} (+ int/boolean/char 重载) — 原始类型槽位 0~31</li>
 *     <li>显式 {@code bc.recycle()} 归池;{@code ofRecycle} 工厂下首次 accept 自动归</li>
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
 * 容量: 默认 2000, 可通过 {@code nasa.object-pool.biconsumer-recycler-capacity} 调.
 *
 * <h2>常见坑</h2>
 *   <ul>
 *     <li>不要在 accept() 里 recycle(): map.forEach 还没遍历完, 后续 entry 会拿到空 consumer.</li>
 *     <li>不要把一个 recycler 同时塞给两个 forEach: refs/vals 共享状态会串.</li>
 *     <li>泛型 hint 必须写在工厂上: {@code BiConsumerRecycler.<String, Long>of(...)}.</li>
 *     <li>map.forEach 顺序非保证: HashMap 是 hash 序, LinkedHashMap 是插入序. 跟 Recycler 无关, 但累加结果若依赖顺序要注意.</li>
 *     <li>refRecycle 的对象不要传出 lambda: 标了 refRecycle 的对象在 owner recycle 时立即归池,
 *         如果你在 accept 内把它传给另一个异步任务/外部作用域, 异步任务读到的就是别人改写后的脏数据.
 *         要异步使用就别标 refRecycle, 业务自己控制生命周期.</li>
 *   </ul>
 */
@SuppressWarnings("unused")
public class BiConsumerRecycler<T, U> implements BiConsumer<T, U>, ObjectPool.Recycler<BiConsumerRecycler<T, U>> {

    /* 策略, 接收 (T, U, recycler) 三元组, 通过 recycler 访问 refs/vals 中的捕获参数 */
    Consumer3<T, U, BiConsumerRecycler<T, U>> consumer;
    /* 是否在 accept() 末尾自我回收. 默认 false, ofRecycle 工厂打开. 仅限单次调用场景 */
    boolean recycleSelf;
    /* 引用类型槽位, 随实例池化, 零 GC */
    final Object[] refs = new Object[32];
    /* refs 回收位掩码, bit i = 1 表示 refs[i] 需要在 restore 时回收 (必须是 ObjectPool.Recycler), 低 32 位覆盖 refs[0~31] */
    long refsRecycle;
    /* 原始类型槽位, 随实例池化, 零 GC */
    final long[] vals = new long[32];

    private BiConsumerRecycler() {}

    // POOL 用具体参数化避免 ObjectPool<T extends Recycler<T>> 的 self-bound 拒绝 raw 类型;
    // 实例的 T/U 仅靠 strategy 类型擦除后存放, 池层面统一当 <Object, Object> 处理
    static final ObjectPool<BiConsumerRecycler<Object, Object>> POOL = new ObjectPool<>(
            ContextUtils.getPropertyInt("nasa.object-pool.biconsumer-recycler-capacity", 2000)) {
        @Override
        public BiConsumerRecycler<Object, Object> newObject() {
            return new BiConsumerRecycler<>();
        }
    };

    @SuppressWarnings({"unchecked", "rawtypes"})
    private final ObjectPool.PooledHandle<BiConsumerRecycler<T, U>> handle =
            (ObjectPool.PooledHandle) new ObjectPool.PooledHandle<>(POOL);

    /**
     * forEach 入口: 由 map / 其它 BiConsumer 调用方触发, 每个 (T, U) 调一次.
     * <p>
     * 默认 recycleSelf=false, 调用方负责 try-with-resources / 显式 recycle.
     * 如果 borrow 时用了 {@link #ofRecycle}, finally 块在本次 accept 完后自动归池
     * (<b>仅适用于</b> 你能保证 BiConsumer 只被调一次的场景; map.forEach 会在第二个 entry NPE).
     */
    @Override
    public void accept(T t, U u) {
        try {
            this.consumer.accept(t, u, this);
        } finally {
            if (recycleSelf) this.recycle();
        }
    }

    /**
     * 链式设置 recycleSelf, 业务确认是单次调用语义后才打开
     */
    public BiConsumerRecycler<T, U> recycleSelf(boolean recycleSelf) {
        this.recycleSelf = recycleSelf;
        return this;
    }

    @Override
    public ObjectPool.PooledHandle<BiConsumerRecycler<T, U>> handle() {
        return this.handle;
    }

    @Override
    public void restore() {
        this.consumer = null;
        this.recycleSelf = false;
        Arrays.fill(this.vals, 0);
        // 按位回收标记了 recycle 的引用槽位, 然后清空所有引用 (防止持有已回收对象)
        for (int i = 0; i < this.refs.length; i++) {
            if ((this.refsRecycle & (1L << i)) != 0 && this.refs[i] instanceof ObjectPool.Recycler<?> r) r.recycle();
            this.refs[i] = null;
        }
        this.refsRecycle = 0;
    }

    // ==================== 工厂 ====================

    /**
     * 从池中获取空白实例 (需手动赋 consumer + 填 refs/vals 后再交给 forEach)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T, U> BiConsumerRecycler<T, U> of() {
        return (BiConsumerRecycler) POOL.get();
    }

    /**
     * 绑定策略: lambda 签名 {@code (t, u, ar) -> ...}, ar 即本实例, 可读 refs/vals.
     * recycleSelf=false, 调用方负责生命周期 (map.forEach 用这个).
     */
    public static <T, U> BiConsumerRecycler<T, U> of(Consumer3<T, U, BiConsumerRecycler<T, U>> consumer) {
        BiConsumerRecycler<T, U> r = of();
        r.consumer = consumer;
        return r;
    }

    /**
     * 绑定策略 + recycleSelf=true: accept() 末尾自动归池.
     * <p>
     * <b>仅限单次调用语义</b>: 你能保证 BiConsumer 只被调一次的场景. map.forEach 会在第二个 entry NPE.
     */
    public static <T, U> BiConsumerRecycler<T, U> ofRecycle(Consumer3<T, U, BiConsumerRecycler<T, U>> consumer) {
        return of(consumer).recycleSelf(true);
    }

    // ==================== 原始类型槽位: 存 ====================

    /**
     * 存 long
     */
    public BiConsumerRecycler<T, U> val(int i, long v) {
        this.vals[i] = v;
        return this;
    }

    /**
     * 存 int (自动拓宽为 long)
     */
    public BiConsumerRecycler<T, U> val(int i, int v) {
        this.vals[i] = v;
        return this;
    }

    /**
     * 存 boolean (true=1, false=0)
     */
    public BiConsumerRecycler<T, U> val(int i, boolean v) {
        this.vals[i] = v ? 1 : 0;
        return this;
    }

    /**
     * 存 char (自动拓宽为 long)
     */
    public BiConsumerRecycler<T, U> val(int i, char v) {
        this.vals[i] = v;
        return this;
    }

    // ==================== 原始类型槽位: 取 ====================

    /**
     * 取 long
     */
    public long longVal(int i) {
        return this.vals[i];
    }

    /**
     * 取 int (窄化)
     */
    public int intVal(int i) {
        return (int) this.vals[i];
    }

    /**
     * 取 boolean (非零为 true)
     */
    public boolean boolVal(int i) {
        return this.vals[i] != 0;
    }

    /**
     * 取 char (窄化)
     */
    public char charVal(int i) {
        return (char) this.vals[i];
    }

    // ==================== 引用类型槽位 ====================

    /**
     * 存引用, 不回收
     */
    public BiConsumerRecycler<T, U> ref(int i, Object v) {
        this.refs[i] = v;
        return this;
    }

    /**
     * 存引用, 标记回收 (restore 时若为 ObjectPool.Recycler 则自动 recycle)
     */
    public BiConsumerRecycler<T, U> refRecycle(int i, Object v) {
        this.refs[i] = v;
        this.refsRecycle |= (1L << i);
        return this;
    }

    /**
     * 取引用 (泛型窄化, 调用方按实际类型接收)
     */
    @SuppressWarnings("unchecked")
    public <R> R ref(int i) {
        return (R) this.refs[i];
    }
}
