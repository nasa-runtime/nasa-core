package io.github.nasaruntime.core.concurrent;

import java.io.Serial;
import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Queue;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;

import static io.github.nasaruntime.core.concurrent.MPSCChunk.CHUNK_MASK;
import static io.github.nasaruntime.core.concurrent.MPSCChunk.CHUNK_SHIFT;

// ============================================================================
// Disruptor 风格 cache line padding 继承链 — 隔离 consumer 端与 producer 端字段伪共享.
// 前置 padding 复用共享的 QPad0 (与 MPMC 队列共用); Pad1/Pad2 因夹在本队列专属
// Consumer/Producer 之间 (布局链中部) 无法共用, 保留在本文件。
// ============================================================================
@SuppressWarnings("all")
abstract class MPSCQConsumer<E> extends QPad0 {

    // 单消费者私有推进序号; 保持 volatile 是为了 size/iterator 等跨线程观测有可见性.
    volatile long consumerIndex;
    // 单消费者当前 chunk hint; consumer 推进时切断 prev, 让已消费前缀可 GC.
    volatile MPSCChunk<E> consumerChunk;

    static final VarHandle C_INDEX;

    static {
        try {
            C_INDEX = MethodHandles.lookup().findVarHandle(MPSCQConsumer.class, "consumerIndex", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}

@SuppressWarnings("all")
abstract class MPSCQPad1<E> extends MPSCQConsumer<E> {
    protected long p10, p11, p12, p13, p14, p15, p16;
}

@SuppressWarnings("all")
abstract class MPSCQProducer<E> extends MPSCQPad1<E> {

    // 多生产者全局序号, getAndAdd 分配唯一槽位.
    volatile long producerIndex;
    // 多生产者当前 chunk hint, best-effort 前移.
    volatile MPSCChunk<E> producerChunk;

    static final VarHandle P_INDEX;
    static final VarHandle P_CHUNK;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            P_INDEX = l.findVarHandle(MPSCQProducer.class, "producerIndex", long.class);
            P_CHUNK = l.findVarHandle(MPSCQProducer.class, "producerChunk", MPSCChunk.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}

@SuppressWarnings("all")
abstract class MPSCQPad2<E> extends MPSCQProducer<E> {
    protected long p20, p21, p22, p23, p24, p25, p26;
}

/**
 * Nasa
 * 无界 MPSC 队列, 基于全局 XADD 序号 + chunk 链 ({@link MPSCChunk}).
 *
 * <h2>适用边界</h2>
 * 多 producer 可并发 {@link #offer(Object)}, 但只能有一个 consumer 调用 {@link #poll()} /
 * {@link #peek()} / {@link #clear()}。多消费者并发调用不保证正确性。
 *
 * <h2>热路径</h2>
 * <ul>
 *   <li>producer: {@code getAndAdd(producerIndex)} 分配全局序号, 定位/追加 chunk, release 写元素</li>
 *   <li>consumer: 顺序读当前 {@code consumerIndex} 对应槽位, 成功后清空槽位并 release 推进序号</li>
 * </ul>
 *
 * <h2>可见性与空队列</h2>
 * producer 先递增 {@code producerIndex}, 后发布元素。consumer 若遇到已预留但尚未发布的 head 槽,
 * {@code poll()} 返回 {@code null} 而不是自旋等待, 避免生产者暂停时阻塞单消费者线程。后续 producer
 * 完成 {@code offer()} 后, 调用方按现有 unpark/tick 机制再次 poll 即可。
 */
@SuppressWarnings("all")
public class MPSCLinkedQueue<E> extends MPSCQPad2<E> implements Queue<E>, Serializable {

    @Serial
    private static final long serialVersionUID = 1634141908768394172L;

    /** 尚未发生永久死槽时使用的故障序号哨兵。 */
    private static final long NO_FAILURE = -1L;
    private static final VarHandle FAILURE_INDEX;

    static {
        try {
            FAILURE_INDEX = MethodHandles.lookup().findVarHandle(MPSCLinkedQueue.class, "failureIndex", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * POISON 补写二次失败形成的首个永久死槽序号。
     *
     * <p>字段位于 producer padding 之后，故障检查不会与 producerIndex 共享同一段热字段布局。</p>
     */
    private volatile long failureIndex = NO_FAILURE;

    /**
     * 单 consumer 观察队头时使用的精确状态。
     */
    public enum ConsumerHeadState {
        /** producer 与 consumer 边界一致，没有已预留槽位。 */
        EMPTY,
        /** producer 已取得槽位序号，但元素尚未 release 发布。 */
        RESERVED,
        /** 队头是真实业务元素，可以正常消费。 */
        PUBLISHED,
        /** 队头是可跳过的 POISON 槽位。 */
        POISONED,
        /** 队头是 POISON 补写失败后无法推进的永久死槽。 */
        FAILED
    }

    /**
     * 可复用的单 consumer 游标，用于把成功 {@link #poll(ConsumerCursor)} 与被消费槽位序号精确关联。
     *
     * <p>该对象只能由调用队列消费 API 的唯一 worker 私有持有，不能跨 consumer 共享。</p>
     */
    public static final class ConsumerCursor {

        private long sequence = NO_FAILURE;

        /**
         * 业务作用：取得最近一次成功 poll 返回元素对应的全局槽位序号，供迁移边界判断任务属于哪个阶段。
         *
         * 参数说明: 无。
         * 返回: 最近一次成功 poll 的槽位序号；最近一次 poll 未返回元素时为 -1。
         */
        public long sequence() {
            return this.sequence;
        }

        /**
         * 业务作用：在每次 poll 前清除旧序号，避免调用方把上一次成功结果误用于本次空读。
         *
         * 参数说明: 无。
         * 返回: 无返回值；重置后 {@link #sequence()} 为 -1。
         */
        private void reset() {
            this.sequence = NO_FAILURE;
        }

        /**
         * 业务作用：登记本次真实业务元素来自哪个槽位，使返回元素与消费边界形成无歧义配对。
         *
         * @param sequence 被成功消费的全局槽位序号
         * 返回: 无返回值；该值保持到下一次 poll 开始。
         */
        private void consumed(long sequence) {
            this.sequence = sequence;
        }
    }

    /**
     * 业务作用：创建从序号 0 开始的空 MPSC 队列，并初始化 producer/consumer 共享的首个 chunk。
     *
     * 参数说明: 无。
     * 返回: 构造完成后队列为空且可接受多 producer 发布。
     */
    public MPSCLinkedQueue() {
        MPSCChunk<E> first = new MPSCChunk<>(0L, null);
        this.consumerChunk = first;
        this.producerChunk = first;
    }

    /**
     * 业务作用：创建队列并按集合迭代顺序发布初始元素，保持初始 FIFO 顺序。
     *
     * @param c 初始元素集合，不允许为 null，集合元素也不能为 null
     * 返回: 构造完成后包含集合中的全部元素；发布异常会直接向调用方传播。
     */
    public MPSCLinkedQueue(Collection<? extends E> c) {
        this();
        this.addAll(c);
    }

    // ==================== 导航 ====================

    /**
     * 业务作用：为已取得全局序号的 producer 定位或并发创建目标 chunk，确保每个序号只对应一个物理槽位。
     *
     * @param chunkId 全局序号对应的 chunk 编号
     * 返回: 覆盖该编号的稳定 chunk；链路被异常切断时抛出异常交给 POISON 协议处理。
     */
    private MPSCChunk<E> producerChunkFor(long chunkId) {
        MPSCChunk<E> chunk = (MPSCChunk<E>) P_CHUNK.getAcquire(this);
        long cur = chunk.index;
        while (cur != chunkId) {
            if (cur < chunkId) {
                MPSCChunk<E> next = chunk.lvNext();
                if (next == null) {
                    MPSCChunk<E> nc = new MPSCChunk<>(cur + 1, chunk);
                    if (chunk.casNext(null, nc)) {
                        next = nc;
                    } else {
                        next = chunk.lvNext();
                    }
                }
                P_CHUNK.compareAndSet(this, chunk, next);
                chunk = next;
            } else {
                MPSCChunk<E> prev = chunk.lvPrev();
                // 防御: 正常不变量下回溯只走 index>chunkId>=consumerChunk 的块, prev 不会被切; 若违背宁可抛也不裸 NPE
                if (prev == null) {
                    throw new IllegalStateException("MPSCLinkedQueue: prev link cut during producer back-nav, chunkId=" + chunkId);
                }
                chunk = prev;
            }
            cur = chunk.index;
        }
        return chunk;
    }

    /**
     * 业务作用：由唯一 consumer 前移到指定 chunk，并切断已消费前缀的反向引用以允许 GC。
     *
     * @param chunkId 当前 consumer 序号对应的 chunk 编号
     * 返回: 已经发布的目标 chunk；producer 尚未链接到该位置时返回 null。
     */
    private MPSCChunk<E> consumerChunkFor(long chunkId) {
        MPSCChunk<E> chunk = this.consumerChunk;
        long cur = chunk.index;
        while (cur != chunkId) {
            if (cur > chunkId) return null;
            MPSCChunk<E> next = chunk.lvNext();
            if (next == null) return null;
            this.consumerChunk = next;
            next.soPrev(null);
            chunk = next;
            cur = chunk.index;
        }
        return chunk;
    }

    /**
     * 业务作用：消费 chunk 最后一个槽位后提前切换 consumer hint，缩短已排空 chunk 的可达时间。
     *
     * @param chunk 刚消费元素所属的 chunk
     * @param cIndex 刚消费的全局序号
     * 返回: 无返回值；尚未到 chunk 尾或后继未发布时保持当前 hint。
     */
    private void advanceConsumerChunkIfNeeded(MPSCChunk<E> chunk, long cIndex) {
        if ((cIndex & CHUNK_MASK) != CHUNK_MASK) return;
        MPSCChunk<E> next = chunk.lvNext();
        if (next == null) return;
        this.consumerChunk = next;
        next.soPrev(null);
    }

    // ==================== offer / add ====================

    /**
     * 业务作用：由任意 producer 取得唯一全局槽位并 release 发布元素，异常时用 POISON 保证 consumer 可继续推进。
     *
     * @param e 待发布的非 null 元素
     * 返回: 发布成功固定返回 true；队列已有永久死槽或本次发布失败时抛出异常。
     */
    @Override
    public boolean offer(E e) {
        Objects.requireNonNull(e);
        long failedAt = (long) FAILURE_INDEX.getAcquire(this);
        if (failedAt != NO_FAILURE) {
            throw new IllegalStateException("MPSCLinkedQueue has a permanent failed slot at sequence " + failedAt);
        }
        // getAndAdd 已占位; producerChunkFor(可能 OOM)/soElement 抛异常会留下永久空洞 →
        // 单消费者在该洞处 poll 永远返回 null (该 partition 永久卡死). 异常时尽力写 POISON 让消费者跳过.
        long pIndex = (long) P_INDEX.getAndAdd(this, 1L);
        try {
            MPSCChunk<E> chunk = this.producerChunkFor(pIndex >> CHUNK_SHIFT);
            chunk.soElement((int) (pIndex & CHUNK_MASK), e);
        } catch (Throwable t) {
            Throwable poisonFailure = this.poisonSlot(pIndex);
            if (poisonFailure != null && poisonFailure != t) {
                try {
                    t.addSuppressed(poisonFailure);
                } catch (Throwable ignore) {
                    // 极端内存压力下附加诊断也可能失败；failureIndex 已经提供无分配的永久故障信号。
                }
            }
            throw t;
        }
        return true;
    }

    /**
     * 业务作用：复用无界队列的 offer 语义发布元素，不引入容量拒绝分支。
     *
     * @param e 待发布的非 null 元素
     * 返回: 发布成功返回 true；发布异常直接传播。
     */
    @Override
    public boolean add(E e) {
        return this.offer(e);
    }

    /**
     * 业务作用：在 producer 已占位却无法发布业务元素时补写可跳过标记，避免唯一 consumer 永久卡在空洞。
     *
     * @param pIndex 发布失败的全局槽位序号
     * 返回: POISON 补写成功返回 null；二次失败时记录永久故障序号并返回原始异常。
     */
    private Throwable poisonSlot(long pIndex) {
        try {
            MPSCChunk<E> chunk = this.producerChunkFor(pIndex >> CHUNK_SHIFT);
            chunk.soElement((int) (pIndex & CHUNK_MASK), MPSCChunk.POISON);
            return null;
        } catch (Throwable poisonFailure) {
            // POISON 二次失败会永久阻塞唯一 consumer，必须先发布故障序号，让所属分区关闭完整故障域。
            FAILURE_INDEX.compareAndSet(this, NO_FAILURE, pIndex);
            return poisonFailure;
        }
    }

    // ==================== poll / remove ====================

    /**
     * 业务作用：由唯一 consumer 取得队头真实元素，自动跳过 producer 发布失败留下的 POISON 槽位。
     *
     * 参数说明: 无。
     * 返回: 成功取得的元素；真空、保留槽尚未发布或永久死槽时返回 null。
     */
    @Override
    public E poll() {
        return this.pollInternal(null);
    }

    /**
     * 业务作用：由唯一 consumer 取出一笔业务元素，并精确记录该元素来自哪个全局槽位，供迁移和归还边界分类。
     *
     * @param cursor 由唯一 consumer 私有复用的序号游标，不能为 null
     * 返回: 成功时返回队头业务元素并推进 consumer；队列真空、队头尚未发布或遇到永久死槽时返回 null。
     */
    public E poll(ConsumerCursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        cursor.reset();
        return this.pollInternal(cursor);
    }

    /**
     * 业务作用：实现唯一 consumer 的顺序推进，并把真实元素与可选槽位游标作为同一次消费原子结果发布。
     *
     * @param cursor 需要记录槽位序号时传入的 consumer 私有游标；普通 poll 传 null
     * 返回: 成功取得的真实元素；当前队头无法推进时返回 null。
     */
    private E pollInternal(ConsumerCursor cursor) {
        // 循环以跳过 POISON (发布失败的死槽); 正常一次即返回
        while (true) {
            long cIndex = this.consumerIndex;
            MPSCChunk<E> chunk = this.consumerChunk;
            long chunkId = cIndex >> CHUNK_SHIFT;
            if (chunk.index != chunkId) {
                chunk = this.consumerChunkFor(chunkId);
                if (chunk == null) return null;
            }

            int offset = (int) (cIndex & CHUNK_MASK);
            Object e = chunk.lvElement(offset);
            if (e == null) return null;

            chunk.soElement(offset, null);
            C_INDEX.setRelease(this, cIndex + 1);
            this.advanceConsumerChunkIfNeeded(chunk, cIndex);
            // 死槽, 已消费推进, 继续取下一个
            if (e == MPSCChunk.POISON) continue;
            if (cursor != null) cursor.consumed(cIndex);
            return (E) e;
        }
    }

    /**
     * 业务作用：以 acquire 语义取得 producer 的排他边界，冻结旧路由代次后据此界定需要迁移或归还的有限槽位区间。
     *
     * 参数说明: 无。
     * 返回: 下一次 producer 将取得的全局序号；只有相关旧代次在途 producer 归零后，该边界才可作为控制门禁。
     */
    public long producerBoundary() {
        return (long) P_INDEX.getAcquire(this);
    }

    /**
     * 业务作用：以 acquire 语义取得唯一 consumer 已经推进到的排他边界，用于判断有限迁移区间是否完成。
     *
     * 参数说明: 无。
     * 返回: 下一次 consumer 将检查的全局序号。
     */
    public long consumerBoundary() {
        return (long) C_INDEX.getAcquire(this);
    }

    /**
     * 业务作用：让唯一 consumer 区分真空、已占位未发布、可消费、POISON 和永久死槽，避免把 poll 的 null 误判为迁移完成。
     *
     * 参数说明: 无。
     * 返回: 当前 consumer 序号对应的精确队头状态；本方法不推进 consumer。
     */
    public ConsumerHeadState consumerHeadState() {
        long cIndex = this.consumerIndex;
        long pIndex = (long) P_INDEX.getAcquire(this);
        if (cIndex >= pIndex) return ConsumerHeadState.EMPTY;

        MPSCChunk<E> chunk = this.consumerChunk;
        long chunkId = cIndex >> CHUNK_SHIFT;
        if (chunk.index != chunkId) {
            chunk = this.consumerChunkFor(chunkId);
        }
        if (chunk == null) {
            return cIndex == (long) FAILURE_INDEX.getAcquire(this)
                    ? ConsumerHeadState.FAILED
                    : ConsumerHeadState.RESERVED;
        }

        Object e = chunk.lvElement((int) (cIndex & CHUNK_MASK));
        if (e == null) {
            return cIndex == (long) FAILURE_INDEX.getAcquire(this)
                    ? ConsumerHeadState.FAILED
                    : ConsumerHeadState.RESERVED;
        }
        return e == MPSCChunk.POISON ? ConsumerHeadState.POISONED : ConsumerHeadState.PUBLISHED;
    }

    /**
     * 业务作用：报告队列是否已经出现无法用 POISON 恢复的永久死槽，供上层立即关闭对应分区或类型路由。
     *
     * 参数说明: 无。
     * 返回: 已记录永久死槽时返回 true；普通 offer 异常但 POISON 补写成功时返回 false。
     */
    public boolean isFailed() {
        return (long) FAILURE_INDEX.getAcquire(this) != NO_FAILURE;
    }

    /**
     * 业务作用：取得首个永久死槽的全局序号，供故障证据和恢复工具精确定位无法推进的位置。
     *
     * 参数说明: 无。
     * 返回: 首个永久死槽序号；队列健康时返回 -1。
     */
    public long failureIndex() {
        return (long) FAILURE_INDEX.getAcquire(this);
    }

    /**
     * 业务作用：以 Queue 强制取出语义消费队头，区分“暂无可推进元素”和成功结果。
     *
     * 参数说明: 无。
     * 返回: 成功取得的队头元素；当前不可推进时抛出 NoSuchElementException。
     */
    @Override
    public E remove() {
        E e = this.poll();
        if (e == null) throw new NoSuchElementException();
        return e;
    }

    // ==================== peek / element ====================

    /**
     * 业务作用：由唯一 consumer 查看下一笔真实业务元素，并顺手清除不会交付业务侧的 POISON 前缀。
     *
     * 参数说明: 无。
     * 返回: 当前队头业务元素；真空、尚未发布或永久死槽时返回 null。
     */
    @Override
    public E peek() {
        // 单消费者: 若 head 是 POISON 死槽, 顺手消费掉再看下一个 (POISON 非真实元素)
        while (true) {
            long cIndex = this.consumerIndex;
            MPSCChunk<E> chunk = this.consumerChunk;
            long chunkId = cIndex >> CHUNK_SHIFT;
            if (chunk.index != chunkId) {
                chunk = this.consumerChunkFor(chunkId);
                if (chunk == null) return null;
            }
            int offset = (int) (cIndex & CHUNK_MASK);
            Object e = chunk.lvElement(offset);
            if (e == MPSCChunk.POISON) {
                chunk.soElement(offset, null);
                C_INDEX.setRelease(this, cIndex + 1);
                this.advanceConsumerChunkIfNeeded(chunk, cIndex);
                continue;
            }
            return (E) e;
        }
    }

    /**
     * 业务作用：以 Queue 强制查看语义读取下一笔真实业务元素，不推进普通业务槽位。
     *
     * 参数说明: 无。
     * 返回: 当前队头元素；当前不可观察时抛出 NoSuchElementException。
     */
    @Override
    public E element() {
        E e = this.peek();
        if (e == null) throw new NoSuchElementException();
        return e;
    }

    // ==================== size / isEmpty ====================

    /**
     * 业务作用：提供 producer/consumer 排他边界之差的弱一致积压估算，供诊断而非完成证明。
     *
     * 参数说明: 无。
     * 返回: 包含保留槽和 POISON 在内的边界差，超过 int 上限时截断为 Integer.MAX_VALUE。
     */
    @Override
    public int size() {
        long c = this.consumerIndex;
        long p = (long) P_INDEX.getVolatile(this);
        long s = p - c;
        if (s <= 0) return 0;
        return s >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) s;
    }

    /**
     * 业务作用：弱一致判断当前是否可观察到真实队头元素，并允许唯一 consumer 清除 POISON 前缀。
     *
     * 参数说明: 无。
     * 返回: peek 未取得真实元素时返回 true；不能替代 consumerBoundary 完成证明。
     */
    @Override
    public boolean isEmpty() {
        return this.peek() == null;
    }

    // ==================== iterator / spliterator / forEach (weakly consistent) ====================

    /**
     * 业务作用：从当前 consumer 边界创建弱一致只读迭代器，用于诊断遍历且不参与队列推进。
     *
     * 参数说明: 无。
     * 返回: 跳过空槽和 POISON、上界固定为创建时 producer 边界的迭代器。
     */
    @Override
    public Iterator<E> iterator() {
        long start = this.consumerIndex;
        long end = (long) P_INDEX.getVolatile(this);
        MPSCChunk<E> chunk = this.consumerChunk;
        long chunkStart = chunk.index << CHUNK_SHIFT;
        if (start < chunkStart) start = chunkStart;
        return new MPSCQueueIterator<>(chunk, start, end);
    }

    /**
     * 业务作用：为并发弱一致诊断遍历提供保持 FIFO 顺序的 Spliterator 视图。
     *
     * 参数说明: 无。
     * 返回: 标记为 ORDERED、NONNULL、CONCURRENT 的未知大小 Spliterator。
     */
    @Override
    public Spliterator<E> spliterator() {
        return Spliterators.spliteratorUnknownSize(this.iterator(),
                Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.CONCURRENT);
    }

    /**
     * 业务作用：按弱一致迭代顺序访问当前可见业务元素，不阻塞 producer 或推进 consumer。
     *
     * @param action 每个可见元素执行的非 null 操作
     * 返回: 无返回值；操作异常直接向调用方传播。
     */
    @Override
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        for (Iterator<E> it = this.iterator(); it.hasNext(); ) {
            action.accept(it.next());
        }
    }

    // ==================== contains / containsAll / addAll ====================

    /**
     * 业务作用：通过弱一致快照诊断目标对象当前是否仍可见，不提供并发线性化存在性证明。
     *
     * @param o 待查找对象；null 固定视为不存在
     * 返回: 本轮迭代观察到 equals 匹配元素时返回 true。
     */
    @Override
    public boolean contains(Object o) {
        if (o == null) return false;
        for (Iterator<E> it = this.iterator(); it.hasNext(); ) {
            if (o.equals(it.next())) return true;
        }
        return false;
    }

    /**
     * 业务作用：逐项执行弱一致存在性检查，满足 Queue 集合接口但不承诺跨元素同一时刻快照。
     *
     * @param c 待检查的对象集合
     * 返回: 本轮分别观察到集合中全部对象时返回 true。
     */
    @Override
    public boolean containsAll(Collection<?> c) {
        for (Object e : c) {
            if (!this.contains(e)) return false;
        }
        return true;
    }

    /**
     * 业务作用：按来源集合迭代顺序逐个发布元素，保留本调用内部的 FIFO 先后关系。
     *
     * @param c 待发布集合，元素不能为 null
     * 返回: 至少成功发布一个元素时返回 true；发布异常直接传播且此前元素保持已发布。
     */
    @Override
    public boolean addAll(Collection<? extends E> c) {
        boolean changed = false;
        for (E e : c) changed |= this.offer(e);
        return changed;
    }

    // ==================== toArray ====================

    /**
     * 业务作用：把一次弱一致诊断遍历复制为普通对象数组，不改变 consumer 边界。
     *
     * 参数说明: 无。
     * 返回: 按本轮迭代顺序收集的业务元素数组。
     */
    @Override
    public Object[] toArray() {
        ArrayList<E> list = new ArrayList<>();
        this.forEach(list::add);
        return list.toArray();
    }

    /**
     * 业务作用：把弱一致诊断遍历复制到调用方指定运行时类型的数组。
     *
     * @param a 目标数组或数组类型模板
     * 返回: 按 Collection.toArray 契约填充或新建的数组。
     */
    @Override
    public <T> T[] toArray(T[] a) {
        ArrayList<E> list = new ArrayList<>();
        this.forEach(list::add);
        return list.toArray(a);
    }

    /**
     * 业务作用：使用调用方数组生成器承载一次弱一致诊断遍历结果。
     *
     * @param generator 按所需长度创建目标数组的生成器
     * 返回: 按本轮迭代顺序收集的类型化数组。
     */
    @Override
    public <T> T[] toArray(IntFunction<T[]> generator) {
        ArrayList<E> list = new ArrayList<>();
        this.forEach(list::add);
        return list.toArray(generator);
    }

    /**
     * 业务作用：在所有 producer 已经确定退出后跨过永久空洞，逐项摘除仍发布在槽位中的业务元素并切断已消费块引用。
     * 该入口只用于关闭故障队列；与 producer 并发会遗漏迟到发布，多个 consumer 并发会重复处置同一元素。
     *
     * @param action 每个已发布业务元素的终态处置动作，POISON 与未发布空槽不会传入
     * 返回: 实际交给 action 的元素数；action 异常时仍清空其余槽位，最后重新抛出首次异常。
     */
    public int drainPublishedAfterProducersStop(Consumer<? super E> action) {
        Objects.requireNonNull(action, "action");
        long cursor = this.consumerIndex;
        long end = (long) P_INDEX.getAcquire(this);
        MPSCChunk<E> chunk = this.consumerChunk;
        MPSCChunk<E> last = chunk;
        int drained = 0;
        Throwable firstFailure = null;

        while (cursor < end) {
            long chunkId = cursor >> CHUNK_SHIFT;
            while (chunk != null && chunk.index < chunkId) {
                MPSCChunk<E> next = chunk.lvNext();
                if (next != null) next.soPrev(null);
                chunk = next;
            }
            if (chunk == null || chunk.index != chunkId) break;
            last = chunk;
            int offset = (int) (cursor & CHUNK_MASK);
            Object element = chunk.lvElement(offset);
            chunk.soElement(offset, null);
            cursor++;
            if (element == null || element == MPSCChunk.POISON) continue;
            drained++;
            try {
                action.accept((E) element);
            } catch (Throwable failure) {
                if (firstFailure == null) firstFailure = failure;
            }
        }

        C_INDEX.setRelease(this, end);
        if (last != null) {
            this.consumerChunk = last;
            last.soPrev(null);
        }
        if (firstFailure instanceof RuntimeException runtime) throw runtime;
        if (firstFailure instanceof Error error) throw error;
        if (firstFailure != null) throw new IllegalStateException("MPSC failure drain action failed", firstFailure);
        return drained;
    }

    // ==================== clear ====================

    /**
     * 业务作用：在确认没有并发 producer 后由唯一 consumer 清空所有已发布元素和 POISON 槽位。
     * <p>
     * <b>不可与 producer 并发使用</b>: poll 在遇到"producer 已 getAndAdd 占位但尚未 soElement 发布"的洞时返回 null,
     * clear 会就此提前退出, 残留其后元素。仅在确保无并发 offer 时调用才能保证真正清空。
     *
     * 参数说明: 无。
     * 返回: 无返回值；存在尚未发布保留槽时可能提前结束。
     */
    @Override
    public void clear() {
        while (this.poll() != null) {
            // 排空
        }
    }

    // ==================== Not supported (数组槽位不允许中段抠洞) ====================

    /**
     * 业务作用：拒绝按对象删除中间槽位，保护单 consumer 连续推进和 POISON 故障协议。
     *
     * @param o 待删除对象
     * 返回: 不返回，固定抛出 UnsupportedOperationException。
     */
    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException("MPSCLinkedQueue: remove(Object) not supported");
    }

    /**
     * 业务作用：拒绝批量删除中间槽位，避免在 MPSC 序号空间制造未登记空洞。
     *
     * @param c 待删除对象集合
     * 返回: 不返回，固定抛出 UnsupportedOperationException。
     */
    @Override
    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException("MPSCLinkedQueue: removeAll not supported");
    }

    /**
     * 业务作用：拒绝通过保留集合批量抠除中间槽位，保持物理 FIFO 连续性。
     *
     * @param c 希望保留的对象集合
     * 返回: 不返回，固定抛出 UnsupportedOperationException。
     */
    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException("MPSCLinkedQueue: retainAll not supported");
    }

    /**
     * 业务作用：拒绝按谓词并发删除中间槽位，取消必须由上层任务状态机发布终态后让 consumer 跳过。
     *
     * @param filter 删除判断谓词
     * 返回: 不返回，固定抛出 UnsupportedOperationException。
     */
    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        throw new UnsupportedOperationException("MPSCLinkedQueue: removeIf not supported");
    }

    // ==================== toString ====================

    /**
     * 业务作用：生成当前弱一致可见业务元素的诊断字符串，不暴露 POISON 或保留槽。
     *
     * 参数说明: 无。
     * 返回: 按本轮迭代顺序拼接的方括号列表。
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Iterator<E> it = this.iterator(); it.hasNext(); ) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(it.next());
        }
        return sb.append(']').toString();
    }
}
