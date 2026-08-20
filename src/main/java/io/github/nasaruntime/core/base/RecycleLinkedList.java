package io.github.nasaruntime.core.base;

import io.github.nasaruntime.core.utils.ContextUtils;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 节点可回收的双向链表。
 * 移除的 Node 不交给 GC，而是放入内部对象池复用，稳态零 Node 分配。
 * 非线程安全，适用于由单线程独占访问的高频处理路径。
 *
 * <h2>时间复杂度</h2>
 * <pre>
 *   ┌──────────────────────────────────────┬──────────────────┐
 *   │ 方法                                  │ 时间复杂度        │
 *   ├──────────────────────────────────────┼──────────────────┤
 *   │ addFirst / addLast / offer / push    │ O(1)             │
 *   │ removeFirst / removeLast / poll / pop│ O(1)             │
 *   │ getFirst / getLast / peek            │ O(1)             │
 *   │ get(index) / set(index)              │ O(n)             │
 *   │ add(index) / remove(index)           │ O(n)             │
 *   │ contains / indexOf                   │ O(n)             │
 *   │ clear                                │ O(n) 逐节点回收    │
 *   └──────────────────────────────────────┴──────────────────┘
 * </pre>
 *
 * <h2>遍历语义 (不维护 modCount, 无 CME)</h2>
 * {@link #forEach} / {@link #forEachReversed} 采用游标前置技巧,
 * action 内 {@code list.remove(currentElement)} 安全;
 * 但 {@link #clear} / {@link #recycle} 或删尚未访问的后续元素会静默错乱。详见方法 API docs。
 */
@SuppressWarnings("all")
public class RecycleLinkedList<E> extends AbstractSequentialList<E>
        implements List<E>, Deque<E>, RecycleLinked<E, Void>, ObjectPool.Recycler<RecycleLinkedList<E>> {

    public static final Consumer<RecycleLinkedList> RECY_CON = RecycleLinkedList::recycle;
    public static final BiConsumer<String, RecycleLinkedList> RECY_BICON = (BiConsumer<String, RecycleLinkedList>) (s, r) -> r.recycle();

    static final ObjectPool<RecycleLinkedList<Object>> POOL = new ObjectPool<>(
            ContextUtils.getPropertyInt("nasa.object-pool.recycle-linked-list-capacity", 1000)) {
        /**
         * 业务作用：池空时创建新实例。
         *
         * 参数说明: 无。
         * 返回: 字段均为初始值的新实例。
         */
        @Override
        public RecycleLinkedList<Object> newObject() {
            return new RecycleLinkedList<>();
        }
    };

    private final ObjectPool.PooledHandle<RecycleLinkedList<E>> handle =
            (ObjectPool.PooledHandle) new ObjectPool.PooledHandle<>(POOL);

    // ==================== ObjectPool.Recycler ====================

    /**
     * 业务作用：暴露池化身份，启用重复归池的 CAS 防御。
     *
     * 参数说明: 无。
     * 返回: 构造时绑定本类对象池的 handle。
     */
    @Override
    public ObjectPool.PooledHandle<RecycleLinkedList<E>> handle() {
        return this.handle;
    }

    /**
     * 业务作用：O(n) — 委托 clear, 逐节点回收
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    @Override
    public void restore() {
        clear();
    }

    transient Node<E, Void> head;
    transient Node<E, Void> tail;
    transient int size;

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * 参数说明: 无。
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <E> RecycleLinkedList<E> of() {
        return (RecycleLinkedList<E>) POOL.get();
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param e1 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <E> RecycleLinkedList<E> of(E e1) {
        RecycleLinkedList<E> list = of();
        list.add(e1);
        return list;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param e1 见上述说明
     * @param e2 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <E> RecycleLinkedList<E> of(E e1, E e2) {
        RecycleLinkedList<E> list = of(e1);
        list.add(e2);
        return list;
    }

    /**
     * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
     *
     * @param e1 见上述说明
     * @param e2 见上述说明
     * @param e3 见上述说明
     * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
     */
    public static <E> RecycleLinkedList<E> of(E e1, E e2, E e3) {
        RecycleLinkedList<E> list = of(e1, e2);
        list.add(e3);
        return list;
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
    public static <E> RecycleLinkedList<E> of(E e1, E e2, E e3, E e4) {
        RecycleLinkedList<E> list = of(e1, e2, e3);
        list.add(e4);
        return list;
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
    public static <E> RecycleLinkedList<E> of(E e1, E e2, E e3, E e4, E e5) {
        RecycleLinkedList<E> list = of(e1, e2, e3, e4);
        list.add(e5);
        return list;
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
    public static <E> RecycleLinkedList<E> of(E e1, E e2, E e3, E e4, E e5, E e6) {
        RecycleLinkedList<E> list = of(e1, e2, e3, e4, e5);
        list.add(e6);
        return list;
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
    public static <E> RecycleLinkedList<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7) {
        RecycleLinkedList<E> list = of(e1, e2, e3, e4, e5, e6);
        list.add(e7);
        return list;
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
    public static <E> RecycleLinkedList<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8) {
        RecycleLinkedList<E> list = of(e1, e2, e3, e4, e5, e6, e7);
        list.add(e8);
        return list;
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
    public static <E> RecycleLinkedList<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8, E e9) {
        RecycleLinkedList<E> list = of(e1, e2, e3, e4, e5, e6, e7, e8);
        list.add(e9);
        return list;
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
    public static <E> RecycleLinkedList<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8, E e9, E e10) {
        RecycleLinkedList<E> list = of(e1, e2, e3, e4, e5, e6, e7, e8, e9);
        list.add(e10);
        return list;
    }

    // ==================== Node 回收 ====================

    /**
     * 业务作用：借出一个链表节点。节点自身也走对象池，使增删元素不产生节点垃圾。
     *
     * @param item 见上述说明
     * 返回: 已绑定键值的节点。
     */
    private Node<E, Void> newNode(E item) {
        Node<E, Void> node = Node.of();
        node.key = item;
        return node;
    }

    // ==================== Link / Unlink ====================

    /**
     * 业务作用：O(1) — 改 head/tail 指针 + size++
     *
     * @param node 见上述说明
     * 返回: 无返回值。
     */
    private void linkFirst(Node<E, Void> node) {
        Node<E, Void> h = head;
        node.prev = null;
        node.next = h;
        head = node;
        if (h == null) {
            tail = node;
        } else {
            h.prev = node;
        }
        size++;
    }

    /**
     * 业务作用：O(1) — 改 head/tail 指针 + size++
     *
     * @param node 见上述说明
     * 返回: 无返回值。
     */
    private void linkLast(Node<E, Void> node) {
        Node<E, Void> t = tail;
        node.next = null;
        node.prev = t;
        tail = node;
        if (t == null) {
            head = node;
        } else {
            t.next = node;
        }
        size++;
    }

    /**
     * 业务作用：O(1) — 三向指针重连 + size++
     *
     * @param node 见上述说明
     * @param succ 见上述说明
     * 返回: 无返回值。
     */
    private void linkBefore(Node<E, Void> node, Node<E, Void> succ) {
        Node<E, Void> pred = succ.prev;
        node.prev = pred;
        node.next = succ;
        succ.prev = node;
        if (pred == null) {
            head = node;
        } else {
            pred.next = node;
        }
        size++;
    }

    /**
     * 业务作用：把节点从双向链表中摘除并归还节点对象，同时维护长度计数。
     *
     * @param node 目标节点
     * 返回: 无返回值；节点归池后不得再被引用。
     */
    private E unlink(Node<E, Void> node) {
        E item = node.key;
        Node<E, Void> p = node.prev;
        Node<E, Void> n = node.next;
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
        size--;
        node.recycle();
        return item;
    }

    /**
     * 业务作用：按下标定位节点。从头尾中较近的一端开始扫描，把平均扫描距离降到长度的一半。
     *
     * @param index 下标
     * 返回: 该下标处的节点；下标越界时抛出 IndexOutOfBoundsException。
     */
    private Node<E, Void> nodeAt(int index) {
        if (index < (size >> 1)) {
            Node<E, Void> x = head;
            for (int i = 0; i < index; i++) x = x.next;
            return x;
        } else {
            Node<E, Void> x = tail;
            for (int i = size - 1; i > index; i--) x = x.prev;
            return x;
        }
    }

    /**
     * 业务作用：O(1) — 边界比较
     *
     * @param index 见上述说明
     * 返回: 无返回值。
     */
    private void checkElementIndex(int index) {
        if (index < 0 || index >= size)
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
    }

    /**
     * 业务作用：O(1) — 边界比较
     *
     * @param index 见上述说明
     * 返回: 无返回值。
     */
    private void checkPositionIndex(int index) {
        if (index < 0 || index > size)
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
    }

    // ==================== Deque 头尾操作 ====================

    /**
     * 业务作用：O(1) — head 链入
     *
     * @param e 见上述说明
     * 返回: 无返回值。
     */
    @Override
    public void addFirst(E e) {
        linkFirst(newNode(e));
    }

    /**
     * 业务作用：O(1) — tail 链入
     *
     * @param e 见上述说明
     * 返回: 无返回值。
     */
    @Override
    public void addLast(E e) {
        linkLast(newNode(e));
    }

    /**
     * 业务作用：O(1) — 委托 addFirst
     *
     * @param e 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    @Override
    public boolean offerFirst(E e) {
        addFirst(e);
        return true;
    }

    /**
     * 业务作用：O(1) — 委托 addLast
     *
     * @param e 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    @Override
    public boolean offerLast(E e) {
        addLast(e);
        return true;
    }

    /**
     * 业务作用：取出并移除首个元素。
     *
     * 参数说明: 无。
     * 返回: 被移除的首元素；容器为空时抛出 NoSuchElementException。
     */
    @Override
    public E removeFirst() {
        if (head == null) throw new NoSuchElementException();
        return unlink(head);
    }

    /**
     * 业务作用：取出并移除末个元素。
     *
     * 参数说明: 无。
     * 返回: 被移除的末元素；容器为空时抛出 NoSuchElementException。
     */
    @Override
    public E removeLast() {
        if (tail == null) throw new NoSuchElementException();
        return unlink(tail);
    }

    /**
     * 业务作用：取出并移除首个元素，空容器不抛异常。
     *
     * 参数说明: 无。
     * 返回: 被移除的首元素；容器为空时返回 null。
     */
    @Override
    public E pollFirst() {
        return head == null ? null : unlink(head);
    }

    /**
     * 业务作用：取出并移除末个元素，空容器不抛异常。
     *
     * 参数说明: 无。
     * 返回: 被移除的末元素；容器为空时返回 null。
     */
    @Override
    public E pollLast() {
        return tail == null ? null : unlink(tail);
    }

    /**
     * 业务作用：读取首个元素但不移除。
     *
     * 参数说明: 无。
     * 返回: 首元素；容器为空时抛出 NoSuchElementException。
     */
    @Override
    public E getFirst() {
        if (head == null) throw new NoSuchElementException();
        return head.key;
    }

    /**
     * 业务作用：读取末个元素但不移除。
     *
     * 参数说明: 无。
     * 返回: 末元素；容器为空时抛出 NoSuchElementException。
     */
    @Override
    public E getLast() {
        if (tail == null) throw new NoSuchElementException();
        return tail.key;
    }

    /**
     * 业务作用：读取首个元素但不移除，空容器不抛异常。
     *
     * 参数说明: 无。
     * 返回: 首元素；容器为空时返回 null。
     */
    @Override
    public E peekFirst() {
        return head == null ? null : head.key;
    }

    /**
     * 业务作用：读取末个元素但不移除，空容器不抛异常。
     *
     * 参数说明: 无。
     * 返回: 末元素；容器为空时返回 null。
     */
    @Override
    public E peekLast() {
        return tail == null ? null : tail.key;
    }

    /**
     * 业务作用：O(n) — 自 head 线性扫描首个匹配
     *
     * @param o 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    @Override
    public boolean removeFirstOccurrence(Object o) {
        for (Node<E, Void> x = head; x != null; x = x.next) {
            if (Objects.equals(o, x.key)) {
                unlink(x);
                return true;
            }
        }
        return false;
    }

    /**
     * 业务作用：O(n) — 自 tail 反向线性扫描首个匹配
     *
     * @param o 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    @Override
    public boolean removeLastOccurrence(Object o) {
        for (Node<E, Void> x = tail; x != null; x = x.prev) {
            if (Objects.equals(o, x.key)) {
                unlink(x);
                return true;
            }
        }
        return false;
    }

    // ==================== Queue / Stack ====================

    /**
     * 业务作用：O(1) — 委托 offerLast
     *
     * @param e 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    @Override
    public boolean offer(E e) {
        return offerLast(e);
    }

    /**
     * 业务作用：移除最近一次 next 或 previous 返回的元素。
     *
     * 参数说明: 无。
     * 返回: 无返回值；尚未调用过 next/previous 时抛出 IllegalStateException。
     */
    @Override
    public E remove() {
        return removeFirst();
    }

    /**
     * 业务作用：取出并移除首个元素，空容器不抛异常。
     *
     * 参数说明: 无。
     * 返回: 首个元素；容器为空时返回 null。
     */
    @Override
    public E poll() {
        return pollFirst();
    }

    /**
     * 业务作用：读取首个元素但不移除。
     *
     * 参数说明: 无。
     * 返回: 首个元素；容器为空时抛出 NoSuchElementException。
     */
    @Override
    public E element() {
        return getFirst();
    }

    /**
     * 业务作用：读取首个元素但不移除，空容器不抛异常。
     *
     * 参数说明: 无。
     * 返回: 首个元素；容器为空时返回 null。
     */
    @Override
    public E peek() {
        return peekFirst();
    }

    /**
     * 业务作用：O(1) — 委托 addFirst
     *
     * @param e 见上述说明
     * 返回: 无返回值。
     */
    @Override
    public void push(E e) {
        addFirst(e);
    }

    /**
     * 业务作用：弹出并返回首个元素，用于把容器当栈或队列使用。
     *
     * 参数说明: 无。
     * 返回: 首个元素；容器为空时抛出 NoSuchElementException。
     */
    @Override
    public E pop() {
        return removeFirst();
    }

    // ==================== List 核心操作（直接实现，避免走 ListIterator） ====================

    /**
     * 业务作用：报告当前元素个数，供容量观测与遍历前的预分配。
     *
     * 参数说明: 无。
     * 返回: 元素个数；并发下为弱一致结果，仅反映采样瞬间的状态。
     */
    @Override
    public int size() {
        return size;
    }

    /**
     * 业务作用：在当前游标位置插入元素，游标随之后移。
     *
     * @param e 待插入元素
     * 返回: 无返回值。
     */
    @Override
    public boolean add(E e) {
        linkLast(newNode(e));
        return true;
    }

    /**
     * 业务作用：按下标读取元素。
     *
     * @param index 元素下标
     * 返回: 该位置的元素；下标越界时抛出 IndexOutOfBoundsException。
     */
    @Override
    public E get(int index) {
        checkElementIndex(index);
        return nodeAt(index).key;
    }

    /**
     * 业务作用：替换指定下标处的元素。
     *
     * @param index 元素下标
     * @param element 见方法语义
     * 返回: 被替换掉的旧元素；下标越界时抛出 IndexOutOfBoundsException。
     */
    @Override
    public E set(int index, E element) {
        checkElementIndex(index);
        Node<E, Void> x = nodeAt(index);
        E old = x.key;
        x.key = element;
        return old;
    }

    /**
     * 业务作用：O(n/2) ≈ O(n) — 末尾插入 O(1), 中间插入需 nodeAt 定位
     *
     * @param index 见上述说明
     * @param element 见上述说明
     * 返回: 无返回值。
     */
    @Override
    public void add(int index, E element) {
        checkPositionIndex(index);
        if (index == size) {
            linkLast(newNode(element));
        } else {
            linkBefore(newNode(element), nodeAt(index));
        }
    }

    /**
     * 业务作用：按值移除首个匹配元素。注意与按下标移除的重载区分：入参为 Object 时走本方法。
     *
     * @param index 见方法语义
     * 返回: 移除成功返回 true；未找到返回 false。
     */
    @Override
    public E remove(int index) {
        checkElementIndex(index);
        return unlink(nodeAt(index));
    }

    /**
     * 业务作用：O(n) — 委托 removeFirstOccurrence 线性扫描
     *
     * @param o 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    @Override
    public boolean remove(Object o) {
        return removeFirstOccurrence(o);
    }

    /**
     * 业务作用：从头查找元素首次出现的位置。
     *
     * @param o 待查找元素
     * 返回: 首个匹配下标；不存在时返回 -1。
     */
    @Override
    public int indexOf(Object o) {
        int index = 0;
        for (Node<E, Void> x = head; x != null; x = x.next) {
            if (Objects.equals(o, x.key)) return index;
            index++;
        }
        return -1;
    }

    /**
     * 业务作用：O(n) — 委托 indexOf 线性扫描
     *
     * @param o 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    @Override
    public boolean contains(Object o) {
        return indexOf(o) >= 0;
    }

    /**
     * 业务作用：移除全部元素，解除容器对它们的强引用后交给 GC。
     *
     * 参数说明: 无。
     * 返回: 无返回值；并发写入仍在进行时不保证返回后容器为空。
     */
    @Override
    public void clear() {
        Node<E, Void> x = head;
        while (x != null) {
            Node<E, Void> next = x.next;
            x.recycle();
            x = next;
        }
        head = tail = null;
        size = 0;
    }

    /**
     * 业务作用：清空容器自身字段并把整条节点链交还给调用方，节点的归池责任随之移交。用于调用方需要复用或延迟释放节点链的场景。
     *
     * 参数说明: 无。
     * 返回: 原来的头节点；调用方负责逐个归还这些节点。
     */
    @Override
    public Node<E, Void> clearRHead() {
        Node<E, Void> x = head;
        head = tail = null;
        size = 0;
        return x;
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
        for (Node<E, Void> x = head; x != null; ) {
            E e = x.key;
            x = x.next;
            action.accept(e);
        }
    }

    /**
     * 业务作用：从 tail 向 head 反向遍历, 单线程, 零 GC.
     * <p>
     * 同样采用 <b>游标前置</b>: 每轮先取出 {@code x.key} 和 {@code x.prev}, 再调 action.
     * action 内 {@code list.remove(currentElement)} 把当前节点 unlink 并回池, 游标早已指向上一个有效节点.
     * <p>
     * <b>安全</b>:
     * <ul>
     * <li>action 内 {@code list.remove(currentElement)} — 删当前已访问的元素</li>
     * <li>action 内 {@link #removeLast()} 等删<b>已访问过</b>的尾部元素</li>
     * <li>action 内 {@link #add(Object)} / {@link #addLast(Object)} 追加到末尾 (新 tail 不在反向迭代路径上, 不影响)</li>
     * <li>action 内删除远处尚未访问的元素 (非紧邻 cursor 的 prev 那个) — 解链时前后指针会重新连接, 跳过即可</li>
     * </ul>
     * <b>不安全 (静默错乱, 无 fail-fast)</b>:
     * <ul>
     * <li>action 内 {@link #clear()} / {@link #recycle()} 整个 list —
     * 已前进的游标会落到已回收节点上, 后续 prev 读到错乱数据</li>
     * <li>action 内删除 <b>cursor 指向的 prev 节点</b> (即反向迭代"下一轮要访问的元素") —
     * 该节点 recycle 后字段全 null, 下一轮会:
     * (a) 多调一次 {@code action.accept(null)},
     * (b) 因 {@code x.prev=null} 提前终止循环, 跳过该节点之前的全部元素.</li>
     * <li>action 内 remove + 同方法体 add 新元素 — 新元素可能复用刚 recycle 那个 Node 实例,
     * 下一轮读到新元素 key, 行为"看似正常"但跳过了原始前面的全部元素, 比静默 null 更隐蔽.</li>
     * <li>action 内 {@link #addFirst(Object)} 插到 head — 不会读到新节点 (head 已在迭代末尾), 但语义不对称, 不建议</li>
     * </ul>
     * 跟 {@link #forEach} 完全对称, 类设计同样不带 modCount, 无 ConcurrentModificationException 保护.
     *
     * @param action 见上述说明
     * 返回: 无返回值。
     */
    public void forEachReversed(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        for (Node<E, Void> x = tail; x != null; ) {
            E e = x.key;
            x = x.prev;
            action.accept(e);
        }
    }

// ==================== ListIterator ====================

    /**
     * 缓存的 iterator 实例, 仅给 {@link #cachedIterator()} 高频热路径用。
     * 默认的 {@link #iterator()} 不读它, 每次返回新实例符合 JDK 集合契约。
     */
    private RecycleListIterator cachedIterator;

    /**
     * 业务作用：提供弱一致迭代器，遍历期间允许并发修改，不抛 ConcurrentModificationException。
     *
     * 参数说明: 无。
     * 返回: 弱一致迭代器；遍历中被并发移除的元素可能出现也可能不出现。
     */
    @Override
    public Iterator<E> iterator() {
        return new RecycleListIterator(0);
    }

    /**
     * 业务作用：复用缓存的迭代器实例并重置到头部，避免每次遍历都分配一个新迭代器。同一容器上不得同时进行两次遍历，否则两者会共用同一个游标。
     *
     * 参数说明: 无。
     * 返回: 已重置到头部的迭代器。
     */
    public Iterator<E> cachedIterator() {
        if (cachedIterator == null) {
            cachedIterator = new RecycleListIterator(0);
        } else {
            cachedIterator.reset(0);
        }
        return cachedIterator;
    }

    /**
     * 业务作用：从指定下标开始提供支持双向遍历与原地修改的迭代器。
     *
     * @param index 起始下标
     * 返回: 弱一致列表迭代器。
     */
    @Override
    public ListIterator<E> listIterator(int index) {
        checkPositionIndex(index);
        return new RecycleListIterator(index);
    }

    class RecycleListIterator implements ListIterator<E> {
        private Node<E, Void> lastReturned;
        private Node<E, Void> next;
        private int nextIndex;

        /**
         * 业务作用：按给定参数构造 RecycleListIterator 实例。
         *
         * @param index 下标
         * 返回: 构造完成后可直接使用的实例。
         */
        RecycleListIterator(int index) {
            next = (index == size) ? null : nodeAt(index);
            nextIndex = index;
        }

        /**
         * 业务作用：O(n/2) ≈ O(n) — 复用实例时调, nodeAt 重新定位; index==0 或 size 时 O(1)
         *
         * @param index 见上述说明
         * 返回: 无返回值。
         */
        void reset(int index) {
            lastReturned = null;
            next = (index == size) ? null : (size == 0 ? null : nodeAt(index));
            nextIndex = index;
        }

        /**
         * 业务作用：判断迭代是否还有下一个元素；必要时先向前探测一格。
         *
         * 参数说明: 无。
         * 返回: 还有元素返回 true。
         */
        @Override
        public boolean hasNext() {
            return nextIndex < size;
        }

        /**
         * 业务作用：返回下一个元素并前移游标。
         *
         * 参数说明: 无。
         * 返回: 下一个元素；已到末尾时抛出 NoSuchElementException。
         */
        @Override
        public E next() {
            if (!hasNext()) throw new NoSuchElementException();
            lastReturned = next;
            next = next.next;
            nextIndex++;
            return lastReturned.key;
        }

        /**
         * 业务作用：判断反向迭代是否还有上一个元素。
         *
         * 参数说明: 无。
         * 返回: 还有元素返回 true。
         */
        @Override
        public boolean hasPrevious() {
            return nextIndex > 0;
        }

        /**
         * 业务作用：返回上一个元素并后移游标。
         *
         * 参数说明: 无。
         * 返回: 上一个元素；已到开头时抛出 NoSuchElementException。
         */
        @Override
        public E previous() {
            if (!hasPrevious()) throw new NoSuchElementException();
            lastReturned = next = (next == null) ? tail : next.prev;
            nextIndex--;
            return lastReturned.key;
        }

        /**
         * 业务作用：给出下一次 next 将返回的元素下标。
         *
         * 参数说明: 无。
         * 返回: 下一个元素的下标；已到末尾时等于列表长度。
         */
        @Override
        public int nextIndex() {
            return nextIndex;
        }

        /**
         * 业务作用：给出下一次 previous 将返回的元素下标。
         *
         * 参数说明: 无。
         * 返回: 上一个元素的下标；已到开头时为 -1。
         */
        @Override
        public int previousIndex() {
            return nextIndex - 1;
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
            Node<E, Void> lastNext = lastReturned.next;
            unlink(lastReturned);
            if (next == lastReturned) {
                next = lastNext;
            } else {
                nextIndex--;
            }
            lastReturned = null;
        }

        /**
         * 业务作用：替换最近一次 next 或 previous 返回的元素。
         *
         * @param e 新元素
         * 返回: 无返回值；尚未调用过 next/previous 时抛出 IllegalStateException。
         */
        @Override
        public void set(E e) {
            if (lastReturned == null) throw new IllegalStateException();
            lastReturned.key = e;
        }

        /**
         * 业务作用：在当前游标位置插入元素，游标随之后移。
         *
         * @param e 待插入元素
         * 返回: 无返回值。
         */
        @Override
        public void add(E e) {
            lastReturned = null;
            if (next == null) {
                linkLast(newNode(e));
            } else {
                linkBefore(newNode(e), next);
            }
            nextIndex++;
        }
    }

    // ==================== Descending Iterator ====================

    /**
     * 业务作用：提供从尾到头的反向迭代器。
     *
     * 参数说明: 无。
     * 返回: 反向迭代器。
     */
    @Override
    public Iterator<E> descendingIterator() {
        return new Iterator<>() {
            private Node<E, Void> current = tail;

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
            public E next() {
                if (current == null) throw new NoSuchElementException();
                E item = current.key;
                current = current.prev;
                return item;
            }
        };
    }

    // ==================== SequencedCollection bridge (JDK 21) ====================

    /**
     * 业务作用：生成元素顺序相反的新实例，原容器不变。
     *
     * 参数说明: 无。
     * 返回: 顺序相反的新实例；同样来自对象池，用完需归池。
     */
    @Override
    public RecycleLinkedList<E> reversed() {
        RecycleLinkedList<E> r = of();
        for (Node<E, Void> x = tail; x != null; x = x.prev) {
            r.addLast(x.key);
        }
        return r;
    }
}
