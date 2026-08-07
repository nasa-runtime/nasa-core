package io.github.nasaruntime.core.function;

import io.github.nasaruntime.core.base.ObjectPool;
import io.github.nasaruntime.core.utils.ContextUtils;

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

    /**
     * 业务作用：私有化构造，实例只能经对象池借出，防止绕过池化直接 new 而失去零 GC 收益。
     *
     * 参数说明: 无。
     * 返回: 仅由本类对象池调用。
     */
    private BiConsumerRecycler() {}

    // POOL 用具体参数化避免 ObjectPool<T extends Recycler<T>> 的 self-bound 拒绝 raw 类型;
    // 实例的 T/U 仅靠 strategy 类型擦除后存放, 池层面统一当 <Object, Object> 处理
    static final ObjectPool<BiConsumerRecycler<Object, Object>> POOL = new ObjectPool<>(
            ContextUtils.getPropertyInt("nasa.object-pool.biconsumer-recycler-capacity", 2000)) {
        /**
         * 业务作用：池空时创建新实例。
         *
         * 参数说明: 无。
         * 返回: 槽位全空、未绑定回调的新实例。
         */
        @Override
        public BiConsumerRecycler<Object, Object> newObject() {
            return new BiConsumerRecycler<>();
        }
    };

    @SuppressWarnings({"unchecked", "rawtypes"})
    private final ObjectPool.PooledHandle<BiConsumerRecycler<T, U>> handle =
            (ObjectPool.PooledHandle) new ObjectPool.PooledHandle<>(POOL);

    /**
     * 业务作用：作为 {@code BiConsumer<T,U>} 被调用，把两个入参与自身一起交给 consumer。
     * map.forEach 等场景会调用 N 次，此时必须用 of 而非 ofRecycle。
     *
     * @param t 调用方传入的第一个参数
     * @param u 调用方传入的第二个参数
     * 返回: 无返回值。
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
     * 业务作用：切换自动回收开关，使回调可以在运行期根据结果决定本次执行后是否归池，
     * 例如重试任务成功后才归池、失败则保留实例等待下次调度。
     *
     * @param recycleSelf true 表示回调执行完毕后自动归池
     * 返回: 当前实例，供链式填充槽位。
     */
    public BiConsumerRecycler<T, U> recycleSelf(boolean recycleSelf) {
        this.recycleSelf = recycleSelf;
        return this;
    }

    /**
     * 业务作用：暴露池化身份，启用重复归池的 CAS 防御。
     *
     * 参数说明: 无。
     * 返回: 构造时绑定本类对象池的 handle。
     */
    @Override
    public ObjectPool.PooledHandle<BiConsumerRecycler<T, U>> handle() {
        return this.handle;
    }

    /**
     * 业务作用：归池前清空回调、开关与全部槽位，并按 refsRecycle 位掩码级联回收被标记为独占的池化引用。
     * 清空引用是必需的：残留引用会让已归池实例继续强引用业务对象，既造成内存滞留，
     * 也会让下一个借用方在未覆盖的槽位上读到上一代数据。
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
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
     * 业务作用：借出一个空白实例，由调用方自行绑定回调与槽位。
     *
     * 参数说明: 无。
     * 返回: 已复位的实例；用完必须 recycle 归池。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T, U> BiConsumerRecycler<T, U> of() {
        return (BiConsumerRecycler) POOL.get();
    }

    /**
     * 业务作用：借出实例并绑定回调，默认不自动回收，生命周期由调用方掌握。
     * 多次调用的场景（forEach、Stream.map、replaceAll）必须使用本入口。
     *
     * @param consumer 回调实现，执行时会拿到本实例以读取槽位
     * 返回: 已绑定回调的实例；用完必须显式 recycle 归池。
     */
    public static <T, U> BiConsumerRecycler<T, U> of(Consumer3<T, U, BiConsumerRecycler<T, U>> consumer) {
        BiConsumerRecycler<T, U> r = of();
        r.consumer = consumer;
        return r;
    }

    /**
     * 业务作用：借出实例并绑定回调，同时打开自动回收，执行一次后自动归池。
     * 前置条件是回调恰好被调用一次：调用零次实例无法归池并永久占用一个池槽位，
     * 调用多次则第一次后回调已被清空，后续调用会读到已归池甚至被他人改写的实例。
     *
     * @param consumer 回调实现，执行时会拿到本实例以读取槽位
     * 返回: 已绑定回调且开启自动回收的实例。
     */
    public static <T, U> BiConsumerRecycler<T, U> ofRecycle(Consumer3<T, U, BiConsumerRecycler<T, U>> consumer) {
        return of(consumer).recycleSelf(true);
    }

    // ==================== 原始类型槽位: 存 ====================

    /**
     * 业务作用：把 long 存入基础类型槽位（原样存入），替代 lambda 捕获以避免装箱分配。
     *
     * @param i 槽位下标，有效范围 0 到 31
     * @param v 待存入的值
     * 返回: 当前实例，供链式填充槽位。
     */
    public BiConsumerRecycler<T, U> val(int i, long v) {
        this.vals[i] = v;
        return this;
    }

    /**
     * 业务作用：把 int 存入基础类型槽位（自动拓宽为 long 存入），替代 lambda 捕获以避免装箱分配。
     *
     * @param i 槽位下标，有效范围 0 到 31
     * @param v 待存入的值
     * 返回: 当前实例，供链式填充槽位。
     */
    public BiConsumerRecycler<T, U> val(int i, int v) {
        this.vals[i] = v;
        return this;
    }

    /**
     * 业务作用：把 boolean 存入基础类型槽位（true 存 1、false 存 0），替代 lambda 捕获以避免装箱分配。
     *
     * @param i 槽位下标，有效范围 0 到 31
     * @param v 待存入的值
     * 返回: 当前实例，供链式填充槽位。
     */
    public BiConsumerRecycler<T, U> val(int i, boolean v) {
        this.vals[i] = v ? 1 : 0;
        return this;
    }

    /**
     * 业务作用：把 char 存入基础类型槽位（自动拓宽为 long 存入），替代 lambda 捕获以避免装箱分配。
     *
     * @param i 槽位下标，有效范围 0 到 31
     * @param v 待存入的值
     * 返回: 当前实例，供链式填充槽位。
     */
    public BiConsumerRecycler<T, U> val(int i, char v) {
        this.vals[i] = v;
        return this;
    }

    // ==================== 原始类型槽位: 取 ====================

    /**
     * 业务作用：按 long 读取基础类型槽位（原样读取）。槽位不记录写入时的类型，
     * 读写必须由调用方自行约定一致，读错类型不会报错只会得到错误的值。
     *
     * @param i 槽位下标，有效范围 0 到 31
     * 返回: 该槽位的 long 值；从未写入时为默认零值。
     */
    public long longVal(int i) {
        return this.vals[i];
    }

    /**
     * 业务作用：按 int 读取基础类型槽位（窄化读取）。槽位不记录写入时的类型，
     * 读写必须由调用方自行约定一致，读错类型不会报错只会得到错误的值。
     *
     * @param i 槽位下标，有效范围 0 到 31
     * 返回: 该槽位的 int 值；从未写入时为默认零值。
     */
    public int intVal(int i) {
        return (int) this.vals[i];
    }

    /**
     * 业务作用：按 boolean 读取基础类型槽位（非零即为 true）。槽位不记录写入时的类型，
     * 读写必须由调用方自行约定一致，读错类型不会报错只会得到错误的值。
     *
     * @param i 槽位下标，有效范围 0 到 31
     * 返回: 该槽位的 boolean 值；从未写入时为默认零值。
     */
    public boolean boolVal(int i) {
        return this.vals[i] != 0;
    }

    /**
     * 业务作用：按 char 读取基础类型槽位（窄化读取）。槽位不记录写入时的类型，
     * 读写必须由调用方自行约定一致，读错类型不会报错只会得到错误的值。
     *
     * @param i 槽位下标，有效范围 0 到 31
     * 返回: 该槽位的 char 值；从未写入时为默认零值。
     */
    public char charVal(int i) {
        return (char) this.vals[i];
    }

    // ==================== 引用类型槽位 ====================

    /**
     * 业务作用：把引用存入引用槽位，本实例只借用不接管其生命周期。
     *
     * @param i 槽位下标，有效范围 0 到 31
     * @param v 待存入的引用
     * 返回: 当前实例，供链式填充槽位。
     */
    public BiConsumerRecycler<T, U> ref(int i, Object v) {
        this.refs[i] = v;
        return this;
    }

    /**
     * 业务作用：存入引用并声明本实例独占该对象，归池时级联回收它。
     * 只有确实由本实例独占的池化对象才能这样标记：对象一旦逃逸到其他异步任务或外部作用域，
     * 本实例先归池就会让外部读到已回收且可能被改写的数据。
     *
     * @param i 槽位下标，有效范围 0 到 31
     * @param v 待存入并接管的池化引用
     * 返回: 当前实例，供链式填充槽位。
     */
    public BiConsumerRecycler<T, U> refRecycle(int i, Object v) {
        this.refs[i] = v;
        this.refsRecycle |= (1L << i);
        return this;
    }

    /**
     * 业务作用：读取引用槽位，由调用方按约定类型接收。槽位不做类型校验，
     * 读取类型与写入不符会在赋值处抛 ClassCastException。
     *
     * @param i 槽位下标，有效范围 0 到 31
     * 返回: 该槽位的引用；从未写入时为 null。
     */
    @SuppressWarnings("unchecked")
    public <R> R ref(int i) {
        return (R) this.refs[i];
    }
}
