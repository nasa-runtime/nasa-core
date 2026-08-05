package com.nasa.runtime.core.config;

import com.nasa.runtime.core.annotation.ThreadPool;
import com.nasa.runtime.core.concurrent.ThreadPoolUtils;
import com.nasa.runtime.core.utils.ReflectUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 为普通 Java 对象初始化 {@link ThreadPool} 标注的执行器字段或 setter。
 */
public final class ThreadPoolInitializer {

    private ThreadPoolInitializer() {
    }

    /**
     * 业务作用: 扫描目标对象上的线程池声明并完成注入，使非容器应用也能复用统一的线程池配置。
     *
     * @param target 等待初始化的业务对象
     * @return 完成线程池注入后的原对象
     * @throws NullPointerException 目标对象为空时抛出
     */
    public static <T> T initialize(T target) {
        Objects.requireNonNull(target, "target");
        initializeSetters(target);
        initializeFields(target);
        return target;
    }

    /**
     * 业务作用: 仅处理签名合法的单参数 setter，避免错误方法声明导致越界或错误注入。
     *
     * @param target 等待初始化的业务对象
     * 返回: 无；匹配的 setter 会收到新建或复用的执行器。
     */
    private static void initializeSetters(Object target) {
        List<Method> setters = ReflectUtils.allSetter(target.getClass());
        for (Method setter : setters) {
            ThreadPool threadPool = setter.getAnnotation(ThreadPool.class);
            if (Objects.isNull(threadPool) || setter.getParameterCount() != 1) {
                continue;
            }
            Object executor = createExecutor(setter.getParameterTypes()[0], threadPool);
            if (Objects.nonNull(executor)) {
                ReflectUtils.invoke(target, setter, executor);
            }
        }
    }

    /**
     * 业务作用: 只填充尚未赋值的执行器字段，避免覆盖调用方显式提供的线程池。
     *
     * @param target 等待初始化的业务对象
     * 返回: 无；匹配且为空的字段会被写入执行器。
     */
    private static void initializeFields(Object target) {
        List<Field> fields = ReflectUtils.allField(target.getClass());
        for (Field field : fields) {
            ThreadPool threadPool = field.getAnnotation(ThreadPool.class);
            if (Objects.isNull(threadPool) || Objects.nonNull(ReflectUtils.fieldGet(target, field))) {
                continue;
            }
            Object executor = createExecutor(field.getType(), threadPool);
            if (Objects.nonNull(executor)) {
                ReflectUtils.fieldSet(target, field, executor);
            }
        }
    }

    /**
     * 业务作用: 根据声明类型选择平台线程池或虚拟线程池，并拒绝向不兼容类型注入对象。
     *
     * @param targetType 字段或 setter 参数类型
     * @param threadPool 线程池创建参数
     * @return 支持的执行器类型返回对应线程池，不支持的类型返回 {@code null}
     */
    private static Object createExecutor(Class<?> targetType, ThreadPool threadPool) {
        if (targetType == Executor.class || targetType == ExecutorService.class) {
            return threadPool.enableVirtualThread()
                    ? ThreadPoolUtils.newVirtualThreadPool(threadPool)
                    : ThreadPoolUtils.newThreadPool(threadPool);
        }
        if (targetType == ThreadPoolExecutor.class) {
            return ThreadPoolUtils.newThreadPool(threadPool);
        }
        return null;
    }
}
