package io.github.nasaruntime.core.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

// ============================================================================
// MPSCChunk — MPSCLinkedQueue 专用数据块 (单消费者).
// ----------------------------------------------------------------------------
// 相对 MPMCChunk 更紧凑: 单消费者无并发认领 → 不需要 SLOT_SHIFT 槽隔离, 也不需要 TOMBSTONE/CAS 认领,
// buffer 为密集 Object[CHUNK_SIZE] (省 2^SLOT_SHIFT 倍内存与 churn). 非池化, index/next 稳定.
//   index : chunk 序号, 构造即定, final
//   buffer: 密集槽位. null=未发布/已消费; 元素=可消费; POISON=producer 发布失败(offer 异常)的死槽, consumer 跳过
//   next  : 前向链, CAS 由 null 单次设置
//   prev  : 后向链, 供 producer 回溯; 单消费者推进时 soPrev(null) 切断使前缀可 GC
// ============================================================================
@SuppressWarnings("all")
final class MPSCChunk<E> {

    static final int CHUNK_SHIFT = MPMCChunk.CHUNK_SHIFT;
    static final int CHUNK_SIZE = MPMCChunk.CHUNK_SIZE;
    static final int CHUNK_MASK = MPMCChunk.CHUNK_MASK;

    // 发布失败哨兵 (与 MPMCChunk.POISON 同义, 独立实例以解耦)
    static final Object POISON = new Object();

    static final VarHandle NEXT;
    static final VarHandle PREV;
    static final VarHandle ELEM = MethodHandles.arrayElementVarHandle(Object[].class);

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            NEXT = l.findVarHandle(MPSCChunk.class, "next", MPSCChunk.class);
            PREV = l.findVarHandle(MPSCChunk.class, "prev", MPSCChunk.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    final long index;
    final Object[] buffer;
    volatile MPSCChunk<E> next;
    volatile MPSCChunk<E> prev;

    /**
     * 业务作用：按给定参数构造 MPSCChunk 实例。
     *
     * @param index 下标
     * @param prev 见上述说明
     * 返回: 构造完成后可直接使用的实例。
     */
    MPSCChunk(long index, MPSCChunk<E> prev) {
        this.index = index;
        this.prev = prev;
        this.buffer = new Object[CHUNK_SIZE];
    }

    // 访问器命名沿用 JCTools 约定: lv=load-volatile(acquire 读), so=store-ordered(release 写), cas=compareAndSet.

    /**
     * 业务作用：以 volatile 语义读后继块。
     *
     * 参数说明: 无。
     * 返回: 后继块；尚未链接时返回 null。
     */
    MPSCChunk<E> lvNext() {
        return (MPSCChunk<E>) NEXT.getAcquire(this);
    }

    /**
     * 业务作用：以 CAS 链接后继块，保证多个生产者同时扩链时只有一个成功。
     *
     * @param expect 期望的当前值
     * @param update 目标值
     * 返回: 链接成功返回 true。
     */
    boolean casNext(MPSCChunk<E> expect, MPSCChunk<E> update) {
        return NEXT.compareAndSet(this, expect, update);
    }

    /**
     * 业务作用：以 volatile 语义读前驱块。
     *
     * 参数说明: 无。
     * 返回: 前驱块。
     */
    MPSCChunk<E> lvPrev() {
        return (MPSCChunk<E>) PREV.getAcquire(this);
    }

    /**
     * 业务作用：以 release 语义写前驱块。
     *
     * @param p 前驱块
     * 返回: 无返回值。
     */
    void soPrev(MPSCChunk<E> p) {
        PREV.setRelease(this, p);
    }

    // 写元素 (release), 与 consumer 的 acquire 读配对发布. Object 形参以便写入 POISON 哨兵.
    void soElement(int offset, Object e) {
        ELEM.setRelease(this.buffer, offset, e);
    }

    // 读元素 (acquire). 返回 Object: 调用方判别 null / POISON / 真实元素
    /**
     * 业务作用：以 volatile 语义读元素。
     *
     * @param offset 槽位偏移
     * 返回: 该槽位元素；尚未写入时返回 null。
     */
    Object lvElement(int offset) {
        return ELEM.getAcquire(this.buffer, offset);
    }
}
