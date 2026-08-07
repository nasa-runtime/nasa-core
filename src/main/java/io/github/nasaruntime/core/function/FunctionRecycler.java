package io.github.nasaruntime.core.function;

import io.github.nasaruntime.core.base.ObjectPool;
import io.github.nasaruntime.core.utils.ContextUtils;

import java.util.Arrays;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 可池化的 {@code Function<T, R>} 包装器, 零 GC 适配
 * "map.computeIfAbsent / map.computeIfPresent / Stream.map / Optional.map + 捕获外部参数" 场景.
 * <p>
 * 提供 {@link #of(BiFunction)} / {@link #ofRecycle(BiFunction)} 对称工厂 + {@code recycleSelf} 开关.
 * 调用次数取决于调用方:
 *   <ul>
 *     <li>{@code computeIfAbsent} / {@code computeIfPresent} / {@code Optional.map} — 0 或 1 次；
 *         不能保证回调一定执行时，使用 {@code of()} 并在 finally 中显式回收</li>
 *     <li>{@code Stream.map} / {@code List.replaceAll} — N 次,
 *         必须用 {@code of()} + 手动 {@code recycle()} (在 apply 里自动归池会让第二次 NPE)</li>
 *   </ul>
 * 不确定调用次数就用 {@code of()} 显式管生命周期, 永远不会错.
 *
 * <h2>典型用法 1: map.computeIfAbsent 懒创建</h2>
 * <pre>{@code
 *   // 替代: cache.computeIfAbsent("o_001", id -> factory.createDefault(id, now));
 *   //       (每次 new lambda, capture factory + long now 装箱)
 *   //
 *   FunctionRecycler<String, Order> f = FunctionRecycler
 *           .<String, Order>of((id, ar) -> {
 *               OrderFactory factory = ar.ref(0);
 *               long now = ar.longVal(0);
 *               return factory.createDefault(id, now);
 *           })
 *           .ref(0, factory)
 *           .val(0, System.currentTimeMillis());
 *   try {
 *       Order o = cache.computeIfAbsent("o_001", f);
 *   } finally {
 *       f.recycle();
 *   }
 * }</pre>
 *
 * <h2>典型用法 2: Stream.map 批量变换 + 捕获共享状态</h2>
 * <pre>{@code
 *   AtomicInteger seq = new AtomicInteger();
 *   FunctionRecycler<String, OrderEvent> f = FunctionRecycler
 *           .<String, OrderEvent>of((id, ar) -> {
 *               AtomicInteger s = ar.ref(0);
 *               long ts = ar.longVal(0);
 *               return new OrderEvent(id, s.incrementAndGet(), ts);
 *           })
 *           .ref(0, seq)
 *           .val(0, System.currentTimeMillis());
 *   try {
 *       List<OrderEvent> events = idList.stream().map(f).toList();
 *   } finally {
 *       f.recycle();
 *   }
 * }</pre>
 * <h2>典型用法 3: Optional.map (0/1 次, 取 boolean 选项)</h2>
 * <pre>{@code
 *   FunctionRecycler<User, String> f = FunctionRecycler
 *           .<User, String>of((u, ar) -> {
 *               boolean masked = ar.boolVal(0);
 *               return masked ? mask(u.getPhone()) : u.getPhone();
 *           })
 *           .val(0, isExternalRequest);
 *   try {
 *       String phone = userOpt.map(f).orElse("");
 *   } finally {
 *       f.recycle();
 *   }
 * }</pre>
 *
 * <h2>API</h2>
 *   <ul>
 *     <li>{@link #of()} / {@link #of(BiFunction)} — 后者绑定 strategy, lambda 签名 {@code (t, ar) -> R}</li>
 *     <li>{@link #ref(int, Object)} / {@link #refRecycle(int, Object)} — 引用槽位 0~31</li>
 *     <li>{@link #val(int, long)} (+ int/boolean/char 重载) — 原始类型槽位 0~31</li>
 *     <li>显式 {@code f.recycle()} 归池;{@code ofRecycle} 工厂下首次 apply 自动归</li>
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
 * 容量: 默认 2000, 可通过 {@code nasa.object-pool.function-recycler-capacity} 调.
 *
 * <h2>常见坑</h2>
 *   <ul>
 *     <li>computeIfAbsent 返回 null: 注意 ConcurrentHashMap 的 computeIfAbsent 不允许 lambda 返回 null,
 *         否则当作 "不缓存". 业务侧若需要可缓存 null, 改用 {@code AtomicReference<Object>} 包装.</li>
 *     <li>Stream.map 是 lazy 的: 如果终端操作 (terminal op) 没执行, lambda 一次都不调.
 *         这种场景 try-with-resources 出 scope 时 recycler 没用过也会被回收, 没问题.</li>
 *     <li>不要在 lambda 里 recycle(): 跟 Consumer/BiConsumer 同理, Stream.map 可能被调 N 次.</li>
 *     <li>refRecycle 的对象不要传出 lambda: 标了 refRecycle 的对象在 owner recycle 时立即归池,
 *         如果你在 apply 内把它放进返回的 R 里(或传给异步任务), 后续读到的就是别人改写后的脏数据.
 *         要保留就别标 refRecycle, 业务自己控制生命周期.</li>
 *   </ul>
 */
@SuppressWarnings("unused")
public class FunctionRecycler<T, R> implements Function<T, R>, ObjectPool.Recycler<FunctionRecycler<T, R>> {

    /* 策略, 接收 (T, recycler) 二元组, 返回 R */
    BiFunction<T, FunctionRecycler<T, R>, R> function;
    /* 是否在 apply() 末尾自我回收. 默认 false, ofRecycle 工厂打开. 仅限能保证回调恰好执行一次的场景 */
    boolean recycleSelf;
    /* 引用类型槽位, 随实例池化, 零 GC */
    final Object[] refs = new Object[32];
    /* refs 回收位掩码 */
    long refsRecycle;
    /* 原始类型槽位, 统一存 long, 零装箱 */
    final long[] vals = new long[32];

    /**
     * 业务作用：私有化构造，实例只能经对象池借出，防止绕过池化直接 new 而失去零 GC 收益。
     *
     * 参数说明: 无。
     * 返回: 仅由本类对象池调用。
     */
    private FunctionRecycler() {}

    // 池层面统一当 <Object, Object>, 借出后强转回 <T, R>
    static final ObjectPool<FunctionRecycler<Object, Object>> POOL = new ObjectPool<>(
            ContextUtils.getPropertyInt("nasa.object-pool.function-recycler-capacity", 2000)) {
        /**
         * 业务作用：池空时创建新实例。
         *
         * 参数说明: 无。
         * 返回: 槽位全空、未绑定回调的新实例。
         */
        @Override
        public FunctionRecycler<Object, Object> newObject() {
            return new FunctionRecycler<>();
        }
    };

    @SuppressWarnings({"unchecked", "rawtypes"})
    private final ObjectPool.PooledHandle<FunctionRecycler<T, R>> handle =
            (ObjectPool.PooledHandle) new ObjectPool.PooledHandle<>(POOL);

    /**
     * 业务作用：作为 {@code Function<T,R>} 被调用，把入参与自身一起交给 function 并返回其结果。
     * computeIfAbsent 调用 0 或 1 次，Stream.map 调用 N 次。
     * 调用零次会让实例无法归池，调用多次则第一次后 function 已被清空，两种情况都不能用 ofRecycle。
     *
     * @param t 调用方传入的第一个参数
     * 返回: 回调的计算结果。
     */
    @Override
    public R apply(T t) {
        try {
            return this.function.apply(t, this);
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
    public FunctionRecycler<T, R> recycleSelf(boolean recycleSelf) {
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
    public ObjectPool.PooledHandle<FunctionRecycler<T, R>> handle() {
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
        this.function = null;
        this.recycleSelf = false;
        Arrays.fill(this.vals, 0);
        for (int i = 0; i < this.refs.length; i++) {
            if ((this.refsRecycle & (1L << i)) != 0 && this.refs[i] instanceof ObjectPool.Recycler<?> rec)
                rec.recycle();
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
    public static <T, R> FunctionRecycler<T, R> of() {
        return (FunctionRecycler) POOL.get();
    }

    /**
     * 业务作用：借出实例并绑定回调，默认不自动回收，生命周期由调用方掌握。
     * 多次调用的场景（forEach、Stream.map、replaceAll）必须使用本入口。
     *
     * @param function 回调实现，执行时会拿到本实例以读取槽位
     * 返回: 已绑定回调的实例；用完必须显式 recycle 归池。
     */
    public static <T, R> FunctionRecycler<T, R> of(BiFunction<T, FunctionRecycler<T, R>, R> function) {
        FunctionRecycler<T, R> r = of();
        r.function = function;
        return r;
    }

    /**
     * 业务作用：借出实例并绑定回调，同时打开自动回收，执行一次后自动归池。
     * 前置条件是回调恰好被调用一次：调用零次实例无法归池并永久占用一个池槽位，
     * 调用多次则第一次后回调已被清空，后续调用会读到已归池甚至被他人改写的实例。
     *
     * @param function 回调实现，执行时会拿到本实例以读取槽位
     * 返回: 已绑定回调且开启自动回收的实例。
     */
    public static <T, R> FunctionRecycler<T, R> ofRecycle(BiFunction<T, FunctionRecycler<T, R>, R> function) {
        return of(function).recycleSelf(true);
    }

    // ==================== 原始类型槽位 ====================

    /**
     * 业务作用：把 long 存入基础类型槽位（原样存入），替代 lambda 捕获以避免装箱分配。
     *
     * @param i 槽位下标，有效范围 0 到 31
     * @param v 待存入的值
     * 返回: 当前实例，供链式填充槽位。
     */
    public FunctionRecycler<T, R> val(int i, long v) {
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
    public FunctionRecycler<T, R> val(int i, int v) {
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
    public FunctionRecycler<T, R> val(int i, boolean v) {
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
    public FunctionRecycler<T, R> val(int i, char v) {
        this.vals[i] = v;
        return this;
    }

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
    public FunctionRecycler<T, R> ref(int i, Object v) {
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
    public FunctionRecycler<T, R> refRecycle(int i, Object v) {
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
    public <X> X ref(int i) {
        return (X) this.refs[i];
    }
}
