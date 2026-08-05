package com.nasa.runtime.core.base;

import com.nasa.runtime.core.utils.ContextUtils;

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
        @Override
        public RecycleLinkedMap<Object, Object> newObject() {
            return new RecycleLinkedMap<>();
        }
    };

    private final ObjectPool.PooledHandle<RecycleLinkedMap<K, V>> handle =
            (ObjectPool.PooledHandle) new ObjectPool.PooledHandle<>(POOL);

    // ==================== ObjectPool.Recycler ====================

    /**
     * O(1) — 返回 per-instance Handle (含 CAS state, 防 double-recycle)
     */
    @Override
    public ObjectPool.PooledHandle<RecycleLinkedMap<K, V>> handle() {
        return this.handle;
    }

    /**
     * O(n) — clear 批量回收节点链 + 可选 table 收缩 + 重置缓存 iterator 引用
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
     * O(1) — 父类最小化构造 + 分配长度 16 的 table 数组
     */
    public RecycleLinkedMap() {
        // 父类 initialCapacity=1, loadFactor=Float.MAX_VALUE → 内部 table 最小化 (长度1, 永不 resize)
        super(1, Float.MAX_VALUE);
        table = new Node[DEFAULT_INITIAL_CAPACITY];
    }

    /**
     * O(1) — 池命中 O(1); 池空时 newObject + table 初始化 O(1)
     */
    public static <K, V> RecycleLinkedMap<K, V> of() {
        return (RecycleLinkedMap<K, V>) POOL.get();
    }

    /**
     * O(m) — m=入参 map 大小; putAll 内部预扩容避免多次 resize
     */
    public static <K, V> RecycleLinkedMap<K, V> of(Map<K, V> map) {
        RecycleLinkedMap<K, V> rtn = of();
        if (map != null) rtn.putAll(map);
        return rtn;
    }

    /**
     * O(1)
     */
    public static <K, V> RecycleLinkedMap<K, V> of(K k, V v) {
        RecycleLinkedMap<K, V> map = of();
        map.put(k, v);
        return map;
    }

    /**
     * O(1) — 共 2 次 put
     */
    public static <K, V> RecycleLinkedMap<K, V> of(K k1, V v1, K k2, V v2) {
        RecycleLinkedMap<K, V> map = of(k1, v1);
        map.put(k2, v2);
        return map;
    }

    /**
     * O(1) — 共 3 次 put
     */
    public static <K, V> RecycleLinkedMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3) {
        RecycleLinkedMap<K, V> map = of(k1, v1, k2, v2);
        map.put(k3, v3);
        return map;
    }

    /**
     * O(1) — 共 4 次 put
     */
    public static <K, V> RecycleLinkedMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
        RecycleLinkedMap<K, V> map = of(k1, v1, k2, v2, k3, v3);
        map.put(k4, v4);
        return map;
    }

    /**
     * O(1) — 共 5 次 put
     */
    public static <K, V> RecycleLinkedMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5) {
        RecycleLinkedMap<K, V> map = of(k1, v1, k2, v2, k3, v3, k4, v4);
        map.put(k5, v5);
        return map;
    }

    /**
     * O(1) — 共 6 次 put
     */
    public static <K, V> RecycleLinkedMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5,
                                                   K k6, V v6) {
        RecycleLinkedMap<K, V> map = of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5);
        map.put(k6, v6);
        return map;
    }

    /**
     * O(1) — 共 7 次 put
     */
    public static <K, V> RecycleLinkedMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5,
                                                   K k6, V v6, K k7, V v7) {
        RecycleLinkedMap<K, V> map = of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6);
        map.put(k7, v7);
        return map;
    }

    /**
     * O(1) — 共 8 次 put
     */
    public static <K, V> RecycleLinkedMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5,
                                                   K k6, V v6, K k7, V v7, K k8, V v8) {
        RecycleLinkedMap<K, V> map = of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7);
        map.put(k8, v8);
        return map;
    }

    /**
     * O(1) — 共 9 次 put
     */
    public static <K, V> RecycleLinkedMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5,
                                                   K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9) {
        RecycleLinkedMap<K, V> map = of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8);
        map.put(k9, v9);
        return map;
    }

    /**
     * O(1) — 共 10 次 put
     */
    public static <K, V> RecycleLinkedMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5,
                                                   K k6, V v6, K k7, V v7, K k8, V v8, K k9, V v9, K k10, V v10) {
        RecycleLinkedMap<K, V> map = of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9);
        map.put(k10, v10);
        return map;
    }

    // ==================== Node 管理 ====================

    /**
     * O(1) — Node.of() 走对象池
     */
    private Node<K, V> newNode(K key, V value) {
        Node<K, V> node = Node.of();
        node.key = key;
        node.value = value;
        return node;
    }

    /**
     * O(1) — hash 扰动 + bitmask 取桶下标
     */
    private int index(Object key) {
        int h = Objects.hashCode(key);
        return (h ^ (h >>> 16)) & (table.length - 1);
    }

    // ==================== 双向链表操作 ====================

    /**
     * O(1) — 改 head/tail 指针
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
     * O(1) — 双向链表解前后指针
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
     * O(1) — bucket 链表头插
     */
    private void addToBucket(Node<K, V> node) {
        int i = index(node.key);
        node.bucketNext = table[i];
        table[i] = node;
    }

    /**
     * O(1) 均摊, O(n) 最差 — bucket 链表线性扫描定位前驱后解链
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
     * O(n) — 分配 2× table + 全节点 rehash; 触发频率 O(log n / n) 故均摊 O(1)
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
     * O(1) — 读字段
     */
    @Override
    public int size() {
        return sz;
    }

    /**
     * O(1) — 读字段
     */
    @Override
    public boolean isEmpty() {
        return sz == 0;
    }

    /**
     * O(1) 均摊, O(n) 最差 — hash bucket 查找, 冲突链长时退化
     */
    @Override
    public boolean containsKey(Object key) {
        return findNode(key) != null;
    }

    /**
     * O(1) 均摊, O(n) 最差 — bucket 链表线性扫描
     */
    private Node<K, V> findNode(Object key) {
        int i = index(key);
        for (Node<K, V> n = table[i]; n != null; n = n.bucketNext) {
            if (Objects.equals(n.key, key)) return n;
        }
        return null;
    }

    /**
     * O(1) 均摊, O(n) 最差 — findNode 决定
     */
    @Override
    public V get(Object key) {
        Node<K, V> n = findNode(key);
        return n == null ? null : n.value;
    }

    /**
     * O(1) 均摊, O(n) 最差 — 触发 resize 时该次为 O(n) (rehash 全部节点)
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
     * O(1) 均摊, O(n) 最差 — findNode + bucket 解链 + 双向链表解链
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
     * O(n) — 单次遍历 head 链, 字段清零后 bulkRecycleChain 整段挂回池
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
     * O(t) — t=table.length; Arrays.fill 清桶, 节点链回池责任移交调用方
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
     * O(1) 创建 — 首次构造 AbstractSet view 并缓存; 完整遍历 O(n)
     */
    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        if (cachedEntrySet == null) {
            cachedEntrySet = new AbstractSet<>() {
                /** O(1) 创建 — 新 EntryIterator; 完整遍历 O(n) */
                @Override
                public Iterator<Map.Entry<K, V>> iterator() {
                    return new EntryIterator();
                }

                /** O(1) — 读外层 sz */
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
     * O(1) — 复用缓存 iterator, reset 到 head; 完整遍历 O(n).
     * <p>
     * 零分配 entry 遍历. 调用者约束同 {@code RecycleLinkedList.cachedIterator()}:
     * 单线程、不嵌套同实例遍历.
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
         * O(1) — 重置 current 到 head
         */
        void reset() {
            current = head;
            lastReturned = null;
        }

        /**
         * O(1)
         */
        @Override
        public boolean hasNext() {
            return current != null;
        }

        /**
         * O(1) — 推进 current 指针
         */
        @Override
        public Map.Entry<K, V> next() {
            if (current == null) throw new NoSuchElementException();
            lastReturned = current;
            current = current.next;
            return lastReturned;
        }

        /**
         * O(1) 均摊, O(n) 最差 — 委托外层 remove(key), 含 findNode + bucket 解链
         */
        @Override
        public void remove() {
            if (lastReturned == null) throw new IllegalStateException();
            RecycleLinkedMap.this.remove(lastReturned.key);
            lastReturned = null;
        }
    }

    /**
     * O(n) — 直接遍历 head 链, 零 iterator 分配, 单线程.
     * <p>
     * 实现采用 <b>游标前置</b>: 每轮先取出 {@code n.key}/{@code n.value} 和 {@code n.next}, 再调 action.
     * action 内 {@code map.remove(currentKey)} 把当前节点 unlink 并回池, 游标早已指向下一个有效节点.
     * <p>
     * <b>安全</b>:
     * <ul>
     *   <li>action 内 {@code map.remove(currentKey)} — 删当前已访问的 entry</li>
     *   <li>action 内 {@code map.put(k, v)} 新增 key (挂 tail, 后续迭代会访问到; 已存在 key 则就地更新 value)</li>
     *   <li>action 内 {@code map.remove(otherKey)} 删除远处尚未访问的 entry (非紧邻 cursor 的那个) —
     *       链表/桶指针被正确修复, 跳过即可</li>
     * </ul>
     * <b>不安全 (静默错乱, 无 fail-fast)</b>:
     * <ul>
     *   <li>action 内 {@link #clear()} / {@link #recycle()} 整个 map</li>
     *   <li>action 内删除 <b>cursor 指向的下一个 entry</b> (即"下一轮要访问的 key") —
     *       该 Node recycle 后 key/value/next 全 null, 下一轮会:
     *       (a) 多调一次 {@code action.accept(null, null)},
     *       (b) 因 {@code n.next=null} 提前终止循环, 跳过该 entry 之后的全部 entry.</li>
     *   <li>action 内 remove 紧邻下一 entry 又 put 新 entry — 新 Node 可能从 NodePool 复用刚 recycle 的那个实例,
     *       下一轮读到新 entry 的 key/value, 行为"看似正常"但跳过了原始后续全部 entry, 比 null 更隐蔽.</li>
     * </ul>
     * 需要批量删除符合条件的 entry, 先 forEach 收集 keys, 再单独 remove. 类设计是单线程热路径优先, 不带 modCount.
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
     * 从 tail 向 head 反向遍历, 零 GC, 单线程.
     * <p>
     * 跟 {@link #forEach} 完全对称, 同样采用游标前置技巧. 安全边界对称:
     * <ul>
     *   <li><b>安全</b>: action 内 {@code map.remove(currentKey)} / {@code map.put(...)}
     *       (新 key 挂 tail, 不在反向迭代路径上) / 删除远处尚未访问的 entry (非紧邻 cursor.prev)</li>
     *   <li><b>不安全 (静默错乱)</b>:
     *     <ul>
     *       <li>{@link #clear()} / {@link #recycle()} 整个 map</li>
     *       <li>删除 <b>cursor 指向的 prev entry</b> — 下一轮 action 多调一次 {@code (null, null)},
     *           然后 prev=null 提前终止, 跳过该 entry 之前的全部 entry</li>
     *       <li>remove + 同 action 内 put 新 entry — 新 Node 可能复用刚 recycle 那个实例, 读到新 key/value 但跳过原始前面全部 entry, 更隐蔽</li>
     *     </ul>
     *   </li>
     * </ul>
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
     * O(1) 均摊, O(n) 最差 — 委托 findNode
     */
    @Override
    public V getOrDefault(Object key, V defaultValue) {
        Node<K, V> n = findNode(key);
        return n == null ? defaultValue : n.value;
    }

    /**
     * O(1) 均摊, O(n) 最差 — findNode + put 各一次
     */
    @Override
    public V putIfAbsent(K key, V value) {
        Node<K, V> existing = findNode(key);
        if (existing != null) return existing.value;
        return put(key, value);
    }

    /**
     * O(1) 均摊 + T_mapping — findNode 命中直返; 否则 mappingFunction.apply + put
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
     * O(1) 均摊, O(n) 最差 — findNode + 改 value
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
     * O(1) 均摊, O(n) 最差 — findNode + 比较 oldValue
     */
    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        Node<K, V> n = findNode(key);
        if (n == null || !Objects.equals(n.value, oldValue)) return false;
        n.value = newValue;
        return true;
    }

    /**
     * O(1) 均摊, O(n) 最差 — findNode + 比较 value + 解链回池
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
     * O(1) 均摊 + T_remap — findNode 未命中直返 null; 命中则 remappingFunction.apply
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
     * O(1) 均摊 + T_remap — findNode 一次, 后续走改值/插入/删除任一分支
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
     * O(1) 均摊 + T_remap — findNode 缺失走 put; 命中走 remappingFunction.apply
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
     * O(1) 均摊, O(n) 最差 — bucket 解链 + 双向链表解链, 复用已找到的 Node 避免二次 findNode
     */
    private void removeNode(Node<K, V> n) {
        removeFromBucket(n);
        unlink(n);
        sz--;
        n.bucketNext = null;
        n.recycle();
    }

    /**
     * O(n) — 直接遍历 head 链, 零分配
     */
    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        for (Node<K, V> n = head; n != null; n = n.next) {
            n.value = function.apply(n.key, n.value);
        }
    }

    /**
     * O(n) — 全链线性扫描 value
     */
    @Override
    public boolean containsValue(Object value) {
        for (Node<K, V> n = head; n != null; n = n.next) {
            if (Objects.equals(n.value, value)) return true;
        }
        return false;
    }

    /**
     * O(m) — m=入参 map 大小; 预扩容 + 逐个 put 均摊 O(1)
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
     * O(1) 创建 — 首次构造 AbstractSet view 并缓存; 完整遍历 O(n)
     */
    @Override
    public Set<K> keySet() {
        if (cachedKeySet == null) {
            cachedKeySet = new AbstractSet<>() {
                /** O(1) 创建 — 新 KeyIterator; 完整遍历 O(n) */
                @Override
                public Iterator<K> iterator() {
                    return new KeyIterator();
                }

                /** O(1) — 读外层 sz */
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
         * O(1) — 重置 current 到 head
         */
        void reset() {
            current = head;
        }

        /**
         * O(1)
         */
        @Override
        public boolean hasNext() {
            return current != null;
        }

        /**
         * O(1) — 推进 current 指针
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
     * O(1) — 复用缓存 iterator, reset 到 head; 完整遍历 O(n).
     * <p>
     * 零分配 key 遍历. 调用者约束: 单线程、不嵌套同实例遍历.
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
     * O(1) 创建 — 首次构造 AbstractCollection view 并缓存; 完整遍历 O(n)
     */
    @Override
    public Collection<V> values() {
        if (cachedValues == null) {
            cachedValues = new AbstractCollection<>() {
                /** O(1) 创建 — 新 ValueIterator; 完整遍历 O(n) */
                @Override
                public Iterator<V> iterator() {
                    return new ValueIterator();
                }

                /** O(1) — 读外层 sz */
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
         * O(1) — 重置 current 到 head
         */
        void reset() {
            current = head;
        }

        /**
         * O(1)
         */
        @Override
        public boolean hasNext() {
            return current != null;
        }

        /**
         * O(1) — 推进 current 指针
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
     * O(1) — 复用缓存 iterator, reset 到 head; 完整遍历 O(n).
     * <p>
     * 零分配 value 遍历. 调用者约束: 单线程、不嵌套同实例遍历.
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
     * O(1) — 直接抛, 不支持
     */
    @Override
    public Object clone() {
        throw new UnsupportedOperationException("RecycleLinkedMap does not support clone, use of() + putAll instead");
    }
}
