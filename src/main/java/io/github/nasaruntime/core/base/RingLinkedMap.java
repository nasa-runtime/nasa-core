package io.github.nasaruntime.core.base;

import io.github.nasaruntime.core.function.Consumer4;
import io.github.nasaruntime.core.utils.MapUtils;

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

    /**
     * 业务作用：构造实例。字段取默认值。
     *
     * 参数说明: 无。
     * 返回: 构造完成后可直接使用的实例。
     */
    public RingLinkedMap() {
        this(128);
    }

    /**
     * 业务作用：按指定容量构造实例，容量估准可以避免后续扩容带来的重哈希或拷贝开销。
     *
     * @param capacity 初始容量
     * 返回: 构造完成后为空的实例。
     */
    public RingLinkedMap(int capacity) {
        super(capacity);
        this.init(capacity);
    }

    /**
     * 业务作用：以给定容器的内容构造实例，元素为浅拷贝，不复制元素对象本身。
     *
     * @param map 源映射
     * 返回: 包含源容器全部元素的新实例。
     */
    public RingLinkedMap(Map<K, V> map) {
        this(map.size());
        this.putAll(map);
    }

    /**
     * 业务作用：按容量与配置完成内部结构初始化，是各构造器的统一收口。
     *
     * @param capacity 容量
     * 返回: 无返回值。
     */
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

    /**
     * 业务作用：触发替换回调。回调抛出的异常不得影响容器自身状态，由实现负责隔离。
     *
     * @param oldKey 见上述说明
     * @param oldValue 见上述说明
     * @param newKey 见上述说明
     * @param newValue 见上述说明
     * 返回: 无返回值。
     */
    private void fireReplace(K oldKey, V oldValue, K newKey, V newValue) {
        if (onReplaceListeners != null) {
            for (Consumer4<K, V, K, V> c : onReplaceListeners) c.accept(oldKey, oldValue, newKey, newValue);
        }
    }

    /**
     * 业务作用：触发移除回调。
     *
     * @param oldKey 见上述说明
     * @param oldValue 见上述说明
     * 返回: 无返回值。
     */
    private void fireRemove(K oldKey, V oldValue) {
        if (onRemoveListeners != null) {
            for (BiConsumer<K, V> c : onRemoveListeners) c.accept(oldKey, oldValue);
        }
    }

    /**
     * 业务作用：触发清空回调。
     *
     * @param k 键
     * @param v 值
     * 返回: 无返回值。
     */
    private void fireClear(K k, V v) {
        if (onClearListeners != null) {
            for (BiConsumer<K, V> l : onClearListeners) l.accept(k, v);
        }
    }

    /**
     * 业务作用：注册元素被替换时的回调，供调用方在覆盖发生时释放旧值持有的资源。
     *
     * @param listener 回调
     * 返回: 当前实例，供链式配置。
     */
    @Override
    public void onReplace(Consumer4<K, V, K, V> listener) {
        if (onReplaceListeners == null) onReplaceListeners = new ArrayList<>(2);
        onReplaceListeners.add(listener);
    }

    /**
     * 业务作用：注册元素被移除时的回调，供调用方释放被移除元素持有的资源。
     *
     * @param listener 回调
     * 返回: 当前实例，供链式配置。
     */
    @Override
    public void onRemove(BiConsumer<K, V> listener) {
        if (onRemoveListeners == null) onRemoveListeners = new ArrayList<>(2);
        onRemoveListeners.add(listener);
    }

    /**
     * 业务作用：注册容器被清空时的回调。
     *
     * @param listener 回调
     * 返回: 当前实例，供链式配置。
     */
    @Override
    public void onClear(BiConsumer<K, V> listener) {
        if (onClearListeners == null) onClearListeners = new ArrayList<>(2);
        onClearListeners.add(listener);
    }

    /**
     * 业务作用：清除全部已注册回调，避免容器复用时沿用上一代的回调。
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    @Override
    public void clearListeners() {
        onReplaceListeners = null;
        onRemoveListeners = null;
        onClearListeners = null;
    }

    // ==================== 条件事件（When<KV<K,V>>） ====================

    /**
     * 业务作用：暴露内部 When 事件链，使条件回调可以挂载到本容器的状态变化上。
     *
     * 参数说明: 无。
     * 返回: 本实例独有的事件链。
     */
    @Override
    public When.WhenChain<KV<K, V>> chain() { return whenChain; }

    /**
     * 业务作用：触发 When 条件链，使注册的条件回调有机会执行。
     *
     * @param key 键
     * @param value 值
     * 返回: 无返回值。
     */
    private void fireWhen(K key, V value) {
        if (!whenChain.isEmpty()) {
            KV<K, V> kv = KV.of(key, value);
            whenChain.fire(kv);
            kv.recycle();
        }
    }

    /**
     * 业务作用：重设环形容量并清空现有内容，供运行期调整保留窗口大小。
     *
     * @param map 见上述说明
     * 返回: 无返回值；原有元素全部丢弃。
     */
    @Override
    public void ringReset(Map<? extends K, ? extends V> map) {
        this.init(map.size());
        this.putAll(map);
    }

    /**
     * 业务作用：报告环形容量上限，超出后最旧的元素会被覆盖。
     *
     * 参数说明: 无。
     * 返回: 容量上限。
     */
    @Override
    public int ringCapacity() {
        return rings.ringCapacity();
    }

    /**
     * 业务作用：写入键值对，已存在同键时覆盖。
     *
     * @param key 键
     * @param value 值
     * 返回: 被覆盖的旧值；原先不存在时返回 null。
     */
    @Override
    public V put(K key, V value) {
        Objects.requireNonNull(key, "key cannot be null");
        boolean exists = containsKey(key);
        V v = super.put(key, value);
        if (!exists) rings.add(key);
        fireWhen(key, value);
        return v;
    }

    /**
     * 业务作用：批量写入，逐项覆盖同键的旧值。
     *
     * @param m 待写入的映射
     * 返回: 无返回值。
     */
    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        if (MapUtils.isNotEmpty(m)) m.forEach(this::put);
    }

    /**
     * 业务作用：按键移除条目。
     *
     * @param key 键
     * 返回: 被移除的值；键不存在时返回 null。
     */
    @Override
    public V remove(Object key) {
        V v = super.get(key);
        rings.remove((K) key);
        return v;
    }

    /**
     * 业务作用：移除全部元素，解除容器对它们的强引用后交给 GC。
     *
     * 参数说明: 无。
     * 返回: 无返回值；并发写入仍在进行时不保证返回后容器为空。
     */
    @Override
    public void clear() {
        rings.clear();
    }

    /**
     * 业务作用：仅在键不存在时写入，已存在时保留原值。
     *
     * @param key 键
     * @param value 值
     * 返回: 已存在的旧值；本次真正写入时返回 null。
     */
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

    /**
     * 业务作用：仅在键当前映射到给定值时才移除，用于避免误删他人写入的新值。
     *
     * @param key 键
     * @param value 期望的当前值
     * 返回: 条件成立并完成移除返回 true。
     */
    @Override
    public boolean remove(Object key, Object value) {
        boolean removed = super.remove(key, value);
        if (removed) rings.remove((K) key);
        return removed;
    }

    /**
     * 业务作用：读取值，缺失时由映射函数计算并写入，是「读取或装载」的原子入口。
     *
     * @param key 键
     * @param mapping 见方法语义
     * 返回: 已有值或本次计算写入的值；函数返回 null 时不写入并返回 null。
     */
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

    /**
     * 业务作用：仅在键已存在时按函数重算其值，函数返回 null 表示删除该条目。
     *
     * @param key 键
     * @param remapping 见方法语义
     * 返回: 新值；键不存在或函数返回 null 时返回 null。
     */
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

    /**
     * 业务作用：无论键是否存在都按函数重算其值，函数返回 null 表示删除该条目。
     *
     * @param key 键
     * @param remapping 见方法语义
     * 返回: 新值；函数返回 null 时返回 null 并删除条目。
     */
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

    /**
     * 业务作用：键不存在时直接写入给定值，已存在时用合并函数把新旧值合并；函数返回 null 表示删除该条目。
     *
     * @param key 键
     * @param value 键不存在时写入的值
     * @param remapping 见方法语义
     * 返回: 合并后的新值；函数返回 null 时返回 null 并删除条目。
     */
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

    /**
     * 业务作用：导出为普通 Map 快照，与本容器脱钩。
     *
     * @param supplier 见上述说明
     * @param filter 命中即处理的条件
     * 返回: 包含当前全部条目的普通映射。
     */
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

    /**
     * 业务作用：按相反顺序导出为普通 Map 快照。
     *
     * @param supplier 见上述说明
     * @param filter 命中即处理的条件
     * 返回: 顺序相反的普通映射。
     */
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

    /**
     * 业务作用：按从新到旧的顺序遍历，用于优先处理最近写入的数据。
     *
     * @param action 对每个元素执行的动作
     * 返回: 无返回值。
     */
    @Override
    public void forEachReverse(BiConsumer<? super K, ? super V> action) {
        Iterator<K> it = rings.reverseIterator();
        while (it.hasNext()) {
            K k = it.next();
            action.accept(k, super.get(k));
        }
    }
}
