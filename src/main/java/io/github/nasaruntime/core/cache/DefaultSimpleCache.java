package io.github.nasaruntime.core.cache;

import io.github.nasaruntime.core.base.TimingWheel;

import java.time.Duration;
import java.util.Collections;
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

    /**
     * 业务作用：从本地 ConcurrentHashMap 读取一级缓存值。
     *
     * @param key 缓存键
     * 返回: 缓存值；键不存在时返回 null。若该键被当作 hash 使用，返回的是内部桶对象，强转会失败。
     */
    @Override
    public V get(K key) {
        return (V) cacheMap.get(key);
    }

    /**
     * 业务作用：写入一级缓存，已存在同键时覆盖。
     *
     * @param key 缓存键
     * @param value 缓存值
     * 返回: 无返回值。
     */
    @Override
    public void put(K key, V value) {
        cacheMap.put(key, value);
    }

    /**
     * 业务作用：读取缓存值，缺失时由映射函数计算并写入。借助 ConcurrentHashMap 的原子语义，
     * 同一键并发缺失时映射函数只会被执行一次，避免并发穿透到后端数据源。
     *
     * @param k 缓存键
     * @param mappingFunction 缺失时用于计算值的函数
     * 返回: 已有值或本次计算并写入的值。
     */
    @Override
    public V computeIfAbsent(K k, Function<K, V> mappingFunction) {
        return (V) cacheMap.computeIfAbsent(k, mappingFunction);
    }

    /**
     * 业务作用：仅在键不存在时写入。覆写接口默认实现，直接使用 ConcurrentHashMap 的原生原子操作，
     * 省去默认实现中包装映射函数的开销。
     *
     * @param key 缓存键
     * @param value 键不存在时写入的值
     * 返回: 无返回值。
     */
    @Override
    public void putIfAbsent(K key, V value) {
        cacheMap.putIfAbsent(key, value);
    }

    /**
     * 业务作用：判断一级缓存中是否存在指定键。
     *
     * @param k 缓存键
     * 返回: 存在返回 true；并发场景下只是采样瞬间的结论。
     */
    @Override
    public boolean containsKey(K k) {
        return cacheMap.containsKey(k);
    }

    /**
     * 业务作用：判断指定一级键下的 hash 桶中是否存在某个字段，且查询不产生缓存副作用。
     *
     * @param k 一级缓存键
     * @param hk hash 字段名
     * 返回: 一级键与字段都存在时返回 true；一级键不存在时返回 false 且不会为其创建空桶。
     */
    @Override
    public boolean containsKey(K k, K hk) {
        ConcurrentMap<K, V> map = hashIfPresent(k);
        return Objects.nonNull(map) && map.containsKey(hk);
    }

    /**
     * 业务作用：取得指定 key 下的 hash 桶，不存在时创建。只允许写路径调用。
     * <p>
     * 读路径必须改用 {@link #hashIfPresent(K)}：本方法基于 {@code computeIfAbsent}，
     * 一旦被读路径使用，任何"查了但从未写入"的 key 都会在缓存里留下一个永不回收的空 map，
     * 对只读多写少的访问模式即为无界增长。
     *
     * @param k 一级缓存 key
     * 返回: 该 key 对应的 hash 桶；原先不存在时新建并放入缓存。
     */
    private ConcurrentMap<K, V> hash(K k) {
        return (ConcurrentMap<K, V>) cacheMap.computeIfAbsent(k, t -> new ConcurrentHashMap<>());
    }

    /**
     * 业务作用：只读地取得指定 key 下的 hash 桶，保证查询不产生任何缓存副作用。
     *
     * @param k 一级缓存 key
     * 返回: 已存在的 hash 桶；key 不存在时返回 null，不创建条目。
     */
    private ConcurrentMap<K, V> hashIfPresent(K k) {
        return (ConcurrentMap<K, V>) cacheMap.get(k);
    }

    /**
     * 业务作用：向指定一级键下的 hash 桶写入字段，桶不存在时创建。
     * 本实现用同一个 map 承载普通值与 hash 桶，因此同一个键先被 put(k, v) 写成普通值后，
     * 再按 hash 使用会抛 ClassCastException；调用方必须保证键空间不混用。
     *
     * @param k 一级缓存键
     * @param hk hash 字段名
     * @param v 字段值
     * 返回: 无返回值。
     */
    @Override
    public void put(K k, K hk, V v) {
        hash(k).put(hk, v);
    }

    /**
     * 业务作用：读取 hash 字段，缺失时由映射函数计算并写入。先做一次只读探测，
     * 命中即返回而不创建桶，只有确实需要写入时才建桶。
     *
     * @param k 一级缓存键
     * @param hk hash 字段名
     * @param mappingFunction 缺失时用于计算值的函数
     * 返回: 已有值或本次计算并写入的值。
     */
    @Override
    public V computeIfAbsent(K k, K hk, BiFunction<K, K, V> mappingFunction) {
        V v = hGet(k, hk);
        if (Objects.nonNull(v)) return v;
        return hash(k).computeIfAbsent(hk, t -> mappingFunction.apply(k, hk));
    }

    /**
     * 业务作用：只读地取得 hash 字段值，一级键不存在时不创建任何条目。
     * 只读路径不得调用建桶入口，否则每个只查询不写入的键都会留下空 map，形成无界增长。
     *
     * @param k 一级缓存键
     * @param hk hash 字段名
     * 返回: 字段值；一级键或字段不存在时返回 null。
     */
    @Override
    public V hGet(K k, K hk) {
        ConcurrentMap<K, V> map = hashIfPresent(k);
        return Objects.isNull(map) ? null : map.get(hk);
    }

    /**
     * 业务作用：整体取得一级键下的 hash 桶，供批量遍历使用。
     * 返回的是缓存内部实例而非副本，调用方修改它会直接改变缓存内容。
     *
     * @param k 一级缓存键
     * 返回: 内部 hash 桶；键不存在时返回 null。该键存的是普通值时强转会失败。
     */
    @Override
    public Map<K, V> hGet(K k) {
        return (Map<K, V>) cacheMap.get(k);
    }

    /**
     * 业务作用：列出一级键下的全部 hash 字段名。缺失键返回空集合而不是 null，
     * 因为调用方通常直接对结果做遍历，返回 null 会把空缓存变成 NPE。
     *
     * @param k 一级缓存键
     * 返回: 字段名集合；键不存在时返回空集合。返回的是内部桶的键视图，随缓存变化。
     */
    @Override
    public Set<String> hKeys(K k) {
        Map<String, V> map = (Map<String, V>) cacheMap.get(k);
        // 缺失 key 返回空集合而不是 NPE：调用方通常直接 for-each 遍历结果。
        return Objects.isNull(map) ? Collections.emptySet() : map.keySet();
    }

    /**
     * 业务作用：批量删除一级缓存键，连同其下的 hash 桶一并移除。
     *
     * @param ks 待删除的键
     * 返回: 无返回值；不存在的键被静默忽略。
     */
    @Override
    public void del(K... ks) {
        for (K k : ks) cacheMap.remove(k);
    }

    /**
     * 业务作用：借助 TimingWheel 为一级键安排到期删除；时间轮未启动时按需启动。
     * 毫秒数必须用 Duration.toMillis 取得：Duration.get 只支持 SECONDS 与 NANOS，
     * 传入 MILLIS 会抛 UnsupportedTemporalTypeException，使整个过期能力不可用。
     *
     * @param k 缓存键
     * @param timeout 存活时长
     * 返回: 无返回值；重复设置会各自登记一个定时任务，先到期者即删除该键。
     */
    @Override
    public void expire(K k, Duration timeout) {
        if (!TimingWheel.isStarted()) TimingWheel.startTimingWheel();
        TimingWheel.exec(timeout.toMillis(), () -> cacheMap.remove(k));
    }

    /**
     * 业务作用：借助 TimingWheel 为一级键下的指定字段安排到期删除，到期后一级键与其余字段保留。
     * 桶引用在登记定时任务时就已捕获，因此到期回调不依赖该键此后是否仍在缓存中。
     *
     * @param k 一级缓存键
     * @param timeout 存活时长
     * @param hks 待过期的字段名
     * 返回: 无返回值。
     */
    @Override
    public void expire(K k, Duration timeout, K... hks) {
        if (!TimingWheel.isStarted()) TimingWheel.startTimingWheel();
        ConcurrentMap<K, V> hkMap = hash(k);
        TimingWheel.exec(timeout.toMillis(), () -> {
            for (K hk : hks) hkMap.remove(hk);
        });
    }

    /**
     * 业务作用：清空全部本地缓存内容，普通值与 hash 桶一并移除。
     * 已登记的到期任务不会被撤销，但其删除动作对已清空的缓存无副作用。
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    @Override
    public void clear() {
        cacheMap.clear();
    }

    /**
     * 业务作用：本地缓存没有权威数据源可供重新装载，因此实现为空动作，只为满足 Reloadable 契约。
     * 需要重载语义的调用方应改用具备数据源的缓存实现。
     *
     * 参数说明: 无。
     * 返回: 无返回值；不改变任何缓存内容。
     */
    @Override
    public void reload() {
        this.clear();
    }
}
