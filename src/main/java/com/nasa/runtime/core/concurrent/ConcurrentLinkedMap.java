package com.nasa.runtime.core.concurrent;

import com.nasa.runtime.core.base.ObjectPool;
import com.nasa.runtime.core.utils.ContextUtils;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Nasa 高性能并发有序 Map
 * 基于 ConcurrentHashMap（O(1)查找）+ 双向链表（插入顺序）实现。
 * 写操作通过 ReentrantReadWriteLock 的写锁串行化，读操作通过 volatile 字段实现无锁遍历（弱一致性）。
 * 迭代器为弱一致性，不会抛出 ConcurrentModificationException。
 * unlink 保留节点的 next 指针，确保并发迭代器不会因节点删除而断链。
 * 迭代器 remove 使用节点引用（identity）匹配，防止 ABA 问题。
 * <p>
 * Map 实例通过 ObjectPool 池化 ({@link #of()} / {@link #recycle()})，
 * 但内部 Node 每次 new 分配、GC 回收（非 recycle），因为并发迭代器安全要求 unlink 后保留 next 引用。
 *
 * <h2>时间复杂度</h2>
 * <pre>
 *   ┌──────────────────────────────────────┬───────────────────────────┐
 *   │ 方法                                  │ 时间复杂度                 │
 *   ├──────────────────────────────────────┼───────────────────────────┤
 *   │ get / containsKey                    │ O(1) 无锁                 │
 *   │ put / putIfAbsent / remove           │ O(1) 写锁                 │
 *   │ computeIfAbsent / compute / merge    │ O(1) 写锁                 │
 *   │ replace                              │ O(1) 写锁                 │
 *   │ containsValue                        │ O(n) 无锁, 链表遍历        │
 *   │ forEach                              │ O(n) 无锁, 链表遍历        │
 *   │ clear                                │ O(n) 写锁, CHM.clear      │
 *   │ size / isEmpty                       │ O(1) 无锁                 │
 *   └──────────────────────────────────────┴───────────────────────────┘
 * </pre>
 */
@SuppressWarnings("all")
public class ConcurrentLinkedMap<K, V> implements ConcurrentMap<K, V>, Serializable, ObjectPool.Recycler<ConcurrentLinkedMap<K, V>> {

    @Serial
    private static final long serialVersionUID = 9069514032061359137L;

    static final ObjectPool<ConcurrentLinkedMap<Object, Object>> POOL = new ObjectPool<>(
            ContextUtils.getPropertyInt("nasa.object-pool.concurrent-linked-map-capacity", 1000)) {
        @Override
        public ConcurrentLinkedMap<Object, Object> newObject() {
            return new ConcurrentLinkedMap<>();
        }
    };

    private final ObjectPool.PooledHandle<ConcurrentLinkedMap<K, V>> handle =
            (ObjectPool.PooledHandle) new ObjectPool.PooledHandle<>(POOL);

    @Override
    public ObjectPool.PooledHandle<ConcurrentLinkedMap<K, V>> handle() {
        return this.handle;
    }

    @Override
    public void restore() {
        this.clear();
    }

    public static <K, V> ConcurrentLinkedMap<K, V> of() {
        return (ConcurrentLinkedMap<K, V>) POOL.get();
    }

    public static class Node<K, V> implements Map.Entry<K, V> {
        final K key;
        volatile V value;
        volatile Node<K, V> prev, next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public K getKey() { return key; }

        @Override
        public V getValue() { return value; }

        @Override
        public V setValue(V value) {
            V old = this.value;
            this.value = value;
            return old;
        }

        public Node<K, V> prev() {
            return prev;
        }

        public Node<K, V> next() {
            return next;
        }

        @Override
        public String toString() { return key + "=" + value; }
    }

    // package-private: ConcurrentLinkedList 需要直接访问
    final ConcurrentHashMap<K, Node<K, V>> map;
    final ReadWriteLock lock = new ReentrantReadWriteLock(false);
    final Lock r = lock.readLock();
    final Lock w = lock.writeLock();

    volatile Node<K, V> head, tail;

    public ConcurrentLinkedMap() {
        map = new ConcurrentHashMap<>();
    }

    public ConcurrentLinkedMap(int capacity) {
        map = new ConcurrentHashMap<>(capacity);
    }

    // ==================== 写操作 (W-Lock) ====================

    @Override
    public V put(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        w.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                V old = node.value;
                node.value = value;
                return old;
            }
            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            linkLast(newNode);
            return null;
        } finally {
            w.unlock();
        }
    }

    @Override
    public V putIfAbsent(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        w.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) return node.value;
            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            linkLast(newNode);
            return null;
        } finally {
            w.unlock();
        }
    }

    @Override
    public V remove(Object key) {
        w.lock();
        try {
            Node<K, V> node = map.remove(key);
            if (node != null) {
                unlink(node);
                return node.value;
            }
            return null;
        } finally {
            w.unlock();
        }
    }

    @Override
    public boolean remove(Object key, Object value) {
        w.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null && Objects.equals(node.value, value)) {
                map.remove(key);
                unlink(node);
                return true;
            }
            return false;
        } finally {
            w.unlock();
        }
    }

    /**
     * 按节点引用删除（identity check），防止 ABA 问题。
     * 仅当 HashMap 中存储的节点与传入节点为同一对象时才执行删除。
     *
     * @return true 如果成功删除
     */
    boolean removeNode(Node<K, V> node) {
        w.lock();
        try {
            Node<K, V> current = map.get(node.key);
            if (current != node) return false;
            map.remove(node.key);
            unlink(node);
            return true;
        } finally {
            w.unlock();
        }
    }

    @Override
    public void clear() {
        w.lock();
        try {
            map.clear();
            head = tail = null;
        } finally {
            w.unlock();
        }
    }

    /**
     * 清空 Map 并返回旧的 head 节点。
     * 调用方可通过 node.next() 遍历旧链表（节点的 next 指针在 clear 后仍然完整）。
     */
    public Node<K, V> clearRHead() {
        w.lock();
        try {
            Node<K, V> h = head;
            map.clear();
            head = tail = null;
            return h;
        } finally {
            w.unlock();
        }
    }

    // ==================== 读操作（无锁，通过 volatile 保证可见性） ====================

    @Override
    public V get(Object key) {
        Node<K, V> node = map.get(key);
        return (node == null) ? null : node.value;
    }

    @Override
    public int size() { return map.size(); }

    @Override
    public boolean isEmpty() { return map.isEmpty(); }

    @Override
    public boolean containsKey(Object key) { return map.containsKey(key); }

    @Override
    public boolean containsValue(Object value) {
        for (Node<K, V> e = head; e != null; e = e.next) {
            if (Objects.equals(e.value, value)) return true;
        }
        return false;
    }

    // ==================== 链表维护（调用方必须持有 W-Lock） ====================

    void linkLast(Node<K, V> p) {
        Node<K, V> last = tail;
        tail = p;
        if (last == null) head = p;
        else {
            p.prev = last;
            last.next = p;
        }
    }

    /**
     * 在 target 节点之前插入 p
     */
    void linkBefore(Node<K, V> p, Node<K, V> target) {
        Node<K, V> prev = target.prev;
        p.prev = prev;
        p.next = target;
        target.prev = p;
        if (prev == null) head = p;
        else prev.next = p;
    }

    /**
     * 从链表中摘除节点 p。
     * 关键设计：保留 p.next 不置 null，使已持有 p 引用的并发迭代器
     * 仍可沿 next 指针继续向前遍历（弱一致性语义）。
     */
    private void unlink(Node<K, V> p) {
        Node<K, V> prev = p.prev;
        Node<K, V> next = p.next;
        if (prev == null) head = next;
        else prev.next = next;
        if (next == null) tail = prev;
        else next.prev = prev;
        p.prev = null;
        // 保留 p.next —— 迭代器安全的关键
    }

    // ==================== 迭代器（弱一致性，无锁） ====================

    abstract class LinkedIter<T> implements Iterator<T> {
        Node<K, V> nextNode;
        Node<K, V> lastReturned;

        LinkedIter() {
            nextNode = head; // volatile read
        }

        public boolean hasNext() { return nextNode != null; }

        Node<K, V> nextNode() {
            Node<K, V> e = nextNode;
            if (e == null) throw new NoSuchElementException();
            lastReturned = e;
            nextNode = e.next; // volatile read，即使 e 已被 unlink 也能继续前进
            return e;
        }

        public void remove() {
            if (lastReturned == null) throw new IllegalStateException();
            removeNode(lastReturned); // identity check，防止 ABA
            lastReturned = null;
        }
    }

    final class KeyIter extends LinkedIter<K> { public K next() { return nextNode().key; } }

    final class ValueIter extends LinkedIter<V> { public V next() { return nextNode().value; } }

    final class EntryIter extends LinkedIter<Entry<K, V>> { public Entry<K, V> next() { return nextNode(); } }

    // ==================== 视图 ====================

    @Override
    public Set<K> keySet() { return new KeySet(); }

    @Override
    public Collection<V> values() { return new Values(); }

    @Override
    public Set<Entry<K, V>> entrySet() { return new EntrySet(); }

    final class KeySet extends AbstractSet<K> {

        public Iterator<K> iterator() { return new KeyIter(); }

        public int size() { return ConcurrentLinkedMap.this.size(); }

        public boolean contains(Object o) { return containsKey(o); }

        public void clear() { ConcurrentLinkedMap.this.clear(); }
    }

    final class Values extends AbstractCollection<V> {

        public Iterator<V> iterator() { return new ValueIter(); }

        public int size() { return ConcurrentLinkedMap.this.size(); }

        public boolean contains(Object o) { return containsValue(o); }

        public void clear() { ConcurrentLinkedMap.this.clear(); }
    }

    final class EntrySet extends AbstractSet<Entry<K, V>> {

        public Iterator<Entry<K, V>> iterator() { return new EntryIter(); }

        public int size() { return ConcurrentLinkedMap.this.size(); }

        public void clear() { ConcurrentLinkedMap.this.clear(); }
    }

    // ==================== JDK 8+ 接口 ====================

    /**
     * 弱一致性遍历，不长期持有锁，不阻塞写操作。
     * action 中可执行耗时逻辑而不会造成写饥饿。
     */
    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        for (Node<K, V> e = head; e != null; e = e.next) {
            action.accept(e.key, e.value);
        }
    }

    /**
     * 原子性批量写入，整个操作期间持有写锁。
     */
    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        w.lock();
        try {
            for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
                K key = e.getKey();
                V value = e.getValue();
                Objects.requireNonNull(key);
                Objects.requireNonNull(value);
                Node<K, V> node = map.get(key);
                if (node != null) {
                    node.value = value;
                } else {
                    Node<K, V> newNode = new Node<>(key, value);
                    map.put(key, newNode);
                    linkLast(newNode);
                }
            }
        } finally {
            w.unlock();
        }
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        w.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null && Objects.equals(node.value, oldValue)) {
                node.value = newValue;
                return true;
            }
            return false;
        } finally {
            w.unlock();
        }
    }

    @Override
    public V replace(K key, V value) {
        w.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                V old = node.value;
                node.value = value;
                return old;
            }
            return null;
        } finally {
            w.unlock();
        }
    }

    // ==================== 复合原子操作 (W-Lock, 内联避免重入开销) ====================

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
        w.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) return node.value;
            V newValue = mappingFunction.apply(key);
            if (newValue != null) {
                Node<K, V> newNode = new Node<>(key, newValue);
                map.put(key, newNode);
                linkLast(newNode);
                return newValue;
            }
            return null;
        } finally {
            w.unlock();
        }
    }

    @Override
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        w.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) return null;
            V newValue = remappingFunction.apply(key, node.value);
            if (newValue != null) {
                node.value = newValue;
                return newValue;
            }
            map.remove(key);
            unlink(node);
            return null;
        } finally {
            w.unlock();
        }
    }

    @Override
    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        w.lock();
        try {
            Node<K, V> oldNode = map.get(key);
            V oldValue = (oldNode == null) ? null : oldNode.value;
            V newValue = remappingFunction.apply(key, oldValue);

            if (newValue == null) {
                if (oldNode != null) {
                    map.remove(key);
                    unlink(oldNode);
                }
                return null;
            }

            if (oldNode != null) {
                oldNode.value = newValue;
            } else {
                Node<K, V> newNode = new Node<>(key, newValue);
                map.put(key, newNode);
                linkLast(newNode);
            }
            return newValue;
        } finally {
            w.unlock();
        }
    }

    @Override
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(value);
        Objects.requireNonNull(remappingFunction);
        w.lock();
        try {
            Node<K, V> node = map.get(key);
            V newValue = (node == null) ? value : remappingFunction.apply(node.value, value);
            if (newValue == null) {
                if (node != null) {
                    map.remove(key);
                    unlink(node);
                }
                return null;
            }

            if (node != null) {
                node.value = newValue;
            } else {
                Node<K, V> newNode = new Node<>(key, newValue);
                map.put(key, newNode);
                linkLast(newNode);
            }
            return newValue;
        } finally {
            w.unlock();
        }
    }

    @Override
    public String toString() {
        Node<K, V> e = head;
        if (e == null) return "{}";
        StringBuilder sb = new StringBuilder().append('{');
        for (boolean first = true; e != null; e = e.next) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.key == this ? "(this Map)" : e.key);
            sb.append('=');
            sb.append(e.value == this ? "(this Map)" : e.value);
        }
        return sb.append('}').toString();
    }
}
