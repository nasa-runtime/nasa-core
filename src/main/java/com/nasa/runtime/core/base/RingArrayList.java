package com.nasa.runtime.core.base;

import java.io.Serial;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Nasa
 * 环形集合（单线程实现）
 * <p>
 * 基于固定大小数组，写满后环形覆盖最旧元素。
 * 支持 unique 模式（Set 语义，add 时自动去重）。
 */
@SuppressWarnings({"unused", "unchecked"})
public class RingArrayList<E> implements RingList<E> {

    @Serial
    private static final long serialVersionUID = -5263258292068336029L;

    Object[] ring;
    int capacity;
    int writePos;
    int count;
    private boolean unique;

    // 操作事件 listeners
    private List<BiConsumer<E, E>> onReplaceListeners;
    private List<Consumer<E>> onRemoveListeners;
    private List<Consumer<E>> onClearListeners;
    // 条件事件
    private final When.WhenChain<E> whenChain = When.WhenChain.of();

    public RingArrayList() {
        this(128);
    }

    public RingArrayList(int capacity) {
        this(capacity, false);
    }

    public RingArrayList(int capacity, boolean unique) {
        this.unique = unique;
        init(capacity);
    }

    public RingArrayList(Collection<? extends E> col) {
        this(col.size());
        this.addAll(col);
    }

    /**
     * 创建 Set 语义的环形集合（add 时自动去重）
     */
    public static <E> RingArrayList<E> unique(int capacity) {
        return new RingArrayList<>(capacity, true);
    }

    private void init(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.ring = new Object[this.capacity];
        this.writePos = 0;
        this.count = 0;
    }

    // ==================== Ring ====================

    @Override
    public int ringCapacity() { return capacity; }

    @Override
    public void ringReset(Collection<E> col) {
        init(col.size());
        addAll(col);
    }

    // ==================== 容量与大小（O(1)） ====================

    @Override
    public int size() { return count; }

    @Override
    public boolean contains(Object o) {
        if (o == null) return false;
        for (int i = 0; i < capacity; i++) {
            if (o.equals(ring[i])) return true;
        }
        return false;
    }

    // ==================== 写操作 ====================

    @Override
    public boolean add(E e) {
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
    }

    @Override
    public void set(int index, E e) {
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
    }

    @Override
    public E removeAt(int index) {
        E e = (E) ring[index];
        if (e != null) {
            ring[index] = null;
            count--;
            fireRemove(e);
        }
        return e;
    }

    @Override
    public boolean remove(Object o) {
        if (o == null) return false;
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
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
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
    }

    @Override
    public void clear() {
        for (int i = 0, len = ring.length; i < len; i++) {
            E e = (E) ring[i];
            if (e != null) fireClear(e);
            ring[i] = null;
        }
        writePos = 0;
        count = 0;
    }

    @Override
    public void compress() {
        Object[] compacted = new Object[capacity];
        int j = 0;
        // 按正序（oldest → newest）收集非空元素
        int cursor = writePos;
        for (int i = 0; i < capacity; i++) {
            Object e = ring[cursor];
            cursor = (cursor + 1) % capacity;
            if (e != null) compacted[j++] = e;
        }
        this.ring = compacted;
        this.writePos = j % capacity;
        // count 不变
    }

    // ==================== 读操作 ====================

    @Override
    public E get(int index) {
        return (E) ring[index];
    }

    @Override
    public int writeOffset() {
        return writePos;
    }

    // ==================== 迭代器（快照游标，不修改 writePos） ====================

    @Override
    public Iterator<E> iterator() {
        return new RingIter(writePos, 1);
    }

    @Override
    public Iterator<E> reverseIterator() {
        // 倒序从 writePos - 1 开始（最新元素），步长 -1
        return new RingIter((writePos - 1 + capacity) % capacity, -1);
    }

    /**
     * 环形迭代器：从 startPos 开始，步进 direction（+1 正序，-1 倒序），跳过 null。
     */
    class RingIter implements Iterator<E> {
        private int cursor;
        private final int direction;
        private int remaining;

        RingIter(int startPos, int direction) {
            this.cursor = startPos;
            this.direction = direction;
            this.remaining = capacity;
        }

        @Override
        public boolean hasNext() {
            while (remaining > 0) {
                if (ring[cursor] != null) return true;
                cursor = (cursor + direction + capacity) % capacity;
                remaining--;
            }
            return false;
        }

        @Override
        public E next() {
            if (!hasNext()) throw new NoSuchElementException();
            E e = (E) ring[cursor];
            cursor = (cursor + direction + capacity) % capacity;
            remaining--;
            return e;
        }
    }

    // ==================== 条件事件（When<E>） ====================

    @Override
    public When.WhenChain<E> chain() { return whenChain; }

    // ==================== 操作事件 ====================

    @Override
    public void onReplace(BiConsumer<E, E> listener) {
        if (onReplaceListeners == null) onReplaceListeners = new ArrayList<>(2);
        onReplaceListeners.add(listener);
    }

    @Override
    public void onRemove(Consumer<E> listener) {
        if (onRemoveListeners == null) onRemoveListeners = new ArrayList<>(2);
        onRemoveListeners.add(listener);
    }

    @Override
    public void onClear(Consumer<E> listener) {
        if (onClearListeners == null) onClearListeners = new ArrayList<>(2);
        onClearListeners.add(listener);
    }

    @Override
    public void clearListeners() {
        onReplaceListeners = null;
        onRemoveListeners = null;
        onClearListeners = null;
    }

    void fireReplace(E old, E now) {
        if (onReplaceListeners != null) {
            for (BiConsumer<E, E> l : onReplaceListeners) l.accept(old, now);
        }
    }

    void fireRemove(E e) {
        if (onRemoveListeners != null) {
            for (Consumer<E> l : onRemoveListeners) l.accept(e);
        }
    }

    void fireClear(E e) {
        if (onClearListeners != null) {
            for (Consumer<E> listener : onClearListeners) listener.accept(e);
        }
    }
}
