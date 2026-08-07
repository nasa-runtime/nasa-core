package io.github.nasaruntime.core.base;

/**
 * Nasa
 * 初始化接口
 */
public interface Initialization {

    /**
     * 业务作用: 在主初始化逻辑前执行依赖准备，适合补齐无法在构造阶段建立的关联。
     * <p>
     * 参数说明: 无。
     * <p>
     * 返回: 无；实现方可更新自身准备状态。
     * <p>
     * 在initialize之前执行
     */
    default void before() {

    }

    /**
     * 业务作用: 执行组件的核心动态初始化逻辑。
     * <p>
     * 参数说明: 无。
     * <p>
     * 返回: 无；实现方完成自身可用状态的建立。
     * <p>
     * 初始化各类动态设置
     */
    default void initialize() {

    }

    /**
     * 业务作用: 在所有核心初始化步骤结束后执行依赖完整状态的收尾任务。
     * <p>
     * 参数说明: 无。
     * <p>
     * 返回: 无；实现方完成初始化后的发布或收尾。
     * <p>
     * 在initialize之后执行
     * 执行各类需要在所有动态设置都结束后执行的任务
     */
    default void after() {

    }

}
