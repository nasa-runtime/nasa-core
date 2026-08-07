package io.github.nasaruntime.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 为可排序的扩展实现声明执行优先级，数值越小越优先。
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Order {

    /**
     * 业务作用: 提供扩展实现的稳定排序权重。
     * <p>
     * 参数说明: 无。
     *
     * @return 排序权重，数值越小越靠前
     */
    int value() default 0;
}
