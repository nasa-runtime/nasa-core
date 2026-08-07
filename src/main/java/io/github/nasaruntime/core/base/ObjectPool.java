package io.github.nasaruntime.core.base;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import io.github.nasaruntime.core.concurrent.ConcurrentRingQueue;

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

    /**
     * 业务作用：创建固定容量的对象池。容量决定池能缓存多少个空闲对象，即能吸收多大幅度的
     * 在途量震荡；超出部分在归池时被丢弃交给 GC，池不会因此失效，只是复用率下降。
     *
     * @param capacity 期望缓存的空闲对象上限
     * 返回: 构造完成后池为空，首次 get 会走 newObject 按需创建。
     */
    public ObjectPool(int capacity) {
        this.pool = new ConcurrentRingQueue<>(capacity);
    }

    /**
     * 业务作用：由具体池实现提供的对象工厂，仅在池内无可复用对象时调用。
     * 实现必须返回一个可直接使用的新实例，不得返回 null，否则 get 会把 null 交给调用方。
     *
     * 参数说明: 无。
     * 返回: 全新的池化对象实例。
     */
    public abstract T newObject();

    /**
     * 业务作用：取得一个可用对象。优先复用池内空闲对象并把其池化状态切回 IN_USE，
     * 池空时按需新建。状态切换必须发生在把对象交给调用方之前，否则后续 recycle 的
     * CAS 会因状态仍是 IN_POOL 而被误判为重复归池，导致对象永久漏出池外。
     *
     * 参数说明: 无。
     * 返回: 可立即使用的对象；调用方负责在用完后 recycle。
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
     * 业务作用：暴露构造期固定的池容量，供容量规划与监控使用。
     *
     * 参数说明: 无。
     * 返回: 池容量；该值在整个生命周期内不变。
     */
    public int getCapacity() {
        return this.pool.capacity();
    }

    /**
     * 业务作用：报告池内当前空闲对象数，供观测复用率与容量是否偏小。
     *
     * 参数说明: 无。
     * 返回: 采样瞬间的空闲对象数；并发场景下为近似值。
     */
    public int size() {
        return this.pool.size();
    }

    /**
     * 业务作用：清空池内全部空闲对象，解除池对它们的强引用后交给 GC，
     * 用于内存吃紧或需要主动释放缓存的场景。已借出的对象不受影响，归池行为也不受影响。
     *
     * 参数说明: 无。
     * 返回: 无返回值；清空后 get 会重新走 newObject。
     */
    public void removeAll() {
        this.pool.clear();
    }

    /**
     * 对于要回收的对象，必须实现这个接口
     *
     */
    public interface Recycler<T extends Recycler<T>> {

        /**
         * 业务作用：老路径的池引用来源，直接归池且无重复归池防御，供已有代码零侵入接入。
         * 与 handle() 二选一：提供了 handle() 时本方法不会被 recycle 走到。
         *
         * 参数说明: 无。
         * 返回: 所属对象池；两个方法都不覆写时返回 null，recycle 会因此抛 NullPointerException。
         */
        default ObjectPool<T> objectPool() {
            return null;
        }

        /**
         * 业务作用：归池前把对象字段复位到初始状态，是防止上一代残留数据泄漏给下一个借用方的
         * 唯一保证。实现必须清空所有业务字段与外部引用，尤其是可能导致跨代误判的状态字段。
         *
         * 参数说明: 无。
         * 返回: 无返回值；由框架在归池路径上调用，业务方不应直接调用。
         */
        void restore();

        /**
         * 业务作用：新路径的池化身份来源，返回非 null 即启用 CAS 重复归池防御。
         * 与 objectPool() 二选一，默认返回 null 以保持老代码零侵入。
         *
         * 参数说明: 无。
         * 返回: 实现类持有的稳定 PooledHandle；未启用该路径时返回 null。
         */
        default PooledHandle<T> handle() {
            return null;
        }

        /**
         * 业务作用：把对象归还给所属池的统一入口。优先走 handle 路径以获得重复归池防御，
         * 没有 handle 时退回直接归池的老路径。
         *
         * 参数说明: 无。
         * 返回: 无返回值；两条路径都不可用（handle 与 objectPool 均为 null）时抛出 NullPointerException。
         *      归池后调用方必须视该引用为失效，继续使用会读到已复位或已被他人借出的状态。
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
         * 业务作用："被持有但从未使用即丢弃" 时的回收钩子。调度框架可能因取消等原因持有 Recycler
         * 却不调用业务逻辑，此时对象脱离正常调用闭环，既未归池也不会被业务方主动回收，只能等 GC，
         * 池化因此失效。实现方按自身生命周期归属决定是否在此主动归池。
         *
         * 参数说明: 无。
         * 返回: 无返回值；默认空实现，表示由业务方自管生命周期。
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

        /**
         * 业务作用：创建与业务对象解耦的池化身份，持有 CAS 状态与所属池引用。
         * 每个池化对象持有一个不变的 handle，使池化状态不污染业务字段，也不受 restore 影响。
         *
         * @param pool 该对象归属的对象池
         * 返回: 构造完成后状态为 IN_USE，表示对象正被调用方持有。
         */
        public PooledHandle(ObjectPool<T> pool) {
            this.pool = pool;
        }

        /**
         * 业务作用：执行一次归池。CAS 把状态从 IN_USE 切到 IN_POOL 后才复位字段并入池，
         * CAS 失败说明已经在池中，属于重复归池，静默吞掉而不是污染整池。
         * 入池失败（池满）时必须把状态回滚为 IN_USE，否则外部若仍持有该引用将永远归还不进。
         *
         * @param self 待归池的对象本身
         * 返回: 无返回值；重复归池与池满两种情况都不抛异常。
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
         * 业务作用：对象出池派发前把状态切回 IN_USE。必须先于把引用交给调用方完成，
         * 否则调用方之后的 recycle 会被 CAS 判为重复归池而失败，对象就此漏出池外。
         *
         * 参数说明: 无。
         * 返回: 无返回值。
         */
        void markInUse() {
            STATE.setRelease(this, IN_USE);
        }
    }
}
