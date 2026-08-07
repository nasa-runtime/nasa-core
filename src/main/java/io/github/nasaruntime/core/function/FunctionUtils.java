package io.github.nasaruntime.core.function;

import io.github.nasaruntime.core.exception.FunctionException;
import io.github.nasaruntime.core.utils.ReflectUtils;
import io.github.nasaruntime.core.utils.StringUtils;
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
     * 业务作用：反射调用 lambda 合成类的 writeReplace 取出其序列化描述，是由方法引用反推字段名的基础。
     * 仅对 SerFunction 这类可序列化函数式接口有效，普通 lambda 不会生成该方法。
     *
     * @param sf 可序列化的方法引用
     * 返回: 该方法引用的序列化描述；反射失败时抛出 FunctionException。
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
     * 业务作用：由方法引用取出其实现方法名，使调用方可以用编译期可校验的 {@code User::getName}
     * 代替易写错且重构不跟随的字符串字面量。
     *
     * @param getterFunction 对象的 get/is 方法引用
     * 返回: 实现方法名，如 getName。
     */
    public static <T, R> String methodName(SerFunction<T, R> getterFunction) {
        return serializedLambda(getterFunction).getImplMethodName();
    }


    /**
     * 业务作用：由 getter 方法引用推出对应的属性名，用于查询条件、字段投影等需要字段名的 DSL。
     * 每次调用都做一次 writeReplace 反射解析，热路径应改用带缓存的版本。
     *
     * @param getterFunction 对象的 get/is 方法引用
     * 返回: 属性名，如 name。
     */
    public static <T, R> String fieldName(SerFunction<T, R> getterFunction) {
        return fieldName(methodName(getterFunction));
    }


    /**
     * 业务作用：带缓存的属性名解析，省去热路径上重复的 writeReplace 反射。
     * 缓存以 lambda 合成类为键：同一处源码的方法引用编译成唯一合成类，因此该键既稳定又能区分不同 getter。
     * 该缓存面向稳定的业务 getter，不处理类热加载与卸载场景。
     *
     * @param getterFunction 对象的 get/is 方法引用，不允许为 null
     * 返回: 属性名；入参为 null 时抛出 IllegalArgumentException。
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
     * 业务作用：批量把一组 getter 方法引用解析成属性名数组，复用同一份缓存，
     * 供 {@code fields(...)}、{@code groupBy(...)} 这类接收可变参数 getter 的 DSL 使用。
     *
     * @param getters getter 方法引用数组
     * 返回: 属性名数组；入参为 null 或长度为 0 时返回 null，语义等价于「不设置字段」。
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
     * 业务作用：把 getter 方法名转换为属性名，只支持 get 与 is 两种前缀。
     *
     * @param getterName 方法名，如 getName、isEnable
     * 返回: 去掉前缀并首字母小写后的属性名。
     */
    private static String fieldName(String getterName) {
        return ReflectUtils.fieldName(getterName);
    }


    /**
     * 业务作用：由方法引用解析出其返回值类型的全限定名，供需要按返回类型做分支或校验的调用方使用。
     *
     * @param methodFunction 可序列化的方法引用
     * 返回: 返回值类型的全限定名。
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
