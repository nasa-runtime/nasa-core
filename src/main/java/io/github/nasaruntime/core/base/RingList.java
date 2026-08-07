package io.github.nasaruntime.core.base;

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

    /**
     * 业务作用：报告当前元素个数，供容量观测与遍历前的预分配。
     *
     * 参数说明: 无。
     * 返回: 元素个数；并发下为弱一致结果，仅反映采样瞬间的状态。
     */
    @Override
    int size();

    /**
     * 业务作用：判断容器当前是否为空。
     *
     * 返回: 为空返回 true；并发下为弱一致结果，仅反映采样瞬间的状态。不能作为后续操作必定成功的依据。
     */
    @Override
    default boolean isEmpty() { return size() == 0; }

    /**
     * 业务作用：判断环是否已写满，写满后新元素会覆盖最旧的元素。
     *
     * 返回: 已写满返回 true。
     */
    default boolean isFull() { return size() == ringCapacity(); }

    /**
     * 业务作用：Collection 契约: 参数为 Object
     *
     * @param o 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    @Override
    boolean contains(Object o);

    // ==================== 写操作 ====================

    /**
     * 业务作用：在当前写游标位置写入元素，游标前进一位。
     * 写满后覆盖最旧元素，触发 REPLACE 事件。
     * unique 模式下若元素已存在则返回 false
     *
     * @param e 见上述说明
     * 返回: 永远返回 true（ring 状态总是变化：要么新增、要么覆盖）；
     */
    @Override
    boolean add(E e);

    /**
     * 业务作用：把给定集合的全部元素按其迭代顺序追加到末尾。
     *
     * @param col 见方法语义
     * 返回: 本容器发生变化返回 true。
     */
    @Override
    default boolean addAll(Collection<? extends E> col) {
        boolean changed = false;
        for (E e : col) changed |= add(e);
        return changed;
    }

    /**
     * 业务作用：在绝对索引位置设置元素
     *
     * @param index 见上述说明
     * @param e 见上述说明
     * 返回: 无返回值。
     */
    void set(int index, E e);

    /**
     * 业务作用：按下标移除元素。
     *
     * @param index 下标
     * 返回: 被移除的元素；下标越界时抛出 IndexOutOfBoundsException。
     */
    E removeAt(int index);

    /**
     * 业务作用：移除第一个匹配的元素
     *
     * @param e 见上述说明
     * 返回: true 如果找到并移除
     */
    @Override
    boolean remove(Object e);

    /**
     * 业务作用：按条件批量移除元素。
     *
     * @param filter 命中即移除的条件
     * 返回: 移除过至少一个元素返回 true；并发修改下不保证条件对最终状态成立。
     */
    @Override
    boolean removeIf(Predicate<? super E> filter);

    /**
     * 业务作用：移除全部元素，解除容器对它们的强引用后交给 GC。
     *
     * 参数说明: 无。
     * 返回: 无返回值；并发写入仍在进行时不保证返回后容器为空。
     */
    @Override
    void clear();

    /**
     * 业务作用：压缩：将非空元素从 0 开始紧密排列，写游标移到末尾
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    void compress();

    // ==================== 读操作 ====================

    /**
     * 业务作用：按下标读取元素。
     *
     * @param index 元素下标
     * 返回: 该位置的元素；下标越界时抛出 IndexOutOfBoundsException。
     */
    E get(int index);

    /**
     * 业务作用：报告下一次写入的位置，供诊断环形覆盖进度。
     *
     * 参数说明: 无。
     * 返回: 下一次写入的下标。
     */
    int writeOffset();

    // ==================== 遍历（不修改任何状态） ====================

    /**
     * 业务作用：提供弱一致迭代器，遍历期间允许并发修改，不抛 ConcurrentModificationException。
     *
     * 参数说明: 无。
     * 返回: 弱一致迭代器；遍历中被并发移除的元素可能出现也可能不出现。
     */
    @Override
    Iterator<E> iterator();

    /**
     * 业务作用：提供从新到旧的反向迭代器，用于优先处理最近写入的数据。
     *
     * 参数说明: 无。
     * 返回: 反向迭代器。
     */
    Iterator<E> reverseIterator();

    /**
     * 业务作用：遍历当前元素并逐个交给回调，遍历不阻塞并发读写。
     *
     * @param action 对每个元素执行的动作
     * 返回: 无返回值；回调抛出的异常直接向上传播，遍历随即中断。
     */
    @Override
    default void forEach(Consumer<? super E> action) {
        for (E e : this) action.accept(e);
    }

    /**
     * 业务作用：倒序遍历
     *
     * @param action 见上述说明
     * 返回: 无返回值。
     */
    default void forEachReverse(Consumer<? super E> action) {
        Iterator<E> it = reverseIterator();
        while (it.hasNext()) action.accept(it.next());
    }

    // ==================== 转换 ====================

    /**
     * 业务作用：导出为普通列表快照，按从旧到新排列，与本容器脱钩。
     *
     * 参数说明: 无。
     * 返回: 元素列表。
     */
    default List<E> toList() {
        return toList(null);
    }

    /**
     * 业务作用：导出为普通列表快照，按从旧到新排列，与本容器脱钩。
     *
     * @param filter 筛选条件
     * 返回: 元素列表。
     */
    default List<E> toList(Predicate<E> filter) {
        List<E> list = new ArrayList<>(size());
        for (E e : this) {
            if (filter == null || filter.test(e)) list.add(e);
        }
        return list;
    }

    /**
     * 业务作用：导出为普通列表快照，按从新到旧排列。
     *
     * 参数说明: 无。
     * 返回: 元素列表。
     */
    default List<E> toListReverse() {
        return toListReverse(null);
    }

    /**
     * 业务作用：导出为普通列表快照，按从新到旧排列。
     *
     * @param filter 筛选条件
     * 返回: 元素列表。
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
     * 业务作用：提供列表视图，与底层环共享数据而非副本。
     *
     * 参数说明: 无。
     * 返回: 列表视图；底层环变化会反映到该视图。
     */
    default List<E> asList() {
        RingList<E> self = this;
        return new AbstractList<E>() {
            /**
             * 业务作用：按下标读取元素。
             *
             * @param index 元素下标
             * 返回: 该位置的元素；下标越界时抛出 IndexOutOfBoundsException。
             */
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

            /**
             * 业务作用：报告当前元素个数，供容量观测与遍历前的预分配。
             *
             * 返回: 元素个数；并发下为弱一致结果，仅反映采样瞬间的状态。
             */
            @Override
            public int size() { return self.size(); }

            /**
             * 业务作用：提供弱一致迭代器，遍历期间允许并发修改，不抛 ConcurrentModificationException。
             *
             * 返回: 弱一致迭代器；遍历中被并发移除的元素可能出现也可能不出现。
             */
            @Override
            public Iterator<E> iterator() { return self.iterator(); }

            /**
             * 业务作用：判断容器当前是否为空。
             *
             * 返回: 为空返回 true；并发下为弱一致结果，仅反映采样瞬间的状态。不能作为后续操作必定成功的依据。
             */
            @Override
            public boolean isEmpty() { return self.isEmpty(); }

            /**
             * 业务作用：判断容器中是否存在给定元素。
             *
             * @param o 源对象
             * 返回: 命中返回 true；并发下为弱一致结果，仅反映采样瞬间的状态。
             */
            @Override
            public boolean contains(Object o) { return self.contains(o); }
        };
    }

    // ==================== Collection 默认方法兜底 ====================

    /**
     * 业务作用：逐个判断给定集合的元素是否都在本容器中。
     *
     * @param c 待判定集合
     * 返回: 全部命中返回 true。并发下多次查找不构成统一快照。
     */
    @Override
    default boolean containsAll(Collection<?> c) {
        for (Object o : c) {
            if (!contains(o)) return false;
        }
        return true;
    }

    /**
     * 业务作用：移除本容器中出现在给定集合里的全部元素。
     *
     * @param c 待移除元素的集合
     * 返回: 本容器发生变化返回 true。
     */
    @Override
    default boolean removeAll(Collection<?> c) {
        boolean changed = false;
        for (Object o : c) {
            if (remove(o)) changed = true;
        }
        return changed;
    }

    /**
     * 业务作用：只保留同时出现在给定集合中的元素，其余全部移除。
     *
     * @param c 需要保留的元素集合
     * 返回: 本容器发生变化返回 true。
     */
    @Override
    default boolean retainAll(Collection<?> c) {
        return removeIf(e -> !c.contains(e));
    }

    /**
     * 业务作用：导出当前元素快照。
     *
     * 参数说明: 无。
     * 返回: 元素数组；不代表任何时刻的原子快照。
     */
    @Override
    default Object[] toArray() {
        Object[] arr = new Object[size()];
        int i = 0;
        for (E e : this) arr[i++] = e;
        return arr;
    }

    /**
     * 业务作用：按调用方指定的数组类型导出元素快照。
     *
     * @param a 目标类型数组，容量不足时由集合框架分配新数组
     * 返回: 装有采样瞬间元素的数组。
     */
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
