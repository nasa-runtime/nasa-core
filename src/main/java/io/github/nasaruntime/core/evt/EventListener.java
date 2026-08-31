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
     * 业务作用：接收接入层完成路由和反序列化后的事件。{@code TS} 由具体子接口决定，
     * 可以是单条事件，也可以是接入层定义的批量结构。
     * 消息确认、offset 提交、失败重试和重复投递语义由具体接入层约定，实现方必须按照对应协议
     * 决定幂等边界，不能把本接口的正常返回或异常直接解释为统一的交付保证。
     *
     * @param data 已反序列化的事件数据
     * 返回: 无返回值；正常返回即视为消费成功。
     */
    void onEvent(TS data);

}
