package io.github.nasaruntime.core.function;

import java.util.function.BiConsumer;

/**
 * Nasa
 * 因子双参消费
 */
public interface FactorConsumer2<T, U, F> extends BiConsumer<T, U> {

    /**
     * 业务作用：让消费者顺带携带一个与消费逻辑相关的因子，供调用方在不改变 BiConsumer 契约的前提下
     * 取得额外上下文（例如分组键或权重）。
     *
     * 参数说明: 无。
     * 返回: 该消费者携带的因子；默认返回 null，表示未携带。
     */
    default F factor() {
        return null;
    }

}
