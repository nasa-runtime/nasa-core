package com.nasa.runtime.core.concurrent;

import java.io.Serial;
import java.io.Serializable;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Nasa
 * 并发HashSet
 */
@SuppressWarnings("all")
public class ConcurrentHashSet<E> extends AbstractSet<E> implements Set<E>, Serializable {

    @Serial
    private static final long serialVersionUID = -7378694602885111887L;

    final ConcurrentHashMap<E, Boolean> map;

    public ConcurrentHashSet() {
        map = new ConcurrentHashMap<>();
    }

    public ConcurrentHashSet(int capacity) {
        map = new ConcurrentHashMap<>(capacity);
    }

    public ConcurrentHashSet(Collection<? extends E> c) {
        this(c.size());
        addAll(c);
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public boolean contains(Object o) {
        return map.containsKey((E) o);
    }

    @Override
    public Iterator<E> iterator() {
        return map.keySet().iterator();
    }

    @Override
    public boolean add(E o) {
        return map.putIfAbsent(o, Boolean.TRUE) == null;
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
    public void forEach(Consumer<? super E> action) {
        map.keySet().forEach(action);
    }
}
