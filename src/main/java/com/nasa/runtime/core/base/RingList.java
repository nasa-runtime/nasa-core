package com.nasa.runtime.core.base;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Nasa
 * 环形集合（Ring Buffer）
 * <p>
 * 固定容量的环形数组，写满后自动覆盖最旧的元素。
 * 支持正序（oldest → newest）和倒序（newest → oldest）遍历。
 * <p>
 * 实现 {@link Collection}，可直接传给吃 {@code Collection<E>} 的 API；
 * 需要 {@code List<E>} 的场景调用 {@link #asList()} 拿到只读视图。
 * <p>
 * 注意：跟 {@link ArrayList} 的 {@code remove(int)} 一样，
 * 本接口的"按 slot 移除"叫 {@link #removeAt(int)}，跟 {@link Collection#remove(Object)}
 * 完全分开，避免重载分派陷阱。
 * <p>
 * 事件：
 * <pre>{@code
 * ring.onReplace((old, now) -> log("replaced " + old + " -> " + now));
 * ring.onRemove(e -> log("removed " + e));
 * ring.onClear(e -> log("cleared " + e));
 * }</pre>
 */
@SuppressWarnings("unused")
public interface RingList<E> extends Collection<E>, Ring<Collection<E>, BiConsumer<E, E>, Consumer<E>, Consumer<E>>, When<E> {

    // ==================== 容量与大小 ====================

    @Override
    int size();

    @Override
    default boolean isEmpty() { return size() == 0; }

    default boolean isFull() { return size() == ringCapacity(); }

    /**
     * Collection 契约: 参数为 Object
     */
    @Override
    boolean contains(Object o);

    // ==================== 写操作 ====================

    /**
     * 在当前写游标位置写入元素，游标前进一位。
     * 写满后覆盖最旧元素，触发 REPLACE 事件。
     *
     * @return 永远返回 true（ring 状态总是变化：要么新增、要么覆盖）；
     *         unique 模式下若元素已存在则返回 false
     */
    @Override
    boolean add(E e);

    @Override
    default boolean addAll(Collection<? extends E> col) {
        boolean changed = false;
        for (E e : col) changed |= add(e);
        return changed;
    }

    /**
     * 在绝对索引位置设置元素
     */
    void set(int index, E e);

    /**
     * 移除绝对索引位置的元素，留下 null 洞（不 shift）
     */
    E removeAt(int index);

    /**
     * 移除第一个匹配的元素
     * @return true 如果找到并移除
     */
    @Override
    boolean remove(Object e);

    @Override
    boolean removeIf(Predicate<? super E> filter);

    @Override
    void clear();

    /**
     * 压缩：将非空元素从 0 开始紧密排列，写游标移到末尾
     */
    void compress();

    // ==================== 读操作 ====================

    /**
     * 获取绝对索引位置的元素
     */
    E get(int index);

    /**
     * 当前写游标位置
     */
    int writeOffset();

    // ==================== 遍历（不修改任何状态） ====================

    /**
     * 正序迭代器：从写游标位置开始，oldest → newest
     */
    @Override
    Iterator<E> iterator();

    /**
     * 倒序迭代器：从写游标位置开始，newest → oldest
     */
    Iterator<E> reverseIterator();

    /**
     * 正序遍历（默认 Iterable.forEach）
     */
    @Override
    default void forEach(Consumer<? super E> action) {
        for (E e : this) action.accept(e);
    }

    /**
     * 倒序遍历
     */
    default void forEachReverse(Consumer<? super E> action) {
        Iterator<E> it = reverseIterator();
        while (it.hasNext()) action.accept(it.next());
    }

    // ==================== 转换 ====================

    /**
     * 正序转 List（snapshot 拷贝，独立于底层 ring 状态）
     */
    default List<E> toList() {
        return toList(null);
    }

    /**
     * 正序转 List（snapshot 拷贝），可选过滤
     */
    default List<E> toList(Predicate<E> filter) {
        List<E> list = new ArrayList<>(size());
        for (E e : this) {
            if (filter == null || filter.test(e)) list.add(e);
        }
        return list;
    }

    /**
     * 倒序转 List（snapshot 拷贝）
     */
    default List<E> toListReverse() {
        return toListReverse(null);
    }

    /**
     * 倒序转 List（snapshot 拷贝），可选过滤
     */
    default List<E> toListReverse(Predicate<E> filter) {
        List<E> list = new ArrayList<>(size());
        Iterator<E> it = reverseIterator();
        while (it.hasNext()) {
            E e = it.next();
            if (filter == null || filter.test(e)) list.add(e);
        }
        return list;
    }

    /**
     * 实时只读 List 视图：包一层 {@link AbstractList}，底层是当前 ring 状态。
     * <p>
     * 适合传给吃 {@code List<E>} 的 API（Jackson 序列化、JdbcTemplate 等）。
     * <ul>
     *   <li>iterator() 直接复用 ring 的迭代器，跳 null，O(n) 走完一圈</li>
     *   <li>get(int) 是 O(n) —— Jackson/forEach 都走 iterator，不是热路径</li>
     *   <li>只读：调用 set/add/remove 抛 {@link UnsupportedOperationException}</li>
     * </ul>
     * 需要 snapshot 用 {@link #toList()}。
     */
    default List<E> asList() {
        RingList<E> self = this;
        return new AbstractList<E>() {
            @Override
            public E get(int index) {
                int sz = self.size();
                if (index < 0 || index >= sz) {
                    throw new IndexOutOfBoundsException("index=" + index + " size=" + sz);
                }
                int i = 0;
                for (E e : self) {
                    if (i++ == index) return e;
                }
                throw new IndexOutOfBoundsException("index=" + index + " size=" + sz);
            }

            @Override
            public int size() { return self.size(); }

            @Override
            public Iterator<E> iterator() { return self.iterator(); }

            @Override
            public boolean isEmpty() { return self.isEmpty(); }

            @Override
            public boolean contains(Object o) { return self.contains(o); }
        };
    }

    // ==================== Collection 默认方法兜底 ====================

    @Override
    default boolean containsAll(Collection<?> c) {
        for (Object o : c) {
            if (!contains(o)) return false;
        }
        return true;
    }

    @Override
    default boolean removeAll(Collection<?> c) {
        boolean changed = false;
        for (Object o : c) {
            if (remove(o)) changed = true;
        }
        return changed;
    }

    @Override
    default boolean retainAll(Collection<?> c) {
        return removeIf(e -> !c.contains(e));
    }

    @Override
    default Object[] toArray() {
        Object[] arr = new Object[size()];
        int i = 0;
        for (E e : this) arr[i++] = e;
        return arr;
    }

    @Override
    @SuppressWarnings("unchecked")
    default <T> T[] toArray(T[] a) {
        int sz = size();
        T[] dst = a.length >= sz ? a
                : (T[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), sz);
        int i = 0;
        for (E e : this) dst[i++] = (T) e;
        if (dst.length > sz) dst[sz] = null;
        return dst;
    }

}
