package com.nasa.runtime.core.interceptor;

import com.nasa.runtime.core.base.AnyHolder;
import com.nasa.runtime.core.utils.Translator;

import java.util.Objects;

/**
 * Nasa
 * 线程的语言类型
 */
public abstract class LangHolder {

    private static final String KEY = "__LangHolder";

    /**
     * 设置当前线程的语言类型
     * @param lang 语言类型，如：en、zh-CN
     */
    public static void set(String lang) {
        AnyHolder.putAsMap(KEY, "lang", lang);
    }


    /**
     * 获取当前线程的语言类型
     */
    public static String get() {
        String lang = AnyHolder.getAsMap(KEY, "lang");
        return Objects.isNull(lang) ? Translator.zh : lang;
    }


    /**
     * 清除 ThreadLocal 中当前线程的缓存
     */
    public static void clear() {
        AnyHolder.remove(KEY);
    }


}
