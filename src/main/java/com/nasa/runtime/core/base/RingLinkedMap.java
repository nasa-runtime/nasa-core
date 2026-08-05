package com.nasa.runtime.core.base;

import com.nasa.runtime.core.function.Consumer4;
import com.nasa.runtime.core.utils.MapUtils;

import java.io.Serial;
import java.util.*;
import java.util.function.*;

/**
 * Nasa
 * LinkedHashMap环
 */
@SuppressWarnings("all")
public class RingLinkedMap<K, V> extends LinkedHashMap<K, V> implements RingMap<K, V> {

    @Serial
    private static final long serialVersionUID = 6490215618997044621L;

    private RingArrayList<K> rings;
    private final When.WhenChain<KV<K, V>> whenChain = When.WhenChain.of();

    // 操作事件 listeners
    private List<Consumer4<K, V, K, V>> onReplaceListeners;
    private List<BiConsumer<K, V>> onRemoveListeners;
    private List<BiConsumer<K, V>> onClearListeners;

    public RingLinkedMap() {
        this(128);
    }

    public RingLinkedMap(int capacity) {
        super(capacity);
        this.init(capacity);
    }

    public RingLinkedMap(Map<K, V> map) {
        this(map.size());
        this.putAll(map);
    }

    private void init(int capacity) {
        rings = new RingArrayList<>(Math.max(1, capacity));
        // 当数据被覆盖时，触发replace事件从map中移除oldKey
        rings.onReplace((oldKey, newKey) -> {
            V oldValue = null;
            if (Objects.nonNull(oldKey)) {
                oldValue = super.remove(oldKey);
            }
            fireReplace(oldKey, oldValue, newKey, super.get(newKey));
        });
        // 当移除key时，触发remove事件从map中移除oldKey
        rings.onRemove(oldKey -> {
            V oldValue = super.remove(oldKey);
            rings.compress();
            fireRemove(oldKey, oldValue);
        });
        // 执行clear时，触发clear事件，将map清空
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

    private void fireRemove(K oldKey, V oldValue) {
        if (onRemoveListeners != null) {
            for (BiConsumer<K, V> c : onRemoveListeners) c.accept(oldKey, oldValue);
        }
    }

    private void fireClear(K k, V v) {
        if (onClearListeners != null) {
            for (BiConsumer<K, V> l : onClearListeners) l.accept(k, v);
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
        boolean exists = containsKey(key);
        V v = super.put(key, value);
        if (!exists) rings.add(key);
        fireWhen(key, value);
        return v;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        if (MapUtils.isNotEmpty(m)) m.forEach(this::put);
    }

    @Override
    public V remove(Object key) {
        V v = super.get(key);
        rings.remove((K) key);
        return v;
    }

    @Override
    public void clear() {
        rings.clear();
    }

    @Override
    public V putIfAbsent(K key, V value) {
        Objects.requireNonNull(key, "key cannot be null");
        boolean exists = containsKey(key);
        V v = super.putIfAbsent(key, value);
        if (v == null) {
            if (!exists) {
                rings.add(key);
            }
            fireWhen(key, value);
        }
        return v;
    }

    @Override
    public boolean remove(Object key, Object value) {
        boolean removed = super.remove(key, value);
        if (removed) rings.remove((K) key);
        return removed;
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mapping) {
        boolean exists = containsKey(key);
        V v = super.computeIfAbsent(key, mapping);
        if (!exists && containsKey(key)) {
            rings.add(key);
            fireWhen(key, v);
        }
        return v;
    }

    @Override
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remapping) {
        boolean existsBefore = containsKey(key);
        V v = super.computeIfPresent(key, remapping);
        boolean existsAfter = containsKey(key);

        if (existsBefore && !existsAfter) {
            rings.remove(key);
        } else if (!existsBefore && existsAfter) {
            rings.add(key);
            fireWhen(key, v);
        }
        return v;
    }

    @Override
    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remapping) {
        boolean existsBefore = containsKey(key);
        V v = super.compute(key, remapping);
        boolean existsAfter = containsKey(key);

        if (existsBefore && !existsAfter) {
            rings.remove(key);
        } else if (!existsBefore && existsAfter) {
            rings.add(key);
            fireWhen(key, v);
        }
        return v;
    }

    @Override
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remapping) {
        boolean existsBefore = containsKey(key);
        V v = super.merge(key, value, remapping);
        boolean existsAfter = containsKey(key);

        if (existsBefore && !existsAfter) {
            rings.remove(key);
        } else if (!existsBefore && existsAfter) {
            rings.add(key);
            fireWhen(key, v);
        }
        return v;
    }

    @Override
    public <M extends Map<K, V>> M toMap(Supplier<M> supplier, BiPredicate<K, V> filter) {
        M map = supplier.get();
        for (K k : rings) {
            if (k == null) continue;
            V v = this.get(k);
            if (filter.test(k, v)) map.put(k, v);
        }
        return map;
    }

    @Override
    public <M extends Map<K, V>> M toMapReverse(Supplier<M> supplier, BiPredicate<K, V> filter) {
        M map = supplier.get();
        Iterator<K> it = rings.reverseIterator();
        while (it.hasNext()) {
            K k = it.next();
            V v = this.get(k);
            if (filter.test(k, v)) map.put(k, v);
        }
        return map;
    }

    @Override
    public void forEachReverse(BiConsumer<? super K, ? super V> action) {
        Iterator<K> it = rings.reverseIterator();
        while (it.hasNext()) {
            K k = it.next();
            action.accept(k, super.get(k));
        }
    }
}
