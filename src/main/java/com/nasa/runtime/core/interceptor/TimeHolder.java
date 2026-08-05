package com.nasa.runtime.core.interceptor;

import com.nasa.runtime.core.base.AnyHolder;

/**
 * Nasa
 * 接口响应时间
 */
public abstract class TimeHolder {

    private static final String KEY = "__TimeHolder";

    /**
     * 请求时间
     */
    public static void setTime(Long time) {
        AnyHolder.putAsMap(KEY, "time", time);
    }

    /**
     * 请求来源ip
     */
    public static void setIp(String ip) {
        AnyHolder.putAsMap(KEY, "ip", ip);
    }

    /**
     * 请求url
     */
    public static void setUrl(String url) {
        AnyHolder.putAsMap(KEY, "url", url);
    }

    public static Long getTime() {
        return AnyHolder.getAsMap(KEY, "time");
    }

    public static String getIp() {
        return AnyHolder.getAsMap(KEY, "ip");
    }

    public static String getUrl() {
        return AnyHolder.getAsMap(KEY, "url");
    }

    public static void clear() {
        AnyHolder.remove(KEY);
    }
}
