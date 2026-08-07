package io.github.nasaruntime.core.function;

import io.github.nasaruntime.core.base.ObjectPool;
import io.github.nasaruntime.core.utils.ContextUtils;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * 业务作用: 复用一次性 {@link Action} 及其捕获参数，以零 GC 方式组合动作和参数。
 * 本类实现 {@link Action} 和 Runnable，{@link #action()} 负责调用 consumer，并根据
 * {@code recycleSelf} 决定执行结束后是否自动归池。
 *
 * <h2>同类组件定位</h2>
 * <ul>
 *   <li>{@link ActionRecycler} 不接收调用参数，适合只执行一次或由调度器重复触发的 Action。</li>
 *   <li>{@link ConsumerRecycler} 实现 {@code Consumer<T>}，forEach 会调用 N 次。</li>
 *   <li>{@link BiConsumerRecycler} 实现 {@code BiConsumer<T,U>}，map.forEach 会调用 N 次。</li>
 *   <li>{@link FunctionRecycler} 实现 {@code Function<T,R>}，computeIfAbsent 调用 0 或 1 次，
 *       Stream.map 调用 N 次。</li>
 *   <li>{@link BiFunctionRecycler} 实现 {@code BiFunction<T,U,R>}，compute 调用 1 次，merge 调用 0 或 1 次，
 *       replaceAll 调用 N 次。</li>
 * </ul>
 * <p>
 * 五类 Recycler 都提供普通工厂、自动回收工厂和 {@code recycleSelf} 开关。Action 天然是单次
 * 调用语义，适合直接使用自动回收；其他函数类型是否可以自动回收取决于调用方。自动回收要求
 * 回调恰好执行一次：执行零次会使实例无法归池，执行多次则会在第一次调用后清空 consumer。
 * forEach、Stream.map、replaceAll 等多次调用场景必须使用普通工厂并显式回收。
 *
 * <h2>用法一：一次性异步任务</h2>
 * 将引用放入 ref 槽位，将基础类型放入 val 槽位，替代直接捕获参数的 lambda，避免每次提交任务时
 * 创建新的闭包实例，并避免基础类型装箱。
 * {@snippet :
 * executor.execute(
 *     ActionRecycler.ofRecycle(action -> {
 *         Handler handler = action.ref(0);
 *         long taskId = action.longVal(0);
 *         long userId = action.longVal(1);
 *         handler.process(taskId, userId);
 *     })
 *     .ref(0, handler)
 *     .val(0, taskId)
 *     .val(1, userId)
 * );
 * }
 * <p>
 * {@link #ofRecycle(Consumer)} 会打开 {@code recycleSelf}。consumer 无论正常返回还是抛出异常，
 * {@link #action()} 的 finally 都会执行回收，调用方不再管理该实例的生命周期。
 *
 * <h2>用法二：周期或延迟调度</h2>
 * 周期任务会重复调用同一个 Action，必须使用 {@link #of(Consumer)}，并在任务取消后显式回收。
 * {@snippet :
 * ActionRecycler action = ActionRecycler.of(value -> {
 *     Metrics metrics = value.ref(0);
 *     String name = value.ref(1);
 *     metrics.tick(name);
 * }).ref(0, metrics).ref(1, "request-rate");
 *
 * TimingWheel.exec(0, 1000, "metrics-refresh", action);
 * // 取消任务后执行 action.recycle()。
 * }
 * <p>
 * 如果周期任务误用 {@link #ofRecycle(Consumer)}，第一次触发后实例就会归池；后续触发可能读取到
 * 已清空或已被其他调用方改写的槽位。取消任务后不显式调用 recycle 不会遗失业务对象，但会让该
 * ActionRecycler 永久脱离对象池并浪费一个池槽位。
 *
 * <h2>用法三：由 consumer 决定回收时机</h2>
 * 普通工厂创建的实例可以在条件满足时调用 {@link #recycleSelf(boolean)} 打开自动回收。
 * 下面的任务成功后取消后续调度并归池，失败时保留当前实例等待再次执行。
 * {@snippet :
 * private static final Consumer<ActionRecycler> RETRY = action -> {
 *     Action body = action.ref(0);
 *     String name = action.ref(1);
 *     try {
 *         body.action();
 *         TimingWheel.cancel(name);
 *         action.recycleSelf(true);
 *     } catch (Throwable ignored) {
 *         // 保留实例，由下一次调度继续执行。
 *     }
 * };
 * }
 *
 * <h2>用法四：级联回收</h2>
 * {@link #refRecycle(int, Object)} 表示 ActionRecycler 拥有该引用。ActionRecycler 归池时会检查
 * {@code refsRecycle} 位掩码，并回收命中的 {@link ObjectPool.Recycler}。
 * {@snippet :
 * ActionRecycler.ofRecycle(action -> {
 *     Worker worker = action.ref(0);
 *     RecycleLinkedMap<String, Object> snapshot = action.ref(1);
 *     worker.consume(snapshot);
 * })
 * .ref(0, worker)
 * .refRecycle(1, AnyHolder.snapshot());
 * }
 * <p>
 * 只有由当前实例独占的池化对象才能使用 refRecycle。对象一旦逃逸到其他异步任务或外部作用域，
 * 当前实例先归池就会使外部任务读到已回收并可能被改写的数据；这类对象不得标记 refRecycle，
 * 必须由业务方单独管理生命周期。
 *
 * <h2>生命周期</h2>
 * 普通流程为：从对象池借出实例，填充 ref 和 val 槽位，执行 consumer，按策略调用 recycle，
 * 再由 restore 清空槽位并完成级联回收，最后返回对象池。
 * {@snippet :
 * // of(consumer)
 * //     -> ref/val 填充槽位
 * //     -> executor 触发 action()
 * //     -> consumer.accept(this)
 * //     -> finally 按 recycleSelf 决定是否 recycle()
 * //     -> restore() 清空所有槽位并完成级联回收
 * //     -> 返回对象池
 * }
 * <p>
 * 自动回收模式由 {@link #action()} 的 finally 保证归池。任务在执行前被调度器取消时，
 * {@link #cancelledRecycle()} 负责补偿没有进入 action 的自动回收实例。
 *
 * <h2>关键 API</h2>
 * <ul>
 *   <li>{@link #of()} 借出空白实例，由调用方设置 consumer 和槽位。</li>
 *   <li>{@link #of(Consumer)} 借出并绑定 consumer，默认不自动回收。</li>
 *   <li>{@link #ofRecycle(Consumer)} 借出并绑定 consumer，执行一次后自动回收。</li>
 *   <li>{@link #ref(int, Object)} 保存普通引用，不接管引用对象的生命周期。</li>
 *   <li>{@link #refRecycle(int, Object)} 保存并接管池化引用，当前实例归池时级联回收。</li>
 *   <li>{@link #val(int, long)} 及其 int、boolean、char 重载保存基础类型，统一使用 long 槽位并避免装箱。</li>
 *   <li>{@link #longVal(int)}、{@link #intVal(int)}、{@link #boolVal(int)} 和 {@link #charVal(int)}
 *       按调用方约定的类型读取基础值。</li>
 * </ul>
 *
 * <h2>槽位与池配置</h2>
 * <ul>
 *   <li>{@code refs} 和 {@code vals} 各有 32 个槽位，有效索引为 0 到 31。</li>
 *   <li>{@code refsRecycle} 使用 long 的低 32 位记录需要级联回收的引用槽位。</li>
 *   <li>对象池默认容量为 2000，可通过 {@code nasa.object-pool.action-recycler-capacity} 调整。</li>
 * </ul>
 *
 * <h2>安全约束</h2>
 * <ul>
 *   <li>实例交给 executor 或 TimingWheel 后，调用方不得继续读取或修改其槽位。</li>
 *   <li>recycle 完成后实例可能立即被其他线程借走，继续持有并读取旧引用会看到其他任务的数据。</li>
 *   <li>直接调用 {@link #action()} 时异常向上传播；通过 Runnable.run() 调用时由 {@link Action#run()}
 *       统一记录并吞掉异常。</li>
 * </ul>
 */
@SuppressWarnings("unused")
public class ActionRecycler implements Action, ObjectPool.Recycler<ActionRecycler> {

    /* 消费策略，接收 ActionRecycler 实例，可访问所有字段（action/vals/refs） */
    Consumer<ActionRecycler> consumer;
    /* 是否在action中自己recycle */
    boolean recycleSelf = false;
    /* 引用类型槽位，随实例池化，零 GC；存放任意 Object 引用 */
    final Object[] refs = new Object[32];
    /* refs 回收位掩码，bit i = 1 表示 refs[i] 需要在 restore 时回收（必须是 ObjectPool.Recycler），低 32 位覆盖 refs[0~31] */
    long refsRecycle;
    /* 原始类型槽位，随实例池化，零 GC；所有基本类型统一存 long，按实际类型窄化读取 */
    final long[] vals = new long[32];

    /**
     * 业务作用：私有化构造，实例只能经对象池借出，防止绕过池化直接 new 而失去零 GC 收益。
     *
     * 参数说明: 无。
     * 返回: 仅由本类对象池调用。
     */
    private ActionRecycler() {}

    /**
     * 业务作用：执行本次动作：把自身交给 consumer，由 consumer 从 ref/val 槽位取回参数。
     * Action 天然是单次调用语义。finally 保证无论 consumer 正常返回还是抛异常都按 recycleSelf 归池；
     * 直接调用本方法时异常向上传播，经 Runnable.run() 调用时由 Action.run() 统一记录并吞掉。
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    @Override
    public void action() {
        try {
            this.consumer.accept(this);
        } finally {
            if (recycleSelf) this.recycle();
        }
    }

    static final ObjectPool<ActionRecycler> POOL = new ObjectPool<>(
            ContextUtils.getPropertyInt("nasa.object-pool.action-recycler-capacity", 2000)) {
        /**
         * 业务作用：池空时创建新实例。
         *
         * 参数说明: 无。
         * 返回: 槽位全空、未绑定回调的新实例。
         */
        @Override
        public ActionRecycler newObject() {
            return new ActionRecycler();
        }
    };

    private final ObjectPool.PooledHandle<ActionRecycler> handle = new ObjectPool.PooledHandle<>(POOL);

    /**
     * 业务作用：暴露池化身份，启用重复归池的 CAS 防御。
     *
     * 参数说明: 无。
     * 返回: 构造时绑定本类对象池的 handle。
     */
    @Override
    public ObjectPool.PooledHandle<ActionRecycler> handle() {
        return handle;
    }

    /**
     * 业务作用：调度框架在「未调用 action() 就丢弃」时的补偿回收钩子。
     * 典型场景是 TimingWheel.cancel 后任务被标记取消、触发时直接跳过执行，
     * 此时 action() 内 finally 的自动归池没有机会执行，实例会永久脱池。
     * 只对 ofRecycle 模式主动归池；of 模式由业务方自管生命周期，框架不介入。
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    @Override
    public void cancelledRecycle() {
        if (this.recycleSelf) {
            this.recycle();
        }
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
        // 按位回收标记了 recycle 的引用槽位，然后清空所有引用（防止持有已回收对象）
        for (int i = 0; i < this.refs.length; i++) {
            if ((this.refsRecycle & (1L << i)) != 0 && this.refs[i] instanceof ObjectPool.Recycler<?> r) r.recycle();
            this.refs[i] = null;
        }
        this.refsRecycle = 0;
    }

    /**
     * 业务作用：切换自动回收开关，使回调可以在运行期根据结果决定本次执行后是否归池，
     * 例如重试任务成功后才归池、失败则保留实例等待下次调度。
     *
     * @param recycleSelf true 表示回调执行完毕后自动归池
     * 返回: 当前实例，供链式填充槽位。
     */
    public ActionRecycler recycleSelf(boolean recycleSelf) {
        this.recycleSelf = recycleSelf;
        return this;
    }

    // ==================== 原始类型槽位：存 ====================

    /**
     * 业务作用：把 long 存入基础类型槽位（原样存入），替代 lambda 捕获以避免装箱分配。
     *
     * @param i 槽位下标，有效范围 0 到 31
     * @param v 待存入的值
     * 返回: 当前实例，供链式填充槽位。
     */
    public ActionRecycler val(int i, long v) {
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
    public ActionRecycler val(int i, int v) {
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
    public ActionRecycler val(int i, boolean v) {
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
    public ActionRecycler val(int i, char v) {
        this.vals[i] = v;
        return this;
    }

    // ==================== 原始类型槽位：取 ====================

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
    public ActionRecycler ref(int i, Object v) {
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
    public ActionRecycler refRecycle(int i, Object v) {
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
    public <T> T ref(int i) {
        return (T) this.refs[i];
    }

    /**
     * 业务作用：借出一个空白实例，由调用方自行绑定回调与槽位。
     *
     * 参数说明: 无。
     * 返回: 已复位的实例；用完必须 recycle 归池。
     */
    public static ActionRecycler of() {
        return POOL.get();
    }

    /**
     * 业务作用：借出实例并绑定回调，默认不自动回收，生命周期由调用方掌握。
     * 多次调用的场景（forEach、Stream.map、replaceAll）必须使用本入口。
     *
     * @param consumer 回调实现，执行时会拿到本实例以读取槽位
     * 返回: 已绑定回调的实例；用完必须显式 recycle 归池。
     */
    public static ActionRecycler of(Consumer<ActionRecycler> consumer) {
        ActionRecycler recycler = of();
        recycler.consumer = consumer;
        return recycler;
    }

    /**
     * 业务作用：借出实例并绑定回调，同时打开自动回收，执行一次后自动归池。
     * 前置条件是回调恰好被调用一次：调用零次实例无法归池并永久占用一个池槽位，
     * 调用多次则第一次后回调已被清空，后续调用会读到已归池甚至被他人改写的实例。
     *
     * @param consumer 回调实现，执行时会拿到本实例以读取槽位
     * 返回: 已绑定回调且开启自动回收的实例。
     */
    public static ActionRecycler ofRecycle(Consumer<ActionRecycler> consumer) {
        return of(consumer).recycleSelf(true);
    }
}
