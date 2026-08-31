package io.github.nasaruntime.core.base;

import io.github.nasaruntime.core.utils.MapUtils;
import io.github.nasaruntime.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Nasa
 * 上下文缓存
 * <p>
 * <b>所有权约定 (强线程隔离)</b>:
 * <ul>
 *   <li>内部 ThreadLocal map 严格私有, 永不外泄引用, 由 AnyHolder 自己负责回收</li>
 *   <li>跨线程 / 长期持有 → 通过 {@link #snapshot()} 借独立快照, caller 用完显式 {@link RecycleLinkedMap#recycle()}</li>
 *   <li>{@link #putAll(Map)} 等接收外部 map 的 API 走 putAll 复制, 不接管引用</li>
 *   <li>{@link #clear()} 安全 recycle 内部 map (没人外面持有引用)</li>
 * </ul>
 */
@Slf4j
@SuppressWarnings({"unchecked", "unused", "rawtypes"})
public abstract class AnyHolder {

    public static String TRACE_ID = "_trace_id";
    public static int TRACE_ID_LENGTH = 10;
    private static final ThreadLocal<RecycleLinkedMap<String, Object>> ANY = new ThreadLocal<>();

    /**
     * 业务作用：构造实例。字段取默认值。
     *
     * 参数说明: 无。
     * 返回: 构造完成后可直接使用的实例。
     */
    private AnyHolder() {}

    /**
     * 业务作用：惰性创建并返回 ThreadLocal 中的真身 map。仅限内部使用，绝不能暴露给外部：外部持有真身后，clear 会造成 use-after-recycle。
     *
     * 参数说明: 无。
     * 返回: 当前线程的上下文 map。
     */
    private static RecycleLinkedMap<String, Object> internal() {
        RecycleLinkedMap<String, Object> map = ANY.get();
        if (map == null) ANY.set(map = RecycleLinkedMap.of());
        return map;
    }

    /**
     * 业务作用：借出当前上下文的独立副本，所有权交给调用方，供跨线程传递或长期持有而不受原线程 clear 影响。
     *
     * 参数说明: 无。
     * 返回: 上下文副本；上下文为空时返回 null。用完必须调用其 recycle 归池，否则池漏。
     */
    public static RecycleLinkedMap<String, Object> snapshot() {
        RecycleLinkedMap<String, Object> orig = ANY.get();
        if (orig == null || orig.isEmpty()) return null;
        return RecycleLinkedMap.of(orig);
    }

    /**
     * 业务作用：取得上下文快照。刻意不直接返回内部 map：内部 map 在 clear 后会被回收，外部若持有就会读到已回收数据。
     *
     * 参数说明: 无。
     * 返回: 独立的上下文快照；上下文为空时返回 null。
     */
    @Deprecated
    public static RecycleLinkedMap<String, Object> getOrNull() {
        return snapshot();
    }

    /**
     * 业务作用：把 map 内容合并到当前上下文 (putAll). 不接管外部 map 的所有权, caller 仍负责自己的 map.
     *
     * @param map 见上述说明
     * 返回: 无返回值。
     */
    public static void putAll(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return;
        internal().putAll(map);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值，由调用方按约定类型接收。
     *
     * @param key 上下文键
     * 返回: 上下文中的值；键不存在时返回默认值（未给默认值时为 null）。
     */
    public static <R> R get(String key) {
        return MapUtils.getObject(ANY.get(), key);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值，由调用方按约定类型接收。
     *
     * @param key 上下文键
     * @param dft 取不到时返回的默认值
     * 返回: 上下文中的值；键不存在时返回默认值（未给默认值时为 null）。
     */
    public static <R> R get(String key, R dft) {
        return MapUtils.getObject(ANY.get(), key, dft);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 String，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * 返回: 转换后的 String；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static String getString(String key) {
        return MapUtils.getString(ANY.get(), key);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 String，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * @param dft 取不到时返回的默认值
     * 返回: 转换后的 String；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static String getString(String key, String dft) {
        return MapUtils.getString(ANY.get(), key, dft);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 Byte，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * 返回: 转换后的 Byte；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static Byte getByte(String key) {
        return MapUtils.getByte(ANY.get(), key);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 Byte，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * @param dft 取不到时返回的默认值
     * 返回: 转换后的 Byte；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static Byte getByte(String key, Byte dft) {
        return MapUtils.getByte(ANY.get(), key, dft);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 Short，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * 返回: 转换后的 Short；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static Short getShort(String key) {
        return MapUtils.getShort(ANY.get(), key);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 Short，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * @param dft 取不到时返回的默认值
     * 返回: 转换后的 Short；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static Short getShort(String key, Short dft) {
        return MapUtils.getShort(ANY.get(), key, dft);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 Integer，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * 返回: 转换后的 Integer；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static Integer getInteger(String key) {
        return MapUtils.getInteger(ANY.get(), key);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 Integer，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * @param dft 取不到时返回的默认值
     * 返回: 转换后的 Integer；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static Integer getInteger(String key, Integer dft) {
        return MapUtils.getInteger(ANY.get(), key, dft);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 Long，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * 返回: 转换后的 Long；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static Long getLong(String key) {
        return MapUtils.getLong(ANY.get(), key);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 Long，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * @param dft 取不到时返回的默认值
     * 返回: 转换后的 Long；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static Long getLong(String key, Long dft) {
        return MapUtils.getLong(ANY.get(), key, dft);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 Float，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * 返回: 转换后的 Float；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static Float getFloat(String key) {
        return MapUtils.getFloat(ANY.get(), key);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 Float，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * @param dft 取不到时返回的默认值
     * 返回: 转换后的 Float；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static Float getFloat(String key, Float dft) {
        return MapUtils.getFloat(ANY.get(), key, dft);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 Double，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * 返回: 转换后的 Double；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static Double getDouble(String key) {
        return MapUtils.getDouble(ANY.get(), key);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 Double，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * @param dft 取不到时返回的默认值
     * 返回: 转换后的 Double；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static Double getDouble(String key, Double dft) {
        return MapUtils.getDouble(ANY.get(), key, dft);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 BigDecimal，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * 返回: 转换后的 BigDecimal；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static BigDecimal getBigDecimal(String key) {
        return MapUtils.getBigDecimal(ANY.get(), key);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 BigDecimal，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * @param dft 取不到时返回的默认值
     * 返回: 转换后的 BigDecimal；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static BigDecimal getBigDecimal(String key, BigDecimal dft) {
        return MapUtils.getBigDecimal(ANY.get(), key, dft);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 BigInteger，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * 返回: 转换后的 BigInteger；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static BigInteger getBigInteger(String key) {
        return MapUtils.getBigInteger(ANY.get(), key);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 BigInteger，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * @param dft 取不到时返回的默认值
     * 返回: 转换后的 BigInteger；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static BigInteger getBigInteger(String key, BigInteger dft) {
        return MapUtils.getBigInteger(ANY.get(), key, dft);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 AtomicInteger，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * 返回: 转换后的 AtomicInteger；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static AtomicInteger getAtomicInteger(String key) {
        return MapUtils.getAtomicInteger(ANY.get(), key);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 AtomicInteger，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * @param dft 取不到时返回的默认值
     * 返回: 转换后的 AtomicInteger；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static AtomicInteger getAtomicInteger(String key, AtomicInteger dft) {
        return MapUtils.getAtomicInteger(ANY.get(), key, dft);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 AtomicLong，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * 返回: 转换后的 AtomicLong；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static AtomicLong getAtomicLong(String key) {
        return MapUtils.getAtomicLong(ANY.get(), key);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 AtomicLong，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * @param dft 取不到时返回的默认值
     * 返回: 转换后的 AtomicLong；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static AtomicLong getAtomicLong(String key, AtomicLong dft) {
        return MapUtils.getAtomicLong(ANY.get(), key, dft);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 AtomicBoolean，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * 返回: 转换后的 AtomicBoolean；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static AtomicBoolean getAtomicBoolean(String key) {
        return MapUtils.getAtomicBoolean(ANY.get(), key);
    }

    /**
     * 业务作用：按键从当前线程上下文读取值并转换成 AtomicBoolean，容忍键不存在，省去调用方逐处判空与强转。
     *
     * @param key 上下文键
     * @param dft 取不到时返回的默认值
     * 返回: 转换后的 AtomicBoolean；键不存在或无法转换时返回默认值（未给默认值时为 null）。
     */
    public static AtomicBoolean getAtomicBoolean(String key, AtomicBoolean dft) {
        return MapUtils.getAtomicBoolean(ANY.get(), key, dft);
    }

    /**
     * 业务作用：是否为true
     *
     * @param key 见上述说明
     * @param dft 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isTrue(String key, boolean dft) {
        return MapUtils.getBoolean(ANY.get(), key, dft);
    }

    /**
     * 业务作用：按键取布尔值并判定为假，缺失时按给定默认值处理，省去调用方判空。
     *
     * @param key 键
     * @param dft 取不到时返回的默认值
     * 返回: 值为假时返回 true。
     */
    public static boolean isFalse(String key, boolean dft) {
        Boolean r = MapUtils.getBoolean(ANY.get(), key);
        return Objects.isNull(r) ? dft : !r;
    }

    /**
     * 业务作用：向上下文中设置一个值
     *
     * @param key 见上述说明
     * @param o 见上述说明
     * 返回: 无返回值。
     */
    public static void set(String key, Object o) {
        internal().put(key, o);
    }

    /**
     * 业务作用：向上下文中设置一个ArrayList，并将值添加到ArrayList
     *
     * @param key 上下文的key
     * @param o 值
     * 返回: 无返回值。
     */
    public static void addAsList(String key, Object o) {
        ((ArrayList<Object>) internal().computeIfAbsent(key, k -> new ArrayList<>())).add(o);
    }

    /**
     * 业务作用：按键取出上下文中的列表。
     *
     * @param key 键
     * 返回: 列表；键不存在或类型不符时返回 null。
     */
    public static <T> ArrayList<T> getAsList(String key) {
        return get(key);
    }

    /**
     * 业务作用：向上下文中设置一个HashSet，并将值添加到HashSet
     *
     * @param key 上下文的key
     * @param o 值
     * 返回: 无返回值。
     */
    public static void addAsSet(String key, Object o) {
        ((HashSet<Object>) internal().computeIfAbsent(key, k -> new HashSet<>())).add(o);
    }

    /**
     * 业务作用：按键取出上下文中的集合。
     *
     * @param key 键
     * 返回: 集合；键不存在或类型不符时返回 null。
     */
    public static <T> HashSet<T> getAsSet(String key) {
        return get(key);
    }

    /**
     * 业务作用：向上下文中设置一个Map，并将值添加到Map
     *
     * @param key 上下文的key
     * @param mk map的key
     * @param o 值
     * 返回: 无返回值。
     */
    public static void putAsMap(String key, Object mk, Object o) {
        ((LinkedHashMap<Object, Object>) internal().computeIfAbsent(key, k -> new LinkedHashMap<>())).put(mk, o);
    }

    /**
     * 业务作用：按键取出上下文中的嵌套 map，供二级键值存取。
     *
     * @param key 键
     * 返回: 嵌套 map；键不存在或类型不符时返回 null。
     */
    public static <K, V> Map<K, V> getAsMap(String key) {
        return get(key);
    }

    /**
     * 业务作用：按键取出上下文中的嵌套 map，供二级键值存取。
     *
     * @param key 键
     * @param mk 见上述说明
     * 返回: 嵌套 map；键不存在或类型不符时返回 null。
     */
    public static <T> T getAsMap(String key, Object mk) {
        return MapUtils.getObject(getAsMap(key), mk);
    }

    /**
     * 业务作用：从当前线程上下文中移除指定键。
     *
     * @param key 键
     * 返回: 被移除的值；键不存在时返回 null。
     */
    public static <T> T remove(String key) {
        RecycleLinkedMap<String, Object> map = ANY.get();
        return map == null ? null : (T) map.remove(key);
    }

    /**
     * 业务作用：遍历当前元素并逐个交给回调，遍历不阻塞并发读写。
     *
     * @param consumer 见方法语义
     * 返回: 无返回值；回调抛出的异常直接向上传播，遍历随即中断。
     */
    public static void forEach(BiConsumer<String, Object> consumer) {
        RecycleLinkedMap<String, Object> map = ANY.get();
        if (Objects.nonNull(map)) map.forEach(consumer);
    }

    /**
     * 业务作用：移除全部元素，解除容器对它们的强引用后交给 GC。
     *
     * 参数说明: 无。
     * 返回: 无返回值；并发写入仍在进行时不保证返回后容器为空。
     */
    public static void clear() {
        RecycleLinkedMap map = ANY.get();
        if (map == null) return;
        ANY.remove();
        map.recycle();
    }

    /**
     * 业务作用：取得当前线程的链路追踪标识，供日志串联同一次请求的各段处理。
     *
     * 参数说明: 无。
     * 返回: 追踪标识；未设置时返回 null。
     */
    public static String getTraceId() {
        String offset = get(TRACE_ID);
        if (Objects.isNull(offset)) set(TRACE_ID, offset = StringUtils.random(TRACE_ID_LENGTH));
        return offset;
    }

}
