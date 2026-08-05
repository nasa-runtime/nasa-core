package com.nasa.runtime.core.function;

import java.util.function.BiConsumer;

/**
 * Nasa
 * 因子双参消费
 * @param <F> 获取因子
 */
public interface FactorConsumer2<T, U, F> extends BiConsumer<T, U> {

    default F factor() {
        return null;
    }

}
