package com.nasa.runtime.core.cache;

import com.nasa.runtime.core.base.TimingWheel;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Nasa
 * 默认本地简单缓存
 */
@SuppressWarnings("unchecked")
public class DefaultSimpleCache<K, V> implements SimpleCache<K, V>, Clearable, Reloadable {

    /* 本地缓存 */
    private final ConcurrentMap<K, Object> cacheMap = new ConcurrentHashMap<>();

    @Override
    public V get(K key) {
        return (V) cacheMap.get(key);
    }

    @Override
    public void put(K key, V value) {
        cacheMap.put(key, value);
    }

    @Override
    public V computeIfAbsent(K k, Function<K, V> mappingFunction) {
        return (V) cacheMap.computeIfAbsent(k, mappingFunction);
    }

    @Override
    public void putIfAbsent(K key, V value) {
        cacheMap.putIfAbsent(key, value);
    }

    @Override
    public boolean containsKey(K k) {
        return cacheMap.containsKey(k);
    }

    @Override
    public boolean containsKey(K k, K hk) {
        ConcurrentMap<K, V> map = hash(k);
        return Objects.nonNull(map) && map.containsKey(hk);
    }

    private ConcurrentMap<K, V> hash(K k) {
        return (ConcurrentMap<K, V>) cacheMap.computeIfAbsent(k, t -> new ConcurrentHashMap<>());
    }

    @Override
    public void put(K k, K hk, V v) {
        hash(k).put(hk, v);
    }

    @Override
    public V computeIfAbsent(K k, K hk, BiFunction<K, K, V> mappingFunction) {
        V v = hGet(k, hk);
        if (Objects.nonNull(v)) return v;
        return hash(k).computeIfAbsent(hk, t -> mappingFunction.apply(k, hk));
    }

    @Override
    public V hGet(K k, K hk) {
        return hash(k).get(hk);
    }

    @Override
    public Map<K, V> hGet(K k) {
        return (Map<K, V>) cacheMap.get(k);
    }

    @Override
    public Set<String> hKeys(K k) {
        return ((Map<String, V>) cacheMap.get(k)).keySet();
    }

    @Override
    public void del(K... ks) {
        for (K k : ks) cacheMap.remove(k);
    }

    @Override
    public void expire(K k, Duration timeout) {
        if (!TimingWheel.isStarted()) TimingWheel.startTimingWheel();
        TimingWheel.exec(timeout.get(ChronoUnit.MILLIS), () -> cacheMap.remove(k));
    }

    @Override
    public void expire(K k, Duration timeout, K... hks) {
        if (!TimingWheel.isStarted()) TimingWheel.startTimingWheel();
        ConcurrentMap<K, V> hkMap = hash(k);
        TimingWheel.exec(timeout.get(ChronoUnit.MILLIS), () -> {
            for (K hk : hks) hkMap.remove(hk);
        });
    }

    @Override
    public void clear() {
        cacheMap.clear();
    }

    @Override
    public void reload() {
        this.clear();
    }
}
