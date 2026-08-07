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

    /**
     * 业务作用：按默认容量 1024 构造有界环形队列，供不关心容量规划的调用方直接使用。
     *
     * 参数说明: 无。
     * 返回: 构造完成后队列为空，容量固定且此后不再变化。
     */
    public ConcurrentRingQueue() {
        this(DEFAULT_CAPACITY);
    }

    // 最大容量 2^30: 再大 roundUpPow2 会溢出成负数 → NegativeArraySizeException (且单数组已 ~8GB, 不现实)
    private static final int MAX_CAPACITY = 1 << 30;

    /**
     * 业务作用：按目标容量构造有界 MPMC 环形队列，并在构造期完成序列号初始化以保证安全发布。
     * 容量向上取到 2 的幂，使 offer/poll 能用位掩码代替取模。
     *
     * @param capacity 期望容量；小于 2 时按 2 处理，必须不超过 2^30
     * 返回: 构造完成后队列为空；容量超过 2^30 时抛出 IllegalArgumentException，
     *      因为再大会让容量计算溢出为负数并触发 NegativeArraySizeException。
     */
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

    /**
     * 业务作用：把请求容量向上对齐到 2 的幂，使环形下标可以用掩码计算，避免热路径取模。
     *
     * @param v 待对齐的容量
     * 返回: 不小于 v 的最小 2 的幂。
     */
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
    /**
     * 业务作用：暴露构造期固定下来的容量，供调用方判断有界队列的写入上限。
     *
     * 参数说明: 无。
     * 返回: 队列容量；该值在整个生命周期内不变。
     */
    public int capacity() {
        return this.mask + 1;
    }

    /**
     * 业务作用：以 volatile 语义读取槽位序列号，与生产者/消费者的 release 写配对，
     * 建立读取 buf 元素前的 happens-before 边界。
     *
     * @param idx 逻辑槽位下标
     * 返回: 该槽位当前序列号，用于判定槽位可写、可读还是尚未就绪。
     */
    private long seqGet(int idx) {
        return (long) SEQ_VH.getVolatile(this.seq, idx << SPARSE_SHIFT);
    }

    /**
     * 业务作用：以 release 语义发布槽位序列号，把本线程对 buf 的写入对配对的 acquire 读可见。
     * 用 release-only 代替全屏障写，省去 StoreLoad 屏障开销而不损失可见性。
     *
     * @param idx 逻辑槽位下标
     * @param val 新的序列号
     * 返回: 无返回值；写入后该槽位对另一侧开放。
     */
    private void seqLazySet(int idx, long val) {
        SEQ_VH.setRelease(this.seq, idx << SPARSE_SHIFT, val);
    }

    // ==================== Vyukov MPMC 核心操作 ====================

    /**
     * 业务作用：Vyukov MPMC 入队核心。先 CAS 抢占 tail 位置取得槽位独占权，再写元素并发布序列号，
     * 顺序不可交换：序列号一旦发布，消费者即可读取该槽位。
     *
     * @param e 待入队元素
     * 返回: 成功入队返回 true；目标槽位尚未被消费者腾出（即队列已满）返回 false。
     */
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

    /**
     * 业务作用：Vyukov MPMC 出队核心。先 CAS 抢占 head 位置取得槽位独占权，取出元素后清空引用
     * 防止对象被队列长期强引用，最后发布下一圈的序列号把槽位交还给生产者。
     *
     * 参数说明: 无。
     * 返回: 成功出队的元素；目标槽位尚未被生产者写入（即队列为空）返回 null。
     */
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

    /**
     * 业务作用：非阻塞入队，队列满时立即失败而不是阻塞或扩容，把背压决策交回调用方。
     *
     * @param e 待入队元素，不允许为 null
     * 返回: 入队成功返回 true；队列已满返回 false；元素为 null 时抛出 NullPointerException。
     */
    @Override
    public boolean offer(E e) {
        Objects.requireNonNull(e);
        return this.ringOffer(e);
    }

    /**
     * 业务作用：按 Queue#add 规约入队，失败时抛异常而非返回 false，使调用方无法忽略写入失败。
     *
     * @param e 待入队元素，不允许为 null
     * 返回: 入队成功返回 true；队列已满抛出 IllegalStateException。
     */
    @Override
    public boolean add(E e) {
        // Queue#add 规约: 失败抛 IllegalStateException; 本队列 offer 失败表示已满
        // 为保持与 CLQ 旧行为一致 (CLQ.add 永远成功), 这里 offer 失败时也抛 IllegalStateException
        if (this.offer(e)) return true;
        throw new IllegalStateException("Queue full");
    }

    /**
     * 业务作用：非阻塞出队，队列空时立即返回而不阻塞。
     *
     * 参数说明: 无。
     * 返回: 队首元素；队列为空返回 null。
     */
    @Override
    public E poll() {
        return this.ringPoll();
    }

    /**
     * 业务作用：读取队首元素但不摘除，用于并发下的窥探；结果是弱一致快照，
     * 返回后该元素可能已被其它消费者取走。
     *
     * 参数说明: 无。
     * 返回: 队首元素；队列为空或队首槽位尚未发布返回 null。
     */
    @Override
    public E peek() {
        long pos = this.pHead;
        int idx = (int) (pos & this.mask);
        long s = this.seqGet(idx);
        return (s - (pos + 1) == 0) ? (E) this.buf[idx] : null;
    }

    /**
     * 业务作用：按 Queue#remove 规约出队，空队列抛异常而非返回 null，供不接受空结果的调用方使用。
     *
     * 参数说明: 无。
     * 返回: 队首元素；队列为空抛出 NoSuchElementException。
     */
    @Override
    public E remove() {
        // Queue#remove: 弹出 head; 队列为空抛 NoSuchElementException
        E e = this.poll();
        if (e == null) throw new NoSuchElementException();
        return e;
    }

    /**
     * 业务作用：按 Queue#element 规约窥探队首，空队列抛异常而非返回 null。
     *
     * 参数说明: 无。
     * 返回: 队首元素；队列为空抛出 NoSuchElementException。
     */
    @Override
    public E element() {
        // Queue#element: peek 但队列为空抛 NoSuchElementException
        E e = this.peek();
        if (e == null) throw new NoSuchElementException();
        return e;
    }

    // ==================== Size / Empty ====================

    /**
     * 业务作用：以两个游标的相对位置判断队列是否为空，不加锁也不遍历。
     *
     * 参数说明: 无。
     * 返回: 采样瞬间为空返回 true；并发场景下只是弱一致提示，不能作为后续操作必定成功的依据。
     */
    @Override
    public boolean isEmpty() {
        return this.pHead >= this.pTail;
    }

    /**
     * 业务作用：以生产/消费游标之差估算队列长度，供监控和容量观测使用。
     *
     * 参数说明: 无。
     * 返回: 采样瞬间的元素个数，下界截断为 0；并发读两个游标不构成原子快照，仅为近似值。
     */
    @Override
    public int size() {
        return (int) Math.max(this.pTail - this.pHead, 0);
    }

    // ==================== Traversal (weakly consistent) ====================

    /**
     * 业务作用：在采样区间内线性查找元素，属于弱一致遍历，不阻塞并发生产和消费。
     *
     * @param o 待查找元素
     * 返回: 采样期间命中返回 true；o 为 null 直接返回 false，因为本队列不接受 null 元素。
     */
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

    /**
     * 业务作用：提供弱一致迭代器，基于创建瞬间的游标快照遍历，不阻塞并发操作，
     * 也不会因遍历期间的入队出队抛 ConcurrentModificationException。
     *
     * 参数说明: 无。
     * 返回: 弱一致迭代器；遍历期间被并发消费的槽位会被跳过。
     */
    @Override
    public Iterator<E> iterator() {
        return new ConcurrentRingQueueIterator<>(this.buf, this.mask, this.pHead, this.pTail);
    }

    /**
     * 业务作用：在采样区间内逐个消费元素快照，跳过并发出队后置空的槽位，避免把 null 交给回调。
     *
     * @param action 对每个非空元素执行的动作
     * 返回: 无返回值；遍历不摘除元素，也不阻塞并发生产消费。
     */
    @Override
    public void forEach(Consumer<? super E> action) {
        long h = this.pHead;
        long t = this.pTail;
        for (long i = h; i < t; i++) {
            Object val = this.buf[(int) (i & this.mask)];
            if (val != null) action.accept((E) val);
        }
    }

    /**
     * 业务作用：导出当前元素快照。经弱一致遍历收集，保证结果数组不含并发出队留下的 null 空位。
     *
     * 参数说明: 无。
     * 返回: 采样瞬间的元素数组；不代表任何时刻的原子快照。
     */
    @Override
    public Object[] toArray() {
        // 经弱一致 iterator (跳过并发 poll 置空的 null 槽), 避免返回含 null 的数组
        ArrayList<E> list = new ArrayList<>();
        this.forEach(list::add);
        return list.toArray();
    }

    /**
     * 业务作用：按调用方指定的数组类型导出元素快照，同样跳过并发出队留下的空位。
     *
     * @param a 目标类型数组；容量不足时由集合框架分配新数组
     * 返回: 装有采样瞬间元素的数组。
     */
    @Override
    public <T> T[] toArray(T[] a) {
        ArrayList<E> list = new ArrayList<>();
        this.forEach(list::add);
        return list.toArray(a);
    }

    // ==================== Batch ====================

    /**
     * 业务作用：批量入队。刻意走 add 而不是 offer：有界队列写满时必须让调用方感知失败，
     * 不能静默地只加入一部分却让调用方以为全部成功。
     *
     * @param c 待入队集合
     * 返回: 至少加入一个元素返回 true；中途队列写满会抛出 IllegalStateException，
     *      此时先前元素已经入队且不会回滚。
     */
    @Override
    public boolean addAll(Collection<? extends E> c) {
        // Queue/Collection 语义: 满时抛 IllegalStateException (经 add), 而非静默部分加入.
        // (有界队列满即无法全部加入; 调用方应感知, 不应误以为全部成功. 用 offer 的 best-effort 已废弃.)
        boolean changed = false;
        for (E e : c) changed |= this.add(e);
        return changed;
    }

    /**
     * 业务作用：逐个执行弱一致查找，判断给定集合是否都在采样区间内。
     *
     * @param c 待判定集合
     * 返回: 全部命中返回 true；任一未命中返回 false。并发下多次查找不构成统一快照。
     */
    @Override
    public boolean containsAll(Collection<?> c) {
        for (Object e : c) {
            if (!this.contains(e)) return false;
        }
        return true;
    }

    // ==================== Not supported ====================

    /**
     * 业务作用：明确拒绝按值删除。环形队列的槽位与序列号严格对应，抽走中间元素会破坏
     * 生产者与消费者之间的序列号约定，因此不提供该语义而不是给出一个近似实现。
     *
     * @param o 忽略
     * 返回: 不返回，恒抛 UnsupportedOperationException。
     */
    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException("bounded MPMC ring: remove not supported");
    }

    /**
     * 业务作用：明确拒绝批量按值删除，理由同 remove(Object)。
     *
     * @param c 忽略
     * 返回: 不返回，恒抛 UnsupportedOperationException。
     */
    @Override
    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException("bounded MPMC ring: removeAll not supported");
    }

    /**
     * 业务作用：明确拒绝保留式删除，理由同 remove(Object)。
     *
     * @param c 忽略
     * 返回: 不返回，恒抛 UnsupportedOperationException。
     */
    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException("bounded MPMC ring: retainAll not supported");
    }

    /**
     * 业务作用：明确拒绝条件删除，理由同 remove(Object)。
     *
     * @param filter 忽略
     * 返回: 不返回，恒抛 UnsupportedOperationException。
     */
    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        throw new UnsupportedOperationException("bounded MPMC ring: removeIf not supported");
    }

    // ==================== Clear / toString ====================

    /**
     * 业务作用：反复出队直到队列为空，用于释放队列对元素的强引用。
     *
     * 参数说明: 无。
     * 返回: 无返回值；并发生产仍在进行时不保证返回后队列为空。
     */
    @Override
    public void clear() {
        while (this.poll() != null) {
        }
    }

    /**
     * 业务作用：输出弱一致快照的可读形式，跳过并发出队留下的空位，避免日志里出现误导性的 null。
     *
     * 参数说明: 无。
     * 返回: 形如 [a, b, c] 的字符串；仅供诊断，不保证与任何时刻的队列状态一致。
     */
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

    /**
     * 业务作用：基于弱一致迭代器提供可分割遍历器，声明 CONCURRENT 与 NONNULL 特征，
     * 使 Stream 使用方知晓遍历期间允许并发修改且不会遇到 null 元素。
     *
     * 参数说明: 无。
     * 返回: 未知大小的有序、非空、并发型 Spliterator。
     */
    @Override
    public Spliterator<E> spliterator() {
        return Spliterators.spliteratorUnknownSize(this.iterator(),
                Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.CONCURRENT);
    }
}
