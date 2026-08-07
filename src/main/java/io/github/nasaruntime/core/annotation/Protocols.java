package io.github.nasaruntime.core.annotation;

import io.github.nasaruntime.core.base.Protocol;

import java.lang.annotation.*;

/**
 * Nasa
 * 标记参与 {@link Protocol} 序列化的字段, 并给定字段在线路上的次序 / tag。
 * 只有标注了本注解的字段才进入协议; 类上一个都没标时编码结果为空数组, 不做全量字段的隐式回退 ——
 * 隐式回退会让后续新增的私有字段意外进入线路格式, 且老消费者按位取值时整体错位。
 * 注解只在字段上生效, getter/setter 不被扫描。
 * @see Protocol
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Protocols {

    /**
     * 字段在协议中的次序, 越小越在前; TAG_VALUE 模式下同时作为跨版本稳定的 tag。
     * 同一个类内不可重复, 重复会在元数据构建阶段直接抛异常。
     */
    int value() default 1000;

    /**
     * 转换器，只有第一个生效。
     * 框架推断不出线路形态时使用: 典型是泛型擦除后拿不到元素类型的容器字段 (如 {@code List<T>}),
     * 不指定转换器则元素按原值透传, 接收端无法还原。
     */
    Class<? extends Protocol.Converter<?>>[] converter() default {};
}
