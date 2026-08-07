package io.github.nasaruntime.core.concurrent;

import lombok.NoArgsConstructor;

import java.io.Serial;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Nasa
 * (key, values) 映射，线程安全
 */
@SuppressWarnings("all")
@NoArgsConstructor
public class MultiValuedMap<K, T, C extends Collection<T>> extends ConcurrentHashMap<K, C> {

    @Serial
    private static final long serialVersionUID = -242272031181829086L;

    /**
     * 业务作用：按指定容量构造实例，容量估准可以避免后续扩容带来的重哈希或拷贝开销。
     *
     * @param initialCapacity 初始容量
     * 返回: 构造完成后为空的实例。
     */
    public MultiValuedMap(int initialCapacity) {
        super(initialCapacity);
    }

    /**
     * 业务作用：按给定参数构造 MultiValuedMap 实例。
     *
     * @param k 键
     * @param values 值集合
     * @param supplier 目标容器的构造器
     * 返回: 构造完成后可直接使用的实例。
     */
    public MultiValuedMap(K k, Collection<T> values, Supplier<C> supplier) {
        this(values.size());
        this.add(k, values, supplier);
    }

    /**
     * 业务作用：以给定容器的内容构造实例，元素为浅拷贝，不复制元素对象本身。
     *
     * @param map 源映射
     * 返回: 包含源容器全部元素的新实例。
     */
    public MultiValuedMap(MultiValuedMap<K, T, C> map) {
        super(map);
    }

    /**
     * 业务作用：把一批值追加到指定键下的集合中，集合不存在时用给定构造器创建。追加而非覆盖，使同一键可以分多次累积值。
     *
     * @param k 键
     * @param values 值集合
     * @param supplier 取到锁后执行的业务逻辑
     * 返回: 该键当前对应的值集合。
     */
    public C add(K k, Collection<T> values, Supplier<C> supplier) {
        C vs = this.computeIfAbsent(k, t -> supplier.get());
        vs.addAll(values);
        return vs;
    }

}
