package io.github.nasaruntime.core.cache;


/**
 * Nasa
 * 重新加载接口
 */
public interface Reloadable {

    /**
     * 业务作用：让实现方从权威数据源重新装载缓存内容，供数据变更后主动刷新。
     * 默认空实现，使不支持重载语义的实现无需被迫提供一个假动作。
     *
     * 参数说明: 无。
     * 返回: 无返回值；实现方应保证可重复调用。
     */
    default void reload() {

    }

}
