package io.github.nasaruntime.core.permission;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Nasa
 * 开启所有接口必须要有权限，否则无法访问
 */
@SuppressWarnings("unused")
@Target({TYPE})
@Retention(RUNTIME)
@Inherited
public @interface EnableGlobalPermission {

    /**
     * 所有接口是否必须有权限
     */
    boolean value() default true;

    /**
     * 提示语
     */
    String message() default "权限不足";

}
