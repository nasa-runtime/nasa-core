package io.github.nasaruntime.core.interceptor;

import io.github.nasaruntime.core.base.AnyHolder;
import io.github.nasaruntime.core.utils.Translator;

import java.util.Objects;

/**
 * Nasa
 * 线程的语言类型
 */
public abstract class LangHolder {

    private static final String KEY = "__LangHolder";

    /**
     * 业务作用：把语言类型绑定到当前线程，供后续的文案翻译按请求方语言输出。
     *
     * @param lang 语言类型，如 en、zh-CN
     * 返回: 无返回值；只影响当前线程，跨线程调用需自行传递。
     */
    public static void set(String lang) {
        AnyHolder.putAsMap(KEY, "lang", lang);
    }


    /**
     * 业务作用：读取当前线程的语言类型。未设置时回落到中文而不是返回 null，
     * 使调用方无需在每个翻译点做空值判断。
     *
     * 参数说明: 无。
     * 返回: 当前线程的语言类型；未设置时返回默认中文标识。
     */
    public static String get() {
        String lang = AnyHolder.getAsMap(KEY, "lang");
        return Objects.isNull(lang) ? Translator.zh : lang;
    }


    /**
     * 业务作用：清除当前线程绑定的语言类型。线程池复用线程时必须调用，
     * 否则上一个请求的语言会泄漏给下一个请求。
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    public static void clear() {
        AnyHolder.remove(KEY);
    }


}
