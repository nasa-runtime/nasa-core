package io.github.nasaruntime.core.concurrent;

import java.io.Serial;
import java.io.Serializable;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Nasa
 */
@SuppressWarnings("all")
public class ConcurrentLinkedSet<E> extends AbstractSet<E> implements Set<E>, Serializable {

    @Serial
    private static final long serialVersionUID = -7661310509268111379L;

    final ConcurrentLinkedMap<E, Boolean> map;

    /**
     * 业务作用：构造实例。字段取默认值。
     *
     * 参数说明: 无。
     * 返回: 构造完成后可直接使用的实例。
     */
    public ConcurrentLinkedSet() {
        map = new ConcurrentLinkedMap<>();
    }

    /**
     * 业务作用：按指定容量构造实例，容量估准可以避免后续扩容带来的重哈希或拷贝开销。
     *
     * @param capacity 初始容量
     * 返回: 构造完成后为空的实例。
     */
    public ConcurrentLinkedSet(int capacity) {
        map = new ConcurrentLinkedMap<>(capacity);
    }

    /**
     * 业务作用：以给定容器的内容构造实例，元素为浅拷贝，不复制元素对象本身。
     *
     * @param c 源集合
     * 返回: 包含源容器全部元素的新实例。
     */
    public ConcurrentLinkedSet(Collection<? extends E> c) {
        this(c.size());
        addAll(c);
    }

    /**
     * 业务作用：判断集合中是否存在给定元素。
     *
     * @param o 待查找元素
     * 返回: 命中返回 true；并发下为弱一致结果，仅反映采样瞬间的状态。
     */
    @Override
    public boolean contains(Object o) {
        return map.containsKey(o);
    }

    /**
     * 业务作用：加入元素，已存在同值时不重复加入。
     *
     * @param e 待加入元素
     * 返回: 本次真正加入返回 true；已存在返回 false。
     */
    @Override
    public boolean add(E e) {
        return map.putIfAbsent(e, Boolean.TRUE) == null;
    }

    /**
     * 业务作用：移除给定元素。
     *
     * @param o 待移除元素
     * 返回: 确实移除了返回 true；不存在返回 false。
     */
    @Override
    public boolean remove(Object o) {
        return map.remove(o) != null;
    }

    /**
     * 业务作用：移除全部元素，解除容器对它们的强引用后交给 GC。
     *
     * 参数说明: 无。
     * 返回: 无返回值；并发写入仍在进行时不保证返回后容器为空。
     */
    @Override
    public void clear() {
        map.clear();
    }

    /**
     * 业务作用：提供弱一致迭代器，遍历期间允许并发修改，不抛 ConcurrentModificationException。
     *
     * 参数说明: 无。
     * 返回: 弱一致迭代器；遍历中被并发移除的元素可能出现也可能不出现。
     */
    @Override
    public Iterator<E> iterator() {
        return map.keySet().iterator();
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
     * 业务作用：遍历当前元素并逐个交给回调，遍历不阻塞并发读写。
     *
     * @param action 对每个元素执行的动作
     * 返回: 无返回值；回调抛出的异常直接向上传播，遍历随即中断。
     */
    @Override
    public void forEach(Consumer<? super E> action) {
        map.keySet().forEach(action);
    }
}
