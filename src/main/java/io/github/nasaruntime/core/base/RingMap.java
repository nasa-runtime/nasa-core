package io.github.nasaruntime.core.base;

import io.github.nasaruntime.core.function.Consumer4;

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
     * 业务作用：导出为普通 Map 快照，与本容器脱钩。
     *
     * 参数说明: 无。
     * 返回: 包含当前全部条目的普通映射。
     */
    default LinkedHashMap<K, V> toMap() {
        return toMap((k, v) -> true);
    }

    /**
     * 业务作用：导出为普通 Map 快照，与本容器脱钩。
     *
     * @param filter 命中即处理的条件
     * 返回: 包含当前全部条目的普通映射。
     */
    default LinkedHashMap<K, V> toMap(BiPredicate<K, V> filter) {
        return toMap(LinkedHashMap::new, filter);
    }

    /**
     * 业务作用：导出为普通 Map 快照，与本容器脱钩。
     *
     * @param supplier 见上述说明
     * @param filter 命中即处理的条件
     * 返回: 包含当前全部条目的普通映射。
     */
    <M extends Map<K, V>> M toMap(Supplier<M> supplier, BiPredicate<K, V> filter);

    /**
     * 业务作用：按相反顺序导出为普通 Map 快照。
     *
     * 参数说明: 无。
     * 返回: 顺序相反的普通映射。
     */
    default LinkedHashMap<K, V> toMapReverse() {
        return toMapReverse((k, v) -> true);
    }

    /**
     * 业务作用：按相反顺序导出为普通 Map 快照。
     *
     * @param filter 命中即处理的条件
     * 返回: 顺序相反的普通映射。
     */
    default LinkedHashMap<K, V> toMapReverse(BiPredicate<K, V> filter) {
        return toMapReverse(LinkedHashMap::new, filter);
    }

    /**
     * 业务作用：按相反顺序导出为普通 Map 快照。
     *
     * @param supplier 见上述说明
     * @param filter 命中即处理的条件
     * 返回: 顺序相反的普通映射。
     */
    <M extends Map<K, V>> M toMapReverse(Supplier<M> supplier, BiPredicate<K, V> filter);

    /**
     * 业务作用：倒序遍历
     *
     * @param action 见上述说明
     * 返回: 无返回值。
     */
    void forEachReverse(BiConsumer<? super K, ? super V> action);

}
