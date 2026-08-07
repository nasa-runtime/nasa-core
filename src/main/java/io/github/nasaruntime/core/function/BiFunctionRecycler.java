package io.github.nasaruntime.core.function;

import io.github.nasaruntime.core.base.ObjectPool;
import io.github.nasaruntime.core.utils.ContextUtils;

import java.util.Arrays;
import java.util.function.BiFunction;

/**
 * 可池化的 {@code BiFunction<T, U, R>} 包装器, 零 GC 适配
 * "map.compute / map.merge / map.replaceAll / Stream.reduce + 捕获外部参数" 场景.
 * <p>
 * 提供 {@link #of(Function3)} / {@link #ofRecycle(Function3)} 对称工厂 + {@code recycleSelf} 开关.
 * 调用次数取决于调用方:
 *   <ul>
 *     <li>{@code map.compute} — 恰好调用 1 次，可用 {@code ofRecycle()} 自动归池</li>
 *     <li>{@code map.computeIfPresent} / {@code map.merge} — 0 或 1 次；只有确定 key 已存在、
 *         回调一定执行时才能用 {@code ofRecycle()}</li>
 *     <li>{@code map.replaceAll} / {@code Stream.reduce} / {@code List.replaceAll} — N 次,
 *         必须用 {@code of()} + 手动 {@code recycle()} (在 apply 里自动归池会让第二次 NPE)</li>
 *   </ul>
 * 不确定调用次数就用 {@code of()} 显式管生命周期, 永远不会错.
 *
 * <h2>典型用法 1: map.compute 单次 upsert with 捕获阈值</h2>
 * <pre>{@code
 *   // 替代: map.compute(key, (k, v) -> (v != null && v > threshold) ? v : threshold);
 *   //       (每次 new lambda, 装箱 threshold)
 *   //
 *   BiFunctionRecycler<String, Long, Long> bf = BiFunctionRecycler
 *           .<String, Long, Long>of((k, v, ar) -> {
 *               long threshold = ar.longVal(0);
 *               return (v != null && v > threshold) ? v : threshold;
 *           })
 *           .val(0, 100L);
 *   try {
 *       map.compute("key", bf);
 *   } finally {
 *       bf.recycle();
 *   }
 * }</pre>
 * <h2>典型用法 2: map.merge 批量累加, 复用同一个 recycler 跑 N 次</h2>
 * <pre>{@code
 *   AtomicLong invokes = new AtomicLong();
 *   BiFunctionRecycler<Long, Long, Long> bf = BiFunctionRecycler
 *           .<Long, Long, Long>of((oldV, newV, ar) -> {
 *               AtomicLong c = ar.ref(0);
 *               c.incrementAndGet();
 *               return oldV + newV;
 *           })
 *           .ref(0, invokes);
 *   try {
 *       updates.forEach((k, v) -> map.merge(k, v, bf));    // ← 同一个 bf 跑 N 次 merge
 *   } finally {
 *       bf.recycle();
 *   }
 *   // invokes.get() = 已存在 key 的次数 (新 key 不调 lambda, merge 直接 put newV)
 * }</pre>
 *
 * <h2>典型用法 3: map.replaceAll 整 map 变换 (N 次调用)</h2>
 * <pre>{@code
 *   BiFunctionRecycler<String, Long, Long> bf = BiFunctionRecycler
 *           .<String, Long, Long>of((k, v, ar) -> {
 *               long add = ar.longVal(0);
 *               return v + add;
 *           })
 *           .val(0, 1L);
 *   try {
 *       map.replaceAll(bf);    // ← 所有 value += 1, lambda 调 map.size() 次
 *   } finally {
 *       bf.recycle();
 *   }
 * }</pre>
 *
 * <h2>典型用法 4: Stream.reduce (N 次, 累加器)</h2>
 * <pre>{@code
 *   BiFunctionRecycler<Long, Order, Long> bf = BiFunctionRecycler
 *           .<Long, Order, Long>of((acc, o, ar) -> {
 *               long bonus = ar.longVal(0);
 *               return acc + o.getAmount() + bonus;
 *           })
 *           .val(0, 5L);
 *   try {
 *       long sum = orders.stream().reduce(0L, bf, Long::sum);
 *   } finally {
 *       bf.recycle();
 *   }
 * }</pre>
 *
 * <h2>API</h2>
 *   <ul>
 *     <li>{@link #of()} / {@link #of(Function3)} — 后者绑定 strategy, lambda 签名 {@code (t, u, ar) -> R}</li>
 *     <li>{@link #ref(int, Object)} / {@link #refRecycle(int, Object)} — 引用槽位 0~31</li>
 *     <li>{@link #val(int, long)} (+ int/boolean/char 重载) — 原始类型槽位 0~31</li>
 *     <li>显式 {@code bf.recycle()} 归池;{@code ofRecycle} 工厂下首次 apply 自动归</li>
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
 * 容量: 默认 2000, 可通过 {@code nasa.object-pool.bifunction-recycler-capacity} 调.
 *
 * <h2>常见坑</h2>
 *   <ul>
 *     <li>compute 的 lambda 返回 null = 删除该 key (JDK 标准库语义). 如果业务上不想删, 显式返回 v.</li>
 *     <li>merge 的语义: key 不存在时直接 put newV, lambda 不调用; key 存在时 lambda(oldV, newV).
 *         想自己控制 "不存在时做啥" 改用 compute.</li>
 *     <li>replaceAll 期间不能改 map 结构: JDK 标准库限制, 跟 Recycler 无关.</li>
 *     <li>不要在 lambda 内 recycle(): replaceAll/reduce 会调 N 次.</li>
 *     <li>泛型 hint: {@code BiFunctionRecycler.<K, V, R>of(...)} 写全, 避免 lambda 参数推导不出来.</li>
 *     <li>refRecycle 的对象不要传出 lambda: 标了 refRecycle 的对象在 owner recycle 时立即归池,
 *         如果你在 apply 内把它放进返回的 R 里(或传给异步任务), 后续读到的就是别人改写后的脏数据.
 *         要保留就别标 refRecycle, 业务自己控制生命周期.</li>
 *   </ul>
 */
@SuppressWarnings("unused")
public class BiFunctionRecycler<T, U, R> implements BiFunction<T, U, R>, ObjectPool.Recycler<BiFunctionRecycler<T, U, R>> {

    /* 策略, 接收 (T, U, recycler) 三元组, 返回 R; 通过 recycler 访问 refs/vals 中的捕获参数 */
    Function3<T, U, BiFunctionRecycler<T, U, R>, R> function;
    /* 是否在 apply() 末尾自我回收. 默认 false, ofRecycle 工厂打开. 仅限能保证回调恰好执行一次的场景 */
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
    private BiFunctionRecycler() {}

    // 池层面统一当 <Object, Object, Object> 处理, 借出后强转回 <T, U, R>
    static final ObjectPool<BiFunctionRecycler<Object, Object, Object>> POOL = new ObjectPool<>(
            ContextUtils.getPropertyInt("nasa.object-pool.bifunction-recycler-capacity", 2000)) {
        /**
         * 业务作用：池空时创建新实例。
         *
         * 参数说明: 无。
         * 返回: 槽位全空、未绑定回调的新实例。
         */
        @Override
        public BiFunctionRecycler<Object, Object, Object> newObject() {
            return new BiFunctionRecycler<>();
        }
    };

    @SuppressWarnings({"unchecked", "rawtypes"})
    private final ObjectPool.PooledHandle<BiFunctionRecycler<T, U, R>> handle =
            (ObjectPool.PooledHandle) new ObjectPool.PooledHandle<>(POOL);

    /**
     * 业务作用：作为 {@code BiFunction<T,U,R>} 被调用，把两个入参与自身一起交给 function 并返回其结果。
     * compute 调用 1 次，merge 调用 0 或 1 次，replaceAll 调用 N 次；只有确定恰好一次时才可用 ofRecycle。
     *
     * @param t 调用方传入的第一个参数
     * @param u 调用方传入的第二个参数
     * 返回: 回调的计算结果。
     */
    @Override
    public R apply(T t, U u) {
        try {
            return this.function.apply(t, u, this);
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
    public BiFunctionRecycler<T, U, R> recycleSelf(boolean recycleSelf) {
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
    public ObjectPool.PooledHandle<BiFunctionRecycler<T, U, R>> handle() {
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
    public static <T, U, R> BiFunctionRecycler<T, U, R> of() {
        return (BiFunctionRecycler) POOL.get();
    }

    /**
     * 业务作用：借出实例并绑定回调，默认不自动回收，生命周期由调用方掌握。
     * 多次调用的场景（forEach、Stream.map、replaceAll）必须使用本入口。
     *
     * @param function 回调实现，执行时会拿到本实例以读取槽位
     * 返回: 已绑定回调的实例；用完必须显式 recycle 归池。
     */
    public static <T, U, R> BiFunctionRecycler<T, U, R> of(Function3<T, U, BiFunctionRecycler<T, U, R>, R> function) {
        BiFunctionRecycler<T, U, R> r = of();
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
    public static <T, U, R> BiFunctionRecycler<T, U, R> ofRecycle(Function3<T, U, BiFunctionRecycler<T, U, R>, R> function) {
        return of(function).recycleSelf(true);
    }

    // ==================== 原始类型槽位: 存 ====================

    /**
     * 业务作用：把 long 存入基础类型槽位（原样存入），替代 lambda 捕获以避免装箱分配。
     *
     * @param i 槽位下标，有效范围 0 到 31
     * @param v 待存入的值
     * 返回: 当前实例，供链式填充槽位。
     */
    public BiFunctionRecycler<T, U, R> val(int i, long v) {
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
    public BiFunctionRecycler<T, U, R> val(int i, int v) {
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
    public BiFunctionRecycler<T, U, R> val(int i, boolean v) {
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
    public BiFunctionRecycler<T, U, R> val(int i, char v) {
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
    public BiFunctionRecycler<T, U, R> ref(int i, Object v) {
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
    public BiFunctionRecycler<T, U, R> refRecycle(int i, Object v) {
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
