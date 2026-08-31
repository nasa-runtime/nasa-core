package io.github.nasaruntime.core.evt;

import io.github.nasaruntime.core.base.Partition;

/**
 * 为事件监听器声明本地 Partition 路由能力。
 * 具体接入层负责选择该能力、管理 Runner 生命周期，并把业务执行结果转换成自身协议的确认或提交结果。
 *
 * @param <T> 单条事件的数据类型
 * @param <TS> 监听器实际接收的数据结构
 */
public interface PartitionedEventListener<T, TS> extends EventListener<T, TS> {

    /**
     * 业务作用：提供事件在本地 Partition 执行架构中的路由键。
     *
     * 参数说明:
     * @param data 待处理的单条业务事件
     *
     * 返回: 返回相同顺序域使用的路由键；返回 null 表示不要求按键保序。
     */
    default Object partitionKey(T data) {
        return null;
    }

    /**
     * 业务作用：指定监听器使用的 Partition 执行器。
     *
     * 参数说明: 无。
     *
     * 返回: 返回自定义执行器；返回 null 时由接入层提供默认执行器。
     */
    default Partition.PartitionRunner partition() {
        return null;
    }
}
