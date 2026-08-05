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
import java.util.function.Predicate;

// ============================================================================
// Disruptor 风格 cache line padding 继承链 (内嵌 volatile long + VarHandle)
// ----------------------------------------------------------------------------
// 关键差异 (相对持有 AtomicLong 引用):
//   AtomicLong 方案 padding 隔的是引用 (4-8B 指针),
//   而 AtomicLong 对象本身 (16B header + 8B value) 由同一线程构造,
//   TLAB 大概率分到相邻地址 → 两个 AtomicLong 对象很可能落在同一 64B cache line,
//   value 字段照样发生伪共享, padding 形同虚设.
//
// 内嵌字段方案 padding 直接包住 pHead/pTail 的 long value:
//   [对象头 12B][LhsPadding 56B] = 68B          ← 占满 cache line 1 (含 header)
//   [pHead 8B][MidPadding 56B] = 64B           ← pHead 独占 cache line 2
//   [pTail 8B][RhsPadding 56B] = 64B           ← pTail 独占 cache line 3
//   [子类只读字段 buf/seq/mask...]               ← final, 只读, 不参与伪共享
//
// HotSpot 不会跨父类合并/重排字段, 5 层继承链的 padding 是稳定生效的
// (单父类 7 个 long 字段可能被 JIT 视为 "未使用" 而打乱布局, 不可靠)
//
// VarHandle 提供 CAS 与原子操作, 替代原来 AtomicLong.compareAndSet
// 注: volatile long 字段的普通读/写就是 volatile read/write, 不需要 VarHandle
// ============================================================================

// 前置 padding: 隔离对象头和 pHead, 防止上方字段污染
abstract class LhsPadding {
    protected long p01, p02, p03, p04, p05, p06, p07;
}

// 持有 pHead 的层 (消费者推进游标, 绝对单调递增)
abstract class HeadField extends LhsPadding {

    protected volatile long pHead = 0L;

    // VarHandle 用于 pHead 的 CAS 操作
    protected static final VarHandle PHEAD;

    static {
        try {
            PHEAD = MethodHandles.lookup()
                    .findVarHandle(HeadField.class, "pHead", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}

// 中置 padding: 彻底隔离 pHead 和 pTail
abstract class MidPadding extends HeadField {
    protected long p11, p12, p13, p14, p15, p16, p17;
}

// 持有 pTail 的层 (生产者推进游标, 绝对单调递增)
abstract class TailField extends MidPadding {

    protected volatile long pTail = 0L;

    // VarHandle 用于 pTail 的 CAS 操作
    protected static final VarHandle PTAIL;

    static {
        try {
            PTAIL = MethodHandles.lookup()
                    .findVarHandle(TailField.class, "pTail", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}

// 后置 padding: 保护 pTail 不被下方子类字段污染
abstract class RhsPadding extends TailField {
    protected long p21, p22, p23, p24, p25, p26, p27;
}


// ============================================================================
// ConcurrentRingQueue
// ----------------------------------------------------------------------------
// 有界 MPMC (Multi-Producer Multi-Consumer) 无锁队列, 基于 Vyukov 环形缓冲区.
//
// 固定容量: 构造时按 roundUpPow2(capacity) 一次性分配 buf/seq, 容量永不变化.
// offer/poll 全程 lock-free, 满返回 false / 空返回 null. 稳态零包装节点分配, 零 GC.
// 设计目标: 作为 ObjectPool 的内部池, 替代 ConcurrentLinkedQueue.
//
// === 设计说明 ===
// 1. 不再 extends ConcurrentLinkedQueue, 改 implements Queue<E>
//    斩断 CLQ 链表语义包袱; 调用方因使用具体类引用, 无须改动
// 2. Disruptor 风格 padding 继承链消除 pHead/pTail 伪共享
//    pHead/pTail 改为内嵌 volatile long + VarHandle, 彻底消除 AtomicLong 对象间共享
// 3. buf/seq/mask 直接保存在本类的 final 字段中，去掉每次操作对
//    volatile ring 的读和一层指针跳转; final 保证构造期 seq 初始化的安全发布
// 4. ringOffer/ringPoll 中 seq 用 setRelease (release-only) 代替全屏障写,
//    release-acquire 配对仍保证 happens-before, 节省 StoreLoad 屏障
//
// === 固定容量设计 ===
// 逐级扩容要求扩容前的操作经过 ReentrantLock；占用率长期低于上限时也会承担锁开销。
// 采用固定容量后:
//   - 需要有界: 直接按目标容量构造 (本类)
//   - 需要无界/可增长: 用 MPMCLinkedQueue (链入新 chunk, 无全局锁、无拷贝)
// ============================================================================
@SuppressWarnings("all")
public class ConcurrentRingQueue<E> extends RhsPadding implements Queue<E>, Serializable {

    @Serial
    private static final long serialVersionUID = -3912847561029384756L;

    private static final int DEFAULT_CAPACITY = 1024;

    // seq 槽位 sparseness: 1<<SPARSE_SHIFT longs 占一个逻辑槽位
    // shift>0 可隔离相邻 seq 槽位的 false sharing, 但 seq 内存放大 2^shift 倍.
    // 基准结论 (cap=1024, 4P4C/8P8C): shift 0/1/2/3 吞吐在噪声内, sh3 在 8P8C 反而最差;
    // 故取 0 — 吞吐中性偏好 + seq 内存最小 (NodePool 此项 64MB→8MB). 索引 idx<<0 被 JIT 抹掉.
    static final int SPARSE_SHIFT = 0;
    static final VarHandle SEQ_VH = MethodHandles.arrayElementVarHandle(long[].class);

    // ==================== 共享状态 (全部 final, 容量固定) ====================
    // pHead / pTail 已在 padding 继承链中定义为内嵌 volatile long, 无需在此重复声明
    // PHEAD / PTAIL VarHandle 也在父类静态初始化

    private final Object[] buf;
    // 物理长度 = capacity << SPARSE_SHIFT, 逻辑槽位 i 落在 seq[i << SPARSE_SHIFT]
    private final long[] seq;
    private final int mask;

    public ConcurrentRingQueue() {
        this(DEFAULT_CAPACITY);
    }

    // 最大容量 2^30: 再大 roundUpPow2 会溢出成负数 → NegativeArraySizeException (且单数组已 ~8GB, 不现实)
    private static final int MAX_CAPACITY = 1 << 30;

    public ConcurrentRingQueue(int capacity) {
        if (capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity too large (max " + MAX_CAPACITY + "): " + capacity);
        }
        int cap = roundUpPow2(Math.max(capacity, 2));
        this.mask = cap - 1;
        this.buf = new Object[cap];
        this.seq = new long[cap << SPARSE_SHIFT];
        for (int i = 0; i < cap; i++) {
            // 构造期单线程, 普通写即可; final 字段冻结 (constructor 结束) 承担安全发布屏障
            this.seq[i << SPARSE_SHIFT] = i;
        }
    }

    private static int roundUpPow2(int v) {
        v--;
        v |= v >> 1;
        v |= v >> 2;
        v |= v >> 4;
        v |= v >> 8;
        v |= v >> 16;
        return v + 1;
    }

    // 容量 (power of 2, 固定不变)
    public int capacity() {
        return this.mask + 1;
    }

    private long seqGet(int idx) {
        return (long) SEQ_VH.getVolatile(this.seq, idx << SPARSE_SHIFT);
    }

    private void seqLazySet(int idx, long val) {
        SEQ_VH.setRelease(this.seq, idx << SPARSE_SHIFT, val);
    }

    // ==================== Vyukov MPMC 核心操作 ====================

    private boolean ringOffer(E e) {
        while (true) {
            // 读 volatile long 字段 = volatile read, 无需 .get()
            long pos = this.pTail;
            int idx = (int) (pos & this.mask);
            long s = this.seqGet(idx);
            long diff = s - pos;
            if (diff == 0) {
                // VarHandle.compareAndSet 提供 full-barrier CAS
                if (PTAIL.compareAndSet(this, pos, pos + 1)) {
                    this.buf[idx] = e;
                    // lazySet (release-only, store-store 屏障) 代替 set 的全屏障 (volatile write)
                    // 论证: 此时本线程已通过 CAS 独占 idx, buf 写入对其他线程的可见性
                    //      由 setRelease 提供的 release 与消费者 seqGet 的 acquire 配对完成 happens-before
                    //      节省的是 StoreLoad 屏障开销 (x86 上 mfence/lock 前缀)
                    this.seqLazySet(idx, pos + 1);
                    return true;
                }
            } else if (diff < 0) {
                // 目标槽未被消费者腾出 ⇒ 队列满
                return false;
            }
        }
    }

    private E ringPoll() {
        while (true) {
            // 读 volatile long 字段 = volatile read
            long pos = this.pHead;
            int idx = (int) (pos & this.mask);
            long s = this.seqGet(idx);
            long diff = s - (pos + 1);
            if (diff == 0) {
                if (PHEAD.compareAndSet(this, pos, pos + 1)) {
                    E e = (E) this.buf[idx];
                    this.buf[idx] = null;
                    // 同 ringOffer: 用 setRelease 释放槽位 (标记下一圈可写), release-acquire 仍保证可见性
                    this.seqLazySet(idx, pos + this.mask + 1);
                    return e;
                }
            } else if (diff < 0) {
                // 目标槽未被生产者写入 ⇒ 队列空
                return null;
            }
        }
    }

    // ==================== Queue 接口: offer / poll / peek / add / remove / element ====================

    @Override
    public boolean offer(E e) {
        Objects.requireNonNull(e);
        return this.ringOffer(e);
    }

    @Override
    public boolean add(E e) {
        // Queue#add 规约: 失败抛 IllegalStateException; 本队列 offer 失败表示已满
        // 为保持与 CLQ 旧行为一致 (CLQ.add 永远成功), 这里 offer 失败时也抛 IllegalStateException
        if (this.offer(e)) return true;
        throw new IllegalStateException("Queue full");
    }

    @Override
    public E poll() {
        return this.ringPoll();
    }

    @Override
    public E peek() {
        long pos = this.pHead;
        int idx = (int) (pos & this.mask);
        long s = this.seqGet(idx);
        return (s - (pos + 1) == 0) ? (E) this.buf[idx] : null;
    }

    @Override
    public E remove() {
        // Queue#remove: 弹出 head; 队列为空抛 NoSuchElementException
        E e = this.poll();
        if (e == null) throw new NoSuchElementException();
        return e;
    }

    @Override
    public E element() {
        // Queue#element: peek 但队列为空抛 NoSuchElementException
        E e = this.peek();
        if (e == null) throw new NoSuchElementException();
        return e;
    }

    // ==================== Size / Empty ====================

    @Override
    public boolean isEmpty() {
        return this.pHead >= this.pTail;
    }

    @Override
    public int size() {
        return (int) Math.max(this.pTail - this.pHead, 0);
    }

    // ==================== Traversal (weakly consistent) ====================

    @Override
    public boolean contains(Object o) {
        if (o == null) return false;
        long h = this.pHead;
        long t = this.pTail;
        for (long i = h; i < t; i++) {
            Object val = this.buf[(int) (i & this.mask)];
            if (o.equals(val)) return true;
        }
        return false;
    }

    @Override
    public Iterator<E> iterator() {
        return new ConcurrentRingQueueIterator<>(this.buf, this.mask, this.pHead, this.pTail);
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        long h = this.pHead;
        long t = this.pTail;
        for (long i = h; i < t; i++) {
            Object val = this.buf[(int) (i & this.mask)];
            if (val != null) action.accept((E) val);
        }
    }

    @Override
    public Object[] toArray() {
        // 经弱一致 iterator (跳过并发 poll 置空的 null 槽), 避免返回含 null 的数组
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

    // ==================== Batch ====================

    @Override
    public boolean addAll(Collection<? extends E> c) {
        // Queue/Collection 语义: 满时抛 IllegalStateException (经 add), 而非静默部分加入.
        // (有界队列满即无法全部加入; 调用方应感知, 不应误以为全部成功. 用 offer 的 best-effort 已废弃.)
        boolean changed = false;
        for (E e : c) changed |= this.add(e);
        return changed;
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        for (Object e : c) {
            if (!this.contains(e)) return false;
        }
        return true;
    }

    // ==================== Not supported ====================

    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException("bounded MPMC ring: remove not supported");
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException("bounded MPMC ring: removeAll not supported");
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException("bounded MPMC ring: retainAll not supported");
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        throw new UnsupportedOperationException("bounded MPMC ring: removeIf not supported");
    }

    // ==================== Clear / toString ====================

    @Override
    public void clear() {
        while (this.poll() != null) {
        }
    }

    @Override
    public String toString() {
        // 经弱一致 iterator 跳过 null 槽, 不打印并发 poll 后的空位
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Iterator<E> it = this.iterator(); it.hasNext(); ) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(it.next());
        }
        return sb.append(']').toString();
    }

    @Override
    public Spliterator<E> spliterator() {
        return Spliterators.spliteratorUnknownSize(this.iterator(),
                Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.CONCURRENT);
    }
}
