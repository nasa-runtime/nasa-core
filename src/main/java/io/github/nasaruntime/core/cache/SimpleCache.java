package io.github.nasaruntime.core.cache;

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
     * 业务作用：读取一级缓存值，是所有普通读取的基础操作。
     *
     * @param key 缓存键
     * 返回: 缓存值；键不存在时返回 null。
     */
    V get(K key);

    /**
     * 业务作用：读取缓存值并在缺失时回落到默认值，供调用方省去空值判断。
     *
     * @param key 缓存键
     * @param dft 缺失时返回的默认值
     * 返回: 缓存值；不存在时返回 dft。注意缓存中显式存入的 null 与「不存在」不可区分，两者都返回 dft。
     */
    default V getOrDft(K key, V dft) {
        V v = get(key);
        return Objects.isNull(v) ? dft : v;
    }

    /**
     * 业务作用：写入一级缓存，已存在同键时覆盖。
     *
     * @param key 缓存键
     * @param value 缓存值
     * 返回: 无返回值。
     */
    void put(K key, V value);

    /**
     * 业务作用：读取缓存值，缺失时由映射函数计算并写入，是「读取或装载」的原子入口。
     * 映射函数只在缺失时调用，因此可用于收敛并发穿透。
     *
     * @param k 缓存键
     * @param mappingFunction 缺失时用于计算值的函数
     * 返回: 已有值或本次计算并写入的值。
     */
    V computeIfAbsent(K k, Function<K, V> mappingFunction);

    /**
     * 业务作用：仅在键不存在时写入，已存在时保留原值不覆盖。
     *
     * @param key 缓存键
     * @param value 键不存在时写入的值
     * 返回: 无返回值；无法据此判断本次是否真正写入。
     */
    default void putIfAbsent(K key, V value) {
        computeIfAbsent(key, k -> value);
    }

    /**
     * 业务作用：判断一级缓存中是否存在指定键。
     *
     * @param k 缓存键
     * 返回: 存在返回 true。并发场景下只是采样瞬间的结论。
     */
    boolean containsKey(K k);

    /**
     * 业务作用：判断指定一级键下的 hash 桶中是否存在某个字段。
     *
     * @param k 一级缓存键
     * @param hk hash 字段名
     * 返回: 一级键与字段都存在时返回 true；一级键不存在时返回 false 且不产生任何缓存副作用。
     */
    boolean containsKey(K k, K hk);

    /**
     * 业务作用：向指定一级键下的 hash 桶写入字段，桶不存在时创建。
     * 注意 hash 用法与普通用法共用同一个键空间：同一个键先被 put(k, v) 写成普通值后，
     * 再按 hash 使用会因类型不符抛出 ClassCastException，调用方必须自行保证键空间不混用。
     *
     * @param k 一级缓存键
     * @param hk hash 字段名
     * @param v 字段值
     * 返回: 无返回值。
     */
    void put(K k, K hk, V v);

    /**
     * 业务作用：读取 hash 字段，缺失时由映射函数计算并写入。
     *
     * @param k 一级缓存键
     * @param hk hash 字段名
     * @param mappingFunction 缺失时用于计算值的函数，入参为一级键与字段名
     * 返回: 已有值或本次计算并写入的值。
     */
    V computeIfAbsent(K k, K hk, BiFunction<K, K, V> mappingFunction);

    /**
     * 业务作用：读取指定一级键下的 hash 字段值。
     *
     * @param k 一级缓存键
     * @param hk hash 字段名
     * 返回: 字段值；一级键或字段不存在时返回 null，且读取不产生任何缓存副作用。
     */
    V hGet(K k, K hk);

    /**
     * 业务作用：整体读取指定一级键下的 hash 桶，供批量遍历使用。
     *
     * @param k 一级缓存键
     * 返回: 该键下的全部字段映射；键不存在时返回 null。返回的映射是否为缓存内部视图由实现决定，
     *      调用方不应假定可安全修改。
     */
    Map<K, V> hGet(K k);

    /**
     * 业务作用：列出指定一级键下的全部 hash 字段名，供遍历或批量删除前的枚举。
     *
     * @param k 一级缓存键
     * 返回: 字段名集合；键不存在时返回空集合而非 null，使调用方可直接遍历。
     */
    Set<String> hKeys(K k);

    /**
     * 业务作用：批量删除一级缓存键，连同其下的 hash 桶一并移除。
     *
     * @param ks 待删除的键；传空数组时不做任何事
     * 返回: 无返回值；不存在的键被静默忽略。
     */
    void del(K... ks);

    /**
     * 业务作用：为一批一级键统一设置存活时长，到期后自动删除。
     *
     * @param millis 存活毫秒数
     * @param ks 待设置过期的键
     * 返回: 无返回值；每个键各自独立计时。
     */
    default void expire(long millis, K... ks) {
        Duration timeout = Duration.ofMillis(millis);
        for (K k : ks) expire(k, timeout);
    }

    /**
     * 业务作用：为单个一级键设置存活时长，到期后自动删除。
     *
     * @param k 缓存键
     * @param millis 存活毫秒数
     * 返回: 无返回值。
     */
    default void expire(K k, long millis) {
        expire(k, Duration.ofMillis(millis));
    }

    /**
     * 业务作用：以显式时间单位为单个一级键设置存活时长，避免调用方手工换算毫秒时出错。
     *
     * @param k 缓存键
     * @param timeout 存活时长
     * @param unit 时长单位
     * 返回: 无返回值。
     */
    default void expire(K k, long timeout, TimeUnit unit) {
        expire(k, Duration.ofMillis(unit.toMillis(timeout)));
    }

    /**
     * 业务作用：过期设置的唯一收口点，全部 expire 重载最终都委派到这里，由实现方决定计时机制。
     *
     * @param k 缓存键
     * @param timeout 存活时长
     * 返回: 无返回值；重复设置的行为由实现决定，调用方不应假定后一次必定覆盖前一次。
     */
    void expire(K k, Duration timeout);

    /**
     * 业务作用：为一级键下的指定 hash 字段设置存活时长，到期只删除这些字段而不删除整个桶。
     *
     * @param k 一级缓存键
     * @param millis 存活毫秒数
     * @param hks 待过期的字段名
     * 返回: 无返回值。
     */
    default void expire(K k, long millis, K... hks) {
        expire(k, Duration.ofMillis(millis), hks);
    }

    /**
     * 业务作用：以显式时间单位为一级键下的指定 hash 字段设置存活时长。
     *
     * @param k 一级缓存键
     * @param timeout 存活时长
     * @param unit 时长单位
     * @param hks 待过期的字段名
     * 返回: 无返回值。
     */
    default void expire(K k, long timeout, TimeUnit unit, K... hks) {
        expire(k, Duration.ofMillis(unit.toMillis(timeout)), hks);
    }

    /**
     * 业务作用：hash 字段过期设置的唯一收口点，全部字段级 expire 重载最终都委派到这里。
     *
     * @param k 一级缓存键
     * @param timeout 存活时长
     * @param hks 待过期的字段名
     * 返回: 无返回值；到期只移除指定字段，一级键与其余字段保留。
     */
    void expire(K k, Duration timeout, K... hks);

}