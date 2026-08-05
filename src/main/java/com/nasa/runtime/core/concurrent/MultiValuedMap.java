package com.nasa.runtime.core.concurrent;

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

    public MultiValuedMap(int initialCapacity) {
        super(initialCapacity);
    }

    public MultiValuedMap(K k, Collection<T> values, Supplier<C> supplier) {
        this(values.size());
        this.add(k, values, supplier);
    }

    public MultiValuedMap(MultiValuedMap<K, T, C> map) {
        super(map);
    }

    /**
     * 不会被覆盖
     */
    public C add(K k, Collection<T> values, Supplier<C> supplier) {
        C vs = this.computeIfAbsent(k, t -> supplier.get());
        vs.addAll(values);
        return vs;
    }

}
