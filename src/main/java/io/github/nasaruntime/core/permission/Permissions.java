package io.github.nasaruntime.core.permission;

import java.lang.annotation.Documented;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Nasa
 * 权限注解
 * {@link Inherited} 保证子类仍能读取类型级权限声明，避免生成子类或业务继承后丢失权限元数据。
 */
@SuppressWarnings("unused")
@Target({METHOD, TYPE})
@Retention(RUNTIME)
@Documented
@Inherited
public @interface Permissions {

    /**
     * 权限
     */
    String[] value() default {};

    /**
     * 提示语
     */
    String message() default "权限不足";

    /**
     * 是否忽略权限校验
     * 当 @EnableGlobalPermission 被注解时，通过此值来忽略必须的权限校验
     */
    boolean ignore() default false;

}
