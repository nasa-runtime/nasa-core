package com.nasa.runtime.core.concurrent;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Nasa 高性能并发有序 List
 * 基于 {@link ConcurrentLinkedMap} 实现，内部通过自增 Long 作为 key 维护插入顺序。
 * 尾部追加 O(1)、头部移除 O(1)、按索引访问 O(n)
 * 提供 {@link #pollFirst()} / {@link #pollLast()} 原子安全方法，避免 TOCTOU 竞态
 */
@SuppressWarnings("all")
public class ConcurrentLinkedList<E> implements List<E>, Serializable {

    @Serial
    private static final long serialVersionUID = -8782324930043438471L;

    private final ConcurrentLinkedMap<Long, E> map;
    private final AtomicLong sequence = new AtomicLong(0);

    public ConcurrentLinkedList() {
        this.map = new ConcurrentLinkedMap<>();
    }

    public ConcurrentLinkedList(int capacity) {
        this.map = new ConcurrentLinkedMap<>(capacity);
    }

    public ConcurrentLinkedList(Collection<E> c) {
        this(c.size());
        addAll(c);
    }

    /**
     * 在持有写锁的前提下，按索引定位节点。O(n)
     */
    private ConcurrentLinkedMap.Node<Long, E> nodeAt(int index) {
        int size = map.size();
        if (index < 0 || index >= size)
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        ConcurrentLinkedMap.Node<Long, E> node = map.head;
        for (int i = 0; i < index; i++) node = node.next;
        return node;
    }

    // ==================== 核心写操作 ====================

    @Override
    public boolean add(E e) {
        map.put(sequence.getAndIncrement(), e);
        return true;
    }

    @Override
    public void add(int index, E element) {
        map.w.lock();
        try {
            int size = map.size();
            if (index < 0 || index > size)
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);

            Long newKey = sequence.getAndIncrement();
            ConcurrentLinkedMap.Node<Long, E> newNode = new ConcurrentLinkedMap.Node<>(newKey, element);
            map.map.put(newKey, newNode);

            if (index == size) {
                // 尾部追加
                map.linkLast(newNode);
            } else {
                // 在目标位置前插入
                ConcurrentLinkedMap.Node<Long, E> target = map.head;
                for (int i = 0; i < index; i++) target = target.next;
                map.linkBefore(newNode, target);
            }
        } finally {
            map.w.unlock();
        }
    }

    @Override
    public E remove(int index) {
        map.w.lock();
        try {
            ConcurrentLinkedMap.Node<Long, E> node = nodeAt(index);
            // 重入写锁，从 HashMap + 链表中同时移除
            return map.remove(node.key);
        } finally {
            map.w.unlock();
        }
    }

    @Override
    public boolean remove(Object o) {
        map.w.lock();
        try {
            for (ConcurrentLinkedMap.Node<Long, E> e = map.head; e != null; e = e.next) {
                if (Objects.equals(o, e.value)) {
                    map.remove(e.key);
                    return true;
                }
            }
            return false;
        } finally {
            map.w.unlock();
        }
    }

    @Override
    public E set(int index, E element) {
        map.w.lock();
        try {
            ConcurrentLinkedMap.Node<Long, E> node = nodeAt(index);
            E old = node.value;
            node.value = element;
            return old;
        } finally {
            map.w.unlock();
        }
    }

    @Override
    public void clear() {
        map.w.lock();
        try {
            map.clear();
            sequence.set(0);
        } finally {
            map.w.unlock();
        }
    }

    /**
     * 清空列表并返回旧的头节点。
     * 调用方可通过 node.next() 遍历旧链，常用于批量回收对象池。
     */
    public ConcurrentLinkedMap.Node<Long, E> clearRHead() {
        map.w.lock();
        try {
            sequence.set(0);
            return map.clearRHead();
        } finally {
            map.w.unlock();
        }
    }

    // ==================== 头尾操作 O(1) ====================

    /**
     * 原子地移除并返回第一个元素，列表为空时返回 null（不抛异常）。
     * 解决 isEmpty() + removeFirst() 的 TOCTOU 竞态问题。
     */
    public E pollFirst() {
        map.w.lock();
        try {
            ConcurrentLinkedMap.Node<Long, E> h = map.head;
            if (h == null) return null;
            return map.remove(h.key);
        } finally {
            map.w.unlock();
        }
    }

    /**
     * 原子地移除并返回最后一个元素，列表为空时返回 null。
     */
    public E pollLast() {
        map.w.lock();
        try {
            ConcurrentLinkedMap.Node<Long, E> t = map.tail;
            if (t == null) return null;
            return map.remove(t.key);
        } finally {
            map.w.unlock();
        }
    }

    public E peekFirst() {
        ConcurrentLinkedMap.Node<Long, E> h = map.head; // volatile read
        return h == null ? null : h.value;
    }

    public E peekLast() {
        ConcurrentLinkedMap.Node<Long, E> t = map.tail; // volatile read
        return t == null ? null : t.value;
    }

    @Override
    public E getFirst() {
        ConcurrentLinkedMap.Node<Long, E> h = map.head;
        if (h == null) throw new NoSuchElementException();
        return h.value;
    }

    @Override
    public E getLast() {
        ConcurrentLinkedMap.Node<Long, E> t = map.tail;
        if (t == null) throw new NoSuchElementException();
        return t.value;
    }

    @Override
    public E removeFirst() {
        map.w.lock();
        try {
            ConcurrentLinkedMap.Node<Long, E> h = map.head;
            if (h == null) throw new NoSuchElementException();
            return map.remove(h.key);
        } finally {
            map.w.unlock();
        }
    }

    @Override
    public E removeLast() {
        map.w.lock();
        try {
            ConcurrentLinkedMap.Node<Long, E> t = map.tail;
            if (t == null) throw new NoSuchElementException();
            return map.remove(t.key);
        } finally {
            map.w.unlock();
        }
    }

    // ==================== 核心读操作 ====================

    @Override
    public E get(int index) {
        map.w.lock();
        try {
            return nodeAt(index).value;
        } finally {
            map.w.unlock();
        }
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return map.containsValue(o);
    }

    // ==================== 批量操作 ====================

    @Override
    public boolean addAll(Collection<? extends E> c) {
        for (E e : c) add(e);
        return !c.isEmpty();
    }

    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
        Objects.requireNonNull(c);
        if (c.isEmpty()) return false;

        map.w.lock();
        try {
            int size = map.size();
            if (index < 0 || index > size)
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);

            if (index == size) {
                // 全部追加到末尾
                for (E e : c) {
                    Long key = sequence.getAndIncrement();
                    ConcurrentLinkedMap.Node<Long, E> newNode = new ConcurrentLinkedMap.Node<>(key, e);
                    map.map.put(key, newNode);
                    map.linkLast(newNode);
                }
            } else {
                // 定位目标节点，在其之前逐个插入
                ConcurrentLinkedMap.Node<Long, E> target = map.head;
                for (int i = 0; i < index; i++) target = target.next;

                for (E e : c) {
                    Long key = sequence.getAndIncrement();
                    ConcurrentLinkedMap.Node<Long, E> newNode = new ConcurrentLinkedMap.Node<>(key, e);
                    map.map.put(key, newNode);
                    map.linkBefore(newNode, target);
                }
            }
            return true;
        } finally {
            map.w.unlock();
        }
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        boolean modified = false;
        map.w.lock();
        try {
            ConcurrentLinkedMap.Node<Long, E> e = map.head;
            while (e != null) {
                ConcurrentLinkedMap.Node<Long, E> next = e.next;
                if (c.contains(e.value)) {
                    map.remove(e.key);
                    modified = true;
                }
                e = next;
            }
        } finally {
            map.w.unlock();
        }
        return modified;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        Objects.requireNonNull(c);
        boolean modified = false;
        map.w.lock();
        try {
            ConcurrentLinkedMap.Node<Long, E> e = map.head;
            while (e != null) {
                ConcurrentLinkedMap.Node<Long, E> next = e.next;
                if (!c.contains(e.value)) {
                    map.remove(e.key);
                    modified = true;
                }
                e = next;
            }
        } finally {
            map.w.unlock();
        }
        return modified;
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        for (Object o : c) {
            if (!contains(o)) return false;
        }
        return true;
    }

    // ==================== 索引查找 ====================

    @Override
    public int indexOf(Object o) {
        int index = 0;
        for (ConcurrentLinkedMap.Node<Long, E> e = map.head; e != null; e = e.next) {
            if (Objects.equals(o, e.value)) return index;
            index++;
        }
        return -1;
    }

    @Override
    public int lastIndexOf(Object o) {
        int index = -1;
        int current = 0;
        for (ConcurrentLinkedMap.Node<Long, E> e = map.head; e != null; e = e.next) {
            if (Objects.equals(o, e.value)) index = current;
            current++;
        }
        return index;
    }

    // ==================== 迭代器 ====================

    @Override
    public Iterator<E> iterator() {
        return new Iterator<>() {
            private final Iterator<E> it = map.values().iterator();

            @Override 
        public boolean hasNext() { return it.hasNext(); }
            @Override 
        public E next() { return it.next(); }
            @Override 
        public void remove() { it.remove(); }
        };
    }

    @Override
    public ListIterator<E> listIterator() {
        return listIterator(0);
    }

    @Override
    public ListIterator<E> listIterator(int index) {
        return new ConcurrentListIterator(index);
    }

    class ConcurrentListIterator implements ListIterator<E> {
        private int cursor;
        private int lastRet = -1;

        ConcurrentListIterator(int index) {
            int size = map.size();
            if (index < 0 || index > size) throw new IndexOutOfBoundsException();
            this.cursor = index;
        }

        @Override 
        public boolean hasNext() { return cursor < size(); }
        
        @Override 
        public boolean hasPrevious() { return cursor > 0; }
        
        @Override 
        public int nextIndex() { return cursor; }
        
        @Override 
        public int previousIndex() { return cursor - 1; }

        @Override
        public E next() {
            if (!hasNext()) throw new NoSuchElementException();
            lastRet = cursor;
            return get(cursor++);
        }

        @Override
        public E previous() {
            if (!hasPrevious()) throw new NoSuchElementException();
            lastRet = --cursor;
            return get(cursor);
        }

        @Override
        public void remove() {
            if (lastRet < 0) throw new IllegalStateException();
            ConcurrentLinkedList.this.remove(lastRet);
            cursor = lastRet;
            lastRet = -1;
        }

        @Override
        public void set(E e) {
            if (lastRet < 0) throw new IllegalStateException();
            ConcurrentLinkedList.this.set(lastRet, e);
        }

        @Override
        public void add(E e) {
            ConcurrentLinkedList.this.add(cursor++, e);
            lastRet = -1;
        }
    }

    // ==================== 视图 ====================

    @Override
    public Object[] toArray() {
        return map.values().toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return map.values().toArray(a);
    }

    @Override
    public List<E> subList(int fromIndex, int toIndex) {
        map.w.lock();
        try {
            int size = map.size();
            if (fromIndex < 0 || toIndex > size || fromIndex > toIndex)
                throw new IndexOutOfBoundsException();

            List<E> sub = new ArrayList<>(toIndex - fromIndex);
            ConcurrentLinkedMap.Node<Long, E> e = map.head;
            for (int i = 0; i < toIndex && e != null; i++, e = e.next) {
                if (i >= fromIndex) sub.add(e.value);
            }
            return new ConcurrentLinkedList<>(sub);
        } finally {
            map.w.unlock();
        }
    }

    @Override
    public String toString() {
        return map.values().toString();
    }
}
