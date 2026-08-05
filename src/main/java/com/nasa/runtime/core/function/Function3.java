package com.nasa.runtime.core.function;

/**
 * Nasa
 * 3个参数的映射函数
 * @param <T1> 参数1泛型
 * @param <T2> 参数2泛型
 * @param <T3> 参数3泛型
 * @param <R> 返回值泛型
 */
@FunctionalInterface
public interface Function3<T1, T2, T3, R> {

    R apply(T1 t1, T2 t2, T3 t3);

}
