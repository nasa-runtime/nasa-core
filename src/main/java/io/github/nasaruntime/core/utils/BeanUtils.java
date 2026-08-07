package io.github.nasaruntime.core.utils;

import io.github.nasaruntime.core.exception.ReflectException;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 基于 JavaBeans 约定执行对象属性复制。
 */
@SuppressWarnings("unused")
public final class BeanUtils {

    /**
     * 业务作用：构造实例。字段取默认值。
     *
     * 参数说明: 无。
     * 返回: 构造完成后可直接使用的实例。
     */
    private BeanUtils() {
    }

    /**
     * 业务作用: 将源对象中名称和类型兼容的可读属性复制到目标对象的可写属性。
     *
     * @param source 源实例
     * @param target 目标实例
     * @param ignoreProperties 不参与复制的属性名称
     * 返回: 无；匹配属性会写入目标对象。
     */
    public static void copyProperties(Object source, Object target, String... ignoreProperties) {
        copy(source, target, false, ignoreProperties);
    }

    /**
     * 业务作用: 复制非空属性，避免局部更新时用空值覆盖目标对象的既有业务数据。
     *
     * @param source 源实例
     * @param target 目标实例
     * 返回: 无；源对象中的非空兼容属性会写入目标对象。
     */
    public static void copyIgnoreNull(Object source, Object target) {
        copy(source, target, true);
    }

    /**
     * 业务作用: 执行属性复制的公共校验、属性匹配和异常转换，保证部分属性失败时不会被静默忽略。
     *
     * @param source 源实例
     * @param target 目标实例
     * @param ignoreNull 是否跳过空值
     * @param ignoreProperties 不参与复制的属性名称
     * 返回: 无；复制失败时抛出统一反射异常。
     */
    private static void copy(
            Object source, Object target, boolean ignoreNull, String... ignoreProperties) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Set<String> ignored = new HashSet<>(Arrays.asList(ignoreProperties));
        try {
            Map<String, PropertyDescriptor> targetProperties = descriptors(target.getClass());
            for (PropertyDescriptor sourceProperty
                    : Introspector.getBeanInfo(source.getClass(), Object.class).getPropertyDescriptors()) {
                if (ignored.contains(sourceProperty.getName())) {
                    continue;
                }
                PropertyDescriptor targetProperty = targetProperties.get(sourceProperty.getName());
                Method read = sourceProperty.getReadMethod();
                Method write = Objects.isNull(targetProperty) ? null : targetProperty.getWriteMethod();
                if (Objects.isNull(read) || Objects.isNull(write)
                        || !write.getParameterTypes()[0].isAssignableFrom(read.getReturnType())) {
                    continue;
                }
                Object value = read.invoke(source);
                if (!ignoreNull || Objects.nonNull(value)) {
                    write.invoke(target, value);
                }
            }
        } catch (IntrospectionException | IllegalAccessException | InvocationTargetException e) {
            Throwable cause = e instanceof InvocationTargetException invocation
                    ? invocation.getTargetException()
                    : e;
            throw new ReflectException(cause.getMessage(), cause);
        }
    }

    /**
     * 业务作用: 为目标类型建立按名称索引的属性描述符，降低复制循环中的匹配成本。
     *
     * @param type 目标对象类型
     * @return 属性名称到描述符的映射
     * @throws IntrospectionException 类型无法按 JavaBeans 规则解析时抛出
     */
    private static Map<String, PropertyDescriptor> descriptors(Class<?> type)
            throws IntrospectionException {
        Map<String, PropertyDescriptor> descriptors = new HashMap<>();
        for (PropertyDescriptor descriptor
                : Introspector.getBeanInfo(type, Object.class).getPropertyDescriptors()) {
            descriptors.put(descriptor.getName(), descriptor);
        }
        return descriptors;
    }
}
