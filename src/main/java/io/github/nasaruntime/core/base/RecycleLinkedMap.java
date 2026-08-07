package io.github.nasaruntime.core.base;

import io.github.nasaruntime.core.utils.ContextUtils;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 节点可回收的 LinkedHashMap。
 * 移除的 Node 不交给 GC，而是放入内部对象池复用，稳态零 Node 分配。
 * 保持插入顺序，非线程安全，适用于单线程热路径。
 * <p>
 * extends LinkedHashMap 使其可作为 LinkedHashMap 的子类型传递，
 * 但所有方法完全重写，不依赖父类的 table/Node 实现。
 * 父类构造 initialCapacity=1, loadFactor=MAX 使其内部 table 分配最小化。
 *
 * <h2>时间复杂度</h2>
 * <pre>
 *   ┌──────────────────────────────────┬────────────────────────┐
 *   │ 方法                              │ 时间复杂度              │
 *   ├──────────────────────────────────┼────────────────────────┤
 *   │ get / put / remove / containsKey │ O(1) 均摊, O(n) 最差    │
 *   │ computeIfAbsent / compute / merge│ O(1) 均摊, O(n) 最差    │
 *   │ putIfAbsent / replace            │ O(1) 均摊, O(n) 最差    │
 *   │ containsValue                    │ O(n)                   │
 *   │ clear                            │ O(n) 批量回收节点链      │
 *   │ forEach / replaceAll / putAll    │ O(n)                   │
 *   └──────────────────────────────────┴────────────────────────┘
 * </pre>
 *
 * <h2>遍历语义 (放弃 Map.forEach 的 CME 契约)</h2>
 * 本类覆写 {@link #forEach(BiConsumer)} / {@link #forEachReversed(BiConsumer)},
 * 不维护 modCount, <b>不抛 ConcurrentModificationException</b>。
 * 实现采用游标前置技巧, action 内 {@code map.remove(currentKey)} 安全;
 * 但 {@link #clear} / {@link #recycle} 或删尚未访问的后续 key 会静默错乱。详见方法 API docs。
 */
@SuppressWarnings("all")
public class RecycleLinkedMap<K, V> extends LinkedHashMap<K, V>
        implements RecycleLinked<K, V>, ObjectPool.Recycler<RecycleLinkedMap<K, V>> {

    public static final Consumer<RecycleLinkedMap> RECY_CON = RecycleLinkedMap::recycle;
    public static final BiConsumer<String, RecycleLinkedMap> RECY_BICON = (BiConsumer<String, RecycleLinkedMap>) (s, r) -> r.recycle();

    static final ObjectPool<RecycleLinkedMap<Object, Object>> POOL = new ObjectPool<>(
            ContextUtils.getPropertyInt("nasa.object-pool.recycle-linked-map-capacity", 1000)) {
        /**
         * 业务作用：池空时创建新实例。
         *
         * 参数说明: 无。
         * 返回: 字段均为初始值的新实例。
         */
        @Override
        public RecycleLinkedMap<Object, Object> newObject() {
            return new RecycleLinkedMap<>();
        }
    };

    private final ObjectPool.PooledHandle<RecycleLinkedMap<K, V>> handle =
            (ObjectPool.PooledHandle) new ObjectPool.PooledHandle<>(POOL);

    // ==================== ObjectPool.Recycler ====================

    /**
     * 业务作用：暴露池化身份，启用重复归池的 CAS 防御。
     *
     * 参数说明: 无。
     * 返回: 构造时绑定本类对象池的 handle。
     */
    @Override
    public ObjectPool.PooledHandle<RecycleLinkedMap<K, V>> handle() {
        return this.handle;
    }

    /**
     * 业务作用：O(n) — clear 批量回收节点链 + 可选 table 收缩 + 重置缓存 iterator 引用
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    @Override
    public void restore() {
        clear();
        if (table.length > 1000) {
            table = new Node[DEFAULT_INITIAL_CAPACITY];
        }
        // 清除缓存 iterator 的悬挂引用, 防止持有已回池 Node
        if (entryIter != null) entryIter.reset();
        if (keyIter != null) keyIter.reset();
        if (valueIter != null) valueIter.reset();
    }

    /**
     * 双向链表维护插入顺序
     */
    transient Node<K, V> head;
    transient Node<K, V> tail;
    /**
     * hash 桶，key hash -> 链表头
     */
    transient Node<K, V>[] table;
    transient int sz;

    static final int DEFAULT_INITIAL_CAPACITY = 16;
    static final float LOAD_FACTOR = 0.75f;

    /**
     * 业务作用：构造实例。字段取默认值。
     *
     * 参数说明: 无。
     * 返回: 构造完成后可直接使用的实例。
     */
    public RecycleLinkedMap() {
        // 父类 initialCapacity=1, loadFactor=Float.MAX_VALUE → 内部 table 最小化 (长度1, 永不 resize)
        super(1, Float.MAX_VALUE);
        table = new Node[DEFAULT_INITIAL_CAPACITY];
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * 参数说明: 无。
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <K, V> RecycleLinkedMap<K, V> of() {
        return (RecycleLinkedMap<K, V>) POOL.get();
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param map 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <K, V> RecycleLinkedMap<K, V> of(Map<K, V> map) {
        RecycleLinkedMap<K, V> rtn = of();
        if (map != null) rtn.putAll(map);
        return rtn;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param k 键
     * @param v 值
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <K, V> RecycleLinkedMap<K, V> of(K k, V v) {
        RecycleLinkedMap<K, V> map = of();
        map.put(k, v);
        return map;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param k1 见上述说明
     * @param v1 见上述说明
     * @param k2 见上述说明
     * @param v2 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <K, V> RecycleLinkedMap<K, V> of(K k1, V v1, K k2, V v2) {
        RecycleLinkedMap<K, V> map = of(k1, v1);
        map.put(k2, v2);
        return map;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param k1 见上述说明
     * @param v1 见上述说明
     * @param k2 见上述说明
     * @param v2 见上述说明
     * @param k3 见上述说明
     * @param v3 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <K, V> RecycleLinkedMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3) {
        RecycleLinkedMap<K, V> map = of(k1, v1, k2, v2);
        map.put(k3, v3);
        return map;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param k1 见上述说明
     * @param v1 见上述说明
     * @param k2 见上述说明
     * @param v2 见上述说明
     * @param k3 见上述说明
     * @param v3 见上述说明
     * @param k4 见上述说明
     * @param v4 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <K, V> RecycleLinkedMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
        RecycleLinkedMap<K, V> map = of(k1, v1, k2, v2, k3, v3);
        map.put(k4, v4);
        return map;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param k1 见上述说明
     * @param v1 见上述说明
     * @param k2 见上述说明
     * @param v2 见上述说明
     * @param k3 见上述说明
     * @param v3 见上述说明
     * @param k4 见上述说明
     * @param v4 见上述说明
     * @param k5 见上述说明
     * @param v5 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <K, V> RecycleLinkedMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5) {
        RecycleLinkedMap<K, V> map = of(k1, v1, k2, v2, k3, v3, k4, v4);
        map.put(k5, v5);
        return map;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param k1 见上述说明
     * @param v1 见上述说明
     * @param k2 见上述说明
     * @param v2 见上述说明
     * @param k3 见上述说明
     * @param v3 见上述说明
     * @param k4 见上述说明
     * @param v4 见上述说明
     * @param k5 见上述说明
     * @param v5 见上述说明
     * @param k6 见上述说明
     * @param v6 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <K, V> RecycleLinkedMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5,
                                                   K k6, V v6) {
        RecycleLinkedMap<K, V> map = of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5);
        map.put(k6, v6);
        return map;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param k1 见上述说明
     * @param v1 见上述说明
     * @param k2 见上述说明
     * @param v2 见上述说明
     * @param k3 见上述说明
     * @param v3 见上述说明
     * @param k4 见上述说明
     * @param v4 见上述说明
     * @param k5 见上述说明
     * @param v5 见上述说明
     * @param k6 见上述说明
     * @param v6 见上述说明
     * @param k7 见上述说明
     * @param v7 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <K, V> RecycleLinkedMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5,
                                                   K k6, V v6, K k7, V v7) {
        RecycleLinkedMap<K, V> map = of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6);
        map.put(k7, v7);
        return map;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param k1 见上述说明
     * @param v1 见上述说明
     * @param k2 见上述说明
     * @param v2 见上述说明
     * @param k3 见上述说明
     * @param v3 见上述说明
     * @param k4 见上述说明
     * @param v4 见上述说明
     * @param k5 见上述说明
     * @param v5 见上述说明
     * @param k6 见上述说明
     * @param v6 见上述说明
     * @param k7 见上述说明
     * @param v7 见上述说明
     * @param k8 见上述说明
     * @param v8 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <K, V> RecycleLinkedMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5,
                                                   K k6, V v6, K k7, V v7, K k8, V v8) {
        RecycleLinkedMap<K, V> map = of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7);
        map.put(k8, v8);
        return map;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param k1 见上述说明
     * @param v1 见上述说明
     * @param k2 见上述说明
     * @param v2 见上述说明
     * @param k3 见上述说明
     * @param v3 见上述说明
     * @param k4 见上述说明
     * @param v4 见上述说明
     * @param k5 见上述说明
     * @param v5 见上述说明
     * @param k6 见上述说明
     * @param v6 见上述说明
     * @param k7 见上述说明
     * @param v7 见上述说明
     * @param k8 见上述说明
     * @param v8 见上述说明
     * @param k9 见上述说明
     * @param v9 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <K, V> RecycleLinkedMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5,
                                                   K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9) {
        RecycleLinkedMap<K, V> map = of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8);
        map.put(k9, v9);
        return map;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param k1 见上述说明
     * @param v1 见上述说明
     * @param k2 见上述说明
     * @param v2 见上述说明
     * @param k3 见上述说明
     * @param v3 见上述说明
     * @param k4 见上述说明
     * @param v4 见上述说明
     * @param k5 见上述说明
     * @param v5 见上述说明
     * @param k6 见上述说明
     * @param v6 见上述说明
     * @param k7 见上述说明
     * @param v7 见上述说明
     * @param k8 见上述说明
     * @param v8 见上述说明
     * @param k9 见上述说明
     * @param v9 见上述说明
     * @param k10 见上述说明
     * @param v10 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <K, V> RecycleLinkedMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5,
                                                   K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9, K k10, V v10) {
        RecycleLinkedMap<K, V> map = of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9);
        map.put(k10, v10);
        return map;
    }

    // ==================== Node 管理 ====================

    /**
     * 业务作用：借出一个链表节点。节点自身也走对象池，使增删元素不产生节点垃圾。
     *
     * @param key 键
     * @param value 值
     * 返回: 已绑定键值的节点。
     */
    private Node<K, V> newNode(K key, V value) {
        Node<K, V> node = Node.of();
        node.key = key;
        node.value = value;
        return node;
    }

    /**
     * 业务作用：对键的哈希做扰动后取桶下标。扰动是必要的：直接用低位会让高位不同、低位相同的键全部撞进同一个桶。
     *
     * @param key 键
     * 返回: 该键对应的桶下标。
     */
    private int index(Object key) {
        int h = Objects.hashCode(key);
        return (h ^ (h >>> 16)) & (table.length - 1);
    }

    // ==================== 双向链表操作 ====================

    /**
     * 业务作用：O(1) — 改 head/tail 指针
     *
     * @param node 见上述说明
     * 返回: 无返回值。
     */
    private void linkLast(Node<K, V> node) {
        Node<K, V> t = tail;
        node.prev = t;
        node.next = null;
        tail = node;
        if (t == null) {
            head = node;
        } else {
            t.next = node;
        }
    }

    /**
     * 业务作用：O(1) — 双向链表解前后指针
     *
     * @param node 见上述说明
     * 返回: 无返回值。
     */
    private void unlink(Node<K, V> node) {
        Node<K, V> p = node.prev;
        Node<K, V> n = node.next;
        if (p == null) {
            head = n;
        } else {
            p.next = n;
        }
        if (n == null) {
            tail = p;
        } else {
            n.prev = p;
        }
    }

    // ==================== hash 桶操作 ====================

    /**
     * 业务作用：O(1) — bucket 链表头插
     *
     * @param node 见上述说明
     * 返回: 无返回值。
     */
    private void addToBucket(Node<K, V> node) {
        int i = index(node.key);
        node.bucketNext = table[i];
        table[i] = node;
    }

    /**
     * 业务作用：O(1) 均摊, O(n) 最差 — bucket 链表线性扫描定位前驱后解链
     *
     * @param node 见上述说明
     * 返回: 无返回值。
     */
    private void removeFromBucket(Node<K, V> node) {
        int i = index(node.key);
        Node<K, V> curr = table[i];
        if (curr == node) {
            table[i] = node.bucketNext;
            return;
        }
        while (curr != null) {
            if (curr.bucketNext == node) {
                curr.bucketNext = node.bucketNext;
                return;
            }
            curr = curr.bucketNext;
        }
    }

    /**
     * 业务作用：O(n) — 分配 2× table + 全节点 rehash; 触发频率 O(log n / n) 故均摊 O(1)
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    private void resize() {
        int newCap = table.length << 1;
        Node<K, V>[] newTable = new Node[newCap];
        for (Node<K, V> n = head; n != null; n = n.next) {
            int i = (Objects.hashCode(n.key) ^ (Objects.hashCode(n.key) >>> 16)) & (newCap - 1);
            n.bucketNext = newTable[i];
            newTable[i] = n;
        }
        table = newTable;
    }

    // ==================== Map 核心操作 ====================

    /**
     * 业务作用：报告当前元素个数，供容量观测与遍历前的预分配。
     *
     * 参数说明: 无。
     * 返回: 元素个数；并发下为弱一致结果，仅反映采样瞬间的状态。
     */
    @Override
    public int size() {
        return sz;
    }

    /**
     * 业务作用：判断容器当前是否为空。
     *
     * 参数说明: 无。
     * 返回: 为空返回 true；并发下为弱一致结果，仅反映采样瞬间的状态。不能作为后续操作必定成功的依据。
     */
    @Override
    public boolean isEmpty() {
        return sz == 0;
    }

    /**
     * 业务作用：O(1) 均摊, O(n) 最差 — hash bucket 查找, 冲突链长时退化
     *
     * @param key 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    @Override
    public boolean containsKey(Object key) {
        return findNode(key) != null;
    }

    /**
     * 业务作用：按键定位节点，先算桶再在桶内链表上线性查找。
     *
     * @param key 键
     * 返回: 命中的节点；不存在时返回 null。
     */
    private Node<K, V> findNode(Object key) {
        int i = index(key);
        for (Node<K, V> n = table[i]; n != null; n = n.bucketNext) {
            if (Objects.equals(n.key, key)) return n;
        }
        return null;
    }

    /**
     * 业务作用：按键读取值。
     *
     * @param key 键
     * 返回: 对应的值；键不存在时返回 null。
     */
    @Override
    public V get(Object key) {
        Node<K, V> n = findNode(key);
        return n == null ? null : n.value;
    }

    /**
     * 业务作用：写入键值对，已存在同键时覆盖。
     *
     * @param key 键
     * @param value 值
     * 返回: 被覆盖的旧值；原先不存在时返回 null。
     */
    @Override
    public V put(K key, V value) {
        Node<K, V> existing = findNode(key);
        if (existing != null) {
            V old = existing.value;
            existing.value = value;
            return old;
        }
        // 新增
        if (sz >= table.length * LOAD_FACTOR) {
            resize();
        }
        Node<K, V> node = newNode(key, value);
        linkLast(node);
        addToBucket(node);
        sz++;
        return null;
    }

    /**
     * 业务作用：按键移除条目。
     *
     * @param key 键
     * 返回: 被移除的值；键不存在时返回 null。
     */
    @Override
    public V remove(Object key) {
        Node<K, V> n = findNode(key);
        if (n == null) return null;
        V old = n.value;
        removeFromBucket(n);
        unlink(n);
        sz--;
        n.bucketNext = null;
        n.recycle();
        return old;
    }

    /**
     * 业务作用：移除全部元素，解除容器对它们的强引用后交给 GC。
     *
     * 参数说明: 无。
     * 返回: 无返回值；并发写入仍在进行时不保证返回后容器为空。
     */
    @Override
    public void clear() {
        if (sz == 0) return;
        // 批量回收：复用 next 字段保留的链，单次原子计数更新
        Node<K, V> x = head;
        while (x != null) {
            x.key = null;
            x.value = null;
            x.prev = null;
            x.bucketNext = null;
            x = x.next; // 保留 next 形成链
        }
        ((NodePool<K, V>) Node.NODE_POOL).bulkRecycleChain(head);
        head = tail = null;
        Arrays.fill(table, null);
        sz = 0;
    }

    /**
     * 业务作用：清空容器自身字段并把整条节点链交还给调用方，节点的归池责任随之移交。用于调用方需要复用或延迟释放节点链的场景。
     *
     * 参数说明: 无。
     * 返回: 原来的头节点；调用方负责逐个归还这些节点。
     */
    @Override
    public Node<K, V> clearRHead() {
        Node<K, V> h = head;
        head = tail = null;
        Arrays.fill(table, null);
        sz = 0;
        return h;
    }

    // ==================== entrySet / keySet / values ====================

    private transient Set<Map.Entry<K, V>> cachedEntrySet;
    private transient Set<K> cachedKeySet;
    private transient Collection<V> cachedValues;

    /**
     * 业务作用：提供条目的集合视图，与底层映射共享数据而非副本。
     *
     * 参数说明: 无。
     * 返回: 条目视图。
     */
    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        if (cachedEntrySet == null) {
            cachedEntrySet = new AbstractSet<>() {
                /**
                 * 业务作用：提供弱一致迭代器，遍历期间允许并发修改，不抛 ConcurrentModificationException。
                 *
                 * 参数说明: 无。
                 * 返回: 弱一致迭代器；遍历中被并发移除的元素可能出现也可能不出现。
                 */
                @Override
                public Iterator<Map.Entry<K, V>> iterator() {
                    return new EntryIterator();
                }

                /**
                 * 业务作用：报告当前元素个数，供容量观测与遍历前的预分配。
                 *
                 * 参数说明: 无。
                 * 返回: 元素个数；并发下为弱一致结果，仅反映采样瞬间的状态。
                 */
                @Override
                public int size() {
                    return sz;
                }
            };
        }
        return cachedEntrySet;
    }

    // ==================== 零分配遍历 (热路径显式调用) ====================

    /**
     * 缓存的 EntryIterator, 仅给 {@link #entryIterator()} 热路径用.
     */
    private transient EntryIterator entryIter;

    /**
     * 业务作用：复用缓存的条目迭代器并重置到头部，避免每次遍历都分配迭代器。
     *
     * 参数说明: 无。
     * 返回: 已重置到头部的条目迭代器。
     */
    public Iterator<Map.Entry<K, V>> entryIterator() {
        if (entryIter == null) {
            entryIter = new EntryIterator();
        } else {
            entryIter.reset();
        }
        return entryIter;
    }

    class EntryIterator implements Iterator<Map.Entry<K, V>> {
        Node<K, V> current = head;
        Node<K, V> lastReturned;

        /**
         * 业务作用：O(1) — 重置 current 到 head
         *
         * 参数说明: 无。
         * 返回: 无返回值。
         */
        void reset() {
            current = head;
            lastReturned = null;
        }

        /**
         * 业务作用：判断迭代是否还有下一个元素；必要时先向前探测一格。
         *
         * 参数说明: 无。
         * 返回: 还有元素返回 true。
         */
        @Override
        public boolean hasNext() {
            return current != null;
        }

        /**
         * 业务作用：返回下一个元素并前移游标。
         *
         * 参数说明: 无。
         * 返回: 下一个元素；已到末尾时抛出 NoSuchElementException。
         */
        @Override
        public Map.Entry<K, V> next() {
            if (current == null) throw new NoSuchElementException();
            lastReturned = current;
            current = current.next;
            return lastReturned;
        }

        /**
         * 业务作用：移除最近一次 next 或 previous 返回的元素。
         *
         * 参数说明: 无。
         * 返回: 无返回值；尚未调用过 next/previous 时抛出 IllegalStateException。
         */
        @Override
        public void remove() {
            if (lastReturned == null) throw new IllegalStateException();
            RecycleLinkedMap.this.remove(lastReturned.key);
            lastReturned = null;
        }
    }

    /**
     * 业务作用：遍历当前元素并逐个交给回调，遍历不阻塞并发读写。
     *
     * @param action 对每个元素执行的动作
     * 返回: 无返回值；回调抛出的异常直接向上传播，遍历随即中断。
     */
    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        for (Node<K, V> n = head; n != null; ) {
            K key = n.key;
            V value = n.value;
            n = n.next;
            action.accept(key, value);
        }
    }

    /**
     * 业务作用：从 tail 向 head 反向遍历, 零 GC, 单线程.
     * <p>
     * 跟 {@link #forEach} 完全对称, 同样采用游标前置技巧. 安全边界对称:
     * <ul>
     * <li><b>安全</b>: action 内 {@code map.remove(currentKey)} / {@code map.put(...)}
     * (新 key 挂 tail, 不在反向迭代路径上) / 删除远处尚未访问的 entry (非紧邻 cursor.prev)</li>
     * <li><b>不安全 (静默错乱)</b>:
     * <ul>
     * <li>{@link #clear()} / {@link #recycle()} 整个 map</li>
     * <li>删除 <b>cursor 指向的 prev entry</b> — 下一轮 action 多调一次 {@code (null, null)},
     * 然后 prev=null 提前终止, 跳过该 entry 之前的全部 entry</li>
     * <li>remove + 同 action 内 put 新 entry — 新 Node 可能复用刚 recycle 那个实例, 读到新 key/value 但跳过原始前面全部 entry, 更隐蔽</li>
     * </ul>
     * </li>
     * </ul>
     *
     * @param action 见上述说明
     * 返回: 无返回值。
     */
    public void forEachReversed(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        for (Node<K, V> n = tail; n != null; ) {
            K key = n.key;
            V value = n.value;
            n = n.prev;
            action.accept(key, value);
        }
    }

    /**
     * 业务作用：按键读取值，缺失时返回调用方给定的默认值，省去空值判断。
     *
     * @param key 键
     * @param defaultValue 键不存在时返回的默认值
     * 返回: 对应的值；键不存在时返回默认值。
     */
    @Override
    public V getOrDefault(Object key, V defaultValue) {
        Node<K, V> n = findNode(key);
        return n == null ? defaultValue : n.value;
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
        Node<K, V> existing = findNode(key);
        if (existing != null) return existing.value;
        return put(key, value);
    }

    /**
     * 业务作用：读取值，缺失时由映射函数计算并写入，是「读取或装载」的原子入口。
     *
     * @param key 键
     * @param mappingFunction 缺失时用于计算值的函数
     * 返回: 已有值或本次计算写入的值；函数返回 null 时不写入并返回 null。
     */
    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        Node<K, V> existing = findNode(key);
        if (existing != null) return existing.value;
        V value = mappingFunction.apply(key);
        if (value != null) put(key, value);
        return value;
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
        Node<K, V> n = findNode(key);
        if (n == null) return null;
        V old = n.value;
        n.value = value;
        return old;
    }

    /**
     * 业务作用：O(1) 均摊, O(n) 最差 — findNode + 比较 oldValue
     *
     * @param key 见上述说明
     * @param oldValue 见上述说明
     * @param newValue 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        Node<K, V> n = findNode(key);
        if (n == null || !Objects.equals(n.value, oldValue)) return false;
        n.value = newValue;
        return true;
    }

    /**
     * 业务作用：O(1) 均摊, O(n) 最差 — findNode + 比较 value + 解链回池
     *
     * @param key 见上述说明
     * @param value 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    @Override
    public boolean remove(Object key, Object value) {
        Node<K, V> n = findNode(key);
        if (n == null || !Objects.equals(n.value, value)) return false;
        removeFromBucket(n);
        unlink(n);
        sz--;
        n.bucketNext = null;
        n.recycle();
        return true;
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
        Node<K, V> n = findNode(key);
        if (n == null) return null;
        V newValue = remappingFunction.apply(key, n.value);
        if (newValue != null) {
            n.value = newValue;
        } else {
            removeNode(n);
        }
        return newValue;
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
        Node<K, V> n = findNode(key);
        V oldValue = n == null ? null : n.value;
        V newValue = remappingFunction.apply(key, oldValue);
        if (newValue != null) {
            if (n != null) {
                n.value = newValue;
            } else {
                put(key, newValue);
            }
        } else if (n != null) {
            removeNode(n);
        }
        return newValue;
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
        Node<K, V> n = findNode(key);
        if (n == null) {
            put(key, value);
            return value;
        }
        V newValue = remappingFunction.apply(n.value, value);
        if (newValue != null) {
            n.value = newValue;
        } else {
            removeNode(n);
        }
        return newValue;
    }

    /**
     * 业务作用：O(1) 均摊, O(n) 最差 — bucket 解链 + 双向链表解链, 复用已找到的 Node 避免二次 findNode
     *
     * @param n 见上述说明
     * 返回: 无返回值。
     */
    private void removeNode(Node<K, V> n) {
        removeFromBucket(n);
        unlink(n);
        sz--;
        n.bucketNext = null;
        n.recycle();
    }

    /**
     * 业务作用：O(n) — 直接遍历 head 链, 零分配
     *
     * @param function 见上述说明
     * 返回: 无返回值。
     */
    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        for (Node<K, V> n = head; n != null; n = n.next) {
            n.value = function.apply(n.key, n.value);
        }
    }

    /**
     * 业务作用：O(n) — 全链线性扫描 value
     *
     * @param value 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    @Override
    public boolean containsValue(Object value) {
        for (Node<K, V> n = head; n != null; n = n.next) {
            if (Objects.equals(n.value, value)) return true;
        }
        return false;
    }

    /**
     * 业务作用：O(m) — m=入参 map 大小; 预扩容 + 逐个 put 均摊 O(1)
     *
     * @param m 见上述说明
     * 返回: 无返回值。
     */
    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        // 预扩容，避免多次 resize
        int needed = sz + m.size();
        while (needed >= table.length * LOAD_FACTOR) {
            resize();
        }
        for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    /**
     * 业务作用：提供键的集合视图，与底层映射共享数据而非副本。
     *
     * 参数说明: 无。
     * 返回: 键视图；对它的移除会作用到底层映射。
     */
    @Override
    public Set<K> keySet() {
        if (cachedKeySet == null) {
            cachedKeySet = new AbstractSet<>() {
                /**
                 * 业务作用：提供弱一致迭代器，遍历期间允许并发修改，不抛 ConcurrentModificationException。
                 *
                 * 参数说明: 无。
                 * 返回: 弱一致迭代器；遍历中被并发移除的元素可能出现也可能不出现。
                 */
                @Override
                public Iterator<K> iterator() {
                    return new KeyIterator();
                }

                /**
                 * 业务作用：报告当前元素个数，供容量观测与遍历前的预分配。
                 *
                 * 参数说明: 无。
                 * 返回: 元素个数；并发下为弱一致结果，仅反映采样瞬间的状态。
                 */
                @Override
                public int size() {
                    return sz;
                }
            };
        }
        return cachedKeySet;
    }

    class KeyIterator implements Iterator<K> {
        Node<K, V> current = head;

        /**
         * 业务作用：O(1) — 重置 current 到 head
         *
         * 参数说明: 无。
         * 返回: 无返回值。
         */
        void reset() {
            current = head;
        }

        /**
         * 业务作用：判断迭代是否还有下一个元素；必要时先向前探测一格。
         *
         * 参数说明: 无。
         * 返回: 还有元素返回 true。
         */
        @Override
        public boolean hasNext() {
            return current != null;
        }

        /**
         * 业务作用：返回下一个元素并前移游标。
         *
         * 参数说明: 无。
         * 返回: 下一个元素；已到末尾时抛出 NoSuchElementException。
         */
        @Override
        public K next() {
            if (current == null) throw new NoSuchElementException();
            K k = current.key;
            current = current.next;
            return k;
        }
    }

    private transient KeyIterator keyIter;

    /**
     * 业务作用：复用缓存的键迭代器并重置到头部。
     *
     * 参数说明: 无。
     * 返回: 已重置到头部的键迭代器。
     */
    public Iterator<K> keyIterator() {
        if (keyIter == null) {
            keyIter = new KeyIterator();
        } else {
            keyIter.reset();
        }
        return keyIter;
    }

    /**
     * 业务作用：提供值的集合视图，与底层映射共享数据而非副本。
     *
     * 参数说明: 无。
     * 返回: 值视图。
     */
    @Override
    public Collection<V> values() {
        if (cachedValues == null) {
            cachedValues = new AbstractCollection<>() {
                /**
                 * 业务作用：提供弱一致迭代器，遍历期间允许并发修改，不抛 ConcurrentModificationException。
                 *
                 * 参数说明: 无。
                 * 返回: 弱一致迭代器；遍历中被并发移除的元素可能出现也可能不出现。
                 */
                @Override
                public Iterator<V> iterator() {
                    return new ValueIterator();
                }

                /**
                 * 业务作用：报告当前元素个数，供容量观测与遍历前的预分配。
                 *
                 * 参数说明: 无。
                 * 返回: 元素个数；并发下为弱一致结果，仅反映采样瞬间的状态。
                 */
                @Override
                public int size() {
                    return sz;
                }
            };
        }
        return cachedValues;
    }

    class ValueIterator implements Iterator<V> {
        Node<K, V> current = head;

        /**
         * 业务作用：O(1) — 重置 current 到 head
         *
         * 参数说明: 无。
         * 返回: 无返回值。
         */
        void reset() {
            current = head;
        }

        /**
         * 业务作用：判断迭代是否还有下一个元素；必要时先向前探测一格。
         *
         * 参数说明: 无。
         * 返回: 还有元素返回 true。
         */
        @Override
        public boolean hasNext() {
            return current != null;
        }

        /**
         * 业务作用：返回下一个元素并前移游标。
         *
         * 参数说明: 无。
         * 返回: 下一个元素；已到末尾时抛出 NoSuchElementException。
         */
        @Override
        public V next() {
            if (current == null) throw new NoSuchElementException();
            V v = current.value;
            current = current.next;
            return v;
        }
    }

    private transient ValueIterator valueIter;

    /**
     * 业务作用：复用缓存的值迭代器并重置到头部。
     *
     * 参数说明: 无。
     * 返回: 已重置到头部的值迭代器。
     */
    public Iterator<V> valueIterator() {
        if (valueIter == null) {
            valueIter = new ValueIterator();
        } else {
            valueIter.reset();
        }
        return valueIter;
    }

    // ==================== clone 禁用 ====================

    /**
     * 业务作用：本容器池化，浅拷贝会让两个实例共享同一批池化节点，任一方归池都会让另一方读到已回收数据，因此不提供该语义。
     *
     * 参数说明: 无。
     * 返回: 不返回，恒抛 UnsupportedOperationException。
     */
    @Override
    public Object clone() {
        throw new UnsupportedOperationException("RecycleLinkedMap does not support clone, use of() + putAll instead");
    }
}
