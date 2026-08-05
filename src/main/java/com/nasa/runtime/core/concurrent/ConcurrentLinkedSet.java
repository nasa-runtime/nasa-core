package com.nasa.runtime.core.concurrent;

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

    public ConcurrentLinkedSet() {
        map = new ConcurrentLinkedMap<>();
    }

    public ConcurrentLinkedSet(int capacity) {
        map = new ConcurrentLinkedMap<>(capacity);
    }

    public ConcurrentLinkedSet(Collection<? extends E> c) {
        this(c.size());
        addAll(c);
    }

    @Override
    public boolean contains(Object o) {
        return map.containsKey(o);
    }

    @Override
    public boolean add(E e) {
        return map.putIfAbsent(e, Boolean.TRUE) == null;
    }

    @Override
    public boolean remove(Object o) {
        return map.remove(o) != null;
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public Iterator<E> iterator() {
        return map.keySet().iterator();
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        map.keySet().forEach(action);
    }
}
