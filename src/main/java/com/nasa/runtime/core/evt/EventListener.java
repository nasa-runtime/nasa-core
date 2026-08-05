package com.nasa.runtime.core.evt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nasa.runtime.core.base.Initialization;

/**
 * Nasa
 * 集群事件驱动 处理事件监听接口
 */
public interface EventListener<T, TS> extends EventTopic, Initialization {

    /**
     * 事件名 (单值, 与 EventListener 对齐)。
     * 一个 listener 一个 event; 多 event 需要多个 listener 实例。
     */
    String event();

    /**
     * 反序列化引用。{@code activateDefaultTyping=false} 时框架用它做二次反序列化拿到具体类型。
     * 默认 null, 业务方可选实现。
     */
    default TypeReference<T> paramType() {
        return null;
    }

    /**
     * 消费回调。{@code TS} 类型由具体子接口决定（Single = T，Batch = {@code List<T>}）。
     * <p>
     * 失败语义:
     *   <ul>
     *     <li>PROXY 模式 — 抛异常由框架的 errorHandler 处理</li>
     *     <li>PARTITION 模式 — 抛异常 → 不 ACK → 留 pending → 30s 后 XAUTOCLAIM 重投, 业务必须幂等</li>
     *   </ul>
     */
    void onEvent(TS data);

}
