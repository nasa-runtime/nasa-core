package com.nasa.runtime.core.base;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.nasa.runtime.core.utils.ContextUtils;
import com.nasa.runtime.core.utils.ObjMprUtils;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Nasa
 */
@SuppressWarnings({"all"})
@Getter
@Setter
public class KV<K, V> implements ObjectPool.Recycler<KV<K, V>>, Serializable {

    @Serial
    private static final long serialVersionUID = 800096719590658893L;

    public static final Consumer<KV> RECY_CON = KV::recycle;
    public static final BiConsumer<String, KV> RECY_BICON = (BiConsumer<String, KV>) (s, r) -> r.recycle();

    /* 对象池 */
    static final ObjectPool<KV<Object, Object>> POOL = new ObjectPool<>(
            ContextUtils.getPropertyInt("nasa.object-pool.kv-capacity", 10000)) {
        @Override
        public KV<Object, Object> newObject() {
            return new KV<>();
        }
    };

    private final ObjectPool.PooledHandle<KV<K, V>> handle = (ObjectPool.PooledHandle) new ObjectPool.PooledHandle<>(POOL);

    private K key;
    private V value;

    /**
     * 从对象池中获取一个对象
     * JsonCreator 这个注解让jackson反序列化框架默认通过这个静态方法创建对象，而不再是默认的空构造方法
     */
    @JsonCreator
    public static <K, V> KV<K, V> of() {
        return (KV<K, V>) POOL.get();
    }

    /**
     * 从对象池中获取一个KV
     */
    public static <K, V> KV<K, V> of(K k, V v) {
        KV<K, V> kv = of();
        kv.key = k;
        kv.value = v;
        return kv;
    }

    @Override
    public String toString() {
        return ObjMprUtils.toString(this);
    }

    @Override
    public ObjectPool.PooledHandle<KV<K, V>> handle() {
        return this.handle;
    }

    @Override
    public void restore() {
        this.key = null;
        this.value = null;
    }
}
