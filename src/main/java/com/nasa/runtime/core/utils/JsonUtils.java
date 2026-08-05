package com.nasa.runtime.core.utils;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.Map;
import java.util.Objects;

/**
 * Nasa
 * Map 转 JSONObject
 */
@SuppressWarnings("unused")
public abstract class JsonUtils {

    /**
     * 将Map转JSONObject
     */
    public static <K, V> JSONObject toJSONObject(Map<K, V> map) {
        return JSONObject.from(map);
    }

    /**
     * 从Map中获取JSONObject
     */
    public static <K, V> JSONObject getJSONObject(Map<K, V> map, K key) {
        return MapUtils.getObjectMapper(map, key, JSONObject::from);
    }

    /**
     * 从Map中获取JSONObject
     */
    public static <K, V> JSONObject getJSONObject(Map<K, V> map, K key, JSONObject dft) {
        JSONObject json = getJSONObject(map, key);
        return Objects.isNull(json) ? dft : json;
    }

    /**
     * 从Map中获取JSONArray
     */
    public static <K, V> JSONArray getJSONArray(Map<K, V> map, K key) {
        return MapUtils.getObjectMapper(map, key, JSONArray::from);
    }

    /**
     * 从Map中获取JSONArray
     */
    public static <K, V> JSONArray getJSONArray(Map<K, V> map, K key, JSONArray dft) {
        JSONArray array = getJSONArray(map, key);
        return Objects.isNull(array) ? dft : array;
    }

}
