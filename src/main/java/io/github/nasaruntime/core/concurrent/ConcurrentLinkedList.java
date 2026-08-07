package io.github.nasaruntime.core.concurrent;

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

    /**
     * 业务作用：构造实例。字段取默认值。
     *
     * 参数说明: 无。
     * 返回: 构造完成后可直接使用的实例。
     */
    public ConcurrentLinkedList() {
        this.map = new ConcurrentLinkedMap<>();
    }

    /**
     * 业务作用：按指定容量构造实例，容量估准可以避免后续扩容带来的重哈希或拷贝开销。
     *
     * @param capacity 初始容量
     * 返回: 构造完成后为空的实例。
     */
    public ConcurrentLinkedList(int capacity) {
        this.map = new ConcurrentLinkedMap<>(capacity);
    }

    /**
     * 业务作用：以给定容器的内容构造实例，元素为浅拷贝，不复制元素对象本身。
     *
     * @param c 源集合
     * 返回: 包含源容器全部元素的新实例。
     */
    public ConcurrentLinkedList(Collection<E> c) {
        this(c.size());
        addAll(c);
    }

    /**
     * 业务作用：按下标定位节点。从头尾中较近的一端开始扫描，把平均扫描距离降到长度的一半。
     *
     * @param index 下标
     * 返回: 该下标处的节点；下标越界时抛出 IndexOutOfBoundsException。
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

    /**
     * 业务作用：在当前游标位置插入元素，游标随之后移。
     *
     * @param e 待插入元素
     * 返回: 无返回值。
     */
    @Override
    public boolean add(E e) {
        map.put(sequence.getAndIncrement(), e);
        return true;
    }

    /**
     * 业务作用：在指定下标处插入元素，其后元素整体后移。
     *
     * @param index 插入位置
     * @param element 见方法语义
     * 返回: 无返回值；下标越界时抛出 IndexOutOfBoundsException。
     */
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

    /**
     * 业务作用：按值移除首个匹配元素。注意与按下标移除的重载区分：入参为 Object 时走本方法。
     *
     * @param index 见方法语义
     * 返回: 移除成功返回 true；未找到返回 false。
     */
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

    /**
     * 业务作用：按值移除首个匹配元素。注意与按下标移除的重载区分：入参为 Object 时走本方法。
     *
     * @param o 待移除元素
     * 返回: 移除成功返回 true；未找到返回 false。
     */
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

    /**
     * 业务作用：替换指定下标处的元素。
     *
     * @param index 元素下标
     * @param element 见方法语义
     * 返回: 被替换掉的旧元素；下标越界时抛出 IndexOutOfBoundsException。
     */
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

    /**
     * 业务作用：移除全部元素，解除容器对它们的强引用后交给 GC。
     *
     * 参数说明: 无。
     * 返回: 无返回值；并发写入仍在进行时不保证返回后容器为空。
     */
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
     * 业务作用：清空容器自身字段并把整条节点链交还给调用方，节点的归池责任随之移交。用于调用方需要复用或延迟释放节点链的场景。
     *
     * 参数说明: 无。
     * 返回: 原来的头节点；调用方负责逐个归还这些节点。
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
     * 业务作用：取出并移除首个元素，空容器不抛异常。
     *
     * 参数说明: 无。
     * 返回: 被移除的首元素；容器为空时返回 null。
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
     * 业务作用：取出并移除末个元素，空容器不抛异常。
     *
     * 参数说明: 无。
     * 返回: 被移除的末元素；容器为空时返回 null。
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

    /**
     * 业务作用：读取首个元素但不移除，空容器不抛异常。
     *
     * 参数说明: 无。
     * 返回: 首元素；容器为空时返回 null。
     */
    public E peekFirst() {
        ConcurrentLinkedMap.Node<Long, E> h = map.head; // volatile read
        return h == null ? null : h.value;
    }

    /**
     * 业务作用：读取末个元素但不移除，空容器不抛异常。
     *
     * 参数说明: 无。
     * 返回: 末元素；容器为空时返回 null。
     */
    public E peekLast() {
        ConcurrentLinkedMap.Node<Long, E> t = map.tail; // volatile read
        return t == null ? null : t.value;
    }

    /**
     * 业务作用：读取首个元素但不移除。
     *
     * 参数说明: 无。
     * 返回: 首元素；容器为空时抛出 NoSuchElementException。
     */
    @Override
    public E getFirst() {
        ConcurrentLinkedMap.Node<Long, E> h = map.head;
        if (h == null) throw new NoSuchElementException();
        return h.value;
    }

    /**
     * 业务作用：读取末个元素但不移除。
     *
     * 参数说明: 无。
     * 返回: 末元素；容器为空时抛出 NoSuchElementException。
     */
    @Override
    public E getLast() {
        ConcurrentLinkedMap.Node<Long, E> t = map.tail;
        if (t == null) throw new NoSuchElementException();
        return t.value;
    }

    /**
     * 业务作用：取出并移除首个元素。
     *
     * 参数说明: 无。
     * 返回: 被移除的首元素；容器为空时抛出 NoSuchElementException。
     */
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

    /**
     * 业务作用：取出并移除末个元素。
     *
     * 参数说明: 无。
     * 返回: 被移除的末元素；容器为空时抛出 NoSuchElementException。
     */
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

    /**
     * 业务作用：按下标读取元素。
     *
     * @param index 元素下标
     * 返回: 该位置的元素；下标越界时抛出 IndexOutOfBoundsException。
     */
    @Override
    public E get(int index) {
        map.w.lock();
        try {
            return nodeAt(index).value;
        } finally {
            map.w.unlock();
        }
    }

    /**
     * 业务作用：报告当前元素个数，供容量观测与遍历前的预分配。
     *
     * 参数说明: 无。
     * 返回: 元素个数；并发下为弱一致结果，仅反映采样瞬间的状态。
     */
    @Override
    public int size() {
        return map.size();
    }

    /**
     * 业务作用：判断容器当前是否为空。
     *
     * 参数说明: 无。
     * 返回: 为空返回 true；并发下为弱一致结果，仅反映采样瞬间的状态。不能作为后续操作必定成功的依据。
     */
    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    /**
     * 业务作用：判断容器中是否存在给定元素。
     *
     * @param o 元素
     * 返回: 命中返回 true；并发下为弱一致结果，仅反映采样瞬间的状态。
     */
    @Override
    public boolean contains(Object o) {
        return map.containsValue(o);
    }

    // ==================== 批量操作 ====================

    /**
     * 业务作用：把给定集合的全部元素按其迭代顺序追加到末尾。
     *
     * @param c 待追加的集合
     * 返回: 本容器发生变化返回 true。
     */
    @Override
    public boolean addAll(Collection<? extends E> c) {
        for (E e : c) add(e);
        return !c.isEmpty();
    }

    /**
     * 业务作用：把给定集合的全部元素插入到指定下标处。
     *
     * @param index 插入位置
     * @param c 待插入的集合
     * 返回: 本容器发生变化返回 true。
     */
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

    /**
     * 业务作用：逐个判断给定集合的元素是否都在本容器中。
     *
     * @param c 待判定集合
     * 返回: 全部命中返回 true。并发下多次查找不构成统一快照。
     */
    @Override
    public boolean containsAll(Collection<?> c) {
        for (Object o : c) {
            if (!contains(o)) return false;
        }
        return true;
    }

    // ==================== 索引查找 ====================

    /**
     * 业务作用：从头查找元素首次出现的位置。
     *
     * @param o 待查找元素
     * 返回: 首个匹配下标；不存在时返回 -1。
     */
    @Override
    public int indexOf(Object o) {
        int index = 0;
        for (ConcurrentLinkedMap.Node<Long, E> e = map.head; e != null; e = e.next) {
            if (Objects.equals(o, e.value)) return index;
            index++;
        }
        return -1;
    }

    /**
     * 业务作用：从尾查找元素最后一次出现的位置。
     *
     * @param o 待查找元素
     * 返回: 最后一个匹配下标；不存在时返回 -1。
     */
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

    /**
     * 业务作用：提供弱一致迭代器，遍历期间允许并发修改，不抛 ConcurrentModificationException。
     *
     * 参数说明: 无。
     * 返回: 弱一致迭代器；遍历中被并发移除的元素可能出现也可能不出现。
     */
    @Override
    public Iterator<E> iterator() {
        return new Iterator<>() {
            private final Iterator<E> it = map.values().iterator();

        /**
         * 业务作用：判断迭代是否还有下一个元素；必要时先向前探测一格。
         *
         * 返回: 还有元素返回 true。
         */
            @Override 
        public boolean hasNext() { return it.hasNext(); }
        /**
         * 业务作用：返回下一个元素并前移游标。
         *
         * 返回: 下一个元素；已到末尾时抛出 NoSuchElementException。
         */
            @Override 
        public E next() { return it.next(); }
        /**
         * 业务作用：移除最近一次 next 或 previous 返回的元素。
         *
         * 返回: 无返回值；尚未调用过 next/previous 时抛出 IllegalStateException。
         */
            @Override 
        public void remove() { it.remove(); }
        };
    }

    /**
     * 业务作用：提供支持双向遍历与原地修改的迭代器。
     *
     * 参数说明: 无。
     * 返回: 弱一致列表迭代器。
     */
    @Override
    public ListIterator<E> listIterator() {
        return listIterator(0);
    }

    /**
     * 业务作用：从指定下标开始提供支持双向遍历与原地修改的迭代器。
     *
     * @param index 起始下标
     * 返回: 弱一致列表迭代器。
     */
    @Override
    public ListIterator<E> listIterator(int index) {
        return new ConcurrentListIterator(index);
    }

    class ConcurrentListIterator implements ListIterator<E> {
        private int cursor;
        private int lastRet = -1;

        /**
         * 业务作用：按给定参数构造 ConcurrentListIterator 实例。
         *
         * @param index 下标
         * 返回: 构造完成后可直接使用的实例。
         */
        ConcurrentListIterator(int index) {
            int size = map.size();
            if (index < 0 || index > size) throw new IndexOutOfBoundsException();
            this.cursor = index;
        }

        /**
         * 业务作用：判断迭代是否还有下一个元素；必要时先向前探测一格。
         *
         * 返回: 还有元素返回 true。
         */
        @Override 
        public boolean hasNext() { return cursor < size(); }
        
        /**
         * 业务作用：判断反向迭代是否还有上一个元素。
         *
         * 参数说明: 无。
         * 返回: 还有元素返回 true。
         */
        @Override 
        public boolean hasPrevious() { return cursor > 0; }
        
        /**
         * 业务作用：给出下一次 next 将返回的元素下标。
         *
         * 参数说明: 无。
         * 返回: 下一个元素的下标；已到末尾时等于列表长度。
         */
        @Override 
        public int nextIndex() { return cursor; }
        
        /**
         * 业务作用：给出下一次 previous 将返回的元素下标。
         *
         * 参数说明: 无。
         * 返回: 上一个元素的下标；已到开头时为 -1。
         */
        @Override 
        public int previousIndex() { return cursor - 1; }

        /**
         * 业务作用：返回下一个元素并前移游标。
         *
         * 参数说明: 无。
         * 返回: 下一个元素；已到末尾时抛出 NoSuchElementException。
         */
        @Override
        public E next() {
            if (!hasNext()) throw new NoSuchElementException();
            lastRet = cursor;
            return get(cursor++);
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
            lastRet = --cursor;
            return get(cursor);
        }

        /**
         * 业务作用：移除最近一次 next 或 previous 返回的元素。
         *
         * 参数说明: 无。
         * 返回: 无返回值；尚未调用过 next/previous 时抛出 IllegalStateException。
         */
        @Override
        public void remove() {
            if (lastRet < 0) throw new IllegalStateException();
            ConcurrentLinkedList.this.remove(lastRet);
            cursor = lastRet;
            lastRet = -1;
        }

        /**
         * 业务作用：替换最近一次 next 或 previous 返回的元素。
         *
         * @param e 新元素
         * 返回: 无返回值；尚未调用过 next/previous 时抛出 IllegalStateException。
         */
        @Override
        public void set(E e) {
            if (lastRet < 0) throw new IllegalStateException();
            ConcurrentLinkedList.this.set(lastRet, e);
        }

        /**
         * 业务作用：在当前游标位置插入元素，游标随之后移。
         *
         * @param e 待插入元素
         * 返回: 无返回值。
         */
        @Override
        public void add(E e) {
            ConcurrentLinkedList.this.add(cursor++, e);
            lastRet = -1;
        }
    }

    // ==================== 视图 ====================

    /**
     * 业务作用：导出当前元素快照。
     *
     * 参数说明: 无。
     * 返回: 元素数组；不代表任何时刻的原子快照。
     */
    @Override
    public Object[] toArray() {
        return map.values().toArray();
    }

    /**
     * 业务作用：按调用方指定的数组类型导出元素快照。
     *
     * @param a 目标类型数组，容量不足时由集合框架分配新数组
     * 返回: 装有采样瞬间元素的数组。
     */
    @Override
    public <T> T[] toArray(T[] a) {
        return map.values().toArray(a);
    }

    /**
     * 业务作用：取出下标区间的视图。
     *
     * @param fromIndex 起始下标，含
     * @param toIndex 结束下标，不含
     * 返回: 该区间的列表；并发修改下内容可能与创建瞬间不一致。
     */
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

    /**
     * 业务作用：输出可读的元素快照，仅供诊断。
     *
     * 参数说明: 无。
     * 返回: 形如 [a, b, c] 的字符串；不保证与任何时刻的容器状态一致。
     */
    @Override
    public String toString() {
        return map.values().toString();
    }
}
