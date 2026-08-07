package io.github.nasaruntime.core.function;

import io.github.nasaruntime.core.base.ObjectPool;
import io.github.nasaruntime.core.utils.ContextUtils;

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

    /**
     * 业务作用：私有化构造，实例只能经对象池借出，防止绕过池化直接 new 而失去零 GC 收益。
     *
     * 参数说明: 无。
     * 返回: 仅由本类对象池调用。
     */
    private ConsumerRecycler() {}

    // 池层面统一当 <Object> 处理, 借出后强转回 <T>
    static final ObjectPool<ConsumerRecycler<Object>> POOL = new ObjectPool<>(
            ContextUtils.getPropertyInt("nasa.object-pool.consumer-recycler-capacity", 2000)) {
        /**
         * 业务作用：池空时创建新实例。
         *
         * 参数说明: 无。
         * 返回: 槽位全空、未绑定回调的新实例。
         */
        @Override
        public ConsumerRecycler<Object> newObject() {
            return new ConsumerRecycler<>();
        }
    };

    @SuppressWarnings({"unchecked", "rawtypes"})
    private final ObjectPool.PooledHandle<ConsumerRecycler<T>> handle =
            (ObjectPool.PooledHandle) new ObjectPool.PooledHandle<>(POOL);

    /**
     * 业务作用：作为 {@code Consumer<T>} 被调用，把入参与自身一起交给 consumer。
     * forEach、Stream.forEach 等场景会调用 N 次，此时必须用 of 而非 ofRecycle：
     * 自动回收会在第一次调用后清空 consumer，后续调用将读到已归池实例。
     *
     * @param t 调用方传入的第一个参数
     * 返回: 无返回值。
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
     * 业务作用：切换自动回收开关，使回调可以在运行期根据结果决定本次执行后是否归池，
     * 例如重试任务成功后才归池、失败则保留实例等待下次调度。
     *
     * @param recycleSelf true 表示回调执行完毕后自动归池
     * 返回: 当前实例，供链式填充槽位。
     */
    public ConsumerRecycler<T> recycleSelf(boolean recycleSelf) {
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
    public ObjectPool.PooledHandle<ConsumerRecycler<T>> handle() {
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
    public static <T> ConsumerRecycler<T> of() {
        return (ConsumerRecycler) POOL.get();
    }

    /**
     * 业务作用：借出实例并绑定回调，默认不自动回收，生命周期由调用方掌握。
     * 多次调用的场景（forEach、Stream.map、replaceAll）必须使用本入口。
     *
     * @param consumer 回调实现，执行时会拿到本实例以读取槽位
     * 返回: 已绑定回调的实例；用完必须显式 recycle 归池。
     */
    public static <T> ConsumerRecycler<T> of(BiConsumer<T, ConsumerRecycler<T>> consumer) {
        ConsumerRecycler<T> r = of();
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
    public static <T> ConsumerRecycler<T> ofRecycle(BiConsumer<T, ConsumerRecycler<T>> consumer) {
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
    public ConsumerRecycler<T> val(int i, long v) {
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
    public ConsumerRecycler<T> val(int i, int v) {
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
    public ConsumerRecycler<T> val(int i, boolean v) {
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
    public ConsumerRecycler<T> val(int i, char v) {
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
    public ConsumerRecycler<T> ref(int i, Object v) {
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
    public ConsumerRecycler<T> refRecycle(int i, Object v) {
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
