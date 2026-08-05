package com.nasa.runtime.core.function;

import com.nasa.runtime.core.exception.FunctionException;
import com.nasa.runtime.core.utils.ReflectUtils;
import com.nasa.runtime.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nasa
 * 函数操作工具
 */
@SuppressWarnings("unused")
@Slf4j
public abstract class FunctionUtils {


    private static final String ReturnType_prefix = "()L";

    /**
     * lambda class → fieldName 缓存。method reference (如 {@code User::getName}) 在同一源位置编译成唯一合成类,
     * 故按 {@code getter.getClass()} 缓存稳定且区分不同 getter。用 CHM 保持实现轻量; 该缓存面向稳定业务 getter,
     * 不处理热加载 class 卸载。
     */
    private static final ConcurrentHashMap<Class<?>, String> FIELD_NAME_CACHE = new ConcurrentHashMap<>(256);


    /**
     * 获取函数的SerializedLambda对象
     *
     * @param sf  函数
     * @param <T> 具体对象的泛型
     * @param <R> 对象的方法返回值泛型
     */
    public static <T, R> SerializedLambda serializedLambda(SerFunction<T, R> sf) {
        try {
            Method method = sf.getClass().getDeclaredMethod("writeReplace");
            method.setAccessible(true);
            return (SerializedLambda) method.invoke(sf);
        } catch (Exception e) {
            throw new FunctionException(e.getMessage(), e);
        }
    }


    /**
     * 获取对象的方法名称
     *
     * @param getterFunction 对象的get/is函数，如：AppCollector::getObj
     * @param <T>            具体对象的泛型
     * @param <R>            对象的方法返回值泛型
     */
    public static <T, R> String methodName(SerFunction<T, R> getterFunction) {
        return serializedLambda(getterFunction).getImplMethodName();
    }


    /**
     * 获取对象的方法名称
     *
     * @param getterFunction 对象的get/is函数，如：AppCollector::getObj
     * @param <T>            具体对象的泛型
     * @param <R>            对象的方法返回值泛型
     */
    public static <T, R> String fieldName(SerFunction<T, R> getterFunction) {
        return fieldName(methodName(getterFunction));
    }


    /**
     * 同 {@link #fieldName(SerFunction)}, 但按 lambda class 缓存解析结果, 省去重复的 writeReplace 反射解析。
     * 适合热路径反复用同一 getter (如 {@code Criteria.where(User::getName)})。
     *
     * @param getterFunction 对象的 get/is 方法引用, 如 {@code User::getName}
     */
    public static <T, R> String fieldNameCached(SerFunction<T, R> getterFunction) {
        if (getterFunction == null) {
            throw new IllegalArgumentException("getterFunction cannot be null");
        }
        Class<?> k = getterFunction.getClass();
        String cached = FIELD_NAME_CACHE.get(k);
        if (cached != null) return cached;
        String name = fieldName(getterFunction);
        String prev = FIELD_NAME_CACHE.putIfAbsent(k, name);
        return prev == null ? name : prev;
    }

    /**
     * 批量版 {@link #fieldNameCached(SerFunction)}: 把一组 getter 解析成 对象字段名数组,
     * 复用同一 CHM 缓存. null / 0 长度入参返回 null (调用方按"等价不设字段"语义处理).
     * <p>
     * 典型用法: {@code RsQuery.fields(Order::getUid, Order::getPrice)} / {@code RsAggregation.groupBy(...)}
     * 等需要把 vararg getter 一次性转成 String[] 的 DSL setter.
     *
     * @param getters getter 方法引用数组
     * @param <T>     具体对象的泛型
     * @return 对象字段名数组; null / 0 长度入参返回 null
     */
    @SafeVarargs
    public static <T> String[] fieldNamesCached(SerFunction<T, ?>... getters) {
        if (getters == null || getters.length == 0) return null;
        String[] names = new String[getters.length];
        for (int i = 0; i < getters.length; i++) {
            names[i] = fieldNameCached(getters[i]);
        }
        return names;
    }


    /**
     * 通过方法名称，获取属性名称，仅支持get/is方法
     *
     * @param getterName 方法名称，如：getName、isEnable
     */
    private static String fieldName(String getterName) {
        return ReflectUtils.fieldName(getterName);
    }


    /**
     * 获取函数指定对象方法的返回值类型
     *
     * @param methodFunction 对象的get函数，如：AppCollector::getObj
     * @param <T>            具体对象的泛型
     * @param <R>            对象的方法返回值泛型
     */
    public static <T, R> String returnType(SerFunction<T, R> methodFunction) {
        String rts = serializedLambda(methodFunction).getImplMethodSignature();
        if (rts.startsWith(ReturnType_prefix)) {
            rts = rts.substring(ReturnType_prefix.length());
        }
        if (rts.endsWith(StringUtils.Mark_semicolon)) {
            rts = rts.substring(0, rts.length() - 1);
        }
        return rts.replaceAll(StringUtils.Mark_right_slash, StringUtils.Mark_spot);
    }

}
