package com.nasa.runtime.core.base;

import com.google.common.util.concurrent.AtomicDouble;
import com.nasa.runtime.core.utils.MapUtils;
import com.nasa.runtime.core.utils.StringUtils;
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

    private AnyHolder() {}

    /**
     * 内部专用: 懒创建并返回 ThreadLocal 真身 map. 永不暴露给外部.
     */
    private static RecycleLinkedMap<String, Object> internal() {
        RecycleLinkedMap<String, Object> map = ANY.get();
        if (map == null) ANY.set(map = RecycleLinkedMap.of());
        return map;
    }

    /**
     * 借快照: 返回当前上下文的独立副本 (RecycleLinkedMap), caller 拥有所有权.
     * <p>
     * <b>用完必须调 {@link RecycleLinkedMap#recycle()} 还池</b>, 否则池漏。
     * 上下文为空时返回 null。
     * <p>
     * 使用场景: 跨线程传递 / 长期持有上下文。
     */
    public static RecycleLinkedMap<String, Object> snapshot() {
        RecycleLinkedMap<String, Object> orig = ANY.get();
        if (orig == null || orig.isEmpty()) return null;
        return RecycleLinkedMap.of(orig);
    }

    /**
     * @deprecated 用 {@link #snapshot()} 替代, 返回值是独立快照, 调用方必须 recycle.
     * 直接返回内部 map 会在 clear 后产生 use-after-recycle，因此统一返回独立快照.
     */
    @Deprecated
    public static RecycleLinkedMap<String, Object> getOrNull() {
        return snapshot();
    }

    /**
     * 把 map 内容合并到当前上下文 (putAll). 不接管外部 map 的所有权, caller 仍负责自己的 map.
     */
    public static void putAll(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return;
        internal().putAll(map);
    }

    /**
     * 获取值
     */
    public static <R> R get(String key) {
        return MapUtils.getObject(ANY.get(), key);
    }

    public static <R> R get(String key, R dft) {
        return MapUtils.getObject(ANY.get(), key, dft);
    }

    public static String getString(String key) {
        return MapUtils.getString(ANY.get(), key);
    }

    public static String getString(String key, String dft) {
        return MapUtils.getString(ANY.get(), key, dft);
    }

    public static Byte getByte(String key) {
        return MapUtils.getByte(ANY.get(), key);
    }

    public static Byte getByte(String key, Byte dft) {
        return MapUtils.getByte(ANY.get(), key, dft);
    }

    public static Short getShort(String key) {
        return MapUtils.getShort(ANY.get(), key);
    }

    public static Short getShort(String key, Short dft) {
        return MapUtils.getShort(ANY.get(), key, dft);
    }

    public static Integer getInteger(String key) {
        return MapUtils.getInteger(ANY.get(), key);
    }

    public static Integer getInteger(String key, Integer dft) {
        return MapUtils.getInteger(ANY.get(), key, dft);
    }

    public static Long getLong(String key) {
        return MapUtils.getLong(ANY.get(), key);
    }

    public static Long getLong(String key, Long dft) {
        return MapUtils.getLong(ANY.get(), key, dft);
    }

    public static Float getFloat(String key) {
        return MapUtils.getFloat(ANY.get(), key);
    }

    public static Float getFloat(String key, Float dft) {
        return MapUtils.getFloat(ANY.get(), key, dft);
    }

    public static Double getDouble(String key) {
        return MapUtils.getDouble(ANY.get(), key);
    }

    public static Double getDouble(String key, Double dft) {
        return MapUtils.getDouble(ANY.get(), key, dft);
    }

    public static BigDecimal getBigDecimal(String key) {
        return MapUtils.getBigDecimal(ANY.get(), key);
    }

    public static BigDecimal getBigDecimal(String key, BigDecimal dft) {
        return MapUtils.getBigDecimal(ANY.get(), key, dft);
    }

    public static BigInteger getBigInteger(String key) {
        return MapUtils.getBigInteger(ANY.get(), key);
    }

    public static BigInteger getBigInteger(String key, BigInteger dft) {
        return MapUtils.getBigInteger(ANY.get(), key, dft);
    }

    public static AtomicInteger getAtomicInteger(String key) {
        return MapUtils.getAtomicInteger(ANY.get(), key);
    }

    public static AtomicInteger getAtomicInteger(String key, AtomicInteger dft) {
        return MapUtils.getAtomicInteger(ANY.get(), key, dft);
    }

    public static AtomicLong getAtomicLong(String key) {
        return MapUtils.getAtomicLong(ANY.get(), key);
    }

    public static AtomicLong getAtomicLong(String key, AtomicLong dft) {
        return MapUtils.getAtomicLong(ANY.get(), key, dft);
    }

    public static AtomicBoolean getAtomicBoolean(String key) {
        return MapUtils.getAtomicBoolean(ANY.get(), key);
    }

    public static AtomicBoolean getAtomicBoolean(String key, AtomicBoolean dft) {
        return MapUtils.getAtomicBoolean(ANY.get(), key, dft);
    }

    public static AtomicDouble getAtomicDouble(String key) {
        return MapUtils.getAtomicDouble(ANY.get(), key);
    }

    public static AtomicDouble getAtomicDouble(String key, AtomicDouble dft) {
        return MapUtils.getAtomicDouble(ANY.get(), key, dft);
    }

    /**
     * 是否为true
     */
    public static boolean isTrue(String key, boolean dft) {
        return MapUtils.getBoolean(ANY.get(), key, dft);
    }

    public static boolean isFalse(String key, boolean dft) {
        Boolean r = MapUtils.getBoolean(ANY.get(), key);
        return Objects.isNull(r) ? dft : !r;
    }

    /**
     * 向上下文中设置一个值
     */
    public static void set(String key, Object o) {
        internal().put(key, o);
    }

    /**
     * 向上下文中设置一个ArrayList，并将值添加到ArrayList
     * @param key 上下文的key
     * @param o 值
     */
    public static void addAsList(String key, Object o) {
        ((ArrayList<Object>) internal().computeIfAbsent(key, k -> new ArrayList<>())).add(o);
    }

    public static <T> ArrayList<T> getAsList(String key) {
        return get(key);
    }

    /**
     * 向上下文中设置一个HashSet，并将值添加到HashSet
     * @param key 上下文的key
     * @param o 值
     */
    public static void addAsSet(String key, Object o) {
        ((HashSet<Object>) internal().computeIfAbsent(key, k -> new HashSet<>())).add(o);
    }

    public static <T> HashSet<T> getAsSet(String key) {
        return get(key);
    }

    /**
     * 向上下文中设置一个Map，并将值添加到Map
     * @param key 上下文的key
     * @param mk map的key
     * @param o 值
     */
    public static void putAsMap(String key, Object mk, Object o) {
        ((LinkedHashMap<Object, Object>) internal().computeIfAbsent(key, k -> new LinkedHashMap<>())).put(mk, o);
    }

    public static <K, V> Map<K, V> getAsMap(String key) {
        return get(key);
    }

    public static <T> T getAsMap(String key, Object mk) {
        return MapUtils.getObject(getAsMap(key), mk);
    }

    /**
     * 从上下文中移除
     */
    public static <T> T remove(String key) {
        RecycleLinkedMap<String, Object> map = ANY.get();
        return map == null ? null : (T) map.remove(key);
    }

    public static void forEach(BiConsumer<String, Object> consumer) {
        RecycleLinkedMap<String, Object> map = ANY.get();
        if (Objects.nonNull(map)) map.forEach(consumer);
    }

    /**
     * 清空上下文: 内部 map 还池 + ThreadLocal remove.
     * 安全前提: 内部 map 永不外泄引用 (见类注释所有权约定), clear 时不会和外部撞车.
     */
    public static void clear() {
        RecycleLinkedMap map = ANY.get();
        if (map == null) return;
        ANY.remove();
        map.recycle();
    }

    public static String getTraceId() {
        String offset = get(TRACE_ID);
        if (Objects.isNull(offset)) set(TRACE_ID, offset = StringUtils.random(TRACE_ID_LENGTH));
        return offset;
    }

}
