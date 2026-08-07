package io.github.nasaruntime.core.utils;

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
     * 业务作用：把任意对象转换成 JSON 对象结构。
     *
     * @param map 源映射
     * 返回: JSON 对象。
     */
    public static <K, V> JSONObject toJSONObject(Map<K, V> map) {
        return JSONObject.from(map);
    }

    /**
     * 业务作用：按键取出嵌套的 JSON 对象。
     *
     * @param map 源映射
     * @param key 键
     * 返回: 嵌套对象；键不存在或类型不符时返回 null。
     */
    public static <K, V> JSONObject getJSONObject(Map<K, V> map, K key) {
        return MapUtils.getObjectMapper(map, key, JSONObject::from);
    }

    /**
     * 业务作用：按键取出嵌套的 JSON 对象。
     *
     * @param map 源映射
     * @param key 键
     * @param dft 取不到时返回的默认值
     * 返回: 嵌套对象；键不存在或类型不符时返回 null。
     */
    public static <K, V> JSONObject getJSONObject(Map<K, V> map, K key, JSONObject dft) {
        JSONObject json = getJSONObject(map, key);
        return Objects.isNull(json) ? dft : json;
    }

    /**
     * 业务作用：按键取出嵌套的 JSON 数组。
     *
     * @param map 源映射
     * @param key 键
     * 返回: 嵌套数组；键不存在或类型不符时返回 null。
     */
    public static <K, V> JSONArray getJSONArray(Map<K, V> map, K key) {
        return MapUtils.getObjectMapper(map, key, JSONArray::from);
    }

    /**
     * 业务作用：按键取出嵌套的 JSON 数组。
     *
     * @param map 源映射
     * @param key 键
     * @param dft 取不到时返回的默认值
     * 返回: 嵌套数组；键不存在或类型不符时返回 null。
     */
    public static <K, V> JSONArray getJSONArray(Map<K, V> map, K key, JSONArray dft) {
        JSONArray array = getJSONArray(map, key);
        return Objects.isNull(array) ? dft : array;
    }

}
