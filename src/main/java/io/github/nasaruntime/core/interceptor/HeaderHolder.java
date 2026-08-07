package io.github.nasaruntime.core.interceptor;

import io.github.nasaruntime.core.base.AnyHolder;
import io.github.nasaruntime.core.utils.MapUtils;
import io.github.nasaruntime.core.utils.StringUtils;

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
     * 业务作用：向当前线程的透传请求头中追加一项，供后续远程调用自动携带。
     * 空白值直接丢弃，避免把无意义的空头透传到下游。
     *
     * @param headerKey 请求头名
     * @param headerValue 请求头值，空白时本次调用不生效
     * 返回: 无返回值；只影响当前线程。
     */
    public static void add(String headerKey, String headerValue) {
        if (StringUtils.isNotBlank(headerValue)) get().put(headerKey, headerValue);
    }

    /**
     * 业务作用：批量追加透传请求头，逐项复用单项添加的空白值过滤规则。
     *
     * @param map 待追加的请求头集合，为空时不做任何事
     * 返回: 无返回值；只影响当前线程。
     */
    public static void add(Map<String, String> map) {
        if (MapUtils.isNotEmpty(map)) map.forEach(HeaderHolder::add);
    }

    /**
     * 业务作用：取得当前线程的透传请求头集合，不存在时惰性创建并绑定，
     * 使调用方可以直接对返回的 map 做写入而不必先判空。
     *
     * 参数说明: 无。
     * 返回: 当前线程持有的可变 map，绝不返回 null。
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
     * 业务作用：按名读取透传请求头。查找前统一转小写，因为 HTTP 头名不区分大小写，
     * 若按原样匹配会因大小写差异漏读。
     *
     * @param header 请求头名，大小写不敏感
     * 返回: 对应的值；不存在时返回 null。
     */
    public static String get(String header) {
        return MapUtils.getString(AnyHolder.getAsMap(KEY), header.toLowerCase());
    }

    /**
     * 业务作用：按名读取透传请求头并转为整数，供数值型头（如重试次数、层级）直接使用。
     *
     * @param header 请求头名，大小写不敏感
     * 返回: 转换后的整数；不存在或无法转换时返回 null。
     */
    public static Integer getInteger(String header) {
        return MapUtils.getInteger(AnyHolder.getAsMap(KEY), header.toLowerCase());
    }

    /**
     * 业务作用：按名读取整数型透传请求头，并在缺失或无法转换时回落到默认值，
     * 供调用方省去空值判断。
     *
     * @param header 请求头名，大小写不敏感
     * @param dft 缺失或转换失败时的回落值
     * 返回: 转换后的整数；不可用时返回 dft。
     */
    public static Integer getInteger(String header, Integer dft) {
        return MapUtils.getInteger(AnyHolder.getAsMap(KEY), header.toLowerCase(), dft);
    }

    /**
     * 业务作用：遍历当前线程的全部透传请求头，供调用方逐项写入到下游请求。
     *
     * @param consumer 接收每对请求头名与值的函数
     * 返回: 无返回值；集合为空时不触发任何回调。
     */
    public static void forEach(BiConsumer<String, String> consumer) {
        get().forEach(consumer);
    }

    /**
     * 业务作用：清除当前线程的透传请求头。线程池复用线程时必须调用，
     * 否则上一个请求的头会被透传到下一个请求的下游调用中，造成越权或串号。
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    public static void clear() {
        AnyHolder.remove(KEY);
    }

}
