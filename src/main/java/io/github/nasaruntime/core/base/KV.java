package io.github.nasaruntime.core.base;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.github.nasaruntime.core.utils.ContextUtils;
import io.github.nasaruntime.core.utils.ObjMprUtils;
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
        /**
         * 业务作用：池空时创建新实例。
         *
         * 参数说明: 无。
         * 返回: 字段均为初始值的新实例。
         */
        @Override
        public KV<Object, Object> newObject() {
            return new KV<>();
        }
    };

    private final ObjectPool.PooledHandle<KV<K, V>> handle = (ObjectPool.PooledHandle) new ObjectPool.PooledHandle<>(POOL);

    private K key;
    private V value;

    /**
     * 业务作用：从对象池借出键值对载体并按入参填充。
     *
     * 参数说明: 无。
     * 返回: 已填充的实例；用完必须调用 recycle 归池。
     */
    @JsonCreator
    public static <K, V> KV<K, V> of() {
        return (KV<K, V>) POOL.get();
    }

    /**
     * 业务作用：从对象池借出键值对载体并按入参填充。
     *
     * @param k 键
     * @param v 值
     * 返回: 已填充的实例；用完必须调用 recycle 归池。
     */
    public static <K, V> KV<K, V> of(K k, V v) {
        KV<K, V> kv = of();
        kv.key = k;
        kv.value = v;
        return kv;
    }

    /**
     * 业务作用：输出可读的元素快照，仅供诊断。
     *
     * 参数说明: 无。
     * 返回: 形如 [a, b, c] 的字符串；不保证与任何时刻的容器状态一致。
     */
    @Override
    public String toString() {
        return ObjMprUtils.toString(this);
    }

    /**
     * 业务作用：暴露池化身份，启用重复归池的 CAS 防御。
     *
     * 参数说明: 无。
     * 返回: 构造时绑定本类对象池的 handle。
     */
    @Override
    public ObjectPool.PooledHandle<KV<K, V>> handle() {
        return this.handle;
    }

    /**
     * 业务作用：归池前清空键与值引用，防止上一代数据泄漏给下一个借用方。
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    @Override
    public void restore() {
        this.key = null;
        this.value = null;
    }
}
