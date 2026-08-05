package com.nasa.runtime.core.base;

import com.nasa.runtime.core.utils.ContextUtils;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 节点可回收的 LinkedHashSet。
 * 基于 {@link RecycleLinkedMap} 实现，value 用固定占位符，和 JDK HashSet 基于 HashMap 同理。
 * 保持插入顺序，非线程安全，适用于单线程热路径。
 *
 * <h2>遍历选择</h2>
 * <ul>
 *   <li>{@link #iterator()} — 每次新建 iterator 实例 (~24B), 跟 JDK 集合契约一致, 安全多次嵌套调用</li>
 *   <li>{@link #cachedIterator()} — <b>零分配</b>, 复用单实例 iterator。
 *       <b>调用方约束</b>: 单线程、不嵌套同实例遍历; 否则 reset 把外层进度毁掉静默错乱</li>
 *   <li>{@link #forEach(Consumer)} / {@link #forEachReversed(Consumer)} — 直接遍历 head/tail 链, 零分配。
 *       游标前置技巧, action 内 {@code remove(currentElement)} 安全;
 *       但 {@link #clear} / {@link #recycle} 或删尚未访问的后续元素会静默错乱 (无 ConcurrentModificationException 检测)。
 *       详见方法 API docs</li>
 * </ul>
 *
 * <h2>不支持的操作</h2>
 * 默认 iterator 不实现 {@code remove()} (KeyIterator 简化设计), 因此本类**覆写**
 * {@link #removeAll}, {@link #retainAll}, {@link #removeIf} 直接走 map.remove 路径。
 */
@SuppressWarnings("all")
public class RecycleLinkedSet<E> extends AbstractSet<E> implements ObjectPool.Recycler<RecycleLinkedSet<E>> {

    private static final Object PRESENT = new Object();

    static final ObjectPool<RecycleLinkedSet<Object>> POOL = new ObjectPool<>(
            ContextUtils.getPropertyInt("nasa.object-pool.recycle-linked-set-capacity", 1000)) {
        @Override
        public RecycleLinkedSet<Object> newObject() {
            return new RecycleLinkedSet<>();
        }
    };

    private final ObjectPool.PooledHandle<RecycleLinkedSet<E>> handle =
            (ObjectPool.PooledHandle) new ObjectPool.PooledHandle<>(POOL);

    // 内部 map 不走 RecycleLinkedMap 的对象池，跟随 RecycleLinkedSet 实例生命周期
    private final RecycleLinkedMap<E, Object> map;

    /**
     * O(1) — 仅 new 内部 map (table 长度 16 的初始化)
     */
    public RecycleLinkedSet() {
        this.map = new RecycleLinkedMap<>();
    }

    // ==================== ObjectPool.Recycler ====================

    /**
     * O(1) — 返回 per-instance Handle (含 CAS state, 防 double-recycle)
     */
    @Override
    public ObjectPool.PooledHandle<RecycleLinkedSet<E>> handle() {
        return this.handle;
    }

    /**
     * O(n) — 委托 map.restore, 触发节点链批量回收
     */
    @Override
    public void restore() {
        // 只清数据，不归池 map 本身（map 跟随 set 实例复用）
        this.map.restore();
    }

    // ==================== Set 核心操作 ====================

    /**
     * O(1) — 读字段
     */
    @Override
    public int size() {
        return this.map.size();
    }

    /**
     * O(1) — 读字段
     */
    @Override
    public boolean isEmpty() {
        return this.map.isEmpty();
    }

    /**
     * O(1) 均摊, O(n) 最差 — hash bucket 查找, 冲突链长时退化
     */
    @Override
    public boolean contains(Object o) {
        return this.map.containsKey(o);
    }

    /**
     * O(1) 均摊, O(n) 最差 — hash bucket 插入, 触发 resize 时该次为 O(n)
     */
    @Override
    public boolean add(E e) {
        return this.map.put(e, PRESENT) == null;
    }

    /**
     * O(1) 均摊, O(n) 最差 — hash bucket 查找+解链
     */
    @Override
    public boolean remove(Object o) {
        return this.map.remove(o) == PRESENT;
    }

    /**
     * O(n) — 遍历 head 链批量回收节点
     */
    @Override
    public void clear() {
        this.map.clear();
    }

    /**
     * O(1) 创建 — 返回新 KeyIterator 实例; 完整遍历 O(n)
     */
    @Override
    public Iterator<E> iterator() {
        return this.map.keySet().iterator();
    }

    /**
     * O(n) — 直接遍历 head 链, 零 iterator 分配, 单线程.
     * <p>
     * 实现采用 <b>游标前置</b>: 每轮先取出 {@code n.key} 和 {@code n.next}, 再调 action.
     * action 内 {@code set.remove(currentElement)} 把当前节点 unlink 并回池, 游标早已指向下一个有效节点.
     * <p>
     * <b>安全</b>:
     * <ul>
     *   <li>action 内 {@code set.remove(currentElement)} — 删当前已访问的元素</li>
     *   <li>action 内 {@code set.add(...)} 追加新元素 (LinkedHashSet 序为插入序, 新元素挂 tail, 后续迭代会访问到)</li>
     *   <li>action 内删除远处尚未访问的元素 (非紧邻 cursor 的那个) — 链表前后指针被正确修复, 跳过即可</li>
     * </ul>
     * <b>不安全 (静默错乱, 无 fail-fast)</b>:
     * <ul>
     *   <li>action 内 {@link #clear()} / {@link #recycle()} 整个 set</li>
     *   <li>action 内删除 <b>cursor 指向的下一个节点</b> (即"下一轮要访问的元素") —
     *       该节点 recycle 后字段全 null, 下一轮会:
     *       (a) 多调一次 {@code action.accept(null)},
     *       (b) 因 {@code n.next=null} 提前终止循环, 跳过该节点之后的全部元素.</li>
     *   <li>action 内 remove 紧邻下一节点又 add 新元素 — 新元素可能复用刚 recycle 那个 Node 实例,
     *       下一轮读到新元素 key, 行为"看似正常"但跳过了原始后续元素, 比静默 null 更隐蔽.</li>
     * </ul>
     * 需要批量删除符合条件的元素, 先 forEach 收集 ids, 再单独 remove. 类设计是单线程热路径优先, 不带 modCount.
     */
    @Override
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        for (RecycleLinked.Node<E, Object> n = this.map.head; n != null; ) {
            E e = n.key;
            n = n.next;
            action.accept(e);
        }
    }

    /**
     * 从 tail 向 head 反向遍历, 零 GC, 单线程.
     * <p>
     * 跟 {@link #forEach} 完全对称, 同样采用游标前置技巧. 安全边界对称:
     * <ul>
     *   <li><b>安全</b>: action 内 {@code set.remove(currentElement)} / {@code set.add(...)}
     *       (新元素挂 tail, 不在反向迭代路径上) / 删除远处尚未访问的元素 (非紧邻 cursor.prev)</li>
     *   <li><b>不安全 (静默错乱)</b>:
     *     <ul>
     *       <li>{@link #clear()} / {@link #recycle()} 整个 set</li>
     *       <li>删除 <b>cursor 指向的 prev 节点</b> — 下一轮 action 多调一次 null, 然后 prev=null 提前终止, 跳过该节点之前的全部元素</li>
     *       <li>remove + 同 action 内 add 新元素 — 新元素可能复用刚 recycle 那个 Node 实例, 读到新元素 key 但跳过原始前面的全部元素, 更隐蔽</li>
     *     </ul>
     *   </li>
     * </ul>
     */
    public void forEachReversed(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        for (RecycleLinked.Node<E, Object> n = this.map.tail; n != null; ) {
            E e = n.key;
            n = n.prev;
            action.accept(e);
        }
    }

    /* ============================== removeAll / retainAll / removeIf 覆写 ============================== */
    /*
     * 默认 KeyIterator 不实现 remove(), AbstractSet 继承的 removeAll/retainAll 走 it.remove() 会抛
     * UnsupportedOperationException; Collection.removeIf 默认实现也走 it.remove(). 这里覆写直接调
     * map.remove(key), 跳过 iterator 路径。
     */

    /**
     * O(c) — c=入参集合大小; 每次 map.remove 均摊 O(1)
     */
    @Override
    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        boolean modified = false;
        for (Object o : c) {
            if (this.map.remove(o) != null) modified = true;
        }
        return modified;
    }

    /**
     * O(n × T_contains) — 遍历自身 n 个节点, 每个调 c.contains; c 为 HashSet 时 O(n)
     */
    @Override
    public boolean retainAll(Collection<?> c) {
        Objects.requireNonNull(c);
        boolean modified = false;
        RecycleLinked.Node<E, Object> n = this.map.head;
        while (n != null) {
            RecycleLinked.Node<E, Object> next = n.next;
            if (!c.contains(n.key)) {
                this.map.remove(n.key);
                modified = true;
            }
            n = next;
        }
        return modified;
    }

    /**
     * O(n) — 遍历整链, 命中元素调 map.remove (均摊 O(1))
     */
    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter);
        boolean modified = false;
        RecycleLinked.Node<E, Object> n = this.map.head;
        while (n != null) {
            RecycleLinked.Node<E, Object> next = n.next;
            if (filter.test(n.key)) {
                this.map.remove(n.key);
                modified = true;
            }
            n = next;
        }
        return modified;
    }

    // ==================== 零分配遍历 ====================

    /**
     * O(1) — 复用缓存 iterator, reset 到 head; 完整遍历 O(n).
     * <p>
     * 零分配 key 遍历. 调用者约束: 单线程、不嵌套同实例遍历.
     */
    public Iterator<E> cachedIterator() {
        return this.map.keyIterator();
    }

    // ==================== 工厂方法 ====================

    /**
     * O(1) — 池命中 O(1); 池空时 newObject + map 初始化 O(1)
     */
    public static <E> RecycleLinkedSet<E> of() {
        return (RecycleLinkedSet<E>) POOL.get();
    }

    /**
     * O(c) — c=入参集合大小; 逐个 add 均摊 O(1)
     */
    public static <E> RecycleLinkedSet<E> of(Collection<E> c) {
        RecycleLinkedSet<E> set = of();
        if (c != null) set.addAll(c);
        return set;
    }

    /**
     * O(1)
     */
    public static <E> RecycleLinkedSet<E> of(E e1) {
        RecycleLinkedSet<E> set = of();
        set.add(e1);
        return set;
    }

    /**
     * O(1) — 共 2 次 add
     */
    public static <E> RecycleLinkedSet<E> of(E e1, E e2) {
        RecycleLinkedSet<E> set = of(e1);
        set.add(e2);
        return set;
    }

    /**
     * O(1) — 共 3 次 add
     */
    public static <E> RecycleLinkedSet<E> of(E e1, E e2, E e3) {
        RecycleLinkedSet<E> set = of(e1, e2);
        set.add(e3);
        return set;
    }

    /**
     * O(1) — 共 4 次 add
     */
    public static <E> RecycleLinkedSet<E> of(E e1, E e2, E e3, E e4) {
        RecycleLinkedSet<E> set = of(e1, e2, e3);
        set.add(e4);
        return set;
    }

    /**
     * O(1) — 共 5 次 add
     */
    public static <E> RecycleLinkedSet<E> of(E e1, E e2, E e3, E e4, E e5) {
        RecycleLinkedSet<E> set = of(e1, e2, e3, e4);
        set.add(e5);
        return set;
    }

    /**
     * O(1) — 共 6 次 add
     */
    public static <E> RecycleLinkedSet<E> of(E e1, E e2, E e3, E e4, E e5, E e6) {
        RecycleLinkedSet<E> set = of(e1, e2, e3, e4, e5);
        set.add(e6);
        return set;
    }

    /**
     * O(1) — 共 7 次 add
     */
    public static <E> RecycleLinkedSet<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7) {
        RecycleLinkedSet<E> set = of(e1, e2, e3, e4, e5, e6);
        set.add(e7);
        return set;
    }

    /**
     * O(1) — 共 8 次 add
     */
    public static <E> RecycleLinkedSet<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8) {
        RecycleLinkedSet<E> set = of(e1, e2, e3, e4, e5, e6, e7);
        set.add(e8);
        return set;
    }

    /**
     * O(1) — 共 9 次 add
     */
    public static <E> RecycleLinkedSet<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8, E e9) {
        RecycleLinkedSet<E> set = of(e1, e2, e3, e4, e5, e6, e7, e8);
        set.add(e9);
        return set;
    }

    /**
     * O(1) — 共 10 次 add
     */
    public static <E> RecycleLinkedSet<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8, E e9, E e10) {
        RecycleLinkedSet<E> set = of(e1, e2, e3, e4, e5, e6, e7, e8, e9);
        set.add(e10);
        return set;
    }

    /**
     * O(1) — 直接抛, 不支持
     */
    @Override
    public Object clone() {
        throw new UnsupportedOperationException("RecycleLinkedSet does not support clone, use of() + addAll instead");
    }
}
