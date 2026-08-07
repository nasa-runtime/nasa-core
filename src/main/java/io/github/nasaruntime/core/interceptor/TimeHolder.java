package io.github.nasaruntime.core.interceptor;

import io.github.nasaruntime.core.base.AnyHolder;

/**
 * Nasa
 * 接口响应时间
 */
public abstract class TimeHolder {

    private static final String KEY = "__TimeHolder";

    /**
     * 业务作用：记录当前线程处理的请求起始时间，供响应耗时统计使用。
     *
     * @param time 请求起始时间戳
     * 返回: 无返回值；只影响当前线程。
     */
    public static void setTime(Long time) {
        AnyHolder.putAsMap(KEY, "time", time);
    }

    /**
     * 业务作用：记录当前线程处理的请求来源 IP，供审计与限流使用。
     *
     * @param ip 请求来源 IP
     * 返回: 无返回值；只影响当前线程。
     */
    public static void setIp(String ip) {
        AnyHolder.putAsMap(KEY, "ip", ip);
    }

    /**
     * 业务作用：记录当前线程处理的请求 URL，供日志与耗时归类使用。
     *
     * @param url 请求 URL
     * 返回: 无返回值；只影响当前线程。
     */
    public static void setUrl(String url) {
        AnyHolder.putAsMap(KEY, "url", url);
    }

    /**
     * 业务作用：读取当前线程记录的请求起始时间。
     *
     * 参数说明: 无。
     * 返回: 请求起始时间戳；未设置时返回 null。
     */
    public static Long getTime() {
        return AnyHolder.getAsMap(KEY, "time");
    }

    /**
     * 业务作用：读取当前线程记录的请求来源 IP。
     *
     * 参数说明: 无。
     * 返回: 请求来源 IP；未设置时返回 null。
     */
    public static String getIp() {
        return AnyHolder.getAsMap(KEY, "ip");
    }

    /**
     * 业务作用：读取当前线程记录的请求 URL。
     *
     * 参数说明: 无。
     * 返回: 请求 URL；未设置时返回 null。
     */
    public static String getUrl() {
        return AnyHolder.getAsMap(KEY, "url");
    }

    /**
     * 业务作用：清除当前线程记录的全部请求信息。线程池复用线程时必须调用，
     * 否则上一个请求的时间、IP 和 URL 会泄漏到下一个请求的日志里。
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    public static void clear() {
        AnyHolder.remove(KEY);
    }
}
