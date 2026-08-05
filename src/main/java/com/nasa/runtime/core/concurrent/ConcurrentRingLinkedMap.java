package com.nasa.runtime.core.concurrent;

import com.nasa.runtime.core.base.*;
import com.nasa.runtime.core.function.Consumer4;

import java.io.Serial;
import java.util.*;
import java.util.function.*;

/**
 * Nasa
 * 并发的LinkedHashMap环
 */
@SuppressWarnings("all")
public class ConcurrentRingLinkedMap<K, V> extends ConcurrentLinkedMap<K, V> implements RingMap<K, V> {

    @Serial
    private static final long serialVersionUID = -5433483978654559286L;

    /* 线程安全由 ConcurrentLinkedMap 的读写锁保证 */
    private RingArrayList<K> rings;
    private final When.WhenChain<KV<K, V>> whenChain = When.WhenChain.of();

    // 操作事件 listeners
    private List<Consumer4<K, V, K, V>> onReplaceListeners;
    private List<BiConsumer<K, V>> onRemoveListeners;
    private List<BiConsumer<K, V>> onClearListeners;

    public ConcurrentRingLinkedMap() {
        this(128);
    }

    public ConcurrentRingLinkedMap(int capacity) {
        super(capacity);
        this.init(capacity);
    }

    public ConcurrentRingLinkedMap(Map<? extends K, ? extends V> map) {
        this(map.size());
        this.putAll(map);
    }

    private void init(int capacity) {
        rings = new RingArrayList<>(capacity);
        rings.onReplace((oldKey, newKey) -> {
            V oldValue = null;
            if (Objects.nonNull(oldKey)) {
                oldValue = super.remove(oldKey);
            }
            fireReplace(oldKey, oldValue, newKey, super.get(newKey));
        });
        rings.onRemove(oldKey -> {
            V oldValue = super.remove(oldKey);
            rings.compress();
            fireRemove(oldKey, oldValue);
        });
        rings.onClear(key -> {
            V v = super.remove(key);
            fireClear(key, v);
        });
    }

    private void fireReplace(K oldKey, V oldValue, K newKey, V newValue) {
        if (onReplaceListeners != null) {
            for (Consumer4<K, V, K, V> c : onReplaceListeners) c.accept(oldKey, oldValue, newKey, newValue);
        }
    }

    private void fireRemove(K k, V v) {
        if (onRemoveListeners != null) {
            for (BiConsumer<K, V> c : onRemoveListeners) c.accept(k, v);
        }
    }

    private void fireClear(K k, V v) {
        if (onClearListeners != null) {
            for (BiConsumer<K, V> c : onClearListeners) c.accept(k, v);
        }
    }

    @Override
    public void onReplace(Consumer4<K, V, K, V> listener) {
        if (onReplaceListeners == null) onReplaceListeners = new ArrayList<>(2);
        onReplaceListeners.add(listener);
    }

    @Override
    public void onRemove(BiConsumer<K, V> listener) {
        if (onRemoveListeners == null) onRemoveListeners = new ArrayList<>(2);
        onRemoveListeners.add(listener);
    }

    @Override
    public void onClear(BiConsumer<K, V> listener) {
        if (onClearListeners == null) onClearListeners = new ArrayList<>(2);
        onClearListeners.add(listener);
    }

    @Override
    public void clearListeners() {
        onReplaceListeners = null;
        onRemoveListeners = null;
        onClearListeners = null;
    }

    // ==================== 条件事件（When<KV<K,V>>） ====================

    @Override
    public When.WhenChain<KV<K, V>> chain() { return whenChain; }

    private void fireWhen(K key, V value) {
        if (!whenChain.isEmpty()) {
            KV<K, V> kv = KV.of(key, value);
            whenChain.fire(kv);
            kv.recycle();
        }
    }

    @Override
    public void ringReset(Map<? extends K, ? extends V> map) {
        this.init(map.size());
        this.putAll(map);
    }

    @Override
    public int ringCapacity() {
        return rings.ringCapacity();
    }

    @Override
    public V put(K key, V value) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        w.lock();
        try {
            V v = super.put(key, value);
            if (v == null) rings.add(key);
            fireWhen(key, value);
            return v;
        } finally {
            w.unlock();
        }
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        if (m == null || m.isEmpty()) return;
        w.lock();
        try {
            m.forEach(this::put);
        } finally {
            w.unlock();
        }
    }

    @Override
    public V remove(Object key) {
        Objects.requireNonNull(key, "key cannot be null");
        w.lock();
        try {
            V v = super.get(key);
            rings.remove((K) key);
            return v;
        } finally {
            w.unlock();
        }
    }

    @Override
    public void clear() {
        w.lock();
        try {
            rings.clear();
        } finally {
            w.unlock();
        }
    }

    @Override
    public V putIfAbsent(K key, V value) {
        w.lock();
        try {
            V v = super.putIfAbsent(key, value);
            if (v == null) {
                rings.add(key);
                fireWhen(key, value);
            }
            return v;
        } finally {
            w.unlock();
        }
    }

    @Override
    public boolean remove(Object key, Object value) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        w.lock();
        try {
            boolean removed = super.remove(key, value);
            if (removed) rings.remove((K) key);
            return removed;
        } finally {
            w.unlock();
        }
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mapping) {
        w.lock();
        try {
            boolean exists = containsKey(key);
            V v = super.computeIfAbsent(key, mapping);
            if (!exists && containsKey(key)) {
                rings.add(key);
                fireWhen(key, v);
            }
            return v;
        } finally {
            w.unlock();
        }
    }

    @Override
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remapping) {
        w.lock();
        try {
            boolean existsBefore = containsKey(key);
            V v = super.computeIfPresent(key, remapping);
            boolean existsAfter = containsKey(key);
            if (existsBefore && !existsAfter) rings.remove(key);
            else if (!existsBefore && existsAfter) {
                rings.add(key);
                fireWhen(key, v);
            }
            return v;
        } finally {
            w.unlock();
        }
    }

    @Override
    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remapping) {
        w.lock();
        try {
            boolean existsBefore = containsKey(key);
            V v = super.compute(key, remapping);
            boolean existsAfter = containsKey(key);
            if (existsBefore && !existsAfter) rings.remove(key);
            else if (!existsBefore && existsAfter) {
                rings.add(key);
                fireWhen(key, v);
            }
            return v;
        } finally {
            w.unlock();
        }
    }

    @Override
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remapping) {
        w.lock();
        try {
            boolean existsBefore = containsKey(key);
            V v = super.merge(key, value, remapping);
            boolean existsAfter = containsKey(key);
            if (existsBefore && !existsAfter) rings.remove(key);
            else if (!existsBefore && existsAfter) {
                rings.add(key);
                fireWhen(key, v);
            }
            return v;
        } finally {
            w.unlock();
        }
    }

    @Override
    public <M extends Map<K, V>> M toMap(Supplier<M> supplier, BiPredicate<K, V> filter) {
        M map = supplier.get();
        for (ConcurrentLinkedMap.Node<K, V> e = head; e != null; e = e.next) {
            if (filter.test(e.key, e.value)) map.put(e.key, e.value);
        }
        return map;
    }

    @Override
    public <M extends Map<K, V>> M toMapReverse(Supplier<M> supplier, BiPredicate<K, V> filter) {
        M map = supplier.get();
        for (ConcurrentLinkedMap.Node<K, V> e = tail; e != null; e = e.prev()) {
            if (filter.test(e.key, e.value)) map.put(e.key, e.value);
        }
        return map;
    }

    @Override
    public void forEachReverse(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        for (Node<K, V> e = tail; e != null; e = e.prev()) {
            action.accept(e.key, e.value);
        }
    }
}
