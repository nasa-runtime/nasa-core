package io.github.nasaruntime.core.cache;


/**
 * Nasa
 * 清空接口
 */
public interface Clearable {

    /**
     * 业务作用：清空实现方持有的全部缓存内容，供配置变更或人工干预时强制失效。
     * 默认空实现，使不支持清空语义的实现无需被迫提供一个假动作。
     *
     * 参数说明: 无。
     * 返回: 无返回值；实现方应保证可重复调用。
     */
    default void clear() {

    }

}
