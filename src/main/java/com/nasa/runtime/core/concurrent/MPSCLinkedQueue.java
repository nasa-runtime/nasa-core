package com.nasa.runtime.core.concurrent;

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

import static com.nasa.runtime.core.concurrent.MPSCChunk.CHUNK_MASK;
import static com.nasa.runtime.core.concurrent.MPSCChunk.CHUNK_SHIFT;

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

    public MPSCLinkedQueue() {
        MPSCChunk<E> first = new MPSCChunk<>(0L, null);
        this.consumerChunk = first;
        this.producerChunk = first;
    }

    public MPSCLinkedQueue(Collection<? extends E> c) {
        this();
        this.addAll(c);
    }

    // ==================== 导航 ====================

    // 定位 producer 序号所属 chunk: 从 producerChunk hint 出发, 落后则前向追加, 领先则沿 prev 回溯.
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

    // 定位 consumer 序号所属 chunk. 单消费者独占推进, 无需 CAS.
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

    private void advanceConsumerChunkIfNeeded(MPSCChunk<E> chunk, long cIndex) {
        if ((cIndex & CHUNK_MASK) != CHUNK_MASK) return;
        MPSCChunk<E> next = chunk.lvNext();
        if (next == null) return;
        this.consumerChunk = next;
        next.soPrev(null);
    }

    // ==================== offer / add ====================

    @Override
    public boolean offer(E e) {
        Objects.requireNonNull(e);
        // getAndAdd 已占位; producerChunkFor(可能 OOM)/soElement 抛异常会留下永久空洞 →
        // 单消费者在该洞处 poll 永远返回 null (该 partition 永久卡死). 异常时尽力写 POISON 让消费者跳过.
        long pIndex = (long) P_INDEX.getAndAdd(this, 1L);
        try {
            MPSCChunk<E> chunk = this.producerChunkFor(pIndex >> CHUNK_SHIFT);
            chunk.soElement((int) (pIndex & CHUNK_MASK), e);
        } catch (Throwable t) {
            this.poisonSlot(pIndex);
            throw t;
        }
        return true;
    }

    @Override
    public boolean add(E e) {
        return this.offer(e);
    }

    private void poisonSlot(long pIndex) {
        try {
            MPSCChunk<E> chunk = this.producerChunkFor(pIndex >> CHUNK_SHIFT);
            chunk.soElement((int) (pIndex & CHUNK_MASK), MPSCChunk.POISON);
        } catch (Throwable ignore) {
            // 二次失败放弃: 该洞会让单消费者卡死, 但已尽力; 上游 OOM 本应触发重启
        }
    }

    // ==================== poll / remove ====================

    @Override
    public E poll() {
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
            return (E) e;
        }
    }

    @Override
    public E remove() {
        E e = this.poll();
        if (e == null) throw new NoSuchElementException();
        return e;
    }

    // ==================== peek / element ====================

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

    @Override
    public E element() {
        E e = this.peek();
        if (e == null) throw new NoSuchElementException();
        return e;
    }

    // ==================== size / isEmpty ====================

    @Override
    public int size() {
        long c = this.consumerIndex;
        long p = (long) P_INDEX.getVolatile(this);
        long s = p - c;
        if (s <= 0) return 0;
        return s >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) s;
    }

    @Override
    public boolean isEmpty() {
        return this.peek() == null;
    }

    // ==================== iterator / spliterator / forEach (weakly consistent) ====================

    @Override
    public Iterator<E> iterator() {
        long start = this.consumerIndex;
        long end = (long) P_INDEX.getVolatile(this);
        MPSCChunk<E> chunk = this.consumerChunk;
        long chunkStart = chunk.index << CHUNK_SHIFT;
        if (start < chunkStart) start = chunkStart;
        return new MPSCQueueIterator<>(chunk, start, end);
    }

    @Override
    public Spliterator<E> spliterator() {
        return Spliterators.spliteratorUnknownSize(this.iterator(),
                Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.CONCURRENT);
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        for (Iterator<E> it = this.iterator(); it.hasNext(); ) {
            action.accept(it.next());
        }
    }

    // ==================== contains / containsAll / addAll ====================

    @Override
    public boolean contains(Object o) {
        if (o == null) return false;
        for (Iterator<E> it = this.iterator(); it.hasNext(); ) {
            if (o.equals(it.next())) return true;
        }
        return false;
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        for (Object e : c) {
            if (!this.contains(e)) return false;
        }
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        boolean changed = false;
        for (E e : c) changed |= this.offer(e);
        return changed;
    }

    // ==================== toArray ====================

    @Override
    public Object[] toArray() {
        ArrayList<E> list = new ArrayList<>();
        this.forEach(list::add);
        return list.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        ArrayList<E> list = new ArrayList<>();
        this.forEach(list::add);
        return list.toArray(a);
    }

    @Override
    public <T> T[] toArray(IntFunction<T[]> generator) {
        ArrayList<E> list = new ArrayList<>();
        this.forEach(list::add);
        return list.toArray(generator);
    }

    // ==================== clear ====================

    /**
     * 弱一致清空: 循环 poll 直到返回 null。
     * <p>
     * <b>不可与 producer 并发使用</b>: poll 在遇到"producer 已 getAndAdd 占位但尚未 soElement 发布"的洞时返回 null,
     * clear 会就此提前退出, 残留其后元素。仅在确保无并发 offer 时调用才能保证真正清空。
     */
    @Override
    public void clear() {
        while (this.poll() != null) {
            // 排空
        }
    }

    // ==================== Not supported (数组槽位不允许中段抠洞) ====================

    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException("MPSCLinkedQueue: remove(Object) not supported");
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException("MPSCLinkedQueue: removeAll not supported");
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException("MPSCLinkedQueue: retainAll not supported");
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        throw new UnsupportedOperationException("MPSCLinkedQueue: removeIf not supported");
    }

    // ==================== toString ====================

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
