package io.github.nasaruntime.core.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

// ============================================================================
// MPMCChunk — MPMCLinkedQueue 的固定容量数据块 (XADD 寻址), 支持池化复用.
// ----------------------------------------------------------------------------
// 全局序号 i 唯一落在 chunk (i >> CHUNK_SHIFT) 的 offset (i & CHUNK_MASK).
// 拆顶级 package-private (项目规范: 不写内部类).
//
// 字段:
//   index  : chunk 序号 (全局单调递增, 永不重复; 复用时赋更大序号). FREE=已退役.
//            volatile — 复用改写, 且 HP 校验/导航靠它判别 chunk 是否被回收/复用
//   buffer : CHUNK_SIZE 个逻辑槽位, 物理稀疏 (offset<<SLOT_SHIFT) 让相邻槽独占 cache line.
//            null=未发布, 元素=可消费, TOMBSTONE=已消费. 复用前 clearForReuse 全清回 null.
//   next   : 前向链, CAS 由 null 单次设置. 退役复位 null.
//   prev   : 后向链, 供 producer 回溯; consumer 推进 consumerChunk 时置 null 切断使前缀可回收.
// ============================================================================
@SuppressWarnings("all")
final class MPMCChunk<E> {

    static final int CHUNK_SHIFT = 10;
    static final int CHUNK_SIZE = 1 << CHUNK_SHIFT;
    static final int CHUNK_MASK = CHUNK_SIZE - 1;

    // 槽位稀疏: 逻辑 offset 物理上乘 1<<SLOT_SHIFT, 让相邻槽独占 cache line. shift=3 即可让多消费者反超 CLQ.
    static final int SLOT_SHIFT = 3;

    static final Object TOMBSTONE = new Object();

    // 发布失败哨兵: producer 已 getAndAdd 占位但 producerChunkFor/soElement 抛异常(如 OOM)无法发布元素时,
    // 尽力把该槽写成 POISON 让 consumer 跳过 (丢这一个元素), 避免永久空洞 → MPMC 自旋熔毁 / MPSC 卡死.
    static final Object POISON = new Object();

    // index 哨兵: 已退役 (在 cache/pool 或待 GC). 真实 chunk 序号 >= 0
    static final long FREE = -1L;

    static final VarHandle NEXT;
    static final VarHandle PREV;
    static final VarHandle INDEX;
    static final VarHandle ELEM = MethodHandles.arrayElementVarHandle(Object[].class);

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            NEXT = l.findVarHandle(MPMCChunk.class, "next", MPMCChunk.class);
            PREV = l.findVarHandle(MPMCChunk.class, "prev", MPMCChunk.class);
            INDEX = l.findVarHandle(MPMCChunk.class, "index", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    volatile long index;
    final Object[] buffer;
    volatile MPMCChunk<E> next;
    volatile MPMCChunk<E> prev;

    /**
     * 业务作用：按给定参数构造 MPMCChunk 实例。
     *
     * @param index 下标
     * @param prev 见上述说明
     * 返回: 构造完成后可直接使用的实例。
     */
    MPMCChunk(long index, MPMCChunk<E> prev) {
        this.index = index;
        this.prev = prev;
        this.buffer = new Object[CHUNK_SIZE << SLOT_SHIFT];
    }

    // 访问器命名沿用 JCTools 约定: lv=load-volatile(acquire 读), so=store-ordered(release 写), cas=compareAndSet.
    // release-acquire 配对完成 producer soElement → consumer lvElement 的发布 happens-before.

    /**
     * 业务作用：以 volatile 语义读块序号，保证与生产者的发布形成 happens-before。
     *
     * 参数说明: 无。
     * 返回: 块序号。
     */
    long lvIndex() {
        return (long) INDEX.getAcquire(this);
    }

    /**
     * 业务作用：以 volatile 语义读后继块。
     *
     * 参数说明: 无。
     * 返回: 后继块；尚未链接时返回 null。
     */
    MPMCChunk<E> lvNext() {
        return (MPMCChunk<E>) NEXT.getAcquire(this);
    }

    /**
     * 业务作用：以 CAS 链接后继块，保证多个生产者同时扩链时只有一个成功。
     *
     * @param expect 期望的当前值
     * @param update 目标值
     * 返回: 链接成功返回 true。
     */
    boolean casNext(MPMCChunk<E> expect, MPMCChunk<E> update) {
        return NEXT.compareAndSet(this, expect, update);
    }

    /**
     * 业务作用：以 volatile 语义读前驱块。
     *
     * 参数说明: 无。
     * 返回: 前驱块。
     */
    MPMCChunk<E> lvPrev() {
        return (MPMCChunk<E>) PREV.getAcquire(this);
    }

    /**
     * 业务作用：以 release 语义写前驱块，使之对随后 acquire 读的线程可见。
     *
     * @param p 前驱块
     * 返回: 无返回值。
     */
    void soPrev(MPMCChunk<E> p) {
        PREV.setRelease(this, p);
    }

    /**
     * 业务作用：以 release 语义写入元素，与消费者的 acquire 读配对建立可见性。
     *
     * @param offset 槽位偏移
     * @param e 元素
     * 返回: 无返回值。
     */
    void soElement(int offset, E e) {
        ELEM.setRelease(this.buffer, offset << SLOT_SHIFT, e);
    }

    /**
     * 业务作用：以 volatile 语义读元素。
     *
     * @param offset 槽位偏移
     * 返回: 该槽位元素；尚未写入时返回 null。
     */
    E lvElement(int offset) {
        return (E) ELEM.getAcquire(this.buffer, offset << SLOT_SHIFT);
    }

    /**
     * 业务作用：以 volatile 语义读槽位原始内容，包含用于标记状态的哨兵对象。
     *
     * @param offset 槽位偏移
     * 返回: 槽位原始内容。
     */
    Object lvElementRaw(int offset) {
        return ELEM.getAcquire(this.buffer, offset << SLOT_SHIFT);
    }

    /**
     * 业务作用：以 CAS 更新槽位，用于消费者抢占与状态标记。
     *
     * @param offset 槽位偏移
     * @param expect 期望的当前值
     * @param update 目标值
     * 返回: 更新成功返回 true。
     */
    boolean casElement(int offset, Object expect, Object update) {
        return ELEM.compareAndSet(this.buffer, offset << SLOT_SHIFT, expect, update);
    }

    // ==================== 池化复用 (HP 协议) ====================

    // unpublish: 置 FREE (release). 必须在回收者扫 HP 之前调用 — 与 protect 的 publish+fence+校验 构成 Dekker.
    void soFree() {
        INDEX.setRelease(this, FREE);
    }

    // 清空 next/prev 与全部槽位 (含残留 TOMBSTONE), 供复用. index 已为 FREE.
    void clearForReuse() {
        this.next = null;
        this.prev = null;
        for (int i = 0; i < CHUNK_SIZE; i++) {
            this.buffer[i << SLOT_SHIFT] = null;
        }
    }

    // 复用: 赋新序号与 prev (块此刻尚未发布, 随后由 casNext release 链入)
    /**
     * 业务作用：复位块以便重新投入使用，避免为每一段队列反复分配块对象。
     *
     * @param newIndex 新的块序号
     * @param prev 前驱块
     * 返回: 无返回值。
     */
    void reuse(long newIndex, MPMCChunk<E> prev) {
        this.prev = prev;
        INDEX.setRelease(this, newIndex);
    }
}
