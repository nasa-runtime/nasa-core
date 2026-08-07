package io.github.nasaruntime.core.concurrent;

import io.github.nasaruntime.core.base.RingList;
import io.github.nasaruntime.core.base.When;

import java.io.Serial;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Nasa
 * 并发环形集合
 * <p>
 * 基于 ReentrantLock 保证写操作原子性。
 * 读操作（get、contains、iterator）基于 volatile 数组引用实现弱一致性无锁读。
 * 支持 unique 模式（Set 语义，add 时自动去重）。
 */
@SuppressWarnings({"unused", "unchecked"})
public class ConcurrentRingList<E> implements RingList<E> {

    @Serial
    private static final long serialVersionUID = 1036544233388332412L;

    private volatile Object[] ring;
    private int capacity;
    private volatile int writePos;
    private volatile int count;
    private boolean unique;
    private final ReentrantLock lock = new ReentrantLock();

    // 操作事件 listeners
    private List<BiConsumer<E, E>> onReplaceListeners;
    private List<Consumer<E>> onRemoveListeners;
    private List<Consumer<E>> onClearListeners;
    // 条件事件
    private final When.WhenChain<E> whenChain = When.WhenChain.of();

    /**
     * 业务作用：构造实例。字段取默认值。
     *
     * 参数说明: 无。
     * 返回: 构造完成后可直接使用的实例。
     */
    public ConcurrentRingList() {
        this(128);
    }

    /**
     * 业务作用：按指定容量构造实例，容量估准可以避免后续扩容带来的重哈希或拷贝开销。
     *
     * @param capacity 初始容量
     * 返回: 构造完成后为空的实例。
     */
    public ConcurrentRingList(int capacity) {
        this(capacity, false);
    }

    /**
     * 业务作用：按指定容量构造实例，容量估准可以避免后续扩容带来的重哈希或拷贝开销。
     *
     * @param capacity 初始容量
     * @param unique 见上述说明
     * 返回: 构造完成后为空的实例。
     */
    public ConcurrentRingList(int capacity, boolean unique) {
        this.unique = unique;
        init(capacity);
    }

    /**
     * 业务作用：按给定参数构造 ConcurrentRingList 实例。
     *
     * @param col 见上述说明
     * 返回: 构造完成后可直接使用的实例。
     */
    public ConcurrentRingList(Collection<E> col) {
        this(col.size());
        this.addAll(col);
    }

    /**
     * 业务作用：开启或关闭去重语义，开启后重复元素不会被再次加入。
     *
     * @param capacity 容量
     * 返回: 当前实例，供链式配置。
     */
    public static <E> ConcurrentRingList<E> unique(int capacity) {
        return new ConcurrentRingList<>(capacity, true);
    }

    /**
     * 业务作用：按容量与配置完成内部结构初始化，是各构造器的统一收口。
     *
     * @param capacity 容量
     * 返回: 无返回值。
     */
    private void init(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.ring = new Object[this.capacity];
        this.writePos = 0;
        this.count = 0;
    }

    // ==================== Ring ====================

    /**
     * 业务作用：报告环形容量上限，超出后最旧的元素会被覆盖。
     *
     * 参数说明: 无。
     * 返回: 容量上限。
     */
    @Override
    public int ringCapacity() { return capacity; }

    /**
     * 业务作用：重设环形容量并清空现有内容，供运行期调整保留窗口大小。
     *
     * @param col 见上述说明
     * 返回: 无返回值；原有元素全部丢弃。
     */
    @Override
    public void ringReset(Collection<E> col) {
        lock.lock();
        try {
            init(col.size());
            addAll(col);
        } finally {
            lock.unlock();
        }
    }

    // ==================== 容量与大小 ====================

    /**
     * 业务作用：报告当前元素个数，供容量观测与遍历前的预分配。
     *
     * 参数说明: 无。
     * 返回: 元素个数；并发下为弱一致结果，仅反映采样瞬间的状态。
     */
    @Override
    public int size() { return count; }

    /**
     * 业务作用：判断容器中是否存在给定元素。
     *
     * @param o 元素
     * 返回: 命中返回 true；并发下为弱一致结果，仅反映采样瞬间的状态。
     */
    @Override
    public boolean contains(Object o) {
        if (o == null) return false;
        Object[] r = ring; // volatile read
        for (int i = 0; i < r.length; i++) {
            if (o.equals(r[i])) return true;
        }
        return false;
    }

    // ==================== 写操作（全部加锁） ====================

    /**
     * 业务作用：在当前游标位置插入元素，游标随之后移。
     *
     * @param e 待插入元素
     * 返回: 无返回值。
     */
    @Override
    public boolean add(E e) {
        lock.lock();
        try {
            if (unique && e != null && contains(e)) return false;
            int i = writePos;
            writePos = (writePos + 1) % capacity;
            E old = (E) ring[i];
            ring[i] = e;
            if (old == null && e != null) count++;
            else if (old != null && e == null) count--;
            fireReplace(old, e);
            if (e != null) whenChain.fire(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：替换指定下标处的元素。
     *
     * @param index 元素下标
     * @param e 新元素
     * 返回: 被替换掉的旧元素；下标越界时抛出 IndexOutOfBoundsException。
     */
    @Override
    public void set(int index, E e) {
        lock.lock();
        try {
            if (unique && e != null) {
                for (int j = 0; j < capacity; j++) {
                    if (j != index && Objects.equals(e, ring[j])) {
                        if (ring[j] != null) count--;
                        ring[j] = null;
                    }
                }
            }
            E old = (E) ring[index];
            ring[index] = e;
            if (old == null && e != null) count++;
            else if (old != null && e == null) count--;
            fireReplace(old, e);
            if (e != null) whenChain.fire(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：按下标移除元素。
     *
     * @param index 下标
     * 返回: 被移除的元素；下标越界时抛出 IndexOutOfBoundsException。
     */
    @Override
    public E removeAt(int index) {
        lock.lock();
        try {
            E e = (E) ring[index];
            if (e != null) {
                ring[index] = null;
                count--;
                fireRemove(e);
            }
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：按值移除首个匹配元素。注意与按下标移除的重载区分：入参为 Object 时走本方法。
     *
     * @param o 待移除元素
     * 返回: 移除成功返回 true；未找到返回 false。
     */
    @Override
    public boolean remove(Object o) {
        if (o == null) return false;
        lock.lock();
        try {
            boolean removed = false;
            for (int i = 0; i < capacity; i++) {
                if (o.equals(ring[i])) {
                    ring[i] = null;
                    count--;
                    removed = true;
                }
            }
            if (removed) fireRemove((E) o);
            return removed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：按条件批量移除元素。
     *
     * @param filter 命中即移除的条件
     * 返回: 移除过至少一个元素返回 true；并发修改下不保证条件对最终状态成立。
     */
    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        lock.lock();
        try {
            boolean removed = false;
            for (int i = 0; i < capacity; i++) {
                E e = (E) ring[i];
                if (e != null && filter.test(e)) {
                    ring[i] = null;
                    count--;
                    fireRemove(e);
                    removed = true;
                }
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：移除全部元素，解除容器对它们的强引用后交给 GC。
     *
     * 参数说明: 无。
     * 返回: 无返回值；并发写入仍在进行时不保证返回后容器为空。
     */
    @Override
    public void clear() {
        lock.lock();
        try {
            for (int i = 0, len = ring.length; i < len; i++) {
                E e = (E) ring[i];
                if (e != null) fireClear(e);
                ring[i] = null;
            }
            writePos = 0;
            count = 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：把逻辑上已删除的空洞压实，回收被墓碑占用的槽位。
     *
     * 参数说明: 无。
     * 返回: 无返回值；压实后元素下标会变化。
     */
    @Override
    public void compress() {
        lock.lock();
        try {
            Object[] compacted = new Object[capacity];
            int j = 0;
            int cursor = writePos;
            for (int i = 0; i < capacity; i++) {
                Object e = ring[cursor];
                cursor = (cursor + 1) % capacity;
                if (e != null) compacted[j++] = e;
            }
            this.ring = compacted;
            this.writePos = j % capacity;
        } finally {
            lock.unlock();
        }
    }

    // ==================== 读操作（无锁，弱一致性） ====================

    /**
     * 业务作用：按下标读取元素。
     *
     * @param index 元素下标
     * 返回: 该位置的元素；下标越界时抛出 IndexOutOfBoundsException。
     */
    @Override
    public E get(int index) {
        return (E) ring[index]; // volatile array ref
    }

    /**
     * 业务作用：报告下一次写入的位置，供诊断环形覆盖进度。
     *
     * 参数说明: 无。
     * 返回: 下一次写入的下标。
     */
    @Override
    public int writeOffset() {
        return writePos;
    }

    // ==================== 迭代器（快照，弱一致性） ====================

    /**
     * 业务作用：提供弱一致迭代器，遍历期间允许并发修改，不抛 ConcurrentModificationException。
     *
     * 参数说明: 无。
     * 返回: 弱一致迭代器；遍历中被并发移除的元素可能出现也可能不出现。
     */
    @Override
    public Iterator<E> iterator() {
        return new SnapshotIter(writePos, 1);
    }

    /**
     * 业务作用：提供从新到旧的反向迭代器，基于创建瞬间的快照，不阻塞并发写入。
     *
     * 参数说明: 无。
     * 返回: 反向迭代器。
     */
    @Override
    public Iterator<E> reverseIterator() {
        return new SnapshotIter((writePos - 1 + capacity) % capacity, -1);
    }

    class SnapshotIter implements Iterator<E> {
        private final Object[] snapshot;
        private int cursor;
        private final int direction;
        private int remaining;
        private E nextElement; // 缓存下一个元素，消除 hasNext/next 之间的竞态

        /**
         * 业务作用：按给定参数构造 SnapshotIter 实例。
         *
         * @param startPos 见上述说明
         * @param direction 见上述说明
         * 返回: 构造完成后可直接使用的实例。
         */
        SnapshotIter(int startPos, int direction) {
            this.snapshot = ring; // volatile read
            this.cursor = startPos;
            this.direction = direction;
            this.remaining = capacity;
            advance();
        }

        /**
         * 业务作用：推进内部游标到下一个有效槽位，跳过空洞。
         *
         * 参数说明: 无。
         * 返回: 无返回值；结果暂存供 hasNext 与 next 复用。
         */
        private void advance() {
            while (remaining > 0) {
                E e = (E) snapshot[cursor];
                cursor = (cursor + direction + capacity) % capacity;
                remaining--;
                if (e != null) {
                    nextElement = e;
                    return;
                }
            }
            nextElement = null;
        }

        /**
         * 业务作用：判断迭代是否还有下一个元素；必要时先向前探测一格。
         *
         * 参数说明: 无。
         * 返回: 还有元素返回 true。
         */
        @Override
        public boolean hasNext() {
            return nextElement != null;
        }

        /**
         * 业务作用：返回下一个元素并前移游标。
         *
         * 参数说明: 无。
         * 返回: 下一个元素；已到末尾时抛出 NoSuchElementException。
         */
        @Override
        public E next() {
            E e = nextElement;
            if (e == null) throw new NoSuchElementException();
            advance();
            return e;
        }
    }

    // ==================== 条件事件（When<E>） ====================

    /**
     * 业务作用：暴露内部 When 事件链，使条件回调可以挂载到本容器的状态变化上。
     *
     * 参数说明: 无。
     * 返回: 本实例独有的事件链。
     */
    @Override
    public When.WhenChain<E> chain() { return whenChain; }

    // ==================== 操作事件 ====================

    /**
     * 业务作用：注册元素被替换时的回调，供调用方在覆盖发生时释放旧值持有的资源。
     *
     * @param listener 回调
     * 返回: 当前实例，供链式配置。
     */
    @Override
    public void onReplace(BiConsumer<E, E> listener) {
        lock.lock();
        try {
            if (onReplaceListeners == null) onReplaceListeners = new ArrayList<>(2);
            onReplaceListeners.add(listener);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：注册元素被移除时的回调，供调用方释放被移除元素持有的资源。
     *
     * @param listener 回调
     * 返回: 当前实例，供链式配置。
     */
    @Override
    public void onRemove(Consumer<E> listener) {
        lock.lock();
        try {
            if (onRemoveListeners == null) onRemoveListeners = new ArrayList<>(2);
            onRemoveListeners.add(listener);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：注册容器被清空时的回调。
     *
     * @param listener 回调
     * 返回: 当前实例，供链式配置。
     */
    @Override
    public void onClear(Consumer<E> listener) {
        lock.lock();
        try {
            if (onClearListeners == null) onClearListeners = new ArrayList<>(2);
            onClearListeners.add(listener);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：清除全部已注册回调，避免容器复用时沿用上一代的回调。
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    @Override
    public void clearListeners() {
        lock.lock();
        try {
            onReplaceListeners = null;
            onRemoveListeners = null;
            onClearListeners = null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：触发替换回调。回调抛出的异常不得影响容器自身状态，由实现负责隔离。
     *
     * @param old 见上述说明
     * @param now 见上述说明
     * 返回: 无返回值。
     */
    private void fireReplace(E old, E now) {
        if (onReplaceListeners != null) {
            for (BiConsumer<E, E> l : onReplaceListeners) l.accept(old, now);
        }
    }

    /**
     * 业务作用：触发移除回调。
     *
     * @param e 元素
     * 返回: 无返回值。
     */
    private void fireRemove(E e) {
        if (onRemoveListeners != null) {
            for (Consumer<E> l : onRemoveListeners) l.accept(e);
        }
    }

    /**
     * 业务作用：触发清空回调。
     *
     * @param e 元素
     * 返回: 无返回值。
     */
    private void fireClear(E e) {
        if (onClearListeners != null) {
            for (Consumer<E> l : onClearListeners) l.accept(e);
        }
    }
}
