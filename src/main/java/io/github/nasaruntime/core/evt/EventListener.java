package io.github.nasaruntime.core.evt;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.nasaruntime.core.base.Initialization;

/**
 * Nasa
 * 集群事件驱动 处理事件监听接口
 */
public interface EventListener<T, TS> extends EventTopic, Initialization {

    /**
     * 业务作用：声明本监听器负责的事件名，是框架把消息路由到该实现的唯一依据。
     * 一个 listener 只绑定一个 event；需要监听多个事件时必须提供多个 listener 实例。
     *
     * 参数说明: 无。
     * 返回: 事件名，不允许为 null 或空。
     */
    String event();

    /**
     * 业务作用：提供反序列化的目标类型引用。当框架配置 {@code activateDefaultTyping=false} 时，
     * 消息体里不带类型信息，框架依赖本方法做二次反序列化才能还原出具体类型。
     *
     * 参数说明: 无。
     * 返回: 目标类型引用；默认返回 null，表示由框架按默认策略反序列化。
     */
    default TypeReference<T> paramType() {
        return null;
    }

    /**
     * 业务作用：消费回调。{@code TS} 由具体子接口决定（Single 为 T，Batch 为 {@code List<T>}）。
     * 失败语义因运行模式而不同，实现方必须据此决定幂等性：
     * PROXY 模式抛异常交由框架的 errorHandler 处理；
     * PARTITION 模式抛异常则不 ACK，消息留在 pending 中并在 30 秒后被 XAUTOCLAIM 重投，
     * 因此该模式下的业务逻辑必须幂等，否则重投会造成重复副作用。
     *
     * @param data 已反序列化的事件数据
     * 返回: 无返回值；正常返回即视为消费成功。
     */
    void onEvent(TS data);

}
