package io.github.nasaruntime.core.base;

import io.github.nasaruntime.core.utils.ContextUtils;

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
        /**
         * 业务作用：池空时创建新实例。
         *
         * 参数说明: 无。
         * 返回: 字段均为初始值的新实例。
         */
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
     * 业务作用：构造实例。字段取默认值。
     *
     * 参数说明: 无。
     * 返回: 构造完成后可直接使用的实例。
     */
    public RecycleLinkedSet() {
        this.map = new RecycleLinkedMap<>();
    }

    // ==================== ObjectPool.Recycler ====================

    /**
     * 业务作用：暴露池化身份，启用重复归池的 CAS 防御。
     *
     * 参数说明: 无。
     * 返回: 构造时绑定本类对象池的 handle。
     */
    @Override
    public ObjectPool.PooledHandle<RecycleLinkedSet<E>> handle() {
        return this.handle;
    }

    /**
     * 业务作用：O(n) — 委托 map.restore, 触发节点链批量回收
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    @Override
    public void restore() {
        // 只清数据，不归池 map 本身（map 跟随 set 实例复用）
        this.map.restore();
    }

    // ==================== Set 核心操作 ====================

    /**
     * 业务作用：报告当前元素个数，供容量观测与遍历前的预分配。
     *
     * 参数说明: 无。
     * 返回: 元素个数；并发下为弱一致结果，仅反映采样瞬间的状态。
     */
    @Override
    public int size() {
        return this.map.size();
    }

    /**
     * 业务作用：判断容器当前是否为空。
     *
     * 参数说明: 无。
     * 返回: 为空返回 true；并发下为弱一致结果，仅反映采样瞬间的状态。不能作为后续操作必定成功的依据。
     */
    @Override
    public boolean isEmpty() {
        return this.map.isEmpty();
    }

    /**
     * 业务作用：O(1) 均摊, O(n) 最差 — hash bucket 查找, 冲突链长时退化
     *
     * @param o 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    @Override
    public boolean contains(Object o) {
        return this.map.containsKey(o);
    }

    /**
     * 业务作用：O(1) 均摊, O(n) 最差 — hash bucket 插入, 触发 resize 时该次为 O(n)
     *
     * @param e 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    @Override
    public boolean add(E e) {
        return this.map.put(e, PRESENT) == null;
    }

    /**
     * 业务作用：O(1) 均摊, O(n) 最差 — hash bucket 查找+解链
     *
     * @param o 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    @Override
    public boolean remove(Object o) {
        return this.map.remove(o) == PRESENT;
    }

    /**
     * 业务作用：移除全部元素，解除容器对它们的强引用后交给 GC。
     *
     * 参数说明: 无。
     * 返回: 无返回值；并发写入仍在进行时不保证返回后容器为空。
     */
    @Override
    public void clear() {
        this.map.clear();
    }

    /**
     * 业务作用：提供弱一致迭代器，遍历期间允许并发修改，不抛 ConcurrentModificationException。
     *
     * 参数说明: 无。
     * 返回: 弱一致迭代器；遍历中被并发移除的元素可能出现也可能不出现。
     */
    @Override
    public Iterator<E> iterator() {
        return this.map.keySet().iterator();
    }

    /**
     * 业务作用：遍历当前元素并逐个交给回调，遍历不阻塞并发读写。
     *
     * @param action 对每个元素执行的动作
     * 返回: 无返回值；回调抛出的异常直接向上传播，遍历随即中断。
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
     * 业务作用：从 tail 向 head 反向遍历, 零 GC, 单线程.
     * <p>
     * 跟 {@link #forEach} 完全对称, 同样采用游标前置技巧. 安全边界对称:
     * <ul>
     * <li><b>安全</b>: action 内 {@code set.remove(currentElement)} / {@code set.add(...)}
     * (新元素挂 tail, 不在反向迭代路径上) / 删除远处尚未访问的元素 (非紧邻 cursor.prev)</li>
     * <li><b>不安全 (静默错乱)</b>:
     * <ul>
     * <li>{@link #clear()} / {@link #recycle()} 整个 set</li>
     * <li>删除 <b>cursor 指向的 prev 节点</b> — 下一轮 action 多调一次 null, 然后 prev=null 提前终止, 跳过该节点之前的全部元素</li>
     * <li>remove + 同 action 内 add 新元素 — 新元素可能复用刚 recycle 那个 Node 实例, 读到新元素 key 但跳过原始前面的全部元素, 更隐蔽</li>
     * </ul>
     * </li>
     * </ul>
     *
     * @param action 见上述说明
     * 返回: 无返回值。
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
     * 业务作用：移除本容器中出现在给定集合里的全部元素。
     *
     * @param c 待移除元素的集合
     * 返回: 本容器发生变化返回 true。
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
     * 业务作用：只保留同时出现在给定集合中的元素，其余全部移除。
     *
     * @param c 需要保留的元素集合
     * 返回: 本容器发生变化返回 true。
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
     * 业务作用：按条件批量移除元素。
     *
     * @param filter 命中即移除的条件
     * 返回: 移除过至少一个元素返回 true；并发修改下不保证条件对最终状态成立。
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
     * 业务作用：复用缓存的迭代器实例并重置到头部，避免每次遍历都分配一个新迭代器。同一容器上不得同时进行两次遍历，否则两者会共用同一个游标。
     *
     * 参数说明: 无。
     * 返回: 已重置到头部的迭代器。
     */
    public Iterator<E> cachedIterator() {
        return this.map.keyIterator();
    }

    // ==================== 工厂方法 ====================

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * 参数说明: 无。
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <E> RecycleLinkedSet<E> of() {
        return (RecycleLinkedSet<E>) POOL.get();
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param c 源集合
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <E> RecycleLinkedSet<E> of(Collection<E> c) {
        RecycleLinkedSet<E> set = of();
        if (c != null) set.addAll(c);
        return set;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param e1 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <E> RecycleLinkedSet<E> of(E e1) {
        RecycleLinkedSet<E> set = of();
        set.add(e1);
        return set;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param e1 见上述说明
     * @param e2 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <E> RecycleLinkedSet<E> of(E e1, E e2) {
        RecycleLinkedSet<E> set = of(e1);
        set.add(e2);
        return set;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param e1 见上述说明
     * @param e2 见上述说明
     * @param e3 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <E> RecycleLinkedSet<E> of(E e1, E e2, E e3) {
        RecycleLinkedSet<E> set = of(e1, e2);
        set.add(e3);
        return set;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param e1 见上述说明
     * @param e2 见上述说明
     * @param e3 见上述说明
     * @param e4 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <E> RecycleLinkedSet<E> of(E e1, E e2, E e3, E e4) {
        RecycleLinkedSet<E> set = of(e1, e2, e3);
        set.add(e4);
        return set;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param e1 见上述说明
     * @param e2 见上述说明
     * @param e3 见上述说明
     * @param e4 见上述说明
     * @param e5 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <E> RecycleLinkedSet<E> of(E e1, E e2, E e3, E e4, E e5) {
        RecycleLinkedSet<E> set = of(e1, e2, e3, e4);
        set.add(e5);
        return set;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param e1 见上述说明
     * @param e2 见上述说明
     * @param e3 见上述说明
     * @param e4 见上述说明
     * @param e5 见上述说明
     * @param e6 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <E> RecycleLinkedSet<E> of(E e1, E e2, E e3, E e4, E e5, E e6) {
        RecycleLinkedSet<E> set = of(e1, e2, e3, e4, e5);
        set.add(e6);
        return set;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param e1 见上述说明
     * @param e2 见上述说明
     * @param e3 见上述说明
     * @param e4 见上述说明
     * @param e5 见上述说明
     * @param e6 见上述说明
     * @param e7 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <E> RecycleLinkedSet<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7) {
        RecycleLinkedSet<E> set = of(e1, e2, e3, e4, e5, e6);
        set.add(e7);
        return set;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param e1 见上述说明
     * @param e2 见上述说明
     * @param e3 见上述说明
     * @param e4 见上述说明
     * @param e5 见上述说明
     * @param e6 见上述说明
     * @param e7 见上述说明
     * @param e8 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <E> RecycleLinkedSet<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8) {
        RecycleLinkedSet<E> set = of(e1, e2, e3, e4, e5, e6, e7);
        set.add(e8);
        return set;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param e1 见上述说明
     * @param e2 见上述说明
     * @param e3 见上述说明
     * @param e4 见上述说明
     * @param e5 见上述说明
     * @param e6 见上述说明
     * @param e7 见上述说明
     * @param e8 见上述说明
     * @param e9 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <E> RecycleLinkedSet<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8, E e9) {
        RecycleLinkedSet<E> set = of(e1, e2, e3, e4, e5, e6, e7, e8);
        set.add(e9);
        return set;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param e1 见上述说明
     * @param e2 见上述说明
     * @param e3 见上述说明
     * @param e4 见上述说明
     * @param e5 见上述说明
     * @param e6 见上述说明
     * @param e7 见上述说明
     * @param e8 见上述说明
     * @param e9 见上述说明
     * @param e10 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <E> RecycleLinkedSet<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8, E e9, E e10) {
        RecycleLinkedSet<E> set = of(e1, e2, e3, e4, e5, e6, e7, e8, e9);
        set.add(e10);
        return set;
    }

    /**
     * 业务作用：本容器池化，浅拷贝会让两个实例共享同一批池化节点，任一方归池都会让另一方读到已回收数据，因此不提供该语义。
     *
     * 参数说明: 无。
     * 返回: 不返回，恒抛 UnsupportedOperationException。
     */
    @Override
    public Object clone() {
        throw new UnsupportedOperationException("RecycleLinkedSet does not support clone, use of() + addAll instead");
    }
}
