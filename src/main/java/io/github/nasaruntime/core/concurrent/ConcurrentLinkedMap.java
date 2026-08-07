package io.github.nasaruntime.core.concurrent;

import io.github.nasaruntime.core.base.ObjectPool;
import io.github.nasaruntime.core.utils.ContextUtils;

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
        /**
         * 业务作用：池空时创建新实例。
         *
         * 参数说明: 无。
         * 返回: 字段均为初始值的新实例。
         */
        @Override
        public ConcurrentLinkedMap<Object, Object> newObject() {
            return new ConcurrentLinkedMap<>();
        }
    };

    private final ObjectPool.PooledHandle<ConcurrentLinkedMap<K, V>> handle =
            (ObjectPool.PooledHandle) new ObjectPool.PooledHandle<>(POOL);

    /**
     * 业务作用：暴露池化身份，启用重复归池的 CAS 防御。
     *
     * 参数说明: 无。
     * 返回: 构造时绑定本类对象池的 handle。
     */
    @Override
    public ObjectPool.PooledHandle<ConcurrentLinkedMap<K, V>> handle() {
        return this.handle;
    }

    /**
     * 业务作用：归池前清空全部字段与节点引用，防止上一代数据泄漏给下一个借用方，并把持有的池化节点级联归还。
     *
     * 参数说明: 无。
     * 返回: 无返回值；执行后实例可安全交给下一个借用方。
     */
    @Override
    public void restore() {
        this.clear();
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * 参数说明: 无。
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <K, V> ConcurrentLinkedMap<K, V> of() {
        return (ConcurrentLinkedMap<K, V>) POOL.get();
    }

    public static class Node<K, V> implements Map.Entry<K, V> {
        final K key;
        volatile V value;
        volatile Node<K, V> prev, next;

        /**
         * 业务作用：按给定参数构造 Node 实例。
         *
         * @param key 键
         * @param value 值
         * 返回: 构造完成后可直接使用的实例。
         */
        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }

        /**
         * 业务作用：读取本节点的键。键在节点生命周期内不可变。
         *
         * 参数说明: 无。
         * 返回: 节点的键。
         */
        @Override
        public K getKey() { return key; }

        /**
         * 业务作用：读取本节点当前的值。
         *
         * 参数说明: 无。
         * 返回: 节点的值。
         */
        @Override
        public V getValue() { return value; }

        /**
         * 业务作用：原地修改本节点的值，修改直接作用于底层容器。
         *
         * @param value 值
         * 返回: 被替换的旧值。
         */
        @Override
        public V setValue(V value) {
            V old = this.value;
            this.value = value;
            return old;
        }

        /**
         * 业务作用：读取前驱节点。
         *
         * 参数说明: 无。
         * 返回: 前驱节点；当前已是头节点时返回 null。
         */
        public Node<K, V> prev() {
            return prev;
        }

        /**
         * 业务作用：返回下一个元素并前移游标。
         *
         * 参数说明: 无。
         * 返回: 下一个元素；已到末尾时抛出 NoSuchElementException。
         */
        public Node<K, V> next() {
            return next;
        }

        /**
         * 业务作用：输出可读的元素快照，仅供诊断。
         *
         * 参数说明: 无。
         * 返回: 形如 [a, b, c] 的字符串；不保证与任何时刻的容器状态一致。
         */
        @Override
        public String toString() { return key + "=" + value; }
    }

    // package-private: ConcurrentLinkedList 需要直接访问
    final ConcurrentHashMap<K, Node<K, V>> map;
    final ReadWriteLock lock = new ReentrantReadWriteLock(false);
    final Lock r = lock.readLock();
    final Lock w = lock.writeLock();

    volatile Node<K, V> head, tail;

    /**
     * 业务作用：构造实例。字段取默认值。
     *
     * 参数说明: 无。
     * 返回: 构造完成后可直接使用的实例。
     */
    public ConcurrentLinkedMap() {
        map = new ConcurrentHashMap<>();
    }

    /**
     * 业务作用：按指定容量构造实例，容量估准可以避免后续扩容带来的重哈希或拷贝开销。
     *
     * @param capacity 初始容量
     * 返回: 构造完成后为空的实例。
     */
    public ConcurrentLinkedMap(int capacity) {
        map = new ConcurrentHashMap<>(capacity);
    }

    // ==================== 写操作 (W-Lock) ====================

    /**
     * 业务作用：写入键值对，已存在同键时覆盖。
     *
     * @param key 键
     * @param value 值
     * 返回: 被覆盖的旧值；原先不存在时返回 null。
     */
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

    /**
     * 业务作用：仅在键不存在时写入，已存在时保留原值。
     *
     * @param key 键
     * @param value 值
     * 返回: 已存在的旧值；本次真正写入时返回 null。
     */
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

    /**
     * 业务作用：按键移除条目。
     *
     * @param key 键
     * 返回: 被移除的值；键不存在时返回 null。
     */
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

    /**
     * 业务作用：仅在键当前映射到给定值时才移除，用于避免误删他人写入的新值。
     *
     * @param key 键
     * @param value 期望的当前值
     * 返回: 条件成立并完成移除返回 true。
     */
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
     * 业务作用：按节点引用删除（identity check），防止 ABA 问题。
     * 仅当 HashMap 中存储的节点与传入节点为同一对象时才执行删除。
     *
     * @param node 见上述说明
     * 返回: true 如果成功删除
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

    /**
     * 业务作用：移除全部元素，解除容器对它们的强引用后交给 GC。
     *
     * 参数说明: 无。
     * 返回: 无返回值；并发写入仍在进行时不保证返回后容器为空。
     */
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
     * 业务作用：清空容器自身字段并把整条节点链交还给调用方，节点的归池责任随之移交。用于调用方需要复用或延迟释放节点链的场景。
     *
     * 参数说明: 无。
     * 返回: 原来的头节点；调用方负责逐个归还这些节点。
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

    /**
     * 业务作用：按键读取值。
     *
     * @param key 键
     * 返回: 对应的值；键不存在时返回 null。
     */
    @Override
    public V get(Object key) {
        Node<K, V> node = map.get(key);
        return (node == null) ? null : node.value;
    }

    /**
     * 业务作用：报告当前元素个数，供容量观测与遍历前的预分配。
     *
     * 返回: 元素个数；并发下为弱一致结果，仅反映采样瞬间的状态。
     */
    @Override
    public int size() { return map.size(); }

    /**
     * 业务作用：判断容器当前是否为空。
     *
     * 返回: 为空返回 true；并发下为弱一致结果，仅反映采样瞬间的状态。不能作为后续操作必定成功的依据。
     */
    @Override
    public boolean isEmpty() { return map.isEmpty(); }

    /**
     * 业务作用：判断是否存在给定键。
     *
     * @param key 键
     * 返回: 存在返回 true；并发下为弱一致结果，仅反映采样瞬间的状态。
     */
    @Override
    public boolean containsKey(Object key) { return map.containsKey(key); }

    /**
     * 业务作用：遍历全部条目判断是否存在给定值，代价与容器规模成正比。
     *
     * @param value 待查找的值
     * 返回: 命中返回 true；并发下为弱一致结果，仅反映采样瞬间的状态。
     */
    @Override
    public boolean containsValue(Object value) {
        for (Node<K, V> e = head; e != null; e = e.next) {
            if (Objects.equals(e.value, value)) return true;
        }
        return false;
    }

    // ==================== 链表维护（调用方必须持有 W-Lock） ====================

    /**
     * 业务作用：把节点接到链表尾部，维持插入顺序。
     *
     * @param p 见上述说明
     * 返回: 无返回值。
     */
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
     * 业务作用：在 target 节点之前插入 p
     *
     * @param p 见上述说明
     * @param target 见上述说明
     * 返回: 无返回值。
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
     * 业务作用：从链表中摘除节点 p。
     * 关键设计：保留 p.next 不置 null，使已持有 p 引用的并发迭代器
     * 仍可沿 next 指针继续向前遍历（弱一致性语义）。
     *
     * @param p 见上述说明
     * 返回: 无返回值。
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

        /**
         * 业务作用：构造实例。字段取默认值。
         *
         * 参数说明: 无。
         * 返回: 构造完成后可直接使用的实例。
         */
        LinkedIter() {
            nextNode = head; // volatile read
        }

        /**
         * 业务作用：判断迭代是否还有下一个元素；必要时先向前探测一格。
         *
         * 参数说明: 无。
         * 返回: 还有元素返回 true。
         */
        public boolean hasNext() { return nextNode != null; }

        /**
         * 业务作用：推进到下一个节点并返回。
         *
         * 参数说明: 无。
         * 返回: 下一个节点；已到末尾时抛出 NoSuchElementException。
         */
        Node<K, V> nextNode() {
            Node<K, V> e = nextNode;
            if (e == null) throw new NoSuchElementException();
            lastReturned = e;
            nextNode = e.next; // volatile read，即使 e 已被 unlink 也能继续前进
            return e;
        }

        /**
         * 业务作用：移除最近一次 next 或 previous 返回的元素。
         *
         * 参数说明: 无。
         * 返回: 无返回值；尚未调用过 next/previous 时抛出 IllegalStateException。
         */
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

    /**
     * 业务作用：提供键的集合视图，与底层映射共享数据而非副本。
     *
     * 返回: 键视图；对它的移除会作用到底层映射。
     */
    @Override
    public Set<K> keySet() { return new KeySet(); }

    /**
     * 业务作用：提供值的集合视图，与底层映射共享数据而非副本。
     *
     * 返回: 值视图。
     */
    @Override
    public Collection<V> values() { return new Values(); }

    /**
     * 业务作用：提供条目的集合视图，与底层映射共享数据而非副本。
     *
     * 返回: 条目视图。
     */
    @Override
    public Set<Entry<K, V>> entrySet() { return new EntrySet(); }

    final class KeySet extends AbstractSet<K> {

        /**
         * 业务作用：提供弱一致迭代器，遍历期间允许并发修改，不抛 ConcurrentModificationException。
         *
         * 返回: 弱一致迭代器；遍历中被并发移除的元素可能出现也可能不出现。
         */
        public Iterator<K> iterator() { return new KeyIter(); }

        /**
         * 业务作用：报告当前元素个数，供容量观测与遍历前的预分配。
         *
         * 返回: 元素个数；并发下为弱一致结果，仅反映采样瞬间的状态。
         */
        public int size() { return ConcurrentLinkedMap.this.size(); }

        /**
         * 业务作用：判断容器中是否存在给定元素。
         *
         * @param o 源对象
         * 返回: 命中返回 true；并发下为弱一致结果，仅反映采样瞬间的状态。
         */
        public boolean contains(Object o) { return containsKey(o); }

        /**
         * 业务作用：移除全部元素，解除容器对它们的强引用后交给 GC。
         *
         * 返回: 无返回值；并发写入仍在进行时不保证返回后容器为空。
         */
        public void clear() { ConcurrentLinkedMap.this.clear(); }
    }

    final class Values extends AbstractCollection<V> {

        /**
         * 业务作用：提供弱一致迭代器，遍历期间允许并发修改，不抛 ConcurrentModificationException。
         *
         * 返回: 弱一致迭代器；遍历中被并发移除的元素可能出现也可能不出现。
         */
        public Iterator<V> iterator() { return new ValueIter(); }

        /**
         * 业务作用：报告当前元素个数，供容量观测与遍历前的预分配。
         *
         * 返回: 元素个数；并发下为弱一致结果，仅反映采样瞬间的状态。
         */
        public int size() { return ConcurrentLinkedMap.this.size(); }

        /**
         * 业务作用：判断容器中是否存在给定元素。
         *
         * @param o 源对象
         * 返回: 命中返回 true；并发下为弱一致结果，仅反映采样瞬间的状态。
         */
        public boolean contains(Object o) { return containsValue(o); }

        /**
         * 业务作用：移除全部元素，解除容器对它们的强引用后交给 GC。
         *
         * 返回: 无返回值；并发写入仍在进行时不保证返回后容器为空。
         */
        public void clear() { ConcurrentLinkedMap.this.clear(); }
    }

    final class EntrySet extends AbstractSet<Entry<K, V>> {

        /**
         * 业务作用：提供弱一致迭代器，遍历期间允许并发修改，不抛 ConcurrentModificationException。
         *
         * 返回: 弱一致迭代器；遍历中被并发移除的元素可能出现也可能不出现。
         */
        public Iterator<Entry<K, V>> iterator() { return new EntryIter(); }

        /**
         * 业务作用：报告当前元素个数，供容量观测与遍历前的预分配。
         *
         * 返回: 元素个数；并发下为弱一致结果，仅反映采样瞬间的状态。
         */
        public int size() { return ConcurrentLinkedMap.this.size(); }

        /**
         * 业务作用：移除全部元素，解除容器对它们的强引用后交给 GC。
         *
         * 返回: 无返回值；并发写入仍在进行时不保证返回后容器为空。
         */
        public void clear() { ConcurrentLinkedMap.this.clear(); }
    }

    // ==================== JDK 8+ 接口 ====================

    /**
     * 业务作用：遍历当前元素并逐个交给回调，遍历不阻塞并发读写。
     *
     * @param action 对每个元素执行的动作
     * 返回: 无返回值；回调抛出的异常直接向上传播，遍历随即中断。
     */
    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        for (Node<K, V> e = head; e != null; e = e.next) {
            action.accept(e.key, e.value);
        }
    }

    /**
     * 业务作用：原子性批量写入，整个操作期间持有写锁。
     *
     * @param m 见上述说明
     * 返回: 无返回值。
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

    /**
     * 业务作用：仅在键当前映射到期望值时才替换，避免覆盖他人写入的新值。
     *
     * @param key 键
     * @param oldValue 期望的当前值
     * @param newValue 新值
     * 返回: 条件成立并完成替换返回 true。
     */
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

    /**
     * 业务作用：仅在键已存在时替换其值，不存在时不做任何事。
     *
     * @param key 键
     * @param value 新值
     * 返回: 被替换的旧值；键不存在时返回 null。
     */
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

    /**
     * 业务作用：读取值，缺失时由映射函数计算并写入，是「读取或装载」的原子入口。
     *
     * @param key 键
     * @param mappingFunction 缺失时用于计算值的函数
     * 返回: 已有值或本次计算写入的值；函数返回 null 时不写入并返回 null。
     */
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

    /**
     * 业务作用：仅在键已存在时按函数重算其值，函数返回 null 表示删除该条目。
     *
     * @param key 键
     * @param remappingFunction 重算函数
     * 返回: 新值；键不存在或函数返回 null 时返回 null。
     */
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

    /**
     * 业务作用：无论键是否存在都按函数重算其值，函数返回 null 表示删除该条目。
     *
     * @param key 键
     * @param remappingFunction 重算函数
     * 返回: 新值；函数返回 null 时返回 null 并删除条目。
     */
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

    /**
     * 业务作用：键不存在时直接写入给定值，已存在时用合并函数把新旧值合并；函数返回 null 表示删除该条目。
     *
     * @param key 键
     * @param value 键不存在时写入的值
     * @param remappingFunction 新旧值的合并函数
     * 返回: 合并后的新值；函数返回 null 时返回 null 并删除条目。
     */
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

    /**
     * 业务作用：输出可读的元素快照，仅供诊断。
     *
     * 参数说明: 无。
     * 返回: 形如 [a, b, c] 的字符串；不保证与任何时刻的容器状态一致。
     */
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
