package com.nasa.runtime.core.function;

/**
 * Nasa
 * 3个参数的消费函数
 */
@SuppressWarnings("unused")
@FunctionalInterface
public interface Consumer3<T1, T2, T3> {

    /**
     * 消费接口
     */
    void accept(T1 t1, T2 t2, T3 t3);

}
