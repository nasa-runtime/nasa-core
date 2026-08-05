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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;

import static com.nasa.runtime.core.concurrent.MPMCChunk.CHUNK_MASK;
import static com.nasa.runtime.core.concurrent.MPMCChunk.CHUNK_SHIFT;
import static com.nasa.runtime.core.concurrent.MPMCChunk.FREE;

// ============================================================================
// Disruptor 风格 padding 继承链 — 隔离 consumer 端与 producer 端字段伪共享.
// 前置 padding 复用共享的 QPad0 (与 MPSC 队列共用); Pad1/Pad2 因夹在本队列专属
// Consumer/Producer 之间 (布局链中部) 无法共用, 保留在本文件。
// ============================================================================
@SuppressWarnings("all")
abstract class MPMCQConsumer<E> extends QPad0 {

    // 懒 hint, 仅越过连续 TOMBSTONE 前缀
    volatile long consumerIndex;
    // 消费端 chunk hint, CAS 前移并切断 prev 使前缀可回收
    volatile MPMCChunk<E> consumerChunk;

    static final VarHandle C_INDEX;
    static final VarHandle C_CHUNK;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            C_INDEX = l.findVarHandle(MPMCQConsumer.class, "consumerIndex", long.class);
            C_CHUNK = l.findVarHandle(MPMCQConsumer.class, "consumerChunk", MPMCChunk.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}

@SuppressWarnings("all")
abstract class MPMCQPad1<E> extends MPMCQConsumer<E> {
    protected long p10, p11, p12, p13, p14, p15, p16;
}

@SuppressWarnings("all")
abstract class MPMCQProducer<E> extends MPMCQPad1<E> {

    // getAndAdd 推进 (wait-free)
    volatile long producerIndex;
    // 生产端 chunk hint, best-effort 前移
    volatile MPMCChunk<E> producerChunk;

    static final VarHandle P_INDEX;
    static final VarHandle P_CHUNK;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            P_INDEX = l.findVarHandle(MPMCQProducer.class, "producerIndex", long.class);
            P_CHUNK = l.findVarHandle(MPMCQProducer.class, "producerChunk", MPMCChunk.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}

@SuppressWarnings("all")
abstract class MPMCQPad2<E> extends MPMCQProducer<E> {
    protected long p20, p21, p22, p23, p24, p25, p26;
}

/**
 * Nasa
 * 无界、高并发、稳态零 GC 的 MPMC 队列, 基于全局 XADD 序号 + chunk 链 ({@link MPMCChunk}), chunk 池化复用.
 *
 * <h2>核心机制</h2>
 * <ul>
 *   <li>producer {@code getAndAdd(producerIndex)} 拿全局唯一序号 → 定位/追加 chunk → soElement(release)</li>
 *   <li>consumer <b>分散认领</b>: 从 consumerIndex hint 扫描, 跳 TOMBSTONE, 洞处按序等, CAS 槽位(元素→TOMBSTONE)
 *       认领首个可用元素; 各 consumer CAS 不同槽位 (配合 SLOT_SHIFT 槽独占 line) 无重试风暴, 对标 CLQ casItem</li>
 *   <li>无 Segment/SEAL: 全局序号唯一确定 (chunk,offset), 无 "stale tail"</li>
 * </ul>
 *
 * <h2>chunk 池化复用的安全 (hazard pointer + 2 级缓存)</h2>
 * 无锁链式节点复用的核心风险: 某线程持有 chunk 引用并 deref/CAS 时, 该 chunk 被回收复用 → ABA / 错链 / 环。
 * <ul>
 *   <li><b>消费者 HP</b>: 认领槽位前对 chunk 发布 hazard pointer + 校验 index, 回收者扫到 HP 则不回收 →
 *       认领 CAS 期间 chunk 不被复用 (防 ABA, 元素常是 ObjectPool 复用对象)。</li>
 *   <li><b>生产者 HP</b>: 仅在跨 chunk 导航 / casNext 追加时保护 (常态 hint==target 直接写, target ≥ consumerChunk
 *       不可回收, 免 HP); 保证 casNext 链接的前驱不会在链接期间被复用 (否则前驱复用后链上错 index → 环)。</li>
 *   <li><b>回收 Dekker</b>: retire 先 soFree(index=FREE)+fullFence 再扫 HP; 与 protect 的 publish+fence+校验 互补,
 *       保证 "回收者见 HP" 或 "持有者见 FREE 重试" 至少一方成立。</li>
 *   <li><b>2 级缓存</b>: 退役块先入 1 槽 cache, 被下一个退役块挤出时才进 pool — 进池块已 FREE 一个退役周期,
 *       任何晚到校验都见 FREE 重试, 降低 retire 时 HP 命中(被跳过)概率, 提高复用率。acquire 只从 pool 取。</li>
 * </ul>
 * 段 (~CHUNK_SIZE×2^SLOT_SHIFT 引用) 复用消除大块 churn; 稳态零 GC。
 *
 * <h2>语义注意</h2>
 * <ul>
 *   <li><b>poll() 返回 null 不代表队列空</b>: head 槽可能是 producer 已 getAndAdd 占位但尚未发布的"洞",
 *       或 producer 崩溃/OOM 留下的永久空洞 (有界自旋 {@code HOLE_SPIN_LIMIT} 后返回 null 防熔毁)。调用方应容忍/重试。</li>
 *   <li>offer 在 getAndAdd 后若 producerChunkFor/soElement 抛异常, 尽力把槽写 POISON 让 consumer 跳过 (丢该元素)。</li>
 *   <li>消费者宜为固定线程池: HP 槽<b>每实例独立</b>分配 (可配 {@code -Dnasa.mpmc.hazard-slots}, 默认 512), 仅本实例
 *       被 >HP_SLOTS 个不同线程触碰才会 {@code poolingDisabled} (安全降级为非池化, 可经 {@link #isPoolingDisabled()} 观测)。</li>
 * </ul>
 *
 * <h2>不支持</h2>
 * 按值删除 (remove(Object)/removeAll/retainAll/removeIf)。
 */
@SuppressWarnings("all")
public class MPMCLinkedQueue<E> extends MPMCQPad2<E> implements Queue<E>, Serializable {

    @Serial
    private static final long serialVersionUID = 6711777503826365828L;

    // ===== chunk 复用池 + 1 槽缓存 =====
    private static final int POOL_CAP = 64;
    private final ConcurrentRingQueue<MPMCChunk<E>> chunkPool = new ConcurrentRingQueue<>(POOL_CAP);
    private volatile MPMCChunk<E> cache;
    private static final VarHandle CACHE;

    // 洞自旋上界: 正常 producer 占位后 <1μs 内 soElement, 永不触界; 仅当 producer 崩溃/OOM/长暂停
    // 导致槽位永久为空时触界 → 返回 null 交还控制权, 防止消费者无限自旋熔毁 CPU.
    private static final int HOLE_SPIN_LIMIT = 1 << 16;

    // ===== hazard pointer (每实例独立, 避免全局静态槽被跨实例的线程 churn 耗尽) =====
    // 可配置: 默认 512. 每个 queue 独立分配槽, 一个队列被 >HP_SLOTS 个线程触碰只会关闭它自己的池化, 不影响其它实例.
    private static final int HP_SLOTS = Math.max(1, Integer.getInteger("nasa.mpmc.hazard-slots", 512));
    private static final VarHandle HAZ = MethodHandles.arrayElementVarHandle(Object[].class);
    private final Object[] hazards = new Object[HP_SLOTS];
    // 每实例的线程→槽分配 (per-instance ThreadLocal: 线程触碰本队列时分配, queue GC 后 TL 弱键自动清, 无泄漏)
    private final AtomicInteger hpNext = new AtomicInteger();
    private final ThreadLocal<Integer> hpSlotTL = ThreadLocal.withInitial(this.hpNext::getAndIncrement);
    // 本实例任一线程拿不到 HP 槽 (>=HP_SLOTS) → 关闭复用 (块永不重用 = 退化非池化, 安全). 可读, 便于压测确认.
    private volatile boolean poolingDisabled;

    public boolean isPoolingDisabled() {
        return this.poolingDisabled;
    }

    static {
        try {
            CACHE = MethodHandles.lookup().findVarHandle(MPMCLinkedQueue.class, "cache", MPMCChunk.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public MPMCLinkedQueue() {
        MPMCChunk<E> first = new MPMCChunk<>(0L, null);
        this.consumerChunk = first;
        this.producerChunk = first;
    }

    public MPMCLinkedQueue(Collection<? extends E> c) {
        this();
        this.addAll(c);
    }

    // ==================== hazard pointer + 池 ====================

    // 取本线程在本实例的 HP 槽 (per-instance ThreadLocal 懒分配).
    // 槽号 >= HP_SLOTS (本队列被太多不同线程触碰) → 关池化降级为非池化 (安全) 并返回 -1 (后续 protect/clear 成 no-op).
    private int hpSlot() {
        int s = this.hpSlotTL.get();
        if (s >= HP_SLOTS) {
            this.poolingDisabled = true;
            return -1;
        }
        return s;
    }

    // 发布 hazard pointer: 声明"本线程正在用 chunk", 回收者扫到则不回收它.
    // setVolatile 发布后必须 fullFence (StoreLoad) 再读校验 —— 与 retireChunk 的 soFree+fullFence+扫描 构成 Dekker:
    // 保证"回收者看见本 HP"或"本线程随后校验读到 FREE 而重试"至少一方成立, 不会出现 chunk 被回收而本线程仍在用.
    private void protect(int slot, MPMCChunk<E> chunk) {
        if (slot < 0) return;
        HAZ.setVolatile(this.hazards, slot, chunk);
        VarHandle.fullFence();
    }

    // 消费者热路径优化: 若已在保护同一 chunk (HP 跨 poll 持续保留), 免去 StoreLoad 栅栏.
    // 安全性: HP 连续持有期间该 chunk 不会被回收复用 → 仍有效, 无需重新 publish+fence.
    // 仅在消费者跨 chunk (约每 CHUNK_SIZE 次 poll 一次) 才真正发栅栏. 代价: 空闲消费者钉住 1 个 chunk.
    private void protectIfNeeded(int slot, MPMCChunk<E> chunk) {
        if (slot < 0) return;
        if (HAZ.getVolatile(this.hazards, slot) == chunk) return;
        HAZ.setVolatile(this.hazards, slot, chunk);
        VarHandle.fullFence();
    }

    // 撤销 hazard pointer: 本线程不再用任何 chunk, 释放保护 (该 chunk 此后可被回收复用). setRelease 即可, 无需栅栏.
    private void clearHp(int slot) {
        if (slot < 0) return;
        HAZ.setRelease(this.hazards, slot, null);
    }

    // exceptSlot: 退役调用方自己的 HP 槽. 它的 HP 还指向刚消费完的 old 是 stale 的 (调用方退役后不再 deref old,
    // 见 retireChunk 调用点), 排除自身槽才能让"边界跨越时退役自己刚读完的 chunk"也能入池, 否则池化几乎失效(实测仅 2%).
    private boolean hazarded(MPMCChunk<E> chunk, int exceptSlot) {
        for (int i = 0; i < HP_SLOTS; i++) {
            if (i == exceptSlot) continue;
            if (HAZ.getVolatile(this.hazards, i) == chunk) return true;
        }
        return false;
    }

    // 取一个新 chunk: 优先从复用池拿 (reuse 重置 index/prev, buffer 已在退役时清空), 池空才 new (产生 GC).
    private MPMCChunk<E> acquireChunk(long index, MPMCChunk<E> prev) {
        MPMCChunk<E> c = this.chunkPool.poll();
        if (c == null) return new MPMCChunk<>(index, prev);
        c.reuse(index, prev);
        return c;
    }

    // 退役 old (consumerChunk 已推进到 newHead): 无 HP 引用则清空, 经 2 级 cache 延迟一拍后入池.
    // retireSlot = 退役调用方(consumer)自己的 HP 槽, 扫描时排除 (其 HP=old 是 stale, 退役后不再 deref).
    private void retireChunk(MPMCChunk<E> old, MPMCChunk<E> newHead, int retireSlot) {
        // 1. 若 producerChunk hint 还滞后指向 old, 拨到 newHead, 避免 producer 从已退役块起步
        P_CHUNK.compareAndSet(this, old, newHead);
        // 2. unpublish: 置 index=FREE (release). 必须在扫 HP 前, 与 protect 的 publish+fence+校验 构成 Dekker
        old.soFree();
        VarHandle.fullFence();
        // 3. 仍被某线程 HP 引用 (排除退役者自身的 stale HP) 或全局关池 → 不复用, 留给 GC
        if (this.poolingDisabled || this.hazarded(old, retireSlot)) return;
        // 4. 无人引用: 清空 next/prev/槽位, 可安全复用
        old.clearForReuse();
        // 5. 2 级缓存: old 入 cache, 把上一拍的退役块挤进 pool —— 进池块已 FREE 一整个退役周期,
        //    任何晚到的 HP 校验都会读到它的 FREE 而重试, 故池中块一定无人 deref, acquire 复用安全
        MPMCChunk<E> displaced = (MPMCChunk<E>) CACHE.getAndSet(this, old);
        if (displaced != null) this.chunkPool.offer(displaced);
    }

    // ==================== 导航 ====================

    // 定位 producer 序号所属 chunk. 常态 hint==target 直接返回 (免 HP, target≥consumerChunk 不可回收).
    // 需导航时 HP 保护途经/前驱块, 防其在 casNext/deref 期间被复用 (→ 错链/环).
    private MPMCChunk<E> producerChunkFor(long chunkId) {
        // 常态快路径: hint 恰是目标 chunk, 直接返回. 目标块 index==chunkId≥consumerChunk, 不可回收, 写入无需 HP.
        MPMCChunk<E> hint = (MPMCChunk<E>) P_CHUNK.getAcquire(this);
        if (hint.index == chunkId) return hint;
        // 慢路径: 需要从 hint 导航 (前向追加 / 沿 prev 回溯). 途经块可能被并发回收复用, 故用 HP 保护.
        int slot = this.hpSlot();
        try {
            restart:
            while (true) {
                MPMCChunk<E> chunk = (MPMCChunk<E>) P_CHUNK.getAcquire(this);
                this.protect(slot, chunk);
                long cur = chunk.lvIndex();
                // hint 指向已退役块 → 重读 (retireChunk 已把 P_CHUNK 拨向前)
                if (cur == FREE) continue;
                while (cur != chunkId) {
                    if (cur < chunkId) {
                        // 前向: 走到 next
                        MPMCChunk<E> next = chunk.lvNext();
                        // 双读校验: 读 next 期间 chunk 若被回收复用 (index 变), 丢弃重启 (chunkId 全局单调不重复, 防 ABA)
                        if (chunk.lvIndex() != cur) continue restart;
                        if (next == null) {
                            // 尾部: 追加新块. chunk 已 HP 保护且校验 cur → casNext 期间不会被复用 (否则会链到错 index 成环)
                            MPMCChunk<E> nc = this.acquireChunk(cur + 1, chunk);
                            if (chunk.casNext(null, nc)) {
                                next = nc;
                            } else {
                                // 竞争失败 (他人已链): 退还 nc 到池 (未发布过, 安全), 改用既有后继
                                nc.soFree();
                                this.chunkPool.offer(nc);
                                next = chunk.lvNext();
                                if (next == null) continue restart;
                            }
                        }
                        // best-effort 把 hint 前移, 让后续 producer 起点更近; 换到 next 并保护它
                        P_CHUNK.compareAndSet(this, chunk, next);
                        chunk = next;
                        this.protect(slot, chunk);
                    } else {
                        // 回溯: hint 领先目标, 沿 prev 往回. 双读校验防回收; prev 被切(null)说明踩到已退役块, 重启
                        MPMCChunk<E> prev = chunk.lvPrev();
                        if (chunk.lvIndex() != cur || prev == null) continue restart;
                        chunk = prev;
                        this.protect(slot, chunk);
                    }
                    cur = chunk.lvIndex();
                    if (cur == FREE) continue restart;
                }
                // 到达目标块; 它 ≥ consumerChunk 不可回收, 返回后写入无需 HP, finally 统一清 HP
                return chunk;
            }
        } finally {
            this.clearHp(slot);
        }
    }

    // 推进消费 hint 至 target + consumerChunk 前移 + 切 prev + 退役越过的 chunk.
    // slot = 调用方(consumer)自己的 HP 槽, 透传给 retireChunk 以排除自身 stale HP, 保证池化有效.
    private void advanceHintTo(long target, int slot) {
        // 1. 把全局消费 hint CAS 推到 target (只增不减); 输了就重读, 直到 hint >= target
        long h = (long) C_INDEX.getVolatile(this);
        while (h < target) {
            if (C_INDEX.weakCompareAndSetRelease(this, h, target)) {
                h = target;
                break;
            }
            h = (long) C_INDEX.getVolatile(this);
        }
        // 2. 把 consumerChunk 逐块前移到 hint 所在 chunk; 每跨过一块就切断其 prev 并退役它
        long hChunkId = h >> CHUNK_SHIFT;
        MPMCChunk<E> cc = (MPMCChunk<E>) C_CHUNK.getAcquire(this);
        while (cc.lvIndex() < hChunkId) {
            MPMCChunk<E> next = cc.lvNext();
            // next 还没建好 (producer 尚未追加), 等下次推进
            if (next == null) return;
            // 由 CAS 赢家独占推进一格: 切 next.prev (前缀失去后向引用可整体 GC) + 退役被跨过的 cc
            if (C_CHUNK.compareAndSet(this, cc, next)) {
                next.soPrev(null);
                this.retireChunk(cc, next, slot);
                cc = next;
            } else {
                cc = (MPMCChunk<E>) C_CHUNK.getAcquire(this);
            }
        }
    }

    // ==================== offer / add ====================

    @Override
    public boolean offer(E e) {
        Objects.requireNonNull(e);
        // getAndAdd 已占位; 之后 producerChunkFor(可能 OOM)/soElement 若抛异常, 该槽永不发布 →
        // 消费者会在此洞处无限自旋(熔毁). 故异常时尽力把槽写成 POISON 让消费者跳过.
        long pIndex = (long) P_INDEX.getAndAdd(this, 1L);
        try {
            MPMCChunk<E> chunk = this.producerChunkFor(pIndex >> CHUNK_SHIFT);
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

    // 尽力把已占位但发布失败的槽写成 POISON (consumer 跳过). 二次失败(如持续 OOM)则放弃,
    // 由 consumer 的有界洞自旋兜底防止 CPU 熔毁.
    @SuppressWarnings("unchecked")
    private void poisonSlot(long pIndex) {
        try {
            MPMCChunk<E> chunk = this.producerChunkFor(pIndex >> CHUNK_SHIFT);
            chunk.soElement((int) (pIndex & CHUNK_MASK), (E) MPMCChunk.POISON);
        } catch (Throwable ignore) {
            // 放弃: 留空洞, consumer 有界自旋返回 null, 不熔毁
        }
    }

    // ==================== poll / remove() ====================

    @Override
    public E poll() {
        // HP 跨 poll 持续保留 (protectIfNeeded), 仅跨 chunk 才发栅栏; 空时释放 HP. 返回元素时保留, 下次 poll 复用.
        int slot = this.hpSlot();
        long holeSpin = 0;
        outer:
        while (true) {
            long c = (long) C_INDEX.getVolatile(this);
            long start = c;
            MPMCChunk<E> chunk = (MPMCChunk<E>) C_CHUNK.getAcquire(this);
            this.protectIfNeeded(slot, chunk);
            while (true) {
                long chunkId = c >> CHUNK_SHIFT;
                long ci = chunk.lvIndex();
                if (ci == chunkId) {
                    // chunk HP 保护且 index 校验通过 → 认领 CAS 期间不会被复用
                    int offset = (int) (c & CHUNK_MASK);
                    Object v = chunk.lvElementRaw(offset);
                    // TOMBSTONE=已消费, POISON=发布失败 → 均跳过
                    if (v == MPMCChunk.TOMBSTONE || v == MPMCChunk.POISON) {
                        c++;
                        continue;
                    }
                    if (v == null) {
                        if (c >= (long) P_INDEX.getVolatile(this)) {
                            if (c != start) this.advanceHintTo(c, slot);
                            // 空: 释放 HP (避免空闲钉住)
                            this.clearHp(slot);
                            return null;
                        }
                        if (c != start) {
                            this.advanceHintTo(c, slot);
                            start = c;
                        }
                        // 洞: 等 producer 发布; 有界自旋兜底 producer 崩溃/OOM 留下的永久空洞, 防熔毁
                        if (++holeSpin > HOLE_SPIN_LIMIT) {
                            this.clearHp(slot);
                            return null;
                        }
                        Thread.onSpinWait();
                        continue;
                    }
                    // 可用元素: CAS 槽位 元素→TOMBSTONE 认领它 (分散认领: 各 consumer 抢不同槽位, 无中心计数器重试风暴)
                    if (chunk.casElement(offset, v, MPMCChunk.TOMBSTONE)) {
                        // 认领成功: 推进 hint 越过本槽; 保留 HP, 下次 poll 同 chunk 免栅栏
                        this.advanceHintTo(c + 1, slot);
                        return (E) v;
                    }
                    // 认领失败 (被其它 consumer 抢走) → 看下一槽
                    c++;
                    continue;
                }
                if (ci != FREE && ci < chunkId) {
                    MPMCChunk<E> next = chunk.lvNext();
                    // chunk 被复用 → 重启
                    if (chunk.lvIndex() != ci) continue outer;
                    if (next == null) {
                        // c 所在 chunk 未建 ⇒ c 在生产前沿之外; 或 producer 崩溃于建链前 → 有界自旋兜底
                        if (c >= (long) P_INDEX.getVolatile(this) || ++holeSpin > HOLE_SPIN_LIMIT) {
                            if (c != start) this.advanceHintTo(c, slot);
                            this.clearHp(slot);
                            return null;
                        }
                        Thread.onSpinWait();
                        continue;
                    }
                    chunk = next;
                    this.protectIfNeeded(slot, chunk);
                    continue;
                }
                // ci == FREE 或 ci > chunkId → 重读 hint 重启
                continue outer;
            }
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
        int slot = this.hpSlot();
        // 非阻塞 API: 对 producer-hole 同样有界自旋, 超界返回 null (不可无界等待)
        long holeSpin = 0;
        outer:
        while (true) {
            long c = (long) C_INDEX.getVolatile(this);
            long start = c;
            MPMCChunk<E> chunk = (MPMCChunk<E>) C_CHUNK.getAcquire(this);
            this.protectIfNeeded(slot, chunk);
            while (true) {
                long chunkId = c >> CHUNK_SHIFT;
                long ci = chunk.lvIndex();
                if (ci == chunkId) {
                    Object v = chunk.lvElementRaw((int) (c & CHUNK_MASK));
                    if (v == MPMCChunk.TOMBSTONE || v == MPMCChunk.POISON) {
                        c++;
                        continue;
                    }
                    if (v == null) {
                        if (c >= (long) P_INDEX.getVolatile(this) || ++holeSpin > HOLE_SPIN_LIMIT) {
                            if (c != start) this.advanceHintTo(c, slot);
                            this.clearHp(slot);
                            return null;
                        }
                        if (c != start) {
                            this.advanceHintTo(c, slot);
                            start = c;
                        }
                        Thread.onSpinWait();
                        continue;
                    }
                    if (c != start) this.advanceHintTo(c, slot);
                    // 非消费 API: 不长期 pin chunk (否则妨碍池化复用)
                    this.clearHp(slot);
                    return (E) v;
                }
                if (ci != FREE && ci < chunkId) {
                    MPMCChunk<E> next = chunk.lvNext();
                    if (chunk.lvIndex() != ci) continue outer;
                    if (next == null) {
                        if (c >= (long) P_INDEX.getVolatile(this) || ++holeSpin > HOLE_SPIN_LIMIT) {
                            if (c != start) this.advanceHintTo(c, slot);
                            this.clearHp(slot);
                            return null;
                        }
                        Thread.onSpinWait();
                        continue;
                    }
                    chunk = next;
                    this.protectIfNeeded(slot, chunk);
                    continue;
                }
                continue outer;
            }
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
        // 近似上界 (弱一致); 精确判空用 isEmpty()
        long c = (long) C_INDEX.getVolatile(this);
        long p = (long) P_INDEX.getVolatile(this);
        long s = p - c;
        if (s <= 0) return 0;
        return s >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) s;
    }

    @Override
    public boolean isEmpty() {
        int slot = this.hpSlot();
        outer:
        while (true) {
            long c = (long) C_INDEX.getVolatile(this);
            long start = c;
            MPMCChunk<E> chunk = (MPMCChunk<E>) C_CHUNK.getAcquire(this);
            this.protectIfNeeded(slot, chunk);
            while (true) {
                if (c >= (long) P_INDEX.getVolatile(this)) {
                    if (c != start) this.advanceHintTo(c, slot);
                    this.clearHp(slot);
                    return true;
                }
                long chunkId = c >> CHUNK_SHIFT;
                long ci = chunk.lvIndex();
                if (ci == chunkId) {
                    Object v = chunk.lvElementRaw((int) (c & CHUNK_MASK));
                    if (v == MPMCChunk.TOMBSTONE || v == MPMCChunk.POISON) {
                        c++;
                        continue;
                    }
                    if (c != start) this.advanceHintTo(c, slot);
                    // 非消费 API: 不长期 pin chunk
                    this.clearHp(slot);
                    return false;
                }
                if (ci != FREE && ci < chunkId) {
                    MPMCChunk<E> next = chunk.lvNext();
                    if (chunk.lvIndex() != ci) continue outer;
                    if (next == null) {
                        if (c != start) this.advanceHintTo(c, slot);
                        boolean empty = c >= (long) P_INDEX.getVolatile(this);
                        // 非消费 API: 无论空否都清 HP, 不 pin chunk
                        this.clearHp(slot);
                        return empty;
                    }
                    chunk = next;
                    this.protectIfNeeded(slot, chunk);
                    continue;
                }
                continue outer;
            }
        }
    }

    // ==================== iterator / spliterator / forEach (weakly consistent) ====================

    @Override
    public Iterator<E> iterator() {
        long start = (long) C_INDEX.getVolatile(this);
        long end = (long) P_INDEX.getVolatile(this);
        MPMCChunk<E> chunk = (MPMCChunk<E>) C_CHUNK.getAcquire(this);
        long chunkStart = chunk.lvIndex() << CHUNK_SHIFT;
        if (start < chunkStart) start = chunkStart;
        return new MPMCQueueIterator<>(chunk, start, end);
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
     * <b>不可与 producer 并发使用</b>: poll 在遇到"producer 已 getAndAdd 占位但尚未 soElement 发布"的洞 (或永久空洞,
     * 见 poll 文档) 时返回 null, clear 会就此提前退出, 残留其后已/将发布的元素。仅在确保无并发 offer 时
     * (如先用外部闸门挡住所有 producer 后) 调用才能保证真正清空。
     */
    @Override
    public void clear() {
        while (this.poll() != null) {
            // 排空
        }
    }

    // ==================== Not supported ====================

    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException("MPMCLinkedQueue: remove(Object) not supported");
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException("MPMCLinkedQueue: removeAll not supported");
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException("MPMCLinkedQueue: retainAll not supported");
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        throw new UnsupportedOperationException("MPMCLinkedQueue: removeIf not supported");
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
