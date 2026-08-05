package com.nasa.runtime.core.concurrent;

import com.nasa.runtime.core.base.RingList;
import com.nasa.runtime.core.base.When;

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

    public ConcurrentRingList() {
        this(128);
    }

    public ConcurrentRingList(int capacity) {
        this(capacity, false);
    }

    public ConcurrentRingList(int capacity, boolean unique) {
        this.unique = unique;
        init(capacity);
    }

    public ConcurrentRingList(Collection<E> col) {
        this(col.size());
        this.addAll(col);
    }

    public static <E> ConcurrentRingList<E> unique(int capacity) {
        return new ConcurrentRingList<>(capacity, true);
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
        lock.lock();
        try {
            init(col.size());
            addAll(col);
        } finally {
            lock.unlock();
        }
    }

    // ==================== 容量与大小 ====================

    @Override
    public int size() { return count; }

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

    @Override
    public E get(int index) {
        return (E) ring[index]; // volatile array ref
    }

    @Override
    public int writeOffset() {
        return writePos;
    }

    // ==================== 迭代器（快照，弱一致性） ====================

    @Override
    public Iterator<E> iterator() {
        return new SnapshotIter(writePos, 1);
    }

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

        SnapshotIter(int startPos, int direction) {
            this.snapshot = ring; // volatile read
            this.cursor = startPos;
            this.direction = direction;
            this.remaining = capacity;
            advance();
        }

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

        @Override
        public boolean hasNext() {
            return nextElement != null;
        }

        @Override
        public E next() {
            E e = nextElement;
            if (e == null) throw new NoSuchElementException();
            advance();
            return e;
        }
    }

    // ==================== 条件事件（When<E>） ====================

    @Override
    public When.WhenChain<E> chain() { return whenChain; }

    // ==================== 操作事件 ====================

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

    private void fireReplace(E old, E now) {
        if (onReplaceListeners != null) {
            for (BiConsumer<E, E> l : onReplaceListeners) l.accept(old, now);
        }
    }

    private void fireRemove(E e) {
        if (onRemoveListeners != null) {
            for (Consumer<E> l : onRemoveListeners) l.accept(e);
        }
    }

    private void fireClear(E e) {
        if (onClearListeners != null) {
            for (Consumer<E> l : onClearListeners) l.accept(e);
        }
    }
}
