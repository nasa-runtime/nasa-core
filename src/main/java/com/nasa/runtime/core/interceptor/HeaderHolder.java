package com.nasa.runtime.core.interceptor;

import com.nasa.runtime.core.base.AnyHolder;
import com.nasa.runtime.core.utils.MapUtils;
import com.nasa.runtime.core.utils.StringUtils;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * Nasa
 * 远程调用执行器添加请求头
 */
@SuppressWarnings("unused")
public abstract class HeaderHolder {

    private static final String KEY = "__HeaderHolder";

    /**
     * 添加header
     * @param headerKey 请求头key
     * @param headerValue 请求头值
     */
    public static void add(String headerKey, String headerValue) {
        if (StringUtils.isNotBlank(headerValue)) get().put(headerKey, headerValue);
    }

    /**
     * 添加header
     * @param map 请求头集合
     */
    public static void add(Map<String, String> map) {
        if (MapUtils.isNotEmpty(map)) map.forEach(HeaderHolder::add);
    }

    /**
     * 获取当前线程缓存的请求头
     */
    public static Map<String, String> get() {
        Map<String, String> map = AnyHolder.getAsMap(KEY);
        if (Objects.isNull(map)) {
            map = new HashMap<>();
            AnyHolder.set(KEY, map);
        }
        return map;
    }

    /**
     * 获取当前线程缓存的请求头
     */
    public static String get(String header) {
        return MapUtils.getString(AnyHolder.getAsMap(KEY), header.toLowerCase());
    }

    public static Integer getInteger(String header) {
        return MapUtils.getInteger(AnyHolder.getAsMap(KEY), header.toLowerCase());
    }

    public static Integer getInteger(String header, Integer dft) {
        return MapUtils.getInteger(AnyHolder.getAsMap(KEY), header.toLowerCase(), dft);
    }

    /**
     * 将每对请求头调用函数
     * @param consumer 消费所有请求头的函数
     */
    public static void forEach(BiConsumer<String, String> consumer) {
        get().forEach(consumer);
    }

    /**
     * 清除 ThreadLocal 中当前线程的缓存
     */
    public static void clear() {
        AnyHolder.remove(KEY);
    }

}
