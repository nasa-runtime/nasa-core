package com.nasa.runtime.core.base;

import com.nasa.runtime.core.function.Consumer4;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * Nasa
 * 环形映射 - 映射环
 */
public interface RingMap<K, V> extends Map<K, V>, Ring<Map<? extends K, ? extends V>, Consumer4<K, V, K, V>, BiConsumer<K, V>, BiConsumer<K, V>>, When<KV<K, V>> {

    /**
     * 正序 - 老数据在前，新数据在后
     */
    default LinkedHashMap<K, V> toMap() {
        return toMap((k, v) -> true);
    }

    /**
     * 正序 - 老数据在前，新数据在后
     * @param filter 过滤函数
     */
    default LinkedHashMap<K, V> toMap(BiPredicate<K, V> filter) {
        return toMap(LinkedHashMap::new, filter);
    }

    /**
     * 正序 - 老数据在前，新数据在后
     * @param supplier map生成函数
     * @param filter 过滤函数
     * @param <M> 返回map泛型
     */
    <M extends Map<K, V>> M toMap(Supplier<M> supplier, BiPredicate<K, V> filter);

    /**
     * 倒序 - 老数据在后，新数据在前
     */
    default LinkedHashMap<K, V> toMapReverse() {
        return toMapReverse((k, v) -> true);
    }

    /**
     * 倒序 - 老数据在后，新数据在前
     * @param filter 过滤函数
     */
    default LinkedHashMap<K, V> toMapReverse(BiPredicate<K, V> filter) {
        return toMapReverse(LinkedHashMap::new, filter);
    }

    /**
     * 倒序 - 老数据在后，新数据在前
     * @param supplier map生成函数
     * @param filter 过滤函数
     * @param <M> 返回map泛型
     */
    <M extends Map<K, V>> M toMapReverse(Supplier<M> supplier, BiPredicate<K, V> filter);

    /**
     * 倒序遍历
     */
    void forEachReverse(BiConsumer<? super K, ? super V> action);

}
