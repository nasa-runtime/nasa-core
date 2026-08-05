package com.nasa.runtime.core.function;

import com.nasa.runtime.core.base.ObjectPool;
import com.nasa.runtime.core.utils.ContextUtils;

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

    private FunctionRecycler() {}

    // 池层面统一当 <Object, Object>, 借出后强转回 <T, R>
    static final ObjectPool<FunctionRecycler<Object, Object>> POOL = new ObjectPool<>(
            ContextUtils.getPropertyInt("nasa.object-pool.function-recycler-capacity", 2000)) {
        @Override
        public FunctionRecycler<Object, Object> newObject() {
            return new FunctionRecycler<>();
        }
    };

    @SuppressWarnings({"unchecked", "rawtypes"})
    private final ObjectPool.PooledHandle<FunctionRecycler<T, R>> handle =
            (ObjectPool.PooledHandle) new ObjectPool.PooledHandle<>(POOL);

    /**
     * apply 入口.
     * <p>
     * 默认 recycleSelf=false. 用了 {@link #ofRecycle} 则 try/finally 在算完 R 之后归池。
     * <b>仅适用于</b>调用方能保证 apply 恰好执行一次的场景：执行零次会使实例无法归池，
     * Stream.map / List.replaceAll 等执行多次的场景会在第二次调用时 NPE。
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
     * 链式设置 recycleSelf, 业务确认回调会恰好执行一次后才打开
     */
    public FunctionRecycler<T, R> recycleSelf(boolean recycleSelf) {
        this.recycleSelf = recycleSelf;
        return this;
    }

    @Override
    public ObjectPool.PooledHandle<FunctionRecycler<T, R>> handle() {
        return this.handle;
    }

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

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T, R> FunctionRecycler<T, R> of() {
        return (FunctionRecycler) POOL.get();
    }

    /**
     * 绑定策略: lambda 签名 {@code (t, ar) -> R}, ar 即本实例, 可读 refs/vals.
     * recycleSelf=false, 调用方负责生命周期 (Stream.map / replaceAll 用这个).
     */
    public static <T, R> FunctionRecycler<T, R> of(BiFunction<T, FunctionRecycler<T, R>, R> function) {
        FunctionRecycler<T, R> r = of();
        r.function = function;
        return r;
    }

    /**
     * 绑定策略 + recycleSelf=true: apply() 末尾自动归池.
     * <p>
     * <b>仅限恰好调用一次</b>。{@code computeIfAbsent}、{@code computeIfPresent} 和
     * {@code Optional.map} 都可能不调用回调；不能保证调用时应使用 {@link #of(BiFunction)} 并显式回收。
     * Stream.map / List.replaceAll 会在第二次调用时 NPE.
     */
    public static <T, R> FunctionRecycler<T, R> ofRecycle(BiFunction<T, FunctionRecycler<T, R>, R> function) {
        return of(function).recycleSelf(true);
    }

    // ==================== 原始类型槽位 ====================

    public FunctionRecycler<T, R> val(int i, long v) {
        this.vals[i] = v;
        return this;
    }

    public FunctionRecycler<T, R> val(int i, int v) {
        this.vals[i] = v;
        return this;
    }

    public FunctionRecycler<T, R> val(int i, boolean v) {
        this.vals[i] = v ? 1 : 0;
        return this;
    }

    public FunctionRecycler<T, R> val(int i, char v) {
        this.vals[i] = v;
        return this;
    }

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

    public FunctionRecycler<T, R> ref(int i, Object v) {
        this.refs[i] = v;
        return this;
    }

    public FunctionRecycler<T, R> refRecycle(int i, Object v) {
        this.refs[i] = v;
        this.refsRecycle |= (1L << i);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <X> X ref(int i) {
        return (X) this.refs[i];
    }
}
