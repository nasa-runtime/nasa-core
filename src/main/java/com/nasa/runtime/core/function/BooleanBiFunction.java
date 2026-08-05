package com.nasa.runtime.core.function;

import java.util.function.BiFunction;

/**
 * Nasa
 * 返回boolean函数
 * @param <T> 参数1泛型
 * @param <U> 参数2泛型
 */
@FunctionalInterface
public interface BooleanBiFunction<T, U> extends BiFunction<T, U, Boolean> {

}
