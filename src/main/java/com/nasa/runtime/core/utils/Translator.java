package com.nasa.runtime.core.utils;

import com.alibaba.fastjson2.JSONArray;
import com.nasa.runtime.core.cache.DefaultSimpleCache;
import com.nasa.runtime.core.cache.Reloadable;
import com.nasa.runtime.core.cache.SimpleCache;
import com.nasa.runtime.core.concurrent.SyncLock;
import com.nasa.runtime.core.interceptor.LangHolder;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Nasa
 * Google翻译工具，国际化语言转换
 */
@SuppressWarnings("unused")
@Slf4j
public abstract class Translator {

    public static final String zh = "zh-CN";
    private static final String URL = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl={}&tl={}&q={}";
    private static final String Mark_zx = "->";
    private static final String Encode_UTF8 = StandardCharsets.UTF_8.name();
    private static final String USER_AGENT = "User-Agent";
    private static final String USER_AGENT_VAL = "Nasa.1.0.0";
    private static final int Connect_timeout_ms = Integer.getInteger("nasa.translate.connect-timeout-ms", 3000);
    private static final int Read_timeout_ms = Integer.getInteger("nasa.translate.read-timeout-ms", 5000);
    /* 语言类型映射关系 */
    private static final Map<String, String> langMapper = new HashMap<>();
    /* 缓存策略接口 */
    private static SimpleCache<String, String> simpleCache = new DefaultSimpleCache<>();
    /* 开启Google在线翻译 */
    private static boolean enable = false;


    /**
     * 开启Google在线翻译
     */
    public static void enable() {
        enable = true;
    }


    /**
     * 关闭Google在线翻译
     */
    public static void disable() {
        enable = false;
    }


    /**
     * 添加语言映射关系
     * 如：
     * langMapper.put("zh-Hans", "zh-CN"); 将zh-Hans映射成Google识别的zh-CN，表示简体中文
     * langMapper.put("zh-HK", "zh-TW");将zh-HK映射成Google识别的zh-TW，表示繁体中文
     * @param mapper 语言映射关系
     */
    public static void putAllMapper(Map<String, String> mapper) {
        langMapper.putAll(mapper);
    }


    /**
     * 添加语言映射关系
     * 如：
     * langMapper.put("zh-Hans", "zh-CN"); 将zh-Hans映射成Google识别的zh-CN，表示简体中文
     * langMapper.put("zh-HK", "zh-TW");将zh-HK映射成Google识别的zh-TW，表示繁体中文
     * @param k 原语言标识
     * @param v 原语言映射标识
     */
    public static void putMapper(String k, String v) {
        langMapper.put(k, v);
    }


    /**
     * 设置缓存策略
     */
    public static void setSimpleCache(SimpleCache<String, String> simpleCache) {
        Translator.simpleCache = simpleCache;
    }


    /**
     * 获取缓存策略
     */
    public static SimpleCache<String, String> getSimpleCache() {
        return Translator.simpleCache;
    }


    /**
     * 重载缓存
     */
    public static void reloadCache() {
        if (Reloadable.class.isAssignableFrom(simpleCache.getClass())) {
            ((Reloadable) simpleCache).reload();
        }
    }


    /**
     * Google翻译
     * 将中文翻译成指定语言
     * @param word 转换的内容
     */
    public static String translate(String word) {
        String langTo = LangHolder.get();
        if (StringUtils.isBlank(langTo)) {
            return word;
        }
        return translate(langTo, word);
    }


    /**
     * Google翻译
     * 将中文翻译成指定语言
     * @param langTo 转换的语言
     * @param word 转换的内容
     */
    public static String translate(String langTo, String word) {
        return translate(zh, langTo, word);
    }


    /**
     * Google翻译
     * @param langFrom 原来的语言
     * @param langTo 转换的语言
     * @param word 转换的内容
     */
    public static String translate(String langFrom, String langTo, String word) {
        if (!enable || StringUtils.isBlank(word)) {
            return word;
        }
        String al = langMapper.get(langTo);
        if (StringUtils.isNotBlank(al)) {
            langTo = al;
        }
        if (langFrom.equals(langTo)) {
            return word;
        }
        try {
            String url = StringUtils.format(URL, langFrom, langTo, URLEncoder.encode(word, Encode_UTF8));
            if (Objects.isNull(simpleCache)) {
                // 不存在缓存策略，执行远程翻译
                String val = applyTranslate(url);
                return StringUtils.isBlank(val) ? word : val;
            }

            // 构建缓存key
            String cacheKey = StringUtils.concat(langFrom, Mark_zx, langTo, StringUtils.Mark_colon, word);

            // 设置了缓存策略，先取缓存
            String val = simpleCache.get(cacheKey);
            if (StringUtils.isNotBlank(val)) {
                return val;
            }

            final String lt = langTo;

            // 注意：在使用String进行同步的时候，一定要使用intern()方法，表示同步String的值
            // synchronized (url.intern()) {}
            // 但是，强烈建议不要这样使用！！！
            // 请看资料：http://www.JVMshuo.com/article/p-wnwhtbqv-u.html
            //
            // 这里也强烈建议不要使用Google的Guava实现的Striped实现lock
            // Striped放弃了String的equals，而是采用了idx方式，去求取String的hash值
            // 这样的话不同的key就有可能进入同一个hash桶，从而获取同一个锁，最终导致死锁！！！
            // 请看资料：https://www.iflym.com/index.php/code/201611190001.html
            //
            // 以下是我自己实现的同步方案，想研究的请自行去看源码
            return SyncLock.lock(Translator.class.getName() + url, () -> {
                String wt = simpleCache.get(cacheKey);
                if (StringUtils.isNotBlank(wt)) {
                    return wt;
                }
                // 执行远程翻译
                wt = applyTranslate(url);
                if (StringUtils.isNotBlank(wt)) {
                    log.info("Google翻译key：{}，语言：{}，结果：{}", word, lt, wt);
                    try {
                        simpleCache.put(cacheKey, wt);
                    } catch (Throwable t) {
                        log.error("Google翻译缓存失败：\nkey：{}\nvalue：{}\n异常：{}", cacheKey, wt, t.getMessage(), t);
                    }
                    return wt;
                }
                // 翻译失败，返回原值
                return word;
            });

        } catch (Exception e) {
            log.error("Google翻译失败：{}", e.getMessage(), e);
            return word;
        }
    }


    /**
     * 业务作用: 调用远程翻译接口并解析首个译文，网络或响应异常时返回空值供上层回退原文。
     *
     * @param url 已完成参数编码的远程接口地址
     * @return 翻译结果，调用失败或响应不合法时返回 {@code null}
     */
    private static String applyTranslate(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(Connect_timeout_ms);
            connection.setReadTimeout(Read_timeout_ms);
            connection.setRequestProperty(USER_AGENT, USER_AGENT_VAL);

            // 响应编码必须固定，避免部署机器默认字符集不同导致译文损坏。
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String inputLine;
                StringBuilder response = new StringBuilder();
                while (Objects.nonNull(inputLine = reader.readLine())) {
                    response.append(inputLine);
                }
                return JSONArray.parse(response.toString()).getJSONArray(0).getJSONArray(0).getString(0);
            }
        } catch (Exception e) {
            log.error("Google翻译失败：{}", e.getMessage(), e);
            return null;
        } finally {
            if (Objects.nonNull(connection)) {
                connection.disconnect();
            }
        }
    }

}
