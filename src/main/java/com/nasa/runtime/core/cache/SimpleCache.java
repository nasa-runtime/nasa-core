package com.nasa.runtime.core.cache;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Nasa
 * 简单缓存接口
 */
@SuppressWarnings({"unused", "unchecked"})
public interface SimpleCache<K, V> {

    /**
     * 获取缓存的值，没有返回null
     */
    V get(K key);

    /**
     * 获取缓存的值，没有返回null
     */
    default V getOrDft(K key, V dft) {
        V v = get(key);
        return Objects.isNull(v) ? dft : v;
    }

    /**
     * 添加缓存
     */
    void put(K key, V value);

    /**
     * 写
     */
    V computeIfAbsent(K k, Function<K, V> mappingFunction);

    /**
     * 添加缓存，不存在key的缓存值则添加
     * 如果已存在key的缓值，则不添加
     */
    default void putIfAbsent(K key, V value) {
        computeIfAbsent(key, k -> value);
    }

    boolean containsKey(K k);

    boolean containsKey(K k, K hk);

    /**
     * hash写
     */
    void put(K k, K hk, V v);

    /**
     * hash写
     */
    V computeIfAbsent(K k, K hk, BiFunction<K, K, V> mappingFunction);

    /**
     * hash读
     */
    V hGet(K k, K hk);

    /**
     * hash读
     */
    Map<K, V> hGet(K k);

    Set<String> hKeys(K k);

    /**
     * 删除缓存
     */
    void del(K... ks);

    /**
     * 对ks设置过期时间
     */
    default void expire(long millis, K... ks) {
        Duration timeout = Duration.ofMillis(millis);
        for (K k : ks) expire(k, timeout);
    }

    /**
     * 对k设置过期时间
     */
    default void expire(K k, long millis) {
        expire(k, Duration.ofMillis(millis));
    }

    /**
     * 对k设置过期时间
     */
    default void expire(K k, long timeout, TimeUnit unit) {
        expire(k, Duration.ofMillis(unit.toMillis(timeout)));
    }

    /**
     * 对k设置过期时间
     */
    void expire(K k, Duration timeout);

    /**
     * 对hks设置过期时间
     */
    default void expire(K k, long millis, K... hks) {
        expire(k, Duration.ofMillis(millis), hks);
    }

    /**
     * 对hks设置过期时间
     */
    default void expire(K k, long timeout, TimeUnit unit, K... hks) {
        expire(k, Duration.ofMillis(unit.toMillis(timeout)), hks);
    }

    /**
     * 对hks设置过期时间
     */
    void expire(K k, Duration timeout, K... hks);

}