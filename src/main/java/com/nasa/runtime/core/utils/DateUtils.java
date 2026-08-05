package com.nasa.runtime.core.utils;

import com.nasa.runtime.core.base.KV;
import com.nasa.runtime.core.exception.DateParseException;

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
     * 获取日期字符串格式
     * @param dt 日期
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
     * 初始化 DateFormat 工具
     * yyyy-MM-dd HH:mm:ss
     * 默认时区：东八区
     */
    public static SimpleDateFormat dateFormat() {
        return dateFormat(f_y_M_d_H_m_s);
    }


    /**
     * 初始化 DateFormat 工具
     * 默认时区：东八区
     * @param f 日期格式
     */
    public static SimpleDateFormat dateFormat(String f) {
        return dateFormat(GMT8, f);
    }


    /**
     * 初始化 DateFormat 工具
     * 全局变量的SimpleDateFormat，在并发情况下，存在安全性问题。
     * SimpleDateFormat继承了 DateFormat
     * DateFormat类中维护了一个全局的Calendar变量
     * sdf.parse(dateStr)和sdf.format(date)，都是由Calendar引用来储存的。
     * 如果SimpleDateFormat是static全局共享的，Calendar引用也会被共享。
     * 又因为Calendar内部并没有线程安全机制，所以全局共享的SimpleDateFormat不是线性安全的。
     * @param timeZone 时区
     * @param f 日期格式
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
     * @param timeZone 时区
     * @param f 日期格式
     */
    private static SimpleDateFormat dateFormatFactory(TimeZone timeZone, String f) {
        SimpleDateFormat dateFormat = new SimpleDateFormat(f);
        dateFormat.setTimeZone(timeZone);
        return dateFormat;
    }


    /**
     * 日期格式化输出
     */
    public static String format(Date date, String f) {
        return dateFormat(f).format(date);
    }


    /**
     * 日期格式化输出
     */
    public static String format(TimeZone timeZone, Date date, String f) {
        return dateFormat(timeZone, f).format(date);
    }


    /**
     * 日期格式化输出
     */
    public static String format(long date, String f) {
        return dateFormat(f).format(date);
    }


    /**
     * 日期格式化输出
     */
    public static String format(TimeZone timeZone, long date, String f) {
        return dateFormat(timeZone, f).format(date);
    }


    /**
     * 日期格式化输出
     * yyyy-MM-dd HH:mm:ss
     */
    public static String format(Date date) {
        return format(date, f_y_M_d_H_m_s);
    }


    /**
     * 日期格式化输出
     * yyyy-MM-dd HH:mm:ss
     */
    public static String format(TimeZone timeZone, Date date) {
        return format(timeZone, date, f_y_M_d_H_m_s);
    }


    /**
     * 日期格式化输出
     * yyyy-MM-dd HH:mm:ss
     */
    public static String format(long date) {
        return format(date, f_y_M_d_H_m_s);
    }


    /**
     * 日期格式化输出
     * yyyy-MM-dd HH:mm:ss
     */
    public static String format(TimeZone timeZone, long date) {
        return format(timeZone, date, f_y_M_d_H_m_s);
    }


    /**
     * 日期格式化输出
     * yyyy-MM-dd
     */
    public static String format_y_M_d(Date date) {
        return format(date, f_y_M_d);
    }


    /**
     * 日期格式化输出
     * yyyy-MM-dd
     */
    public static String format_y_M_d(TimeZone timeZone, Date date) {
        return format(timeZone, date, f_y_M_d);
    }


    /**
     * 日期格式化输出
     * yyyy-MM-dd
     */
    public static String format_y_M_d(long date) {
        return format(date, f_y_M_d);
    }


    /**
     * 日期格式化输出
     * yyyy-MM-dd
     */
    public static String format_y_M_d(TimeZone timeZone, long date) {
        return format(timeZone, date, f_y_M_d);
    }


    /**
     * 日期格式化输出
     * yyyy-MM
     */
    public static String format_y_M(Date date) {
        return format(date, f_y_M);
    }


    /**
     * 日期格式化输出
     * yyyy-MM
     */
    public static String format_y_M(TimeZone timeZone, Date date) {
        return format(timeZone, date, f_y_M);
    }


    /**
     * 日期格式化输出
     * yyyy-MM
     */
    public static String format_y_M(long date) {
        return format(date, f_y_M);
    }


    /**
     * 日期格式化输出
     * yyyy-MM
     */
    public static String format_y_M(TimeZone timeZone, long date) {
        return format(timeZone, date, f_y_M);
    }


    /**
     * 日期格式化输出
     * yyyyMM
     */
    public static String format_yM(Date date) {
        return format(date, f_yM);
    }


    /**
     * 日期格式化输出
     * yyyyMM
     */
    public static String format_yM(TimeZone timeZone, Date date) {
        return format(timeZone, date, f_yM);
    }


    /**
     * 日期格式化输出
     * yyyyMM
     */
    public static String format_yM(long date) {
        return format(date, f_yM);
    }


    /**
     * 日期格式化输出
     * yyyyMM
     */
    public static String format_yM(TimeZone timeZone, long date) {
        return format(timeZone, date, f_yM);
    }


    /**
     * 日期格式化输出
     * yyyyMMdd
     */
    public static String format_yMd(Date date) {
        return format(date, f_yMd);
    }


    /**
     * 日期格式化输出
     * yyyyMMdd
     */
    public static String format_yMd(TimeZone timeZone, Date date) {
        return format(timeZone, date, f_yMd);
    }


    /**
     * 日期格式化输出
     * yyyyMMdd
     */
    public static String format_yMd(long date) {
        return format(date, f_yMd);
    }


    /**
     * 日期格式化输出
     * yyyyMMdd
     */
    public static String format_yMd(TimeZone timeZone, long date) {
        return format(timeZone, date, f_yMd);
    }


    /**
     * 日期格式化输出
     * yyyyMMddHHmmss
     */
    public static String format_yMdHms(Date date) {
        return format(date, f_yMdHms);
    }


    /**
     * 日期格式化输出
     * yyyyMMddHHmmss
     */
    public static String format_yMdHms(TimeZone timeZone, Date date) {
        return format(timeZone, date, f_yMdHms);
    }


    /**
     * 日期格式化输出
     * yyyyMMddHHmmss
     */
    public static String format_yMdHms(long date) {
        return format(date, f_yMdHms);
    }


    /**
     * 日期格式化输出
     * yyyyMMddHHmmss
     */
    public static String format_yMdHms(TimeZone timeZone, long date) {
        return format(timeZone, date, f_yMdHms);
    }


    /**
     * 日期格式化输出
     * yyyy/MM
     */
    public static String format_yM_path(Date date) {
        return format(date, f_yM_path);
    }


    /**
     * 日期格式化输出
     * yyyy/MM
     */
    public static String format_yM_path(TimeZone timeZone, Date date) {
        return format(timeZone, date, f_yM_path);
    }


    /**
     * 日期格式化输出
     * yyyy/MM
     */
    public static String format_yM_path(long date) {
        return format(date, f_yM_path);
    }


    /**
     * 日期格式化输出
     * yyyy/MM
     */
    public static String format_yM_path(TimeZone timeZone, long date) {
        return format(timeZone, date, f_yM_path);
    }


    /**
     * 日期格式化输出
     * yyyy/MM/dd
     */
    public static String format_yMd_path(Date date) {
        return format(date, f_yMd_path);
    }


    /**
     * 日期格式化输出
     * yyyy/MM/dd
     */
    public static String format_yMd_path(TimeZone timeZone, Date date) {
        return format(timeZone, date, f_yMd_path);
    }


    /**
     * 日期格式化输出
     * yyyy/MM/dd
     */
    public static String format_yMd_path(long date) {
        return format(date, f_yMd_path);
    }


    /**
     * 日期格式化输出
     * yyyy/MM/dd
     */
    public static String format_yMd_path(TimeZone timeZone, long date) {
        return format(timeZone, date, f_yMd_path);
    }


    /**
     * 日期格式化输出
     * yyyy/MM/dd HH:mm:ss
     */
    public static String format_yMdHms_path(Date date) {
        return format(date, f_yMdHms_path);
    }


    /**
     * 日期格式化输出
     * yyyy/MM/dd HH:mm:ss
     */
    public static String format_yMdHms_path(TimeZone timeZone, Date date) {
        return format(timeZone, date, f_yMdHms_path);
    }


    /**
     * 日期格式化输出
     * yyyy/MM/dd HH:mm:ss
     */
    public static String format_yMdHms_path(long date) {
        return format(date, f_yMdHms_path);
    }


    /**
     * 日期格式化输出
     * yyyy/MM/dd HH:mm:ss
     */
    public static String format_yMdHms_path(TimeZone timeZone, long date) {
        return format(timeZone, date, f_yMdHms_path);
    }


    /**
     * 日期时间字符串转Date
     */
    public static Date parse(String dt, String f) {
        return parse(GMT8, dt, f);
    }


    /**
     * 日期时间字符串转Date
     */
    public static Date parse(TimeZone timeZone, String dt, String f) {
        try {
            return dateFormat(timeZone, f).parse(dt);
        } catch (ParseException e) {
            throw new DateParseException(e.getMessage(), e);
        }
    }


    /**
     * 日期时间字符串转Date
     * 自动校验日期格式
     */
    public static Date parse(String dt) {
        return parse(dt, getFormat(dt));
    }


    /**
     * 日期时间字符串转Date
     * 自动校验日期格式
     */
    public static Date parse(TimeZone timeZone, String dt) {
        return parse(timeZone, dt, getFormat(dt));
    }


    /**
     * 日期时间字符串转long
     */
    public static long parseLong(String dt, String f) {
        return parse(dt, f).getTime();
    }


    /**
     * 日期时间字符串转long
     */
    public static long parseLong(TimeZone timeZone, String dt, String f) {
        return parse(timeZone, dt, f).getTime();
    }


    /**
     * 日期时间字符串转long
     * 自动校验日期格式
     */
    public static long parseLong(String dt) {
        return parseLong(dt, getFormat(dt));
    }


    /**
     * 日期时间字符串转long
     * 自动校验日期格式
     */
    public static long parseLong(TimeZone timeZone, String dt) {
        return parseLong(timeZone, dt, getFormat(dt));
    }


    /**
     * 增加时间
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
     * 增加时间
     */
    public static Date add(Date date, int... params) {
        return new Date(add(date.getTime(), params));
    }


    /**
     * 增加时间
     * 自动校验日期格式
     */
    public static String add(String dt, int... params) {
        return format(add(parse(dt), params));
    }


    /**
     * 增加时间
     * 自动校验日期格式
     */
    public static String add(TimeZone timeZone, String dt, int... params) {
        return format(add(parse(timeZone, dt), params));
    }


    /**
     * 增加时间
     * 指定日期时间格式
     */
    public static String add(String dt, String f, int... params) {
        return format(add(parse(dt, f), params), f);
    }


    /**
     * 增加时间
     * 指定日期时间格式
     */
    public static String add(TimeZone timeZone, String dt, String f, int... params) {
        return format(add(parse(timeZone, dt, f), params), f);
    }


    /**
     * 增加毫秒
     */
    public static long addMillis(long date, int millis) {
        return add(date, Calendar.MILLISECOND, millis);
    }


    /**
     * 增加毫秒
     */
    public static Date addMillis(Date date, int millis) {
        return add(date, Calendar.MILLISECOND, millis);
    }


    /**
     * 增加毫秒
     * 自动校验日期格式
     */
    public static String addMillis(String dt, int millis) {
        return add(dt, Calendar.MILLISECOND, millis);
    }


    /**
     * 增加毫秒
     * 自动校验日期格式
     */
    public static String addMillis(TimeZone timeZone, String dt, int millis) {
        return add(timeZone, dt, Calendar.MILLISECOND, millis);
    }


    /**
     * 增加毫秒
     * 指定日期时间格式
     */
    public static String addMillis(String dt, String f, int millis) {
        return add(dt, f, Calendar.MILLISECOND, millis);
    }


    /**
     * 增加毫秒
     * 指定日期时间格式
     */
    public static String addMillis(TimeZone timeZone, String dt, String f, int millis) {
        return add(timeZone, dt, f, Calendar.MILLISECOND, millis);
    }


    /**
     * 增加秒
     */
    public static long addSeconds(long date, int seconds) {
        return add(date, Calendar.SECOND, seconds);
    }


    /**
     * 增加秒
     */
    public static Date addSeconds(Date date, int seconds) {
        return add(date, Calendar.SECOND, seconds);
    }


    /**
     * 增加秒
     * 自动校验日期格式
     */
    public static String addSeconds(String dt, int seconds) {
        return add(dt, Calendar.SECOND, seconds);
    }


    /**
     * 增加秒
     * 自动校验日期格式
     */
    public static String addSeconds(TimeZone timeZone, String dt, int seconds) {
        return add(timeZone, dt, Calendar.SECOND, seconds);
    }


    /**
     * 增加秒
     * 指定日期时间格式
     */
    public static String addSeconds(String dt, String f, int seconds) {
        return add(dt, f, Calendar.SECOND, seconds);
    }


    /**
     * 增加秒
     * 指定日期时间格式
     */
    public static String addSeconds(TimeZone timeZone, String dt, String f, int seconds) {
        return add(timeZone, dt, f, Calendar.SECOND, seconds);
    }


    /**
     * 增加分钟
     */
    public static long addMinutes(long date, int minutes) {
        return add(date, Calendar.MINUTE, minutes);
    }


    /**
     * 增加分钟
     */
    public static Date addMinutes(Date date, int minutes) {
        return add(date, Calendar.MINUTE, minutes);
    }


    /**
     * 增加分钟
     * 自动校验日期格式
     */
    public static String addMinutes(String dt, int minutes) {
        return add(dt, Calendar.MINUTE, minutes);
    }


    /**
     * 增加分钟
     * 自动校验日期格式
     */
    public static String addMinutes(TimeZone timeZone, String dt, int minutes) {
        return add(timeZone, dt, Calendar.MINUTE, minutes);
    }


    /**
     * 增加分钟
     * 指定日期时间格式
     */
    public static String addMinutes(String dt, String f, int minutes) {
        return add(dt, f, Calendar.MINUTE, minutes);
    }


    /**
     * 增加分钟
     * 指定日期时间格式
     */
    public static String addMinutes(TimeZone timeZone, String dt, String f, int minutes) {
        return add(timeZone, dt, f, Calendar.MINUTE, minutes);
    }


    /**
     * 增加小时
     */
    public static long addHours(long date, int hours) {
        return add(date, Calendar.HOUR_OF_DAY, hours);
    }


    /**
     * 增加小时
     */
    public static Date addHours(Date date, int hours) {
        return add(date, Calendar.HOUR_OF_DAY, hours);
    }


    /**
     * 增加小时
     * 自动校验日期格式
     */
    public static String addHours(String dt, int hours) {
        return add(dt, Calendar.HOUR_OF_DAY, hours);
    }


    /**
     * 增加小时
     * 自动校验日期格式
     */
    public static String addHours(TimeZone timeZone, String dt, int hours) {
        return add(timeZone, dt, Calendar.HOUR_OF_DAY, hours);
    }


    /**
     * 增加小时
     * 指定日期时间格式
     */
    public static String addHours(String dt, String f, int hours) {
        return add(dt, f, Calendar.HOUR_OF_DAY, hours);
    }


    /**
     * 增加小时
     * 指定日期时间格式
     */
    public static String addHours(TimeZone timeZone, String dt, String f, int hours) {
        return add(timeZone, dt, f, Calendar.HOUR_OF_DAY, hours);
    }


    /**
     * 增加天
     */
    public static long addDays(long date, int days) {
        return add(date, Calendar.DAY_OF_MONTH, days);
    }


    /**
     * 增加天
     */
    public static Date addDays(Date date, int days) {
        return add(date, Calendar.DAY_OF_MONTH, days);
    }


    /**
     * 增加天
     * 自动校验日期格式
     */
    public static String addDays(String dt, int days) {
        return add(dt, Calendar.DAY_OF_MONTH, days);
    }


    /**
     * 增加天
     * 自动校验日期格式
     */
    public static String addDays(TimeZone timeZone, String dt, int days) {
        return add(timeZone, dt, Calendar.DAY_OF_MONTH, days);
    }


    /**
     * 增加天
     * 指定日期时间格式
     */
    public static String addDays(String dt, String f, int days) {
        return add(dt, f, Calendar.DAY_OF_MONTH, days);
    }


    /**
     * 增加天
     * 指定日期时间格式
     */
    public static String addDays(TimeZone timeZone, String dt, String f, int days) {
        return add(timeZone, dt, f, Calendar.DAY_OF_MONTH, days);
    }


    /**
     * 增加星期
     */
    public static long addWeeks(long date, int weeks) {
        return add(date, Calendar.WEEK_OF_MONTH, weeks);
    }


    /**
     * 增加星期
     */
    public static Date addWeeks(Date date, int weeks) {
        return add(date, Calendar.WEEK_OF_MONTH, weeks);
    }


    /**
     * 增加星期
     * 自动校验日期格式
     */
    public static String addWeeks(String dt, int weeks) {
        return add(dt, Calendar.WEEK_OF_MONTH, weeks);
    }


    /**
     * 增加星期
     * 自动校验日期格式
     */
    public static String addWeeks(TimeZone timeZone, String dt, int weeks) {
        return add(timeZone, dt, Calendar.WEEK_OF_MONTH, weeks);
    }


    /**
     * 增加星期
     * 指定日期时间格式
     */
    public static String addWeeks(String dt, String f, int weeks) {
        return add(dt, f, Calendar.WEEK_OF_MONTH, weeks);
    }


    /**
     * 增加星期
     * 指定日期时间格式
     */
    public static String addWeeks(TimeZone timeZone, String dt, String f, int weeks) {
        return add(timeZone, dt, f, Calendar.WEEK_OF_MONTH, weeks);
    }


    /**
     * 增加月
     */
    public static long addMonths(long date, int months) {
        return add(date, Calendar.MONTH, months);
    }


    /**
     * 增加月
     */
    public static Date addMonths(Date date, int months) {
        return add(date, Calendar.MONTH, months);
    }


    /**
     * 增加月
     * 自动校验日期格式
     */
    public static String addMonths(String dt, int months) {
        return add(dt, Calendar.MONTH, months);
    }


    /**
     * 增加月
     * 自动校验日期格式
     */
    public static String addMonths(TimeZone timeZone, String dt, int months) {
        return add(timeZone, dt, Calendar.MONTH, months);
    }


    /**
     * 增加月
     * 指定日期时间格式
     */
    public static String addMonths(String dt, String f, int months) {
        return add(dt, f, Calendar.MONTH, months);
    }


    /**
     * 增加月
     * 指定日期时间格式
     */
    public static String addMonths(TimeZone timeZone, String dt, String f, int months) {
        return add(timeZone, dt, f, Calendar.MONTH, months);
    }


    /**
     * 增加年
     */
    public static long addYears(long date, int years) {
        return add(date, Calendar.YEAR, years);
    }


    /**
     * 增加年
     */
    public static Date addYears(Date date, int years) {
        return add(date, Calendar.YEAR, years);
    }


    /**
     * 增加年
     * 自动校验日期格式
     */
    public static String addYears(String dt, int years) {
        return add(dt, Calendar.YEAR, years);
    }


    /**
     * 增加年
     * 自动校验日期格式
     */
    public static String addYears(TimeZone timeZone, String dt, int years) {
        return add(timeZone, dt, Calendar.YEAR, years);
    }


    /**
     * 增加年
     * 指定日期时间格式
     */
    public static String addYears(String dt, String f, int years) {
        return add(dt, f, Calendar.YEAR, years);
    }


    /**
     * 增加年
     * 指定日期时间格式
     */
    public static String addYears(TimeZone timeZone, String dt, String f, int years) {
        return add(timeZone, dt, f, Calendar.YEAR, years);
    }


    /**
     * 获取当前日期时间
     * @param f 日期格式
     */
    public static String today(String f) {
        return format(System.currentTimeMillis(), f);
    }


    /**
     * 获取当前日期
     * yyyy-MM-dd
     */
    public static String today() {
        return today(f_y_M_d);
    }


    /**
     * 获取当前日期时间
     * yyyy-MM-dd HH:mm:ss
     */
    public static String now() {
        return today(f_y_M_d_H_m_s);
    }


    /**
     * 获取昨天的同时刻日期时间
     * @param f 日期格式
     */
    public static String yesterday(String f) {
        return format(System.currentTimeMillis() - day_ms, f);
    }


    /**
     * 获取昨天日期
     * yyyy-MM-dd
     */
    public static String yesterday() {
        return yesterday(f_y_M_d);
    }


    /**
     * 比较2个时间关系是否满足
     * @param t1 比较时间1
     * @param t2 比较时间2
     * @param predicate 比较函数
     */
    public static boolean compare(long t1, long t2, BiPredicate<Long, Long> predicate) {
        return predicate.test(t1, t2);
    }


    /**
     * 判断第一个日期早于后一个日期
     * @param before 第一个日期
     * @param after 后一个日期
     */
    public static boolean before(long before, long after) {
        return compare(before, after, (a, b) -> a < b);
    }


    /**
     * 判断第一个日期早于后一个日期
     * @param before 第一个日期
     * @param after 后一个日期
     */
    public static boolean before(Date before, long after) {
        return before(before.getTime(), after);
    }


    /**
     * 判断第一个日期早于后一个日期
     * @param before 第一个日期
     * @param after 后一个日期
     */
    public static boolean before(long before, Date after) {
        return before(before, after.getTime());
    }


    /**
     * 判断第一个日期早于后一个日期
     * @param before 第一个日期
     * @param after 后一个日期
     */
    public static boolean before(Date before, Date after) {
        return before(before.getTime(), after.getTime());
    }


    /**
     * 判断第一个日期早于后一个日期
     * @param before 第一个日期
     * @param f 日期格式
     * @param after 后一个日期
     */
    public static boolean before(String before, String f, Date after) {
        return before(parseLong(before, f), after);
    }


    /**
     * 判断第一个日期早于后一个日期
     * @param timeZone before的时区
     * @param before 第一个日期
     * @param f 日期格式
     * @param after 后一个日期
     */
    public static boolean before(TimeZone timeZone, String before, String f, Date after) {
        return before(parseLong(timeZone, before, f), after);
    }


    /**
     * 判断第一个日期早于后一个日期
     * 自动校验日期格式
     * @param before 第一个日期
     * @param after 后一个日期
     */
    public static boolean before(String before, Date after) {
        return before(before, getFormat(before), after);
    }


    /**
     * 判断第一个日期早于后一个日期
     * 自动校验日期格式
     * @param timeZone before的时区
     * @param before 第一个日期
     * @param after 后一个日期
     */
    public static boolean before(TimeZone timeZone, String before, Date after) {
        return before(timeZone, before, getFormat(before), after);
    }


    /**
     * 判断第一个日期早于后一个日期
     * @param before 第一个日期
     * @param after 后一个日期
     * @param f 日期格式
     */
    public static boolean before(Date before, String after, String f) {
        return before(before, parseLong(after, f));
    }


    /**
     * 判断第一个日期早于后一个日期
     * @param timeZone after的时区
     * @param before 第一个日期
     * @param after 后一个日期
     * @param f 日期格式
     */
    public static boolean before(TimeZone timeZone, Date before, String after, String f) {
        return before(before, parseLong(timeZone, after, f));
    }


    /**
     * 判断第一个日期早于后一个日期
     * 自动校验日期格式
     * @param before 第一个日期
     * @param after 后一个日期
     */
    public static boolean before(Date before, String after) {
        return before(before, after, getFormat(after));
    }


    /**
     * 判断第一个日期早于后一个日期
     * 自动校验日期格式
     * @param timeZone after的时区
     * @param before 第一个日期
     * @param after 后一个日期
     */
    public static boolean before(TimeZone timeZone, Date before, String after) {
        return before(timeZone, before, after, getFormat(after));
    }


    /**
     * 判断第一个日期早于后一个日期
     * @param before 第一个日期
     * @param f 日期格式
     * @param after 后一个日期
     */
    public static boolean before(String before, String f, long after) {
        return before(parseLong(before, f), after);
    }


    /**
     * 判断第一个日期早于后一个日期
     * @param timeZone before的时区
     * @param before 第一个日期
     * @param f 日期格式
     * @param after 后一个日期
     */
    public static boolean before(TimeZone timeZone, String before, String f, long after) {
        return before(parseLong(timeZone, before, f), after);
    }


    /**
     * 判断第一个日期早于后一个日期
     * 自动校验日期格式
     * @param before 第一个日期
     * @param after 后一个日期
     */
    public static boolean before(String before, long after) {
        return before(before, getFormat(before), after);
    }


    /**
     * 判断第一个日期早于后一个日期
     * 自动校验日期格式
     * @param timeZone before的时区
     * @param before 第一个日期
     * @param after 后一个日期
     */
    public static boolean before(TimeZone timeZone, String before, long after) {
        return before(timeZone, before, getFormat(before), after);
    }


    /**
     * 判断第一个日期早于后一个日期
     * @param before 第一个日期
     * @param after 后一个日期
     * @param f 日期格式
     */
    public static boolean before(long before, String after, String f) {
        return before(before, parseLong(after, f));
    }


    /**
     * 判断第一个日期早于后一个日期
     * @param timeZone after的时区
     * @param before 第一个日期
     * @param after 后一个日期
     * @param f 日期格式
     */
    public static boolean before(TimeZone timeZone, long before, String after, String f) {
        return before(before, parseLong(timeZone, after, f));
    }


    /**
     * 判断第一个日期早于后一个日期
     * 自动校验日期格式
     * @param before 第一个日期
     * @param after 后一个日期
     */
    public static boolean before(long before, String after) {
        return before(before, after, getFormat(after));
    }


    /**
     * 判断第一个日期早于后一个日期
     * 自动校验日期格式
     * @param timeZone after的时区
     * @param before 第一个日期
     * @param after 后一个日期
     */
    public static boolean before(TimeZone timeZone, long before, String after) {
        return before(timeZone, before, after, getFormat(after));
    }


    /**
     * 判断第一个日期早于后一个日期
     * @param before 第一个日期
     * @param bf before日期格式
     * @param after 后一个日期
     * @param af after日期格式
     */
    public static boolean before(String before, String bf, String after, String af) {
        return before(parse(before, bf), parse(after, af));
    }


    /**
     * 判断第一个日期早于后一个日期
     * 自动校验日期格式
     * @param before 第一个日期
     * @param after 后一个日期
     */
    public static boolean before(String before, String after) {
        return before(before, getFormat(before), after, getFormat(after));
    }


    /**
     * 判断第一个日期晚于后一个日期
     * @param after 第一个日期
     * @param before 后一个日期
     */
    public static boolean after(long after, long before) {
        return compare(after, before, (a, b) -> a > b);
    }


    /**
     * 判断第一个日期晚于后一个日期
     * @param after 第一个日期
     * @param before 后一个日期
     */
    public static boolean after(Date after, long before) {
        return after(after.getTime(), before);
    }


    /**
     * 判断第一个日期晚于后一个日期
     * @param after 第一个日期
     * @param before 后一个日期
     */
    public static boolean after(long after, Date before) {
        return after(after, before.getTime());
    }


    /**
     * 判断第一个日期晚于后一个日期
     * @param after 第一个日期
     * @param before 后一个日期
     */
    public static boolean after(Date after, Date before) {
        return after(after.getTime(), before.getTime());
    }


    /**
     * 判断第一个日期晚于后一个日期
     * @param after 第一个日期
     * @param f 日期格式
     * @param before 后一个日期
     */
    public static boolean after(String after, String f, Date before) {
        return after(parseLong(after, f), before);
    }


    /**
     * 判断第一个日期晚于后一个日期
     * @param timeZone after的时区
     * @param after 第一个日期
     * @param f 日期格式
     * @param before 后一个日期
     */
    public static boolean after(TimeZone timeZone, String after, String f, Date before) {
        return after(parseLong(timeZone, after, f), before);
    }


    /**
     * 判断第一个日期晚于后一个日期
     * 自动校验日期格式
     * @param after 第一个日期
     * @param before 后一个日期
     */
    public static boolean after(String after, Date before) {
        return after(after, getFormat(after), before);
    }


    /**
     * 判断第一个日期晚于后一个日期
     * 自动校验日期格式
     * @param timeZone after的时区
     * @param after 第一个日期
     * @param before 后一个日期
     */
    public static boolean after(TimeZone timeZone, String after, Date before) {
        return after(timeZone, after, getFormat(after), before);
    }


    /**
     * 判断第一个日期晚于后一个日期
     * @param after 第一个日期
     * @param before 后一个日期
     * @param f 日期格式
     */
    public static boolean after(Date after, String before, String f) {
        return after(after, parseLong(before, f));
    }


    /**
     * 判断第一个日期晚于后一个日期
     * @param timeZone before的时区
     * @param after 第一个日期
     * @param before 后一个日期
     * @param f 日期格式
     */
    public static boolean after(TimeZone timeZone, Date after, String before, String f) {
        return after(after, parseLong(timeZone, before, f));
    }


    /**
     * 判断第一个日期晚于后一个日期
     * 自动校验日期格式
     * @param after 第一个日期
     * @param before 后一个日期
     */
    public static boolean after(Date after, String before) {
        return after(after, before, getFormat(before));
    }


    /**
     * 判断第一个日期晚于后一个日期
     * 自动校验日期格式
     * @param timeZone before的时区
     * @param after 第一个日期
     * @param before 后一个日期
     */
    public static boolean after(TimeZone timeZone, Date after, String before) {
        return after(timeZone, after, before, getFormat(before));
    }


    /**
     * 判断第一个日期晚于后一个日期
     * @param after 第一个日期
     * @param f 日期格式
     * @param before 后一个日期
     */
    public static boolean after(String after, String f, long before) {
        return after(parseLong(after, f), before);
    }


    /**
     * 判断第一个日期晚于后一个日期
     * @param timeZone after的时区
     * @param after 第一个日期
     * @param f 日期格式
     * @param before 后一个日期
     */
    public static boolean after(TimeZone timeZone, String after, String f, long before) {
        return after(parseLong(timeZone, after, f), before);
    }


    /**
     * 判断第一个日期晚于后一个日期
     * 自动校验日期格式
     * @param after 第一个日期
     * @param before 后一个日期
     */
    public static boolean after(String after, long before) {
        return after(after, getFormat(after), before);
    }


    /**
     * 判断第一个日期晚于后一个日期
     * 自动校验日期格式
     * @param timeZone after的时区
     * @param after 第一个日期
     * @param before 后一个日期
     */
    public static boolean after(TimeZone timeZone, String after, long before) {
        return after(timeZone, after, getFormat(after), before);
    }


    /**
     * 判断第一个日期晚于后一个日期
     * @param after 第一个日期
     * @param before 后一个日期
     * @param f 日期格式
     */
    public static boolean after(long after, String before, String f) {
        return after(after, parseLong(before, f));
    }


    /**
     * 判断第一个日期晚于后一个日期
     * @param timeZone before的时区
     * @param after 第一个日期
     * @param before 后一个日期
     * @param f 日期格式
     */
    public static boolean after(TimeZone timeZone, long after, String before, String f) {
        return after(after, parseLong(timeZone, before, f));
    }


    /**
     * 判断第一个日期晚于后一个日期
     * 自动校验日期格式
     * @param after 第一个日期
     * @param before 后一个日期
     */
    public static boolean after(long after, String before) {
        return after(after, before, getFormat(before));
    }


    /**
     * 判断第一个日期晚于后一个日期
     * 自动校验日期格式
     * @param timeZone before的时区
     * @param after 第一个日期
     * @param before 后一个日期
     */
    public static boolean after(TimeZone timeZone, long after, String before) {
        return after(timeZone, after, before, getFormat(before));
    }


    /**
     * 判断第一个日期晚于后一个日期
     * @param after 第一个日期
     * @param af after日期格式
     * @param before 后一个日期
     * @param bf before日期格式
     */
    public static boolean after(String after, String af, String before, String bf) {
        return after(parse(after, af), parse(before, bf));
    }


    /**
     * 判断第一个日期晚于后一个日期
     * 自动校验日期格式
     * @param after 第一个日期
     * @param before 后一个日期
     */
    public static boolean after(String after, String before) {
        return after(after, getFormat(after), before, getFormat(before));
    }


    /**
     * 判断第一个日期等于后一个日期
     * @param t1 第一个日期
     * @param t2 后一个日期
     */
    public static boolean equals(long t1, long t2) {
        return compare(t1, t2, Long::equals);
    }


    /**
     * 判断第一个日期等于后一个日期
     * @param t1 第一个日期
     * @param t2 后一个日期
     */
    public static boolean equals(Date t1, long t2) {
        return equals(t1.getTime(), t2);
    }


    /**
     * 判断第一个日期等于后一个日期
     * @param t1 第一个日期
     * @param t2 后一个日期
     */
    public static boolean equals(long t1, Date t2) {
        return equals(t1, t2.getTime());
    }


    /**
     * 判断第一个日期等于后一个日期
     * @param t1 第一个日期
     * @param t2 后一个日期
     */
    public static boolean equals(Date t1, Date t2) {
        return equals(t1.getTime(), t2.getTime());
    }


    /**
     * 判断第一个日期等于后一个日期
     * @param t1 第一个日期
     * @param f 日期格式
     * @param t2 后一个日期
     */
    public static boolean equals(String t1, String f, Date t2) {
        return equals(parseLong(t1, f), t2);
    }


    /**
     * 判断第一个日期等于后一个日期
     * @param timeZone t1的时区
     * @param t1 第一个日期
     * @param f 日期格式
     * @param t2 后一个日期
     */
    public static boolean equals(TimeZone timeZone, String t1, String f, Date t2) {
        return equals(parseLong(timeZone, t1, f), t2);
    }


    /**
     * 判断第一个日期等于后一个日期
     * 自动校验日期格式
     * @param t1 第一个日期
     * @param t2 后一个日期
     */
    public static boolean equals(String t1, Date t2) {
        return equals(t1, getFormat(t1), t2);
    }


    /**
     * 判断第一个日期等于后一个日期
     * 自动校验日期格式
     * @param timeZone t1的时区
     * @param t1 第一个日期
     * @param t2 后一个日期
     */
    public static boolean equals(TimeZone timeZone, String t1, Date t2) {
        return equals(timeZone, t1, getFormat(t1), t2);
    }


    /**
     * 判断第一个日期等于后一个日期
     * @param t1 第一个日期
     * @param t2 后一个日期
     * @param f 日期格式
     */
    public static boolean equals(Date t1, String t2, String f) {
        return equals(t1, parseLong(t2, f));
    }


    /**
     * 判断第一个日期等于后一个日期
     * @param timeZone t2的时区
     * @param t1 第一个日期
     * @param t2 后一个日期
     * @param f 日期格式
     */
    public static boolean equals(TimeZone timeZone, Date t1, String t2, String f) {
        return equals(t1, parseLong(timeZone, t2, f));
    }


    /**
     * 判断第一个日期等于后一个日期
     * 自动校验日期格式
     * @param t1 第一个日期
     * @param t2 后一个日期
     */
    public static boolean equals(Date t1, String t2) {
        return equals(t1, t2, getFormat(t2));
    }


    /**
     * 判断第一个日期等于后一个日期
     * 自动校验日期格式
     * @param timeZone t2的时区
     * @param t1 第一个日期
     * @param t2 后一个日期
     */
    public static boolean equals(TimeZone timeZone, Date t1, String t2) {
        return equals(timeZone, t1, t2, getFormat(t2));
    }


    /**
     * 判断第一个日期等于后一个日期
     * @param t1 第一个日期
     * @param f 日期格式
     * @param t2 后一个日期
     */
    public static boolean equals(String t1, String f, long t2) {
        return equals(parseLong(t1, f), t2);
    }


    /**
     * 判断第一个日期等于后一个日期
     * @param timeZone t1的时区
     * @param t1 第一个日期
     * @param f 日期格式
     * @param t2 后一个日期
     */
    public static boolean equals(TimeZone timeZone, String t1, String f, long t2) {
        return equals(parseLong(timeZone, t1, f), t2);
    }


    /**
     * 判断第一个日期等于后一个日期
     * 自动校验日期格式
     * @param t1 第一个日期
     * @param t2 后一个日期
     */
    public static boolean equals(String t1, long t2) {
        return equals(t1, getFormat(t1), t2);
    }


    /**
     * 判断第一个日期等于后一个日期
     * 自动校验日期格式
     * @param timeZone t1的时区
     * @param t1 第一个日期
     * @param t2 后一个日期
     */
    public static boolean equals(TimeZone timeZone, String t1, long t2) {
        return equals(timeZone, t1, getFormat(t1), t2);
    }


    /**
     * 判断第一个日期等于后一个日期
     * @param t1 第一个日期
     * @param t2 后一个日期
     * @param f 日期格式
     */
    public static boolean equals(long t1, String t2, String f) {
        return equals(t1, parseLong(t2, f));
    }


    /**
     * 判断第一个日期等于后一个日期
     * @param timeZone t2的时区
     * @param t1 第一个日期
     * @param t2 后一个日期
     * @param f 日期格式
     */
    public static boolean equals(TimeZone timeZone, long t1, String t2, String f) {
        return equals(t1, parseLong(timeZone, t2, f));
    }


    /**
     * 判断第一个日期等于后一个日期
     * 自动校验日期格式
     * @param t1 第一个日期
     * @param t2 后一个日期
     */
    public static boolean equals(long t1, String t2) {
        return equals(t1, t2, getFormat(t2));
    }


    /**
     * 判断第一个日期等于后一个日期
     * 自动校验日期格式
     * @param timeZone t2的时区
     * @param t1 第一个日期
     * @param t2 后一个日期
     */
    public static boolean equals(TimeZone timeZone, long t1, String t2) {
        return equals(timeZone, t1, t2, getFormat(t2));
    }


    /**
     * 判断第一个日期等于后一个日期
     * @param t1 第一个日期
     * @param t1f t1日期格式
     * @param t2 后一个日期
     * @param t2f t2日期格式
     */
    public static boolean equals(String t1, String t1f, String t2, String t2f) {
        if (t1.equals(t2)) {
            return true;
        }
        return equals(parse(t1, t1f), parse(t2, t2f));
    }


    /**
     * 判断第一个日期等于后一个日期
     * 自动校验日期格式
     * @param t1 第一个日期
     * @param t2 后一个日期
     */
    public static boolean equals(String t1, String t2) {
        return equals(t1, getFormat(t1), t2, getFormat(t2));
    }


    /**
     * 获取指定时间在指定格式精度内的最早时间
     * @param date 日期时间
     * @param f 日期时间格式精度
     */
    public static Date earliest(Date date, String f) {
        return parse(format(date, f), f);
    }


    /**
     * 获取指定时间在指定格式精度内的最早时间
     * @param date 日期时间
     * @param f 日期时间格式精度
     */
    public static Date earliest(long date, String f) {
        return parse(format(date, f), f);
    }


    /**
     * 获取指定时间在指定格式精度内的最早时间
     * @param date 日期时间
     * @param df 日期时间格式
     * @param f 日期时间格式精度
     */
    public static Date earliest(String date, String df, String f) {
        return earliest(parse(date, df), f);
    }


    /**
     * 获取指定时间在指定格式精度内的最早时间
     * @param timeZone date的时区
     * @param date 日期时间
     * @param df 日期时间格式
     * @param f 日期时间格式精度
     */
    public static Date earliest(TimeZone timeZone, String date, String df, String f) {
        return earliest(parse(timeZone, date, df), f);
    }


    /**
     * 获取指定时间在指定格式精度内的最早时间
     * 自动校验日期格式
     * @param date 日期时间
     * @param f 日期时间格式精度
     */
    public static Date earliest(String date, String f) {
        return earliest(parse(date, getFormat(date)), f);
    }


    /**
     * 获取指定时间在指定格式精度内的最早时间
     * 自动校验日期格式
     * @param timeZone date的时区
     * @param date 日期时间
     * @param f 日期时间格式精度
     */
    public static Date earliest(TimeZone timeZone, String date, String f) {
        return earliest(parse(timeZone, date, getFormat(date)), f);
    }


    /**
     * 获取指定时间在指定格式精度内的最早时间
     * @param date 日期时间
     * @param f 日期时间格式精度
     */
    public static long earliestLong(Date date, String f) {
        return parseLong(format(date, f), f);
    }


    /**
     * 获取指定时间在指定格式精度内的最早时间
     * @param date 日期时间
     * @param f 日期时间格式精度
     */
    public static long earliestLong(long date, String f) {
        return parseLong(format(date, f), f);
    }


    /**
     * 获取指定时间在指定格式精度内的最早时间
     * @param date 日期时间
     * @param df 日期时间格式
     * @param f 日期时间格式精度
     */
    public static long earliestLong(String date, String df, String f) {
        return earliestLong(parse(date, df), f);
    }


    /**
     * 获取指定时间在指定格式精度内的最早时间
     * @param timeZone date的时区
     * @param date 日期时间
     * @param df 日期时间格式
     * @param f 日期时间格式精度
     */
    public static long earliestLong(TimeZone timeZone, String date, String df, String f) {
        return earliestLong(parse(timeZone, date, df), f);
    }


    /**
     * 获取指定时间在指定格式精度内的最早时间
     * 自动校验日期格式
     * @param date 日期时间
     * @param f 日期时间格式精度
     */
    public static long earliestLong(String date, String f) {
        return earliestLong(date, getFormat(date), f);
    }


    /**
     * 获取指定时间在指定格式精度内的最早时间
     * 自动校验日期格式
     * @param timeZone date的时区
     * @param date 日期时间
     * @param f 日期时间格式精度
     */
    public static long earliestLong(TimeZone timeZone, String date, String f) {
        return earliestLong(timeZone, date, getFormat(date), f);
    }


    /**
     * 获取指定时间在指定格式精度内的最早时间
     * @param date 日期时间
     * @param f 日期时间格式精度
     * @param rf 返回日期时间格式精度
     */
    public static String earliestString(Date date, String f, String rf) {
        return format(earliest(date, f), rf);
    }


    /**
     * 获取指定时间在指定格式精度内的最早时间
     * @param timeZone 返回日期时间的时区
     * @param date 日期时间
     * @param f 日期时间格式精度
     * @param rf 返回日期时间格式精度
     */
    public static String earliestString(TimeZone timeZone, Date date, String f, String rf) {
        return format(timeZone, earliest(date, f), rf);
    }


    /**
     * 获取指定时间在指定格式精度内的最早时间
     * @param date 日期时间
     * @param f 日期时间格式精度
     * @param rf 返回日期时间格式精度
     */
    public static String earliestString(long date, String f, String rf) {
        return format(earliest(date, f), rf);
    }


    /**
     * 获取指定时间在指定格式精度内的最早时间
     * @param timeZone 时区
     * @param date 日期时间
     * @param f 日期时间格式精度
     * @param rf 返回日期时间格式精度
     */
    public static String earliestString(TimeZone timeZone, long date, String f, String rf) {
        return format(timeZone, earliest(date, f), rf);
    }


    /**
     * 获取指定时间在指定格式精度内的最早时间
     * @param date 日期时间
     * @param df 日期时间格式
     * @param f 日期时间格式精度
     * @param rf 返回日期时间格式精度
     */
    public static String earliestString(String date, String df, String f, String rf) {
        return earliestString(parse(date, df), f, rf);
    }


    /**
     * 获取指定时间在指定格式精度内的最早时间
     * @param timeZone 时区
     * @param date 日期时间
     * @param df 日期时间格式
     * @param f 日期时间格式精度
     * @param rf 返回日期时间格式精度
     */
    public static String earliestString(TimeZone timeZone, String date, String df, String f, String rf) {
        return earliestString(timeZone, parse(date, df), f, rf);
    }


    /**
     * 获取指定时间在指定格式精度内的最早时间
     * 自动校验日期格式
     * @param date 日期时间
     * @param f 日期时间格式精度
     * @param rf 返回日期时间格式精度
     */
    public static String earliestString(String date, String f, String rf) {
        return earliestString(date, getFormat(date), f, rf);
    }


    /**
     * 获取指定时间在指定格式精度内的最早时间
     * 自动校验日期格式
     * @param timeZone 时区
     * @param date 日期时间
     * @param f 日期时间格式精度
     * @param rf 返回日期时间格式精度
     */
    public static String earliestString(TimeZone timeZone, String date, String f, String rf) {
        return earliestString(timeZone, date, getFormat(date), f, rf);
    }


    /**
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
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(long start, long end, String rf) {
        return allTime(start, end, f_yM, rf, Calendar.MONTH);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(TimeZone timeZone, long start, long end, String rf) {
        return allTime(timeZone, start, end, f_yM, rf, Calendar.MONTH);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(long start, Date end, String rf) {
        return allMonth(start, end.getTime(), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(TimeZone timeZone, long start, Date end, String rf) {
        return allMonth(timeZone, start, end.getTime(), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(Date start, long end, String rf) {
        return allMonth(start.getTime(), end, rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(TimeZone timeZone, Date start, long end, String rf) {
        return allMonth(timeZone, start.getTime(), end, rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * @param start 开始时间，包含当前月份
     * @param end 结束时间，包含当前月份
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(Date start, Date end, String rf) {
        return allMonth(start.getTime(), end.getTime(), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * @param timeZone 时区
     * @param start 开始时间，包含当前月份
     * @param end 结束时间，包含当前月份
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(TimeZone timeZone, Date start, Date end, String rf) {
        return allMonth(timeZone, start.getTime(), end.getTime(), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * @param start 开始时间
     * @param sf start日期时间格式
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(String start, String sf, long end, String rf) {
        return allMonth(parseLong(start, sf), end, rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * @param timeZone 时区
     * @param start 开始时间
     * @param sf start日期时间格式
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(TimeZone timeZone, String start, String sf, long end, String rf) {
        return allMonth(timeZone, parseLong(timeZone, start, sf), end, rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 自动校验日期格式
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(String start, long end, String rf) {
        return allMonth(parseLong(start, getFormat(start)), end, rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 自动校验日期格式
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(TimeZone timeZone, String start, long end, String rf) {
        return allMonth(timeZone, parseLong(timeZone, start, getFormat(start)), end, rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * @param start 开始时间
     * @param end 结束时间
     * @param ef end日期时间格式
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(long start, String end, String ef, String rf) {
        return allMonth(start, parseLong(end, ef), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     * @param ef end日期时间格式
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(TimeZone timeZone, long start, String end, String ef, String rf) {
        return allMonth(timeZone, start, parseLong(timeZone, end, ef), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 自动校验日期格式
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(long start, String end, String rf) {
        return allMonth(start, parseLong(end, getFormat(end)), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 自动校验日期格式
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(TimeZone timeZone, long start, String end, String rf) {
        return allMonth(timeZone, start, parseLong(timeZone, end, getFormat(end)), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * @param start 开始时间
     * @param sf 日期时间格式
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(String start, String sf, Date end, String rf) {
        return allMonth(parse(start, sf), end, rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * @param timeZone 时区
     * @param start 开始时间
     * @param sf 日期时间格式
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(TimeZone timeZone, String start, String sf, Date end, String rf) {
        return allMonth(timeZone, parse(timeZone, start, sf), end, rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 自动校验日期格式
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(String start, Date end, String rf) {
        return allMonth(parse(start, getFormat(start)), end, rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 自动校验日期格式
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(TimeZone timeZone, String start, Date end, String rf) {
        return allMonth(timeZone, parse(timeZone, start, getFormat(start)), end, rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * @param start 开始时间
     * @param end 结束时间
     * @param ef 日期时间格式
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(Date start, String end, String ef, String rf) {
        return allMonth(start, parseLong(end, ef), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     * @param ef 日期时间格式
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(TimeZone timeZone, Date start, String end, String ef, String rf) {
        return allMonth(timeZone, start, parseLong(timeZone, end, ef), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 自动校验日期格式
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(Date start, String end, String rf) {
        return allMonth(start, parseLong(end, getFormat(end)), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 自动校验日期格式
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(TimeZone timeZone, Date start, String end, String rf) {
        return allMonth(timeZone, start, parseLong(timeZone, end, getFormat(end)), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * @param start 开始时间
     * @param sf start日期时间格式
     * @param end 结束时间
     * @param ef end日期时间格式
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(String start, String sf, String end, String ef, String rf) {
        return allMonth(parseLong(start, sf), parseLong(end, ef), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * @param timeZone 时区
     * @param start 开始时间
     * @param sf start日期时间格式
     * @param end 结束时间
     * @param ef end日期时间格式
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(TimeZone timeZone, String start, String sf, String end, String ef, String rf) {
        return allMonth(timeZone, parseLong(timeZone, start, sf), parseLong(timeZone, end, ef), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 自动校验日期格式
     * 自动校验日期格式
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(String start, String end, String rf) {
        return allMonth(parseLong(start, getFormat(start)), parseLong(end, getFormat(end)), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 自动校验日期格式
     * 自动校验日期格式
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allMonth(TimeZone timeZone, String start, String end, String rf) {
        return allMonth(timeZone
                , parseLong(timeZone, start, getFormat(start))
                , parseLong(timeZone, end, getFormat(end))
                , rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 自动校验日期格式
     * 返回日期格式：yyyy-MM
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allMonth(String start, long end) {
        return allMonth(parseLong(start, getFormat(start)), end, f_y_M);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 自动校验日期格式
     * 返回日期格式：yyyy-MM
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allMonth(TimeZone timeZone, String start, long end) {
        return allMonth(timeZone, parseLong(timeZone, start, getFormat(start)), end, f_y_M);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 自动校验日期格式
     * 返回日期格式：yyyy-MM
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allMonth(String start, Date end) {
        return allMonth(parseLong(start, getFormat(start)), end, f_y_M);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 自动校验日期格式
     * 返回日期格式：yyyy-MM
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allMonth(TimeZone timeZone, String start, Date end) {
        return allMonth(timeZone, parseLong(timeZone, start, getFormat(start)), end, f_y_M);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 自动校验日期格式
     * 返回日期格式：yyyy-MM
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allMonth(String start, String end) {
        return allMonth(start, end, f_y_M);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 自动校验日期格式
     * 返回日期格式：yyyy-MM
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allMonth(TimeZone timeZone, String start, String end) {
        return allMonth(timeZone, start, end, f_y_M);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 自动校验日期格式
     * 返回日期格式：yyyy-MM
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allMonth(long start, String end) {
        return allMonth(start, parseLong(end, getFormat(end)), f_y_M);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 自动校验日期格式
     * 返回日期格式：yyyy-MM
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allMonth(TimeZone timeZone, long start, String end) {
        return allMonth(timeZone, start, parseLong(timeZone, end, getFormat(end)), f_y_M);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 自动校验日期格式
     * 返回日期格式：yyyy-MM
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allMonth(Date start, String end) {
        return allMonth(start, parseLong(end, getFormat(end)), f_y_M);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 自动校验日期格式
     * 返回日期格式：yyyy-MM
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allMonth(TimeZone timeZone, Date start, String end) {
        return allMonth(timeZone, start, parseLong(timeZone, end, getFormat(end)), f_y_M);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 返回日期格式：yyyy-MM
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allMonth(long start, long end) {
        return allMonth(start, end, f_y_M);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 返回日期格式：yyyy-MM
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allMonth(TimeZone timeZone, long start, long end) {
        return allMonth(timeZone, start, end, f_y_M);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 返回日期格式：yyyy-MM
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allMonth(long start, Date end) {
        return allMonth(start, end, f_y_M);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 返回日期格式：yyyy-MM
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allMonth(TimeZone timeZone, long start, Date end) {
        return allMonth(timeZone, start, end, f_y_M);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 返回日期格式：yyyy-MM
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allMonth(Date start, long end) {
        return allMonth(start, end, f_y_M);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 返回日期格式：yyyy-MM
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allMonth(TimeZone timeZone, Date start, long end) {
        return allMonth(timeZone, start, end, f_y_M);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 返回日期格式：yyyy-MM
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allMonth(Date start, Date end) {
        return allMonth(start, end, f_y_M);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月字符串集合
     * 返回日期格式：yyyy-MM
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allMonth(TimeZone timeZone, Date start, Date end) {
        return allMonth(timeZone, start, end, f_y_M);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allDay(long start, long end, String rf) {
        return allTime(start, end, f_yMd, rf, Calendar.DAY_OF_MONTH);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allDay(TimeZone timeZone, long start, long end, String rf) {
        return allTime(timeZone, start, end, f_yMd, rf, Calendar.DAY_OF_MONTH);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allDay(long start, Date end, String rf) {
        return allDay(start, end.getTime(), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allDay(TimeZone timeZone, long start, Date end, String rf) {
        return allDay(timeZone, start, end.getTime(), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allDay(Date start, long end, String rf) {
        return allDay(start.getTime(), end, rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allDay(TimeZone timeZone, Date start, long end, String rf) {
        return allDay(timeZone, start.getTime(), end, rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * @param start 开始时间，包含当前月份
     * @param end 结束时间，包含当前月份
     * @param rf 返回的年月格式
     */
    public static List<String> allDay(Date start, Date end, String rf) {
        return allDay(start.getTime(), end.getTime(), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * @param timeZone 时区
     * @param start 开始时间，包含当前月份
     * @param end 结束时间，包含当前月份
     * @param rf 返回的年月格式
     */
    public static List<String> allDay(TimeZone timeZone, Date start, Date end, String rf) {
        return allDay(timeZone, start.getTime(), end.getTime(), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * @param start 开始时间
     * @param sf start日期时间格式
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allDay(String start, String sf, long end, String rf) {
        return allDay(parseLong(start, sf), end, rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * @param timeZone 时区
     * @param start 开始时间
     * @param sf start日期时间格式
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allDay(TimeZone timeZone, String start, String sf, long end, String rf) {
        return allDay(timeZone, parseLong(timeZone, start, sf), end, rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * @param start 开始时间
     * @param end 结束时间
     * @param ef end日期时间格式
     * @param rf 返回的年月格式
     */
    public static List<String> allDay(long start, String end, String ef, String rf) {
        return allDay(start, parseLong(end, ef), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     * @param ef end日期时间格式
     * @param rf 返回的年月格式
     */
    public static List<String> allDay(TimeZone timeZone, long start, String end, String ef, String rf) {
        return allDay(timeZone, start, parseLong(timeZone, end, ef), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * @param start 开始时间
     * @param sf start日期时间格式
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allDay(String start, String sf, Date end, String rf) {
        return allDay(parse(start, sf), end, rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * @param timeZone 时区
     * @param start 开始时间
     * @param sf start日期时间格式
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allDay(TimeZone timeZone, String start, String sf, Date end, String rf) {
        return allDay(timeZone, parse(timeZone, start, sf), end, rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * @param start 开始时间
     * @param end 结束时间
     * @param ef end日期时间格式
     * @param rf 返回的年月格式
     */
    public static List<String> allDay(Date start, String end, String ef, String rf) {
        return allDay(start, parseLong(end, ef), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     * @param ef end日期时间格式
     * @param rf 返回的年月格式
     */
    public static List<String> allDay(TimeZone timeZone, Date start, String end, String ef, String rf) {
        return allDay(timeZone, start, parseLong(timeZone, end, ef), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * @param start 开始时间
     * @param sf start日期时间格式
     * @param end 结束时间
     * @param ef end日期时间格式
     * @param rf 返回的年月格式
     */
    public static List<String> allDay(String start, String sf, String end, String ef, String rf) {
        return allDay(parseLong(start, sf), parseLong(end, ef), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * @param timeZone 时区
     * @param start 开始时间
     * @param sf start日期时间格式
     * @param end 结束时间
     * @param ef end日期时间格式
     * @param rf 返回的年月格式
     */
    public static List<String> allDay(TimeZone timeZone, String start, String sf, String end, String ef, String rf) {
        return allDay(timeZone, parseLong(timeZone, start, sf), parseLong(timeZone, end, ef), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 返回日期格式：yyyy-MM-dd
     * @param start 开始时间
     * @param sf start日期时间格式
     * @param end 结束时间
     */
    public static List<String> allDay(String start, String sf, long end) {
        return allDay(parseLong(start, sf), end, f_y_M_d);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 返回日期格式：yyyy-MM-dd
     * @param timeZone 时区
     * @param start 开始时间
     * @param sf start日期时间格式
     * @param end 结束时间
     */
    public static List<String> allDay(TimeZone timeZone, String start, String sf, long end) {
        return allDay(timeZone, parseLong(timeZone, start, sf), end, f_y_M_d);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 返回日期格式：yyyy-MM-dd
     * @param start 开始时间
     * @param sf start日期时间格式
     * @param end 结束时间
     */
    public static List<String> allDay(String start, String sf, Date end) {
        return allDay(parseLong(start, sf), end, f_y_M_d);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 返回日期格式：yyyy-MM-dd
     * @param timeZone 时区
     * @param start 开始时间
     * @param sf start日期时间格式
     * @param end 结束时间
     */
    public static List<String> allDay(TimeZone timeZone, String start, String sf, Date end) {
        return allDay(timeZone, parseLong(timeZone, start, sf), end, f_y_M_d);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 返回日期格式：yyyy-MM-dd
     * @param start 开始时间
     * @param sf start日期时间格式
     * @param end 结束时间
     * @param ef end日期时间格式
     */
    public static List<String> allDay(String start, String sf, String end, String ef) {
        return allDay(start, sf, end, ef, f_y_M_d);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 返回日期格式：yyyy-MM-dd
     * @param timeZone 时区
     * @param start 开始时间
     * @param sf start日期时间格式
     * @param end 结束时间
     * @param ef end日期时间格式
     */
    public static List<String> allDay(TimeZone timeZone, String start, String sf, String end, String ef) {
        return allDay(timeZone, start, sf, end, ef, f_y_M_d);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 自动校验日期格式
     * 返回日期格式：yyyy-MM-dd
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allDay(String start, String end, String rf) {
        return allDay(start, getFormat(start), end, getFormat(end), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 自动校验日期格式
     * 返回日期格式：yyyy-MM-dd
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     * @param rf 返回的年月格式
     */
    public static List<String> allDay(TimeZone timeZone, String start, String end, String rf) {
        return allDay(timeZone, start, getFormat(start), end, getFormat(end), rf);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 返回日期格式：yyyy-MM-dd
     * @param start 开始时间
     * @param end 结束时间
     * @param ef end日期时间格式
     */
    public static List<String> allDay(long start, String end, String ef) {
        return allDay(start, parseLong(end, ef), f_y_M_d);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 返回日期格式：yyyy-MM-dd
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     * @param ef end日期时间格式
     */
    public static List<String> allDay(TimeZone timeZone, long start, String end, String ef) {
        return allDay(timeZone, start, parseLong(timeZone, end, ef), f_y_M_d);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 返回日期格式：yyyy-MM-dd
     * @param start 开始时间
     * @param end 结束时间
     * @param ef end日期时间格式
     */
    public static List<String> allDay(Date start, String end, String ef) {
        return allDay(start, parseLong(end, ef), f_y_M_d);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 返回日期格式：yyyy-MM-dd
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     * @param ef end日期时间格式
     */
    public static List<String> allDay(TimeZone timeZone, Date start, String end, String ef) {
        return allDay(timeZone, start, parseLong(timeZone, end, ef), f_y_M_d);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 返回日期格式：yyyy-MM-dd
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allDay(long start, long end) {
        return allDay(start, end, f_y_M_d);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 返回日期格式：yyyy-MM-dd
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allDay(TimeZone timeZone, long start, long end) {
        return allDay(timeZone, start, end, f_y_M_d);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 返回日期格式：yyyy-MM-dd
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allDay(long start, Date end) {
        return allDay(start, end, f_y_M_d);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 返回日期格式：yyyy-MM-dd
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allDay(TimeZone timeZone, long start, Date end) {
        return allDay(timeZone, start, end, f_y_M_d);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 返回日期格式：yyyy-MM-dd
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allDay(Date start, long end) {
        return allDay(start, end, f_y_M_d);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 返回日期格式：yyyy-MM-dd
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allDay(TimeZone timeZone, Date start, long end) {
        return allDay(timeZone, start, end, f_y_M_d);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 返回日期格式：yyyy-MM-dd
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allDay(Date start, Date end) {
        return allDay(start, end, f_y_M_d);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 返回日期格式：yyyy-MM-dd
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allDay(TimeZone timeZone, Date start, Date end) {
        return allDay(timeZone, start, end, f_y_M_d);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 自动校验日期格式
     * 返回日期格式：yyyy-MM-dd
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allDay(String start, long end) {
        return allDay(start, getFormat(start), end);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 自动校验日期格式
     * 返回日期格式：yyyy-MM-dd
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allDay(TimeZone timeZone, String start, long end) {
        return allDay(timeZone, start, getFormat(start), end);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 自动校验日期格式
     * 返回日期格式：yyyy-MM-dd
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allDay(String start, Date end) {
        return allDay(start, getFormat(start), end);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 自动校验日期格式
     * 返回日期格式：yyyy-MM-dd
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allDay(TimeZone timeZone, String start, Date end) {
        return allDay(timeZone, start, getFormat(start), end);
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 自动校验日期格式
     * 返回日期格式：yyyy-MM-dd
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allDay(String start, String end) {
        return allDay(start, getFormat(start), end, getFormat(end));
    }


    /**
     * 获取开始时间和结束时间之间所有的年月日字符串集合
     * 自动校验日期格式
     * 返回日期格式：yyyy-MM-dd
     * @param timeZone 时区
     * @param start 开始时间
     * @param end 结束时间
     */
    public static List<String> allDay(TimeZone timeZone, String start, String end) {
        return allDay(timeZone, start, getFormat(start), end, getFormat(end));
    }
}
