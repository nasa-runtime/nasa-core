package com.nasa.runtime.core.base;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.nasa.runtime.core.concurrent.ConcurrentRingQueue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Nasa
 * 堆内对象池，尽量复用堆内常用的对象。
 * 内部使用 {@link ConcurrentRingQueue} 作为有界 MPMC 池，容量和边界由 ring 管理。
 *
 * <h2>Recycler 实现二选一</h2>
 * Recycler 实现类必须 override {@link Recycler#objectPool()} 或 {@link Recycler#handle()} 之一 (都不 override 时 recycle 抛 NPE):
 * <ul>
 *   <li><b>{@link Recycler#objectPool()}</b> — 老路径, 直接归池, 无 double-recycle 防御</li>
 *   <li><b>{@link Recycler#handle()}</b> — 持有 {@link PooledHandle} final 字段, 自带 CAS 防御, 同一对象多次 recycle 仅第一次生效</li>
 * </ul>
 * 两者在 recycle 路径上互斥: 有 handle 走 handle, 否则走 objectPool. 老代码保持 objectPool 实现即可零侵入,
 * 新代码或对 double-recycle 敏感的代码建议用 handle.
 */
public abstract class ObjectPool<T extends ObjectPool.Recycler<T>> {

    /* object pool */
    final ConcurrentRingQueue<T> pool;

    public ObjectPool(int capacity) {
        this.pool = new ConcurrentRingQueue<>(capacity);
    }

    /**
     * new object
     */
    public abstract T newObject();

    /**
     * Get an object from object pool
     * initialize the object with the parameters
     */
    public T get() {
        T t = this.pool.poll();
        if (t != null) {
            PooledHandle<T> h = t.handle();
            if (h != null) h.markInUse();
            return t;
        }
        return this.newObject();
    }

    /**
     * return the pool capacity
     */
    public int getCapacity() {
        return this.pool.capacity();
    }

    /**
     * return the pool size
     */
    public int size() {
        return this.pool.size();
    }

    /**
     * 清空对象池，所有对象将由JVM回收
     */
    public void removeAll() {
        this.pool.clear();
    }

    /**
     * 对于要回收的对象，必须实现这个接口
     *
     * @param <T> 回收实例泛型
     */
    public interface Recycler<T extends Recycler<T>> {

        /**
         * 老路径: 直接归池, 无 double-recycle 防御.
         * <p>
         * <b>与 {@link #handle()} 二选一</b> — 提供了 {@link #handle()} 时本方法无需 override (handle 自带池引用,
         * recycle 路径不会走到这里). 都不 override 则 {@link #recycle()} 抛 NPE.
         */
        default ObjectPool<T> objectPool() {
            return null;
        }

        /**
         * 属性值恢复如初
         */
        void restore();

        /**
         * 新路径: 池化身份 Handle, 提供 double-recycle 防御.
         * <p>
         * <b>与 {@link #objectPool()} 二选一</b> — 提供了本方法就无需 override {@link #objectPool()}
         * (Handle 自带池引用). 默认 null 走老路径, 老代码零侵入.
         * <p>
         * 想要 double-recycle 防御的实现类:
         * <pre>{@code
         *   class MyObj implements ObjectPool.Recycler<MyObj> {
         *       static final ObjectPool<MyObj> POOL = ...;
         *       private final PooledHandle<MyObj> handle = new PooledHandle<>(POOL);
         *
         *       @Override public PooledHandle<MyObj> handle() { return this.handle; }
         *       @Override public void restore() { ... }
         *       // objectPool() 无需 override
         *   }
         * }</pre>
         * <p>
         * <b>序列化</b>: {@link PooledHandle} 类标了 {@link JsonIgnoreType}, Recycler 实现的 handle 字段被 Jackson
         * 自动跳过, 实现类无需逐个加 {@code @JsonIgnore}.
         */
        default PooledHandle<T> handle() {
            return null;
        }

        /**
         * 对象池回收
         */
        @SuppressWarnings("unchecked")
        default void recycle() {
            PooledHandle<T> h = this.handle();
            if (h != null) {
                // 走 Handle 路径: CAS 防 double-recycle, 池满自动回滚状态
                h.recycle((T) this);
                return;
            }
            // 兼容老实现: 没 override handle(), 走原直接归池路径
            ObjectPool<T> pool = this.objectPool();
            if (pool == null) {
                throw new NullPointerException("object pool can not be null");
            }
            // restore all field's value
            this.restore();
            // 满了返回 false, 对象交给 GC
            pool.pool.offer((T) this);
        }

        /**
         * "未被使用即丢弃" 时的回收钩子. 默认 no-op (业务方自管生命周期).
         * <p>
         * 设计动机: 调度框架 (例如 {@code TimingWheel}) 可能持有 Recycler 但因 cancel 等原因不调用业务逻辑,
         * 此时 Recycler 实例脱离调用闭环, 既未归池也未被业务方主动 recycle, 只能等 GC, 池化失效.
         * <p>
         * Recycler 实现可 override 此方法决定 "未被使用就丢弃" 时是否归池:
         * <ul>
         *   <li>{@code ActionRecycler.ofRecycle} 模式 (recycleSelf=true) — override 调 {@link #recycle()} 主动归池</li>
         *   <li>{@code ActionRecycler.of} 模式 / 其他业务自管 Recycler — 保持 default no-op, 由业务方控制</li>
         * </ul>
         */
        default void cancelledRecycle() {
            // no-op by default; 想要 "未使用即归池" 语义的 Recycler 自行 override
        }
    }

    /**
     * 池化身份 Handle, 持有 CAS 状态 + 池引用, 与业务对象解耦.
     * <p>
     * 用途: 防 double-recycle (同一对象被业务侧错误 recycle 两次, 污染整池).
     * Recycler 实现类持有一个 final {@code PooledHandle} 字段并通过 {@link Recycler#handle()} 暴露即可启用.
     * <p>
     * 语义:
     *   <ul>
     *     <li>新对象 / 刚出池: state = {@link #IN_USE}</li>
     *     <li>recycle 入池: CAS IN_USE → IN_POOL, 失败 (已是 IN_POOL) 说明重复 recycle, 静默忽略</li>
     *     <li>get 派发: {@link #markInUse()} 把 state 切回 IN_USE</li>
     *     <li>池满 offer 失败: 状态回滚为 IN_USE, 对象交给 GC, 不留 "卡死在 IN_POOL" 的孤儿引用</li>
     *   </ul>
     * <p>
     * 跨线程安全: 单字段 CAS, 防同线程串行 + 跨线程并发两种 double-recycle 形态.
     * <p>
     * 内存开销: 每对象 +1 Handle (~28~32 字节), 与 AtomicBoolean 方案同价, 但状态逻辑集中,
     * 可演化性更好 (后续可扩展为 IN_USE / IN_POOL / DISPOSED 三态机).
     * <p>
     * {@link JsonIgnoreType} 通用解: 类型级跳过, 所有 Recycler 实现的 handle 字段无需逐个标 {@code @JsonIgnore}.
     * 不忽略会触发 Jackson 死循环: handle.pool → ObjectPool.pool (ConcurrentRingQueue 缓存其它池化对象)
     * → 它们又有 handle → 递归直到撞 {@code StreamWriteConstraints.maxNestingDepth=1000} 抛错.
     * <p>
     * 选择类型级注解而非每个实现类字段级注解的理由:
     *   <ul>
     *     <li>语义匹配: PooledHandle 是对象池内部状态 (CAS state + pool 引用), 跟业务数据无关,
     *         在任何上下文都不应该出现在 JSON 里 —— 这是类型固有属性, 不是 case-by-case 业务决策.</li>
     *     <li>零维护成本: 未来新增 Recycler 实现或业务 DTO 不用额外维护类型清单，编译器和 Jackson
     *         自动兜底; 漏加导致的死循环不会复发.</li>
     *     <li>不依赖 visibility 配置: 业务 ObjectMapper 设 {@code fieldVisibility=ANY} 探到 private 字段,
     *         Jackson 看字段类型仍跳过, 比 {@code @JsonIgnore} 标在 getter 上更稳 (handle() 不以 get/is 开头,
     *         默认 Jackson 不识别为 getter, 命中性取决于配置).</li>
     *   </ul>
     * 跟 {@code MatchOrder.takerKey} 那种字段级 @JsonIgnore 的语义对比: 后者是 "String 类型本身要序列化, 只是这个字段不要",
     * 前者 (本方案) 是 "PooledHandle 类型从不序列化", 类型级注解才是正确语义.
     */
    @JsonIgnoreType
    public static final class PooledHandle<T extends Recycler<T>> {

        private static final VarHandle STATE;

        static {
            try {
                STATE = MethodHandles.lookup().findVarHandle(PooledHandle.class, "state", int.class);
            } catch (ReflectiveOperationException e) {
                throw new Error(e);
            }
        }

        private static final int IN_USE = 0;
        private static final int IN_POOL = 1;

        private final ObjectPool<T> pool;

        @SuppressWarnings("all")
        private volatile int state = IN_USE;

        public PooledHandle(ObjectPool<T> pool) {
            this.pool = pool;
        }

        /**
         * 由 {@link Recycler#recycle()} 默认路径调用. CAS IN_USE → IN_POOL,
         * 失败说明 double-recycle, 静默忽略 (不抛异常, 不污染池).
         */
        public void recycle(T self) {
            if (!STATE.compareAndSet(this, IN_USE, IN_POOL)) {
                // 已在池中, 重复 recycle 直接吞掉
                return;
            }
            self.restore();
            if (!this.pool.pool.offer(self)) {
                // 池满, offer 失败, 状态回滚, 否则外部若还持有引用会永远 recycle 不进
                STATE.setRelease(this, IN_USE);
            }
        }

        /**
         * {@link ObjectPool#get()} 派发对象前调用, 把状态切回 IN_USE.
         */
        void markInUse() {
            STATE.setRelease(this, IN_USE);
        }
    }
}
