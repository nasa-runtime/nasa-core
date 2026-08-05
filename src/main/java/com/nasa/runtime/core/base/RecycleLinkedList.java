package com.nasa.runtime.core.base;

import com.nasa.runtime.core.utils.ContextUtils;

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
        @Override
        public RecycleLinkedList<Object> newObject() {
            return new RecycleLinkedList<>();
        }
    };

    private final ObjectPool.PooledHandle<RecycleLinkedList<E>> handle =
            (ObjectPool.PooledHandle) new ObjectPool.PooledHandle<>(POOL);

    // ==================== ObjectPool.Recycler ====================

    /**
     * O(1) — 返回 per-instance Handle (含 CAS state, 防 double-recycle)
     */
    @Override
    public ObjectPool.PooledHandle<RecycleLinkedList<E>> handle() {
        return this.handle;
    }

    /**
     * O(n) — 委托 clear, 逐节点回收
     */
    @Override
    public void restore() {
        clear();
    }

    transient Node<E, Void> head;
    transient Node<E, Void> tail;
    transient int size;

    /**
     * O(1) — 池命中 O(1); 池空时 newObject O(1).
     * 如果是反序列化，可以直接通过 {@code new RecycleLinkedList<>()} 创建对象.
     */
    public static <E> RecycleLinkedList<E> of() {
        return (RecycleLinkedList<E>) POOL.get();
    }

    /**
     * O(1)
     */
    public static <E> RecycleLinkedList<E> of(E e1) {
        RecycleLinkedList<E> list = of();
        list.add(e1);
        return list;
    }

    /**
     * O(1) — 共 2 次 add
     */
    public static <E> RecycleLinkedList<E> of(E e1, E e2) {
        RecycleLinkedList<E> list = of(e1);
        list.add(e2);
        return list;
    }

    /**
     * O(1) — 共 3 次 add
     */
    public static <E> RecycleLinkedList<E> of(E e1, E e2, E e3) {
        RecycleLinkedList<E> list = of(e1, e2);
        list.add(e3);
        return list;
    }

    /**
     * O(1) — 共 4 次 add
     */
    public static <E> RecycleLinkedList<E> of(E e1, E e2, E e3, E e4) {
        RecycleLinkedList<E> list = of(e1, e2, e3);
        list.add(e4);
        return list;
    }

    /**
     * O(1) — 共 5 次 add
     */
    public static <E> RecycleLinkedList<E> of(E e1, E e2, E e3, E e4, E e5) {
        RecycleLinkedList<E> list = of(e1, e2, e3, e4);
        list.add(e5);
        return list;
    }

    /**
     * O(1) — 共 6 次 add
     */
    public static <E> RecycleLinkedList<E> of(E e1, E e2, E e3, E e4, E e5, E e6) {
        RecycleLinkedList<E> list = of(e1, e2, e3, e4, e5);
        list.add(e6);
        return list;
    }

    /**
     * O(1) — 共 7 次 add
     */
    public static <E> RecycleLinkedList<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7) {
        RecycleLinkedList<E> list = of(e1, e2, e3, e4, e5, e6);
        list.add(e7);
        return list;
    }

    /**
     * O(1) — 共 8 次 add
     */
    public static <E> RecycleLinkedList<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8) {
        RecycleLinkedList<E> list = of(e1, e2, e3, e4, e5, e6, e7);
        list.add(e8);
        return list;
    }

    /**
     * O(1) — 共 9 次 add
     */
    public static <E> RecycleLinkedList<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8, E e9) {
        RecycleLinkedList<E> list = of(e1, e2, e3, e4, e5, e6, e7, e8);
        list.add(e9);
        return list;
    }

    /**
     * O(1) — 共 10 次 add
     */
    public static <E> RecycleLinkedList<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E e7, E e8, E e9, E e10) {
        RecycleLinkedList<E> list = of(e1, e2, e3, e4, e5, e6, e7, e8, e9);
        list.add(e10);
        return list;
    }

    // ==================== Node 回收 ====================

    /**
     * O(1) — Node.of() 走对象池, 池命中或 newObject 都是 O(1)
     */
    private Node<E, Void> newNode(E item) {
        Node<E, Void> node = Node.of();
        node.key = item;
        return node;
    }

    // ==================== Link / Unlink ====================

    /**
     * O(1) — 改 head/tail 指针 + size++
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
     * O(1) — 改 head/tail 指针 + size++
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
     * O(1) — 三向指针重连 + size++
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
     * O(1) — 解前后指针 + size-- + Node 回池
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
     * O(n/2) ≈ O(n) — 双向定位, 自较近端 head/tail 扫描
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
     * O(1) — 边界比较
     */
    private void checkElementIndex(int index) {
        if (index < 0 || index >= size)
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
    }

    /**
     * O(1) — 边界比较
     */
    private void checkPositionIndex(int index) {
        if (index < 0 || index > size)
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
    }

    // ==================== Deque 头尾操作 ====================

    /**
     * O(1) — head 链入
     */
    @Override
    public void addFirst(E e) {
        linkFirst(newNode(e));
    }

    /**
     * O(1) — tail 链入
     */
    @Override
    public void addLast(E e) {
        linkLast(newNode(e));
    }

    /**
     * O(1) — 委托 addFirst
     */
    @Override
    public boolean offerFirst(E e) {
        addFirst(e);
        return true;
    }

    /**
     * O(1) — 委托 addLast
     */
    @Override
    public boolean offerLast(E e) {
        addLast(e);
        return true;
    }

    /**
     * O(1) — head 解链 + Node 回池; 空集合抛 NSE
     */
    @Override
    public E removeFirst() {
        if (head == null) throw new NoSuchElementException();
        return unlink(head);
    }

    /**
     * O(1) — tail 解链 + Node 回池; 空集合抛 NSE
     */
    @Override
    public E removeLast() {
        if (tail == null) throw new NoSuchElementException();
        return unlink(tail);
    }

    /**
     * O(1) — head 解链 + Node 回池; 空集合返回 null
     */
    @Override
    public E pollFirst() {
        return head == null ? null : unlink(head);
    }

    /**
     * O(1) — tail 解链 + Node 回池; 空集合返回 null
     */
    @Override
    public E pollLast() {
        return tail == null ? null : unlink(tail);
    }

    /**
     * O(1) — 读 head.key; 空集合抛 NSE
     */
    @Override
    public E getFirst() {
        if (head == null) throw new NoSuchElementException();
        return head.key;
    }

    /**
     * O(1) — 读 tail.key; 空集合抛 NSE
     */
    @Override
    public E getLast() {
        if (tail == null) throw new NoSuchElementException();
        return tail.key;
    }

    /**
     * O(1) — 读 head.key; 空集合返回 null
     */
    @Override
    public E peekFirst() {
        return head == null ? null : head.key;
    }

    /**
     * O(1) — 读 tail.key; 空集合返回 null
     */
    @Override
    public E peekLast() {
        return tail == null ? null : tail.key;
    }

    /**
     * O(n) — 自 head 线性扫描首个匹配
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
     * O(n) — 自 tail 反向线性扫描首个匹配
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
     * O(1) — 委托 offerLast
     */
    @Override
    public boolean offer(E e) {
        return offerLast(e);
    }

    /**
     * O(1) — 委托 removeFirst
     */
    @Override
    public E remove() {
        return removeFirst();
    }

    /**
     * O(1) — 委托 pollFirst
     */
    @Override
    public E poll() {
        return pollFirst();
    }

    /**
     * O(1) — 委托 getFirst
     */
    @Override
    public E element() {
        return getFirst();
    }

    /**
     * O(1) — 委托 peekFirst
     */
    @Override
    public E peek() {
        return peekFirst();
    }

    /**
     * O(1) — 委托 addFirst
     */
    @Override
    public void push(E e) {
        addFirst(e);
    }

    /**
     * O(1) — 委托 removeFirst
     */
    @Override
    public E pop() {
        return removeFirst();
    }

    // ==================== List 核心操作（直接实现，避免走 ListIterator） ====================

    /**
     * O(1) — 读字段
     */
    @Override
    public int size() {
        return size;
    }

    /**
     * O(1) — 委托 linkLast
     */
    @Override
    public boolean add(E e) {
        linkLast(newNode(e));
        return true;
    }

    /**
     * O(n/2) ≈ O(n) — nodeAt 双向定位, 自 head/tail 较近端扫描
     */
    @Override
    public E get(int index) {
        checkElementIndex(index);
        return nodeAt(index).key;
    }

    /**
     * O(n/2) ≈ O(n) — nodeAt 定位 + 改 key
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
     * O(n/2) ≈ O(n) — 末尾插入 O(1), 中间插入需 nodeAt 定位
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
     * O(n/2) ≈ O(n) — nodeAt 定位 + 解链
     */
    @Override
    public E remove(int index) {
        checkElementIndex(index);
        return unlink(nodeAt(index));
    }

    /**
     * O(n) — 委托 removeFirstOccurrence 线性扫描
     */
    @Override
    public boolean remove(Object o) {
        return removeFirstOccurrence(o);
    }

    /**
     * O(n) — 自 head 线性扫描
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
     * O(n) — 委托 indexOf 线性扫描
     */
    @Override
    public boolean contains(Object o) {
        return indexOf(o) >= 0;
    }

    /**
     * O(n) — 逐节点 recycle 回池
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
     * O(1) — 仅清字段并返回 head 链, 节点回池责任移交调用方
     */
    @Override
    public Node<E, Void> clearRHead() {
        Node<E, Void> x = head;
        head = tail = null;
        size = 0;
        return x;
    }

    /**
     * 单线程遍历, 零 GC (跳过 AbstractSequentialList 默认的 iterator 分配).
     * <p>
     * 实现采用 <b>游标前置</b>: 每轮先取出 {@code x.key} 和 {@code x.next}, 再调 action.
     * 这样即使 action 内 {@code list.remove(currentElement)} 把当前节点 unlink 并回池,
     * 游标早已指向下一个有效节点, 不会读到已 recycle 的节点.
     * <p>
     * <b>安全</b>:
     * <ul>
     *   <li>action 内 {@code list.remove(currentElement)} — 删当前已访问的元素</li>
     *   <li>action 内 {@link #removeFirst()} 等删<b>已访问过</b>的头部元素</li>
     *   <li>action 内 {@link #add(Object)} 追加到末尾 (后续迭代会访问到新增节点)</li>
     *   <li>action 内删除远处尚未访问的元素 (非紧邻 cursor 的那个) — 解链时前后节点指针被正确修复, 跳过即可</li>
     * </ul>
     * <b>不安全 (静默错乱, 无 fail-fast)</b>:
     * <ul>
     *   <li>action 内 {@link #clear()} / {@link #recycle()} 整个 list —
     *       已前进的游标会落到已回收节点上, 后续 next 读到错乱数据</li>
     *   <li>action 内删除 <b>cursor 指向的下一个节点</b> (即"下一轮要访问的元素") —
     *       该节点 recycle 后字段全 null, 下一轮会:
     *       (a) 多调一次 {@code action.accept(null)},
     *       (b) 因 {@code x.next=null} 提前终止循环, 跳过该节点之后的全部元素.</li>
     *   <li>action 内 remove 紧邻下一个节点之后又 add 新元素 — 新元素从 NodePool 借出, 可能正是刚 recycle 那个 Node 实例.
     *       下一轮会读到新元素的 key 而不是 null, 行为"看似正常"但跳过了 C, D, E... 全部原始后续, 隐蔽更深.</li>
     * </ul>
     * 需要"批量删除符合条件的元素", 先 forEach 收集 id, 再单独 remove.
     * 类设计是单线程热路径优先, 不带 modCount, 没有 ConcurrentModificationException 保护.
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
     * 从 tail 向 head 反向遍历, 单线程, 零 GC.
     * <p>
     * 同样采用 <b>游标前置</b>: 每轮先取出 {@code x.key} 和 {@code x.prev}, 再调 action.
     * action 内 {@code list.remove(currentElement)} 把当前节点 unlink 并回池, 游标早已指向上一个有效节点.
     * <p>
     * <b>安全</b>:
     * <ul>
     *   <li>action 内 {@code list.remove(currentElement)} — 删当前已访问的元素</li>
     *   <li>action 内 {@link #removeLast()} 等删<b>已访问过</b>的尾部元素</li>
     *   <li>action 内 {@link #add(Object)} / {@link #addLast(Object)} 追加到末尾 (新 tail 不在反向迭代路径上, 不影响)</li>
     *   <li>action 内删除远处尚未访问的元素 (非紧邻 cursor 的 prev 那个) — 解链时前后指针被正确修复, 跳过即可</li>
     * </ul>
     * <b>不安全 (静默错乱, 无 fail-fast)</b>:
     * <ul>
     *   <li>action 内 {@link #clear()} / {@link #recycle()} 整个 list —
     *       已前进的游标会落到已回收节点上, 后续 prev 读到错乱数据</li>
     *   <li>action 内删除 <b>cursor 指向的 prev 节点</b> (即反向迭代"下一轮要访问的元素") —
     *       该节点 recycle 后字段全 null, 下一轮会:
     *       (a) 多调一次 {@code action.accept(null)},
     *       (b) 因 {@code x.prev=null} 提前终止循环, 跳过该节点之前的全部元素.</li>
     *   <li>action 内 remove + 同方法体 add 新元素 — 新元素可能复用刚 recycle 那个 Node 实例,
     *       下一轮读到新元素 key, 行为"看似正常"但跳过了原始前面的全部元素, 比静默 null 更隐蔽.</li>
     *   <li>action 内 {@link #addFirst(Object)} 插到 head — 不会读到新节点 (head 已在迭代末尾), 但语义不对称, 不建议</li>
     * </ul>
     * 跟 {@link #forEach} 完全对称, 类设计同样不带 modCount, 无 ConcurrentModificationException 保护.
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
     * O(1) 创建 — 完整遍历 O(n).
     * <p>
     * 默认 iterator: 每次返回新实例, 与 {@link LinkedList} / {@link ArrayList}
     * 行为一致 — 多线程并发遍历 / 嵌套 for-each / 跨方法传递都安全。
     * <p>
     * 默认 for-each 走这条路径 (JVM for-each 编译为 Iterable.iterator() 调用)。
     * 高频热路径需要零 GC 时, 显式调 {@link #cachedIterator()}。
     */
    @Override
    public Iterator<E> iterator() {
        return new RecycleListIterator(0);
    }

    /**
     * O(1) — 复用缓存 iterator, reset 到 head; 完整遍历 O(n).
     * <p>
     * 高频遍历专用零分配 iterator: 复用缓存实例, 重置到 head。
     * <p>
     * <b>调用者必须保证</b>:
     * <ol>
     *   <li>同一 list 实例不会跨线程并发遍历 (cachedIterator 字段无 volatile / 同步, 共享会数据竞争)</li>
     *   <li>不嵌套同一实例的 for-each (内层会 reset(0) 把外层状态毁掉, 静默错乱不抛异常)</li>
     * </ol>
     * 不满足以上约束请直接用 {@link #iterator()} (新实例, 与标准 List 一致)。
     * <p>
     * 典型用例: {@code BatchStreamMessageListenerContainer.ManagedRunner} 主循环 — 1 个 runner 线程
     * 独占 tasks 列表, 单层 while, 每秒数百次遍历, cache 节省每周期一个 24B iterator 对象。
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
     * O(n/2) ≈ O(n) 创建 — 构造时 nodeAt(index) 定位起始节点
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
         * O(n/2) ≈ O(n) — 构造时 nodeAt 定位起始节点; index==size 时 O(1)
         */
        RecycleListIterator(int index) {
            next = (index == size) ? null : nodeAt(index);
            nextIndex = index;
        }

        /**
         * O(n/2) ≈ O(n) — 复用实例时调, nodeAt 重新定位; index==0 或 size 时 O(1)
         */
        void reset(int index) {
            lastReturned = null;
            next = (index == size) ? null : (size == 0 ? null : nodeAt(index));
            nextIndex = index;
        }

        /**
         * O(1)
         */
        @Override
        public boolean hasNext() {
            return nextIndex < size;
        }

        /**
         * O(1) — 推进指针
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
         * O(1)
         */
        @Override
        public boolean hasPrevious() {
            return nextIndex > 0;
        }

        /**
         * O(1) — 退指针
         */
        @Override
        public E previous() {
            if (!hasPrevious()) throw new NoSuchElementException();
            lastReturned = next = (next == null) ? tail : next.prev;
            nextIndex--;
            return lastReturned.key;
        }

        /**
         * O(1)
         */
        @Override
        public int nextIndex() {
            return nextIndex;
        }

        /**
         * O(1)
         */
        @Override
        public int previousIndex() {
            return nextIndex - 1;
        }

        /**
         * O(1) — 委托 unlink + 推进 next 指针
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
         * O(1) — 改 key 字段
         */
        @Override
        public void set(E e) {
            if (lastReturned == null) throw new IllegalStateException();
            lastReturned.key = e;
        }

        /**
         * O(1) — linkLast 或 linkBefore
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
     * O(1) 创建 — 自 tail 反向遍历; 完整遍历 O(n)
     */
    @Override
    public Iterator<E> descendingIterator() {
        return new Iterator<>() {
            private Node<E, Void> current = tail;

            /** O(1) */
            @Override
            public boolean hasNext() {
                return current != null;
            }

            /** O(1) — 退 prev 指针 */
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
     * O(n) — 自 tail 反向拷贝到新池化实例
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
