package io.github.nasaruntime.core.utils;

import io.github.nasaruntime.core.base.KV;
import io.github.nasaruntime.core.exception.DateParseException;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

/**
 * Nasa
 * 日期时间工具
 * 全局变量的SimpleDateFormat，在并发情况下，存在安全性问题。
 * SimpleDateFormat继承了 DateFormat
 * DateFormat类中维护了一个全局的Calendar变量
 * sdf.parse(dateStr)和sdf.format(date)，都是由Calendar引用来储存的。
 * 如果SimpleDateFormat是static全局共享的，Calendar引用也会被共享。
 * 又因为Calendar内部并没有线程安全机制，所以全局共享的SimpleDateFormat不是线性安全的。
 */
@SuppressWarnings("unused")
public abstract class DateUtils {

    /* 一天的秒数 */
    public static final int day_s = 24 * 60 * 60;
    /* 一天的毫秒数 */
    public static final int day_ms = day_s * 1000;

    public static final String f_y_M = "yyyy-MM";
    public static final String f_y_M_d = "yyyy-MM-dd";
    public static final String f_y_M_d_H_m_s = "yyyy-MM-dd HH:mm:ss";
    public static final String f_yM = "yyyyMM";
    public static final String f_yMd = "yyyyMMdd";
    public static final String f_yMdHms = "yyyyMMddHHmmss";
    public static final String f_yM_path = "yyyy/MM";
    public static final String f_yMd_path = "yyyy/MM/dd";
    public static final String f_yMdHms_path = "yyyy/MM/dd HH:mm:ss";

    /* 缓存SimpleDateFormat */
    private static final ThreadLocal<Map<String, SimpleDateFormat>> sdfLocal = new ThreadLocal<>();

    /* 时区 */
    /* 北京-上海 */
    public static final TimeZone GMT8 = TimeZone.getTimeZone("GMT+8");

    /* 缓存日期格式的正则表达式 */
    private static final List<KV<String, Pattern>> formats = new ArrayList<>();

    static {
        formats.add(KV.of(f_y_M_d_H_m_s, Pattern.compile("^\\d{4}-\\d{2}-\\d{2}\\s\\d{2}:\\d{2}:\\d{2}$")));
        formats.add(KV.of(f_y_M_d, Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$")));
        formats.add(KV.of(f_y_M, Pattern.compile("^\\d{4}-\\d{2}$")));
        formats.add(KV.of(f_yM, Pattern.compile("^\\d{6}$")));
        formats.add(KV.of(f_yMd, Pattern.compile("^\\d{8}$")));
        formats.add(KV.of(f_yMdHms, Pattern.compile("^\\d{14}$")));
        formats.add(KV.of("yyyyMMddHH", Pattern.compile("^\\d{10}$")));
        formats.add(KV.of("yyyyMMddHHmm", Pattern.compile("^\\d{12}$")));
        formats.add(KV.of("yyyyMMddHHmmss.SSS", Pattern.compile("^\\d{14}.\\d{1,3}$")));
        formats.add(KV.of("yyyy-MM-dd HH:mm:ss.SSS", Pattern.compile("^\\d{4}-\\d{2}-\\d{2}\\s\\d{2}:\\d{2}:\\d{2}.\\d{1,3}$")));
        formats.add(KV.of(f_yMdHms_path, Pattern.compile("^\\d{4}/\\d{2}/\\d{2}\\s\\d{2}:\\d{2}:\\d{2}$")));
        formats.add(KV.of(f_yMd_path, Pattern.compile("^\\d{4}/\\d{2}/\\d{2}$")));
        formats.add(KV.of(f_yM_path, Pattern.compile("^\\d{4}/\\d{2}$")));
        formats.add(KV.of("yyyy/MM/dd HH:mm:ss.SSS", Pattern.compile("^\\d{4}/\\d{2}/\\d{2}\\s\\d{2}:\\d{2}:\\d{2}.\\d{1,3}$")));
        formats.add(KV.of("yyyy", Pattern.compile("^\\d{4}$")));
        formats.add(KV.of("HH:mm:ss", Pattern.compile("^\\d{2}:\\d{2}:\\d{2}$")));
        formats.add(KV.of("HH:mm:ss.SSS", Pattern.compile("^\\d{2}:\\d{2}:\\d{2}.\\d{1,3}$")));
        formats.add(KV.of("yyyy-MM-dd HH:mm", Pattern.compile("^\\d{4}-\\d{2}-\\d{2}\\s\\d{2}:\\d{2}$")));
        formats.add(KV.of("yyyy-MM-dd HH", Pattern.compile("^\\d{4}-\\d{2}-\\d{2}\\s\\d{2}$")));
        formats.add(KV.of("yyyy/MM/dd HH:mm", Pattern.compile("^\\d{4}/\\d{2}/\\d{2}\\s\\d{2}:\\d{2}$")));
        formats.add(KV.of("yyyy/MM/dd HH", Pattern.compile("^\\d{4}/\\d{2}/\\d{2}\\s\\d{2}$")));
        formats.add(KV.of("yyyy-MM-dd'T'HH:mm:ss", Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}$")));
        formats.add(KV.of("yyyy-MM-dd'T'HH:mm:ss.SSS", Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.\\d{1,3}$")));
        formats.add(KV.of("yyyy/MM/dd'T'HH:mm:ss", Pattern.compile("^\\d{4}/\\d{2}/\\d{2}T\\d{2}:\\d{2}:\\d{2}$")));
        formats.add(KV.of("yyyy/MM/dd'T'HH:mm:ss.SSS", Pattern.compile("^\\d{4}/\\d{2}/\\d{2}T\\d{2}:\\d{2}:\\d{2}.\\d{1,3}$")));
    }


    /**
     * 业务作用：按内置正则表逐条匹配，推断日期字符串采用的格式，供调用方在格式未知时先识别再解析。
     *
     * @param dt 待处理的日期时间字符串
     * 返回: 匹配到的格式串；没有任何内置格式匹配时抛出 DateParseException。
     */
    public static String getFormat(String dt) {
        for (KV<String, Pattern> kv : formats) {
            if (kv.getValue().matcher(dt).matches()) {
                return kv.getKey();
            }
        }
        throw new DateParseException("不存在的日期格式");
    }


    /**
     * 业务作用：取得绑定指定时区与格式的 SimpleDateFormat。
     * SimpleDateFormat 本身线程不安全，因此这里按线程缓存实例，调用方不得把返回的实例跨线程共享。
     *
     * 参数说明: 无。
     * 返回: 当前线程可安全使用的格式化器。
     */
    public static SimpleDateFormat dateFormat() {
        return dateFormat(f_y_M_d_H_m_s);
    }


    /**
     * 业务作用：取得绑定指定时区与格式的 SimpleDateFormat。
     * SimpleDateFormat 本身线程不安全，因此这里按线程缓存实例，调用方不得把返回的实例跨线程共享。
     *
     * @param f 日期格式串，同时决定精度
     * 返回: 当前线程可安全使用的格式化器。
     */
    public static SimpleDateFormat dateFormat(String f) {
        return dateFormat(GMT8, f);
    }


    /**
     * 业务作用：取得绑定指定时区与格式的 SimpleDateFormat。
     * SimpleDateFormat 本身线程不安全，因此这里按线程缓存实例，调用方不得把返回的实例跨线程共享。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param f 日期格式串，同时决定精度
     * 返回: 当前线程可安全使用的格式化器。
     */
    public static SimpleDateFormat dateFormat(TimeZone timeZone, String f) {
        if (Thread.currentThread().isVirtual()) {
            // 虚拟线程每次都创建一个
            return dateFormatFactory(timeZone, f);
        }
        String key = timeZone.getID() + f;
        Map<String, SimpleDateFormat> sdfMap = sdfLocal.get();
        if (Objects.isNull(sdfMap)) {
            sdfMap = new HashMap<>();
            sdfLocal.set(sdfMap);
            SimpleDateFormat dateFormat = dateFormatFactory(timeZone, f);
            sdfMap.put(key, dateFormat);
            return dateFormat;
        }
        SimpleDateFormat dateFormat = sdfMap.get(key);
        if (Objects.nonNull(dateFormat)) {
            return dateFormat;
        }
        dateFormat = dateFormatFactory(timeZone, f);
        sdfMap.put(key, dateFormat);
        return dateFormat;
    }


    /**
     * 业务作用：创建绑定指定时区与格式的 SimpleDateFormat 新实例，不参与线程缓存。
     * 供需要独占实例、或缓存策略不适用的调用方使用。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param f 日期格式串，同时决定精度
     * 返回: 新建的格式化器实例。
     */
    private static SimpleDateFormat dateFormatFactory(TimeZone timeZone, String f) {
        SimpleDateFormat dateFormat = new SimpleDateFormat(f);
        dateFormat.setTimeZone(timeZone);
        return dateFormat;
    }


    /**
     * 业务作用：把时间按给定格式渲染成字符串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param f 日期格式串，同时决定精度
     * 返回: 格式化后的日期时间字符串。
     */
    public static String format(Date date, String f) {
        return dateFormat(f).format(date);
    }


    /**
     * 业务作用：把时间按给定格式渲染成字符串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * @param f 日期格式串，同时决定精度
     * 返回: 格式化后的日期时间字符串。
     */
    public static String format(TimeZone timeZone, Date date, String f) {
        return dateFormat(timeZone, f).format(date);
    }


    /**
     * 业务作用：把时间按给定格式渲染成字符串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param f 日期格式串，同时决定精度
     * 返回: 格式化后的日期时间字符串。
     */
    public static String format(long date, String f) {
        return dateFormat(f).format(date);
    }


    /**
     * 业务作用：把时间按给定格式渲染成字符串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * @param f 日期格式串，同时决定精度
     * 返回: 格式化后的日期时间字符串。
     */
    public static String format(TimeZone timeZone, long date, String f) {
        return dateFormat(timeZone, f).format(date);
    }


    /**
     * 业务作用：把时间按给定格式渲染成字符串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * 返回: 格式化后的日期时间字符串。
     */
    public static String format(Date date) {
        return format(date, f_y_M_d_H_m_s);
    }


    /**
     * 业务作用：把时间按给定格式渲染成字符串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * 返回: 格式化后的日期时间字符串。
     */
    public static String format(TimeZone timeZone, Date date) {
        return format(timeZone, date, f_y_M_d_H_m_s);
    }


    /**
     * 业务作用：把时间按给定格式渲染成字符串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * 返回: 格式化后的日期时间字符串。
     */
    public static String format(long date) {
        return format(date, f_y_M_d_H_m_s);
    }


    /**
     * 业务作用：把时间按给定格式渲染成字符串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * 返回: 格式化后的日期时间字符串。
     */
    public static String format(TimeZone timeZone, long date) {
        return format(timeZone, date, f_y_M_d_H_m_s);
    }


    /**
     * 业务作用：把时间按固定格式 yyyy-MM-dd 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * 返回: 形如 yyyy-MM-dd 的日期时间字符串。
     */
    public static String format_y_M_d(Date date) {
        return format(date, f_y_M_d);
    }


    /**
     * 业务作用：把时间按固定格式 yyyy-MM-dd 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * 返回: 形如 yyyy-MM-dd 的日期时间字符串。
     */
    public static String format_y_M_d(TimeZone timeZone, Date date) {
        return format(timeZone, date, f_y_M_d);
    }


    /**
     * 业务作用：把时间按固定格式 yyyy-MM-dd 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * 返回: 形如 yyyy-MM-dd 的日期时间字符串。
     */
    public static String format_y_M_d(long date) {
        return format(date, f_y_M_d);
    }


    /**
     * 业务作用：把时间按固定格式 yyyy-MM-dd 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * 返回: 形如 yyyy-MM-dd 的日期时间字符串。
     */
    public static String format_y_M_d(TimeZone timeZone, long date) {
        return format(timeZone, date, f_y_M_d);
    }


    /**
     * 业务作用：把时间按固定格式 yyyy-MM 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * 返回: 形如 yyyy-MM 的日期时间字符串。
     */
    public static String format_y_M(Date date) {
        return format(date, f_y_M);
    }


    /**
     * 业务作用：把时间按固定格式 yyyy-MM 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * 返回: 形如 yyyy-MM 的日期时间字符串。
     */
    public static String format_y_M(TimeZone timeZone, Date date) {
        return format(timeZone, date, f_y_M);
    }


    /**
     * 业务作用：把时间按固定格式 yyyy-MM 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * 返回: 形如 yyyy-MM 的日期时间字符串。
     */
    public static String format_y_M(long date) {
        return format(date, f_y_M);
    }


    /**
     * 业务作用：把时间按固定格式 yyyy-MM 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * 返回: 形如 yyyy-MM 的日期时间字符串。
     */
    public static String format_y_M(TimeZone timeZone, long date) {
        return format(timeZone, date, f_y_M);
    }


    /**
     * 业务作用：把时间按固定格式 yyyyMM 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * 返回: 形如 yyyyMM 的日期时间字符串。
     */
    public static String format_yM(Date date) {
        return format(date, f_yM);
    }


    /**
     * 业务作用：把时间按固定格式 yyyyMM 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * 返回: 形如 yyyyMM 的日期时间字符串。
     */
    public static String format_yM(TimeZone timeZone, Date date) {
        return format(timeZone, date, f_yM);
    }


    /**
     * 业务作用：把时间按固定格式 yyyyMM 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * 返回: 形如 yyyyMM 的日期时间字符串。
     */
    public static String format_yM(long date) {
        return format(date, f_yM);
    }


    /**
     * 业务作用：把时间按固定格式 yyyyMM 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * 返回: 形如 yyyyMM 的日期时间字符串。
     */
    public static String format_yM(TimeZone timeZone, long date) {
        return format(timeZone, date, f_yM);
    }


    /**
     * 业务作用：把时间按固定格式 yyyyMMdd 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * 返回: 形如 yyyyMMdd 的日期时间字符串。
     */
    public static String format_yMd(Date date) {
        return format(date, f_yMd);
    }


    /**
     * 业务作用：把时间按固定格式 yyyyMMdd 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * 返回: 形如 yyyyMMdd 的日期时间字符串。
     */
    public static String format_yMd(TimeZone timeZone, Date date) {
        return format(timeZone, date, f_yMd);
    }


    /**
     * 业务作用：把时间按固定格式 yyyyMMdd 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * 返回: 形如 yyyyMMdd 的日期时间字符串。
     */
    public static String format_yMd(long date) {
        return format(date, f_yMd);
    }


    /**
     * 业务作用：把时间按固定格式 yyyyMMdd 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * 返回: 形如 yyyyMMdd 的日期时间字符串。
     */
    public static String format_yMd(TimeZone timeZone, long date) {
        return format(timeZone, date, f_yMd);
    }


    /**
     * 业务作用：把时间按固定格式 yyyyMMddHHmmss 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * 返回: 形如 yyyyMMddHHmmss 的日期时间字符串。
     */
    public static String format_yMdHms(Date date) {
        return format(date, f_yMdHms);
    }


    /**
     * 业务作用：把时间按固定格式 yyyyMMddHHmmss 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * 返回: 形如 yyyyMMddHHmmss 的日期时间字符串。
     */
    public static String format_yMdHms(TimeZone timeZone, Date date) {
        return format(timeZone, date, f_yMdHms);
    }


    /**
     * 业务作用：把时间按固定格式 yyyyMMddHHmmss 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * 返回: 形如 yyyyMMddHHmmss 的日期时间字符串。
     */
    public static String format_yMdHms(long date) {
        return format(date, f_yMdHms);
    }


    /**
     * 业务作用：把时间按固定格式 yyyyMMddHHmmss 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * 返回: 形如 yyyyMMddHHmmss 的日期时间字符串。
     */
    public static String format_yMdHms(TimeZone timeZone, long date) {
        return format(timeZone, date, f_yMdHms);
    }


    /**
     * 业务作用：把时间按固定格式 yyyy/MM 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * 返回: 形如 yyyy/MM 的日期时间字符串。
     */
    public static String format_yM_path(Date date) {
        return format(date, f_yM_path);
    }


    /**
     * 业务作用：把时间按固定格式 yyyy/MM 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * 返回: 形如 yyyy/MM 的日期时间字符串。
     */
    public static String format_yM_path(TimeZone timeZone, Date date) {
        return format(timeZone, date, f_yM_path);
    }


    /**
     * 业务作用：把时间按固定格式 yyyy/MM 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * 返回: 形如 yyyy/MM 的日期时间字符串。
     */
    public static String format_yM_path(long date) {
        return format(date, f_yM_path);
    }


    /**
     * 业务作用：把时间按固定格式 yyyy/MM 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * 返回: 形如 yyyy/MM 的日期时间字符串。
     */
    public static String format_yM_path(TimeZone timeZone, long date) {
        return format(timeZone, date, f_yM_path);
    }


    /**
     * 业务作用：把时间按固定格式 yyyy/MM/dd 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * 返回: 形如 yyyy/MM/dd 的日期时间字符串。
     */
    public static String format_yMd_path(Date date) {
        return format(date, f_yMd_path);
    }


    /**
     * 业务作用：把时间按固定格式 yyyy/MM/dd 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * 返回: 形如 yyyy/MM/dd 的日期时间字符串。
     */
    public static String format_yMd_path(TimeZone timeZone, Date date) {
        return format(timeZone, date, f_yMd_path);
    }


    /**
     * 业务作用：把时间按固定格式 yyyy/MM/dd 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * 返回: 形如 yyyy/MM/dd 的日期时间字符串。
     */
    public static String format_yMd_path(long date) {
        return format(date, f_yMd_path);
    }


    /**
     * 业务作用：把时间按固定格式 yyyy/MM/dd 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * 返回: 形如 yyyy/MM/dd 的日期时间字符串。
     */
    public static String format_yMd_path(TimeZone timeZone, long date) {
        return format(timeZone, date, f_yMd_path);
    }


    /**
     * 业务作用：把时间按固定格式 yyyy/MM/dd HH:mm:ss 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * 返回: 形如 yyyy/MM/dd HH:mm:ss 的日期时间字符串。
     */
    public static String format_yMdHms_path(Date date) {
        return format(date, f_yMdHms_path);
    }


    /**
     * 业务作用：把时间按固定格式 yyyy/MM/dd HH:mm:ss 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * 返回: 形如 yyyy/MM/dd HH:mm:ss 的日期时间字符串。
     */
    public static String format_yMdHms_path(TimeZone timeZone, Date date) {
        return format(timeZone, date, f_yMdHms_path);
    }


    /**
     * 业务作用：把时间按固定格式 yyyy/MM/dd HH:mm:ss 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * 返回: 形如 yyyy/MM/dd HH:mm:ss 的日期时间字符串。
     */
    public static String format_yMdHms_path(long date) {
        return format(date, f_yMdHms_path);
    }


    /**
     * 业务作用：把时间按固定格式 yyyy/MM/dd HH:mm:ss 渲染成字符串，省去调用方重复书写格式串。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * 返回: 形如 yyyy/MM/dd HH:mm:ss 的日期时间字符串。
     */
    public static String format_yMdHms_path(TimeZone timeZone, long date) {
        return format(timeZone, date, f_yMdHms_path);
    }


    /**
     * 业务作用：把日期时间字符串解析为 Date。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * 返回: 解析得到的时间；字符串与格式不匹配时抛出 DateParseException。
     */
    public static Date parse(String dt, String f) {
        return parse(GMT8, dt, f);
    }


    /**
     * 业务作用：把日期时间字符串解析为 Date。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * 返回: 解析得到的时间；字符串与格式不匹配时抛出 DateParseException。
     */
    public static Date parse(TimeZone timeZone, String dt, String f) {
        try {
            return dateFormat(timeZone, f).parse(dt);
        } catch (ParseException e) {
            throw new DateParseException(e.getMessage(), e);
        }
    }


    /**
     * 业务作用：把日期时间字符串解析为 Date。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * 返回: 解析得到的时间；字符串与格式不匹配时抛出 DateParseException。
     */
    public static Date parse(String dt) {
        return parse(dt, getFormat(dt));
    }


    /**
     * 业务作用：把日期时间字符串解析为 Date。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * 返回: 解析得到的时间；字符串与格式不匹配时抛出 DateParseException。
     */
    public static Date parse(TimeZone timeZone, String dt) {
        return parse(timeZone, dt, getFormat(dt));
    }


    /**
     * 业务作用：把日期时间字符串解析为毫秒时间戳。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * 返回: 解析得到的毫秒时间戳；字符串与格式不匹配时抛出 DateParseException。
     */
    public static long parseLong(String dt, String f) {
        return parse(dt, f).getTime();
    }


    /**
     * 业务作用：把日期时间字符串解析为毫秒时间戳。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * 返回: 解析得到的毫秒时间戳；字符串与格式不匹配时抛出 DateParseException。
     */
    public static long parseLong(TimeZone timeZone, String dt, String f) {
        return parse(timeZone, dt, f).getTime();
    }


    /**
     * 业务作用：把日期时间字符串解析为毫秒时间戳。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * 返回: 解析得到的毫秒时间戳；字符串与格式不匹配时抛出 DateParseException。
     */
    public static long parseLong(String dt) {
        return parseLong(dt, getFormat(dt));
    }


    /**
     * 业务作用：把日期时间字符串解析为毫秒时间戳。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * 返回: 解析得到的毫秒时间戳；字符串与格式不匹配时抛出 DateParseException。
     */
    public static long parseLong(TimeZone timeZone, String dt) {
        return parseLong(timeZone, dt, getFormat(dt));
    }


    /**
     * 业务作用：按 Calendar 字段成对给出的调整项一次性完成多项时间增减，避免逐项调用产生中间值。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param millis 增加的毫秒数，可为负表示回退
     * @param params 按 Calendar 字段与增量成对给出的调整项
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static long add(long millis, int... params) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        for (int i = 0; i < params.length; i = i + 2) {
            if (i + 1 == params.length) {
                c.add(params[i], 0);
            } else {
                c.add(params[i], params[i + 1]);
            }
        }
        return c.getTimeInMillis();
    }


    /**
     * 业务作用：按 Calendar 字段成对给出的调整项一次性完成多项时间增减，避免逐项调用产生中间值。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param params 按 Calendar 字段与增量成对给出的调整项
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static Date add(Date date, int... params) {
        return new Date(add(date.getTime(), params));
    }


    /**
     * 业务作用：按 Calendar 字段成对给出的调整项一次性完成多项时间增减，避免逐项调用产生中间值。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * @param params 按 Calendar 字段与增量成对给出的调整项
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String add(String dt, int... params) {
        return format(add(parse(dt), params));
    }


    /**
     * 业务作用：按 Calendar 字段成对给出的调整项一次性完成多项时间增减，避免逐项调用产生中间值。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * @param params 按 Calendar 字段与增量成对给出的调整项
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String add(TimeZone timeZone, String dt, int... params) {
        return format(add(parse(timeZone, dt), params));
    }


    /**
     * 业务作用：按 Calendar 字段成对给出的调整项一次性完成多项时间增减，避免逐项调用产生中间值。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * @param params 按 Calendar 字段与增量成对给出的调整项
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String add(String dt, String f, int... params) {
        return format(add(parse(dt, f), params), f);
    }


    /**
     * 业务作用：按 Calendar 字段成对给出的调整项一次性完成多项时间增减，避免逐项调用产生中间值。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * @param params 按 Calendar 字段与增量成对给出的调整项
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String add(TimeZone timeZone, String dt, String f, int... params) {
        return format(add(parse(timeZone, dt, f), params), f);
    }


    /**
     * 业务作用：在给定时间上增减指定毫秒数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param millis 增加的毫秒数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static long addMillis(long date, int millis) {
        return add(date, Calendar.MILLISECOND, millis);
    }


    /**
     * 业务作用：在给定时间上增减指定毫秒数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param millis 增加的毫秒数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static Date addMillis(Date date, int millis) {
        return add(date, Calendar.MILLISECOND, millis);
    }


    /**
     * 业务作用：在给定时间上增减指定毫秒数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * @param millis 增加的毫秒数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addMillis(String dt, int millis) {
        return add(dt, Calendar.MILLISECOND, millis);
    }


    /**
     * 业务作用：在给定时间上增减指定毫秒数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * @param millis 增加的毫秒数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addMillis(TimeZone timeZone, String dt, int millis) {
        return add(timeZone, dt, Calendar.MILLISECOND, millis);
    }


    /**
     * 业务作用：在给定时间上增减指定毫秒数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * @param millis 增加的毫秒数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addMillis(String dt, String f, int millis) {
        return add(dt, f, Calendar.MILLISECOND, millis);
    }


    /**
     * 业务作用：在给定时间上增减指定毫秒数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * @param millis 增加的毫秒数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addMillis(TimeZone timeZone, String dt, String f, int millis) {
        return add(timeZone, dt, f, Calendar.MILLISECOND, millis);
    }


    /**
     * 业务作用：在给定时间上增减指定秒数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param seconds 增加的秒数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static long addSeconds(long date, int seconds) {
        return add(date, Calendar.SECOND, seconds);
    }


    /**
     * 业务作用：在给定时间上增减指定秒数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param seconds 增加的秒数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static Date addSeconds(Date date, int seconds) {
        return add(date, Calendar.SECOND, seconds);
    }


    /**
     * 业务作用：在给定时间上增减指定秒数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * @param seconds 增加的秒数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addSeconds(String dt, int seconds) {
        return add(dt, Calendar.SECOND, seconds);
    }


    /**
     * 业务作用：在给定时间上增减指定秒数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * @param seconds 增加的秒数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addSeconds(TimeZone timeZone, String dt, int seconds) {
        return add(timeZone, dt, Calendar.SECOND, seconds);
    }


    /**
     * 业务作用：在给定时间上增减指定秒数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * @param seconds 增加的秒数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addSeconds(String dt, String f, int seconds) {
        return add(dt, f, Calendar.SECOND, seconds);
    }


    /**
     * 业务作用：在给定时间上增减指定秒数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * @param seconds 增加的秒数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addSeconds(TimeZone timeZone, String dt, String f, int seconds) {
        return add(timeZone, dt, f, Calendar.SECOND, seconds);
    }


    /**
     * 业务作用：在给定时间上增减指定分钟数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param minutes 增加的分钟数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static long addMinutes(long date, int minutes) {
        return add(date, Calendar.MINUTE, minutes);
    }


    /**
     * 业务作用：在给定时间上增减指定分钟数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param minutes 增加的分钟数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static Date addMinutes(Date date, int minutes) {
        return add(date, Calendar.MINUTE, minutes);
    }


    /**
     * 业务作用：在给定时间上增减指定分钟数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * @param minutes 增加的分钟数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addMinutes(String dt, int minutes) {
        return add(dt, Calendar.MINUTE, minutes);
    }


    /**
     * 业务作用：在给定时间上增减指定分钟数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * @param minutes 增加的分钟数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addMinutes(TimeZone timeZone, String dt, int minutes) {
        return add(timeZone, dt, Calendar.MINUTE, minutes);
    }


    /**
     * 业务作用：在给定时间上增减指定分钟数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * @param minutes 增加的分钟数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addMinutes(String dt, String f, int minutes) {
        return add(dt, f, Calendar.MINUTE, minutes);
    }


    /**
     * 业务作用：在给定时间上增减指定分钟数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * @param minutes 增加的分钟数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addMinutes(TimeZone timeZone, String dt, String f, int minutes) {
        return add(timeZone, dt, f, Calendar.MINUTE, minutes);
    }


    /**
     * 业务作用：在给定时间上增减指定小时数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param hours 增加的小时数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static long addHours(long date, int hours) {
        return add(date, Calendar.HOUR_OF_DAY, hours);
    }


    /**
     * 业务作用：在给定时间上增减指定小时数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param hours 增加的小时数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static Date addHours(Date date, int hours) {
        return add(date, Calendar.HOUR_OF_DAY, hours);
    }


    /**
     * 业务作用：在给定时间上增减指定小时数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * @param hours 增加的小时数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addHours(String dt, int hours) {
        return add(dt, Calendar.HOUR_OF_DAY, hours);
    }


    /**
     * 业务作用：在给定时间上增减指定小时数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * @param hours 增加的小时数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addHours(TimeZone timeZone, String dt, int hours) {
        return add(timeZone, dt, Calendar.HOUR_OF_DAY, hours);
    }


    /**
     * 业务作用：在给定时间上增减指定小时数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * @param hours 增加的小时数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addHours(String dt, String f, int hours) {
        return add(dt, f, Calendar.HOUR_OF_DAY, hours);
    }


    /**
     * 业务作用：在给定时间上增减指定小时数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * @param hours 增加的小时数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addHours(TimeZone timeZone, String dt, String f, int hours) {
        return add(timeZone, dt, f, Calendar.HOUR_OF_DAY, hours);
    }


    /**
     * 业务作用：在给定时间上增减指定天数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param days 增加的天数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static long addDays(long date, int days) {
        return add(date, Calendar.DAY_OF_MONTH, days);
    }


    /**
     * 业务作用：在给定时间上增减指定天数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param days 增加的天数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static Date addDays(Date date, int days) {
        return add(date, Calendar.DAY_OF_MONTH, days);
    }


    /**
     * 业务作用：在给定时间上增减指定天数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * @param days 增加的天数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addDays(String dt, int days) {
        return add(dt, Calendar.DAY_OF_MONTH, days);
    }


    /**
     * 业务作用：在给定时间上增减指定天数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * @param days 增加的天数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addDays(TimeZone timeZone, String dt, int days) {
        return add(timeZone, dt, Calendar.DAY_OF_MONTH, days);
    }


    /**
     * 业务作用：在给定时间上增减指定天数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * @param days 增加的天数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addDays(String dt, String f, int days) {
        return add(dt, f, Calendar.DAY_OF_MONTH, days);
    }


    /**
     * 业务作用：在给定时间上增减指定天数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * @param days 增加的天数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addDays(TimeZone timeZone, String dt, String f, int days) {
        return add(timeZone, dt, f, Calendar.DAY_OF_MONTH, days);
    }


    /**
     * 业务作用：在给定时间上增减指定星期数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param weeks 增加的星期数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static long addWeeks(long date, int weeks) {
        return add(date, Calendar.WEEK_OF_MONTH, weeks);
    }


    /**
     * 业务作用：在给定时间上增减指定星期数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param weeks 增加的星期数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static Date addWeeks(Date date, int weeks) {
        return add(date, Calendar.WEEK_OF_MONTH, weeks);
    }


    /**
     * 业务作用：在给定时间上增减指定星期数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * @param weeks 增加的星期数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addWeeks(String dt, int weeks) {
        return add(dt, Calendar.WEEK_OF_MONTH, weeks);
    }


    /**
     * 业务作用：在给定时间上增减指定星期数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * @param weeks 增加的星期数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addWeeks(TimeZone timeZone, String dt, int weeks) {
        return add(timeZone, dt, Calendar.WEEK_OF_MONTH, weeks);
    }


    /**
     * 业务作用：在给定时间上增减指定星期数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * @param weeks 增加的星期数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addWeeks(String dt, String f, int weeks) {
        return add(dt, f, Calendar.WEEK_OF_MONTH, weeks);
    }


    /**
     * 业务作用：在给定时间上增减指定星期数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * @param weeks 增加的星期数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addWeeks(TimeZone timeZone, String dt, String f, int weeks) {
        return add(timeZone, dt, f, Calendar.WEEK_OF_MONTH, weeks);
    }


    /**
     * 业务作用：在给定时间上增减指定月数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param months 增加的月数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static long addMonths(long date, int months) {
        return add(date, Calendar.MONTH, months);
    }


    /**
     * 业务作用：在给定时间上增减指定月数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param months 增加的月数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static Date addMonths(Date date, int months) {
        return add(date, Calendar.MONTH, months);
    }


    /**
     * 业务作用：在给定时间上增减指定月数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * @param months 增加的月数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addMonths(String dt, int months) {
        return add(dt, Calendar.MONTH, months);
    }


    /**
     * 业务作用：在给定时间上增减指定月数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * @param months 增加的月数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addMonths(TimeZone timeZone, String dt, int months) {
        return add(timeZone, dt, Calendar.MONTH, months);
    }


    /**
     * 业务作用：在给定时间上增减指定月数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * @param months 增加的月数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addMonths(String dt, String f, int months) {
        return add(dt, f, Calendar.MONTH, months);
    }


    /**
     * 业务作用：在给定时间上增减指定月数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * @param months 增加的月数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addMonths(TimeZone timeZone, String dt, String f, int months) {
        return add(timeZone, dt, f, Calendar.MONTH, months);
    }


    /**
     * 业务作用：在给定时间上增减指定年数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param years 增加的年数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static long addYears(long date, int years) {
        return add(date, Calendar.YEAR, years);
    }


    /**
     * 业务作用：在给定时间上增减指定年数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param years 增加的年数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static Date addYears(Date date, int years) {
        return add(date, Calendar.YEAR, years);
    }


    /**
     * 业务作用：在给定时间上增减指定年数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * @param years 增加的年数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addYears(String dt, int years) {
        return add(dt, Calendar.YEAR, years);
    }


    /**
     * 业务作用：在给定时间上增减指定年数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * @param years 增加的年数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addYears(TimeZone timeZone, String dt, int years) {
        return add(timeZone, dt, Calendar.YEAR, years);
    }


    /**
     * 业务作用：在给定时间上增减指定年数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * @param years 增加的年数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addYears(String dt, String f, int years) {
        return add(dt, f, Calendar.YEAR, years);
    }


    /**
     * 业务作用：在给定时间上增减指定年数，用于到期时间、账期推算等场景。
     * 按日历规则推进而非简单毫秒加减，因此跨月、跨年与闰年边界都会被正确处理。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param dt 待处理的日期时间字符串
     * @param f 日期格式串，同时决定精度
     * @param years 增加的年数，可为负表示回退
     * 返回: 调整后的时间，类型与入参一致；原入参不被修改。
     */
    public static String addYears(TimeZone timeZone, String dt, String f, int years) {
        return add(timeZone, dt, f, Calendar.YEAR, years);
    }


    /**
     * 业务作用：取得当前时刻。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param f 日期格式串，同时决定精度
     * 返回: 当前时刻。
     */
    public static String today(String f) {
        return format(System.currentTimeMillis(), f);
    }


    /**
     * 业务作用：取得当前时刻。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * 参数说明: 无。
     * 返回: 当前时刻。
     */
    public static String today() {
        return today(f_y_M_d);
    }


    /**
     * 业务作用：取得当前时刻的毫秒时间戳。
     *
     * 参数说明: 无。
     * 返回: 当前时刻的毫秒时间戳。
     */
    public static String now() {
        return today(f_y_M_d_H_m_s);
    }


    /**
     * 业务作用：取得昨天的同一时刻，用于同比、环比等按天偏移的取数。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param f 日期格式串，同时决定精度
     * 返回: 昨天同一时刻的时间。
     */
    public static String yesterday(String f) {
        return format(System.currentTimeMillis() - day_ms, f);
    }


    /**
     * 业务作用：取得昨天的同一时刻，用于同比、环比等按天偏移的取数。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * 参数说明: 无。
     * 返回: 昨天同一时刻的时间。
     */
    public static String yesterday() {
        return yesterday(f_y_M_d);
    }


    /**
     * 业务作用：比较2个时间关系是否满足
     *
     * @param t1 比较时间1
     * @param t2 比较时间2
     * @param predicate 比较函数
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean compare(long t1, long t2, BiPredicate<Long, Long> predicate) {
        return predicate.test(t1, t2);
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     *
     * @param before 第一个日期
     * @param after 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(long before, long after) {
        return compare(before, after, (a, b) -> a < b);
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     *
     * @param before 第一个日期
     * @param after 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(Date before, long after) {
        return before(before.getTime(), after);
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     *
     * @param before 第一个日期
     * @param after 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(long before, Date after) {
        return before(before, after.getTime());
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     *
     * @param before 第一个日期
     * @param after 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(Date before, Date after) {
        return before(before.getTime(), after.getTime());
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     *
     * @param before 第一个日期
     * @param f 日期格式
     * @param after 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(String before, String f, Date after) {
        return before(parseLong(before, f), after);
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     *
     * @param timeZone before的时区
     * @param before 第一个日期
     * @param f 日期格式
     * @param after 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(TimeZone timeZone, String before, String f, Date after) {
        return before(parseLong(timeZone, before, f), after);
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     * 自动校验日期格式
     *
     * @param before 第一个日期
     * @param after 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(String before, Date after) {
        return before(before, getFormat(before), after);
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     * 自动校验日期格式
     *
     * @param timeZone before的时区
     * @param before 第一个日期
     * @param after 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(TimeZone timeZone, String before, Date after) {
        return before(timeZone, before, getFormat(before), after);
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     *
     * @param before 第一个日期
     * @param after 后一个日期
     * @param f 日期格式
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(Date before, String after, String f) {
        return before(before, parseLong(after, f));
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     *
     * @param timeZone after的时区
     * @param before 第一个日期
     * @param after 后一个日期
     * @param f 日期格式
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(TimeZone timeZone, Date before, String after, String f) {
        return before(before, parseLong(timeZone, after, f));
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     * 自动校验日期格式
     *
     * @param before 第一个日期
     * @param after 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(Date before, String after) {
        return before(before, after, getFormat(after));
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     * 自动校验日期格式
     *
     * @param timeZone after的时区
     * @param before 第一个日期
     * @param after 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(TimeZone timeZone, Date before, String after) {
        return before(timeZone, before, after, getFormat(after));
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     *
     * @param before 第一个日期
     * @param f 日期格式
     * @param after 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(String before, String f, long after) {
        return before(parseLong(before, f), after);
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     *
     * @param timeZone before的时区
     * @param before 第一个日期
     * @param f 日期格式
     * @param after 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(TimeZone timeZone, String before, String f, long after) {
        return before(parseLong(timeZone, before, f), after);
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     * 自动校验日期格式
     *
     * @param before 第一个日期
     * @param after 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(String before, long after) {
        return before(before, getFormat(before), after);
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     * 自动校验日期格式
     *
     * @param timeZone before的时区
     * @param before 第一个日期
     * @param after 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(TimeZone timeZone, String before, long after) {
        return before(timeZone, before, getFormat(before), after);
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     *
     * @param before 第一个日期
     * @param after 后一个日期
     * @param f 日期格式
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(long before, String after, String f) {
        return before(before, parseLong(after, f));
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     *
     * @param timeZone after的时区
     * @param before 第一个日期
     * @param after 后一个日期
     * @param f 日期格式
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(TimeZone timeZone, long before, String after, String f) {
        return before(before, parseLong(timeZone, after, f));
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     * 自动校验日期格式
     *
     * @param before 第一个日期
     * @param after 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(long before, String after) {
        return before(before, after, getFormat(after));
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     * 自动校验日期格式
     *
     * @param timeZone after的时区
     * @param before 第一个日期
     * @param after 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(TimeZone timeZone, long before, String after) {
        return before(timeZone, before, after, getFormat(after));
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     *
     * @param before 第一个日期
     * @param bf before日期格式
     * @param after 后一个日期
     * @param af after日期格式
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(String before, String bf, String after, String af) {
        return before(parse(before, bf), parse(after, af));
    }


    /**
     * 业务作用：判断第一个日期早于后一个日期
     * 自动校验日期格式
     *
     * @param before 第一个日期
     * @param after 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean before(String before, String after) {
        return before(before, getFormat(before), after, getFormat(after));
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     *
     * @param after 第一个日期
     * @param before 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(long after, long before) {
        return compare(after, before, (a, b) -> a > b);
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     *
     * @param after 第一个日期
     * @param before 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(Date after, long before) {
        return after(after.getTime(), before);
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     *
     * @param after 第一个日期
     * @param before 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(long after, Date before) {
        return after(after, before.getTime());
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     *
     * @param after 第一个日期
     * @param before 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(Date after, Date before) {
        return after(after.getTime(), before.getTime());
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     *
     * @param after 第一个日期
     * @param f 日期格式
     * @param before 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(String after, String f, Date before) {
        return after(parseLong(after, f), before);
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     *
     * @param timeZone after的时区
     * @param after 第一个日期
     * @param f 日期格式
     * @param before 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(TimeZone timeZone, String after, String f, Date before) {
        return after(parseLong(timeZone, after, f), before);
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     * 自动校验日期格式
     *
     * @param after 第一个日期
     * @param before 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(String after, Date before) {
        return after(after, getFormat(after), before);
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     * 自动校验日期格式
     *
     * @param timeZone after的时区
     * @param after 第一个日期
     * @param before 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(TimeZone timeZone, String after, Date before) {
        return after(timeZone, after, getFormat(after), before);
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     *
     * @param after 第一个日期
     * @param before 后一个日期
     * @param f 日期格式
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(Date after, String before, String f) {
        return after(after, parseLong(before, f));
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     *
     * @param timeZone before的时区
     * @param after 第一个日期
     * @param before 后一个日期
     * @param f 日期格式
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(TimeZone timeZone, Date after, String before, String f) {
        return after(after, parseLong(timeZone, before, f));
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     * 自动校验日期格式
     *
     * @param after 第一个日期
     * @param before 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(Date after, String before) {
        return after(after, before, getFormat(before));
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     * 自动校验日期格式
     *
     * @param timeZone before的时区
     * @param after 第一个日期
     * @param before 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(TimeZone timeZone, Date after, String before) {
        return after(timeZone, after, before, getFormat(before));
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     *
     * @param after 第一个日期
     * @param f 日期格式
     * @param before 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(String after, String f, long before) {
        return after(parseLong(after, f), before);
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     *
     * @param timeZone after的时区
     * @param after 第一个日期
     * @param f 日期格式
     * @param before 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(TimeZone timeZone, String after, String f, long before) {
        return after(parseLong(timeZone, after, f), before);
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     * 自动校验日期格式
     *
     * @param after 第一个日期
     * @param before 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(String after, long before) {
        return after(after, getFormat(after), before);
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     * 自动校验日期格式
     *
     * @param timeZone after的时区
     * @param after 第一个日期
     * @param before 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(TimeZone timeZone, String after, long before) {
        return after(timeZone, after, getFormat(after), before);
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     *
     * @param after 第一个日期
     * @param before 后一个日期
     * @param f 日期格式
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(long after, String before, String f) {
        return after(after, parseLong(before, f));
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     *
     * @param timeZone before的时区
     * @param after 第一个日期
     * @param before 后一个日期
     * @param f 日期格式
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(TimeZone timeZone, long after, String before, String f) {
        return after(after, parseLong(timeZone, before, f));
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     * 自动校验日期格式
     *
     * @param after 第一个日期
     * @param before 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(long after, String before) {
        return after(after, before, getFormat(before));
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     * 自动校验日期格式
     *
     * @param timeZone before的时区
     * @param after 第一个日期
     * @param before 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(TimeZone timeZone, long after, String before) {
        return after(timeZone, after, before, getFormat(before));
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     *
     * @param after 第一个日期
     * @param af after日期格式
     * @param before 后一个日期
     * @param bf before日期格式
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(String after, String af, String before, String bf) {
        return after(parse(after, af), parse(before, bf));
    }


    /**
     * 业务作用：判断第一个日期晚于后一个日期
     * 自动校验日期格式
     *
     * @param after 第一个日期
     * @param before 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean after(String after, String before) {
        return after(after, getFormat(after), before, getFormat(before));
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     *
     * @param t1 第一个日期
     * @param t2 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(long t1, long t2) {
        return compare(t1, t2, Long::equals);
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     *
     * @param t1 第一个日期
     * @param t2 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(Date t1, long t2) {
        return equals(t1.getTime(), t2);
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     *
     * @param t1 第一个日期
     * @param t2 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(long t1, Date t2) {
        return equals(t1, t2.getTime());
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     *
     * @param t1 第一个日期
     * @param t2 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(Date t1, Date t2) {
        return equals(t1.getTime(), t2.getTime());
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     *
     * @param t1 第一个日期
     * @param f 日期格式
     * @param t2 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(String t1, String f, Date t2) {
        return equals(parseLong(t1, f), t2);
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     *
     * @param timeZone t1的时区
     * @param t1 第一个日期
     * @param f 日期格式
     * @param t2 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(TimeZone timeZone, String t1, String f, Date t2) {
        return equals(parseLong(timeZone, t1, f), t2);
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     * 自动校验日期格式
     *
     * @param t1 第一个日期
     * @param t2 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(String t1, Date t2) {
        return equals(t1, getFormat(t1), t2);
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     * 自动校验日期格式
     *
     * @param timeZone t1的时区
     * @param t1 第一个日期
     * @param t2 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(TimeZone timeZone, String t1, Date t2) {
        return equals(timeZone, t1, getFormat(t1), t2);
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     *
     * @param t1 第一个日期
     * @param t2 后一个日期
     * @param f 日期格式
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(Date t1, String t2, String f) {
        return equals(t1, parseLong(t2, f));
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     *
     * @param timeZone t2的时区
     * @param t1 第一个日期
     * @param t2 后一个日期
     * @param f 日期格式
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(TimeZone timeZone, Date t1, String t2, String f) {
        return equals(t1, parseLong(timeZone, t2, f));
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     * 自动校验日期格式
     *
     * @param t1 第一个日期
     * @param t2 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(Date t1, String t2) {
        return equals(t1, t2, getFormat(t2));
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     * 自动校验日期格式
     *
     * @param timeZone t2的时区
     * @param t1 第一个日期
     * @param t2 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(TimeZone timeZone, Date t1, String t2) {
        return equals(timeZone, t1, t2, getFormat(t2));
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     *
     * @param t1 第一个日期
     * @param f 日期格式
     * @param t2 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(String t1, String f, long t2) {
        return equals(parseLong(t1, f), t2);
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     *
     * @param timeZone t1的时区
     * @param t1 第一个日期
     * @param f 日期格式
     * @param t2 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(TimeZone timeZone, String t1, String f, long t2) {
        return equals(parseLong(timeZone, t1, f), t2);
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     * 自动校验日期格式
     *
     * @param t1 第一个日期
     * @param t2 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(String t1, long t2) {
        return equals(t1, getFormat(t1), t2);
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     * 自动校验日期格式
     *
     * @param timeZone t1的时区
     * @param t1 第一个日期
     * @param t2 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(TimeZone timeZone, String t1, long t2) {
        return equals(timeZone, t1, getFormat(t1), t2);
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     *
     * @param t1 第一个日期
     * @param t2 后一个日期
     * @param f 日期格式
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(long t1, String t2, String f) {
        return equals(t1, parseLong(t2, f));
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     *
     * @param timeZone t2的时区
     * @param t1 第一个日期
     * @param t2 后一个日期
     * @param f 日期格式
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(TimeZone timeZone, long t1, String t2, String f) {
        return equals(t1, parseLong(timeZone, t2, f));
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     * 自动校验日期格式
     *
     * @param t1 第一个日期
     * @param t2 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(long t1, String t2) {
        return equals(t1, t2, getFormat(t2));
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     * 自动校验日期格式
     *
     * @param timeZone t2的时区
     * @param t1 第一个日期
     * @param t2 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(TimeZone timeZone, long t1, String t2) {
        return equals(timeZone, t1, t2, getFormat(t2));
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     *
     * @param t1 第一个日期
     * @param t1f t1日期格式
     * @param t2 后一个日期
     * @param t2f t2日期格式
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(String t1, String t1f, String t2, String t2f) {
        if (t1.equals(t2)) {
            return true;
        }
        return equals(parse(t1, t1f), parse(t2, t2f));
    }


    /**
     * 业务作用：判断第一个日期等于后一个日期
     * 自动校验日期格式
     *
     * @param t1 第一个日期
     * @param t2 后一个日期
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean equals(String t1, String t2) {
        return equals(t1, getFormat(t1), t2, getFormat(t2));
    }


    /**
     * 业务作用：把时间截断到给定格式所代表的精度，取该精度区间内的最早时刻。
     * 例如格式精确到月时返回该月 1 号零点，精确到日时返回当日零点。
     * 常用于构造闭区间查询的下界。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param f 日期格式串，同时决定精度
     * 返回: 该精度区间内最早时刻的Date表示。
     */
    public static Date earliest(Date date, String f) {
        return parse(format(date, f), f);
    }


    /**
     * 业务作用：把时间截断到给定格式所代表的精度，取该精度区间内的最早时刻。
     * 例如格式精确到月时返回该月 1 号零点，精确到日时返回当日零点。
     * 常用于构造闭区间查询的下界。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param f 日期格式串，同时决定精度
     * 返回: 该精度区间内最早时刻的Date表示。
     */
    public static Date earliest(long date, String f) {
        return parse(format(date, f), f);
    }


    /**
     * 业务作用：把时间截断到给定格式所代表的精度，取该精度区间内的最早时刻。
     * 例如格式精确到月时返回该月 1 号零点，精确到日时返回当日零点。
     * 常用于构造闭区间查询的下界。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param df 入参字符串的格式串
     * @param f 日期格式串，同时决定精度
     * 返回: 该精度区间内最早时刻的Date表示。
     */
    public static Date earliest(String date, String df, String f) {
        return earliest(parse(date, df), f);
    }


    /**
     * 业务作用：把时间截断到给定格式所代表的精度，取该精度区间内的最早时刻。
     * 例如格式精确到月时返回该月 1 号零点，精确到日时返回当日零点。
     * 常用于构造闭区间查询的下界。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * @param df 入参字符串的格式串
     * @param f 日期格式串，同时决定精度
     * 返回: 该精度区间内最早时刻的Date表示。
     */
    public static Date earliest(TimeZone timeZone, String date, String df, String f) {
        return earliest(parse(timeZone, date, df), f);
    }


    /**
     * 业务作用：把时间截断到给定格式所代表的精度，取该精度区间内的最早时刻。
     * 例如格式精确到月时返回该月 1 号零点，精确到日时返回当日零点。
     * 常用于构造闭区间查询的下界。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param f 日期格式串，同时决定精度
     * 返回: 该精度区间内最早时刻的Date表示。
     */
    public static Date earliest(String date, String f) {
        return earliest(parse(date, getFormat(date)), f);
    }


    /**
     * 业务作用：把时间截断到给定格式所代表的精度，取该精度区间内的最早时刻。
     * 例如格式精确到月时返回该月 1 号零点，精确到日时返回当日零点。
     * 常用于构造闭区间查询的下界。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * @param f 日期格式串，同时决定精度
     * 返回: 该精度区间内最早时刻的Date表示。
     */
    public static Date earliest(TimeZone timeZone, String date, String f) {
        return earliest(parse(timeZone, date, getFormat(date)), f);
    }


    /**
     * 业务作用：把时间截断到给定格式所代表的精度，取该精度区间内的最早时刻。
     * 例如格式精确到月时返回该月 1 号零点，精确到日时返回当日零点。
     * 常用于构造闭区间查询的下界。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param f 日期格式串，同时决定精度
     * 返回: 该精度区间内最早时刻的毫秒时间戳表示。
     */
    public static long earliestLong(Date date, String f) {
        return parseLong(format(date, f), f);
    }


    /**
     * 业务作用：把时间截断到给定格式所代表的精度，取该精度区间内的最早时刻。
     * 例如格式精确到月时返回该月 1 号零点，精确到日时返回当日零点。
     * 常用于构造闭区间查询的下界。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param f 日期格式串，同时决定精度
     * 返回: 该精度区间内最早时刻的毫秒时间戳表示。
     */
    public static long earliestLong(long date, String f) {
        return parseLong(format(date, f), f);
    }


    /**
     * 业务作用：把时间截断到给定格式所代表的精度，取该精度区间内的最早时刻。
     * 例如格式精确到月时返回该月 1 号零点，精确到日时返回当日零点。
     * 常用于构造闭区间查询的下界。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param df 入参字符串的格式串
     * @param f 日期格式串，同时决定精度
     * 返回: 该精度区间内最早时刻的毫秒时间戳表示。
     */
    public static long earliestLong(String date, String df, String f) {
        return earliestLong(parse(date, df), f);
    }


    /**
     * 业务作用：把时间截断到给定格式所代表的精度，取该精度区间内的最早时刻。
     * 例如格式精确到月时返回该月 1 号零点，精确到日时返回当日零点。
     * 常用于构造闭区间查询的下界。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * @param df 入参字符串的格式串
     * @param f 日期格式串，同时决定精度
     * 返回: 该精度区间内最早时刻的毫秒时间戳表示。
     */
    public static long earliestLong(TimeZone timeZone, String date, String df, String f) {
        return earliestLong(parse(timeZone, date, df), f);
    }


    /**
     * 业务作用：把时间截断到给定格式所代表的精度，取该精度区间内的最早时刻。
     * 例如格式精确到月时返回该月 1 号零点，精确到日时返回当日零点。
     * 常用于构造闭区间查询的下界。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param f 日期格式串，同时决定精度
     * 返回: 该精度区间内最早时刻的毫秒时间戳表示。
     */
    public static long earliestLong(String date, String f) {
        return earliestLong(date, getFormat(date), f);
    }


    /**
     * 业务作用：把时间截断到给定格式所代表的精度，取该精度区间内的最早时刻。
     * 例如格式精确到月时返回该月 1 号零点，精确到日时返回当日零点。
     * 常用于构造闭区间查询的下界。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * @param f 日期格式串，同时决定精度
     * 返回: 该精度区间内最早时刻的毫秒时间戳表示。
     */
    public static long earliestLong(TimeZone timeZone, String date, String f) {
        return earliestLong(timeZone, date, getFormat(date), f);
    }


    /**
     * 业务作用：把时间截断到给定格式所代表的精度，取该精度区间内的最早时刻。
     * 例如格式精确到月时返回该月 1 号零点，精确到日时返回当日零点。
     * 常用于构造闭区间查询的下界。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param f 日期格式串，同时决定精度
     * @param rf 结果格式串
     * 返回: 该精度区间内最早时刻的字符串表示。
     */
    public static String earliestString(Date date, String f, String rf) {
        return format(earliest(date, f), rf);
    }


    /**
     * 业务作用：把时间截断到给定格式所代表的精度，取该精度区间内的最早时刻。
     * 例如格式精确到月时返回该月 1 号零点，精确到日时返回当日零点。
     * 常用于构造闭区间查询的下界。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * @param f 日期格式串，同时决定精度
     * @param rf 结果格式串
     * 返回: 该精度区间内最早时刻的字符串表示。
     */
    public static String earliestString(TimeZone timeZone, Date date, String f, String rf) {
        return format(timeZone, earliest(date, f), rf);
    }


    /**
     * 业务作用：把时间截断到给定格式所代表的精度，取该精度区间内的最早时刻。
     * 例如格式精确到月时返回该月 1 号零点，精确到日时返回当日零点。
     * 常用于构造闭区间查询的下界。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param f 日期格式串，同时决定精度
     * @param rf 结果格式串
     * 返回: 该精度区间内最早时刻的字符串表示。
     */
    public static String earliestString(long date, String f, String rf) {
        return format(earliest(date, f), rf);
    }


    /**
     * 业务作用：把时间截断到给定格式所代表的精度，取该精度区间内的最早时刻。
     * 例如格式精确到月时返回该月 1 号零点，精确到日时返回当日零点。
     * 常用于构造闭区间查询的下界。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * @param f 日期格式串，同时决定精度
     * @param rf 结果格式串
     * 返回: 该精度区间内最早时刻的字符串表示。
     */
    public static String earliestString(TimeZone timeZone, long date, String f, String rf) {
        return format(timeZone, earliest(date, f), rf);
    }


    /**
     * 业务作用：把时间截断到给定格式所代表的精度，取该精度区间内的最早时刻。
     * 例如格式精确到月时返回该月 1 号零点，精确到日时返回当日零点。
     * 常用于构造闭区间查询的下界。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param df 入参字符串的格式串
     * @param f 日期格式串，同时决定精度
     * @param rf 结果格式串
     * 返回: 该精度区间内最早时刻的字符串表示。
     */
    public static String earliestString(String date, String df, String f, String rf) {
        return earliestString(parse(date, df), f, rf);
    }


    /**
     * 业务作用：把时间截断到给定格式所代表的精度，取该精度区间内的最早时刻。
     * 例如格式精确到月时返回该月 1 号零点，精确到日时返回当日零点。
     * 常用于构造闭区间查询的下界。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * @param df 入参字符串的格式串
     * @param f 日期格式串，同时决定精度
     * @param rf 结果格式串
     * 返回: 该精度区间内最早时刻的字符串表示。
     */
    public static String earliestString(TimeZone timeZone, String date, String df, String f, String rf) {
        return earliestString(timeZone, parse(date, df), f, rf);
    }


    /**
     * 业务作用：把时间截断到给定格式所代表的精度，取该精度区间内的最早时刻。
     * 例如格式精确到月时返回该月 1 号零点，精确到日时返回当日零点。
     * 常用于构造闭区间查询的下界。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param date 待处理的时间
     * @param f 日期格式串，同时决定精度
     * @param rf 结果格式串
     * 返回: 该精度区间内最早时刻的字符串表示。
     */
    public static String earliestString(String date, String f, String rf) {
        return earliestString(date, getFormat(date), f, rf);
    }


    /**
     * 业务作用：把时间截断到给定格式所代表的精度，取该精度区间内的最早时刻。
     * 例如格式精确到月时返回该月 1 号零点，精确到日时返回当日零点。
     * 常用于构造闭区间查询的下界。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param date 待处理的时间
     * @param f 日期格式串，同时决定精度
     * @param rf 结果格式串
     * 返回: 该精度区间内最早时刻的字符串表示。
     */
    public static String earliestString(TimeZone timeZone, String date, String f, String rf) {
        return earliestString(timeZone, date, getFormat(date), f, rf);
    }


    /**
     * 业务作用：按给定日历字段逐格推进，列出起止时间之间经过的每一个时间格。是 allDay 与 allMonth 的统一实现，精度由日历字段与格式串共同决定。
     *
     * 获取开始时间和结束时间之间
     * 在指定日期格式精度下，所有的日期时间字符串集合
     * @param start 开始时间
     * @param end 结束时间
     * @param f 日期时间格式精度
     * @param rf 返回的日期时间格式
     * @param field Calendar的枚举值，如：Calendar.MONTH
     */
    public static List<String> allTime(
            long start
            , long end
            , String f
            , String rf
            , int field
    ) {
        return allTime(GMT8, start, end, f, rf, field);
    }


    /**
     * 业务作用：按给定日历字段逐格推进，列出起止时间之间经过的每一个时间格。是 allDay 与 allMonth 的统一实现，精度由日历字段与格式串共同决定。
     *
     * 获取开始时间和结束时间之间
     * 在指定日期格式精度下，所有的日期时间字符串集合
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     * @param f 日期时间格式精度
     * @param rf 返回的日期时间格式
     * @param field Calendar的枚举值，如：Calendar.MONTH
     */
    public static List<String> allTime(
            TimeZone timeZone
            , long start
            , long end
            , String f
            , String rf
            , int field
    ) {
        List<String> list = new ArrayList<>();
        if (after(start, end)) {
            return list;
        }
        start = earliestLong(start, f);
        Calendar c = Calendar.getInstance();
        c.setTimeZone(timeZone);
        c.setTimeInMillis(start);
        do {
            list.add(format(start, rf));

            c.add(field, 1);
            start = c.getTimeInMillis();
        } while (before(start, end));
        return list;
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(long start, long end, String rf) {
        return allTime(start, end, f_yM, rf, Calendar.MONTH);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, long start, long end, String rf) {
        return allTime(timeZone, start, end, f_yM, rf, Calendar.MONTH);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(long start, Date end, String rf) {
        return allMonth(start, end.getTime(), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, long start, Date end, String rf) {
        return allMonth(timeZone, start, end.getTime(), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(Date start, long end, String rf) {
        return allMonth(start.getTime(), end, rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, Date start, long end, String rf) {
        return allMonth(timeZone, start.getTime(), end, rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(Date start, Date end, String rf) {
        return allMonth(start.getTime(), end.getTime(), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, Date start, Date end, String rf) {
        return allMonth(timeZone, start.getTime(), end.getTime(), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param sf 起始时间字符串的格式串
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(String start, String sf, long end, String rf) {
        return allMonth(parseLong(start, sf), end, rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param sf 起始时间字符串的格式串
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, String start, String sf, long end, String rf) {
        return allMonth(timeZone, parseLong(timeZone, start, sf), end, rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(String start, long end, String rf) {
        return allMonth(parseLong(start, getFormat(start)), end, rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, String start, long end, String rf) {
        return allMonth(timeZone, parseLong(timeZone, start, getFormat(start)), end, rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param ef 结束时间字符串的格式串
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(long start, String end, String ef, String rf) {
        return allMonth(start, parseLong(end, ef), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param ef 结束时间字符串的格式串
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, long start, String end, String ef, String rf) {
        return allMonth(timeZone, start, parseLong(timeZone, end, ef), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(long start, String end, String rf) {
        return allMonth(start, parseLong(end, getFormat(end)), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, long start, String end, String rf) {
        return allMonth(timeZone, start, parseLong(timeZone, end, getFormat(end)), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param sf 起始时间字符串的格式串
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(String start, String sf, Date end, String rf) {
        return allMonth(parse(start, sf), end, rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param sf 起始时间字符串的格式串
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, String start, String sf, Date end, String rf) {
        return allMonth(timeZone, parse(timeZone, start, sf), end, rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(String start, Date end, String rf) {
        return allMonth(parse(start, getFormat(start)), end, rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, String start, Date end, String rf) {
        return allMonth(timeZone, parse(timeZone, start, getFormat(start)), end, rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param ef 结束时间字符串的格式串
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(Date start, String end, String ef, String rf) {
        return allMonth(start, parseLong(end, ef), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param ef 结束时间字符串的格式串
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, Date start, String end, String ef, String rf) {
        return allMonth(timeZone, start, parseLong(timeZone, end, ef), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(Date start, String end, String rf) {
        return allMonth(start, parseLong(end, getFormat(end)), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, Date start, String end, String rf) {
        return allMonth(timeZone, start, parseLong(timeZone, end, getFormat(end)), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param sf 起始时间字符串的格式串
     * @param end 区间结束时间
     * @param ef 结束时间字符串的格式串
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(String start, String sf, String end, String ef, String rf) {
        return allMonth(parseLong(start, sf), parseLong(end, ef), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param sf 起始时间字符串的格式串
     * @param end 区间结束时间
     * @param ef 结束时间字符串的格式串
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, String start, String sf, String end, String ef, String rf) {
        return allMonth(timeZone, parseLong(timeZone, start, sf), parseLong(timeZone, end, ef), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(String start, String end, String rf) {
        return allMonth(parseLong(start, getFormat(start)), parseLong(end, getFormat(end)), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, String start, String end, String rf) {
        return allMonth(timeZone
                , parseLong(timeZone, start, getFormat(start))
                , parseLong(timeZone, end, getFormat(end))
                , rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(String start, long end) {
        return allMonth(parseLong(start, getFormat(start)), end, f_y_M);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, String start, long end) {
        return allMonth(timeZone, parseLong(timeZone, start, getFormat(start)), end, f_y_M);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(String start, Date end) {
        return allMonth(parseLong(start, getFormat(start)), end, f_y_M);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, String start, Date end) {
        return allMonth(timeZone, parseLong(timeZone, start, getFormat(start)), end, f_y_M);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(String start, String end) {
        return allMonth(start, end, f_y_M);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, String start, String end) {
        return allMonth(timeZone, start, end, f_y_M);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(long start, String end) {
        return allMonth(start, parseLong(end, getFormat(end)), f_y_M);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, long start, String end) {
        return allMonth(timeZone, start, parseLong(timeZone, end, getFormat(end)), f_y_M);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(Date start, String end) {
        return allMonth(start, parseLong(end, getFormat(end)), f_y_M);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, Date start, String end) {
        return allMonth(timeZone, start, parseLong(timeZone, end, getFormat(end)), f_y_M);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(long start, long end) {
        return allMonth(start, end, f_y_M);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, long start, long end) {
        return allMonth(timeZone, start, end, f_y_M);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(long start, Date end) {
        return allMonth(start, end, f_y_M);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, long start, Date end) {
        return allMonth(timeZone, start, end, f_y_M);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(Date start, long end) {
        return allMonth(start, end, f_y_M);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, Date start, long end) {
        return allMonth(timeZone, start, end, f_y_M);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(Date start, Date end) {
        return allMonth(start, end, f_y_M);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然月，供按月分表、按月聚合等场景生成月份键。
     * 两端均包含：只要区间与某个自然月有交集，该月就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月字符串列表；起止时间落在同一个月时只返回一项。
     */
    public static List<String> allMonth(TimeZone timeZone, Date start, Date end) {
        return allMonth(timeZone, start, end, f_y_M);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(long start, long end, String rf) {
        return allTime(start, end, f_yMd, rf, Calendar.DAY_OF_MONTH);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, long start, long end, String rf) {
        return allTime(timeZone, start, end, f_yMd, rf, Calendar.DAY_OF_MONTH);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(long start, Date end, String rf) {
        return allDay(start, end.getTime(), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, long start, Date end, String rf) {
        return allDay(timeZone, start, end.getTime(), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(Date start, long end, String rf) {
        return allDay(start.getTime(), end, rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, Date start, long end, String rf) {
        return allDay(timeZone, start.getTime(), end, rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(Date start, Date end, String rf) {
        return allDay(start.getTime(), end.getTime(), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, Date start, Date end, String rf) {
        return allDay(timeZone, start.getTime(), end.getTime(), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param sf 起始时间字符串的格式串
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(String start, String sf, long end, String rf) {
        return allDay(parseLong(start, sf), end, rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param sf 起始时间字符串的格式串
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, String start, String sf, long end, String rf) {
        return allDay(timeZone, parseLong(timeZone, start, sf), end, rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param ef 结束时间字符串的格式串
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(long start, String end, String ef, String rf) {
        return allDay(start, parseLong(end, ef), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param ef 结束时间字符串的格式串
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, long start, String end, String ef, String rf) {
        return allDay(timeZone, start, parseLong(timeZone, end, ef), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param sf 起始时间字符串的格式串
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(String start, String sf, Date end, String rf) {
        return allDay(parse(start, sf), end, rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param sf 起始时间字符串的格式串
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, String start, String sf, Date end, String rf) {
        return allDay(timeZone, parse(timeZone, start, sf), end, rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param ef 结束时间字符串的格式串
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(Date start, String end, String ef, String rf) {
        return allDay(start, parseLong(end, ef), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param ef 结束时间字符串的格式串
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, Date start, String end, String ef, String rf) {
        return allDay(timeZone, start, parseLong(timeZone, end, ef), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param sf 起始时间字符串的格式串
     * @param end 区间结束时间
     * @param ef 结束时间字符串的格式串
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(String start, String sf, String end, String ef, String rf) {
        return allDay(parseLong(start, sf), parseLong(end, ef), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param sf 起始时间字符串的格式串
     * @param end 区间结束时间
     * @param ef 结束时间字符串的格式串
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, String start, String sf, String end, String ef, String rf) {
        return allDay(timeZone, parseLong(timeZone, start, sf), parseLong(timeZone, end, ef), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param sf 起始时间字符串的格式串
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(String start, String sf, long end) {
        return allDay(parseLong(start, sf), end, f_y_M_d);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param sf 起始时间字符串的格式串
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, String start, String sf, long end) {
        return allDay(timeZone, parseLong(timeZone, start, sf), end, f_y_M_d);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param sf 起始时间字符串的格式串
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(String start, String sf, Date end) {
        return allDay(parseLong(start, sf), end, f_y_M_d);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param sf 起始时间字符串的格式串
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, String start, String sf, Date end) {
        return allDay(timeZone, parseLong(timeZone, start, sf), end, f_y_M_d);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param sf 起始时间字符串的格式串
     * @param end 区间结束时间
     * @param ef 结束时间字符串的格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(String start, String sf, String end, String ef) {
        return allDay(start, sf, end, ef, f_y_M_d);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param sf 起始时间字符串的格式串
     * @param end 区间结束时间
     * @param ef 结束时间字符串的格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, String start, String sf, String end, String ef) {
        return allDay(timeZone, start, sf, end, ef, f_y_M_d);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(String start, String end, String rf) {
        return allDay(start, getFormat(start), end, getFormat(end), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param rf 结果格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, String start, String end, String rf) {
        return allDay(timeZone, start, getFormat(start), end, getFormat(end), rf);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param ef 结束时间字符串的格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(long start, String end, String ef) {
        return allDay(start, parseLong(end, ef), f_y_M_d);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param ef 结束时间字符串的格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, long start, String end, String ef) {
        return allDay(timeZone, start, parseLong(timeZone, end, ef), f_y_M_d);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param ef 结束时间字符串的格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(Date start, String end, String ef) {
        return allDay(start, parseLong(end, ef), f_y_M_d);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * @param ef 结束时间字符串的格式串
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, Date start, String end, String ef) {
        return allDay(timeZone, start, parseLong(timeZone, end, ef), f_y_M_d);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(long start, long end) {
        return allDay(start, end, f_y_M_d);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, long start, long end) {
        return allDay(timeZone, start, end, f_y_M_d);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(long start, Date end) {
        return allDay(start, end, f_y_M_d);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, long start, Date end) {
        return allDay(timeZone, start, end, f_y_M_d);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(Date start, long end) {
        return allDay(start, end, f_y_M_d);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, Date start, long end) {
        return allDay(timeZone, start, end, f_y_M_d);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(Date start, Date end) {
        return allDay(start, end, f_y_M_d);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, Date start, Date end) {
        return allDay(timeZone, start, end, f_y_M_d);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(String start, long end) {
        return allDay(start, getFormat(start), end);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, String start, long end) {
        return allDay(timeZone, start, getFormat(start), end);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(String start, Date end) {
        return allDay(start, getFormat(start), end);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, String start, Date end) {
        return allDay(timeZone, start, getFormat(start), end);
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(String start, String end) {
        return allDay(start, getFormat(start), end, getFormat(end));
    }


    /**
     * 业务作用：列出起止时间之间经过的每一个自然日，供按日分表、按日聚合等场景生成日期键。
     * 两端均包含：只要区间与某一天有交集，该日就会出现在结果中。
     * 未显式传入时区的重载一律按东八区处理，跨时区服务必须使用带 timeZone 的重载，否则日界会算错。
     *
     * @param timeZone 时区；决定日界与月界的切分位置
     * @param start 区间起始时间
     * @param end 区间结束时间
     * 返回: 按时间升序排列的年月日字符串列表；起止时间落在同一天时只返回一项。
     */
    public static List<String> allDay(TimeZone timeZone, String start, String end) {
        return allDay(timeZone, start, getFormat(start), end, getFormat(end));
    }
}
