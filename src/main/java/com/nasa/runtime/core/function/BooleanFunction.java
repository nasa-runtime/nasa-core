package com.nasa.runtime.core.function;

import java.util.function.Function;

/**
 * Nasa
 * 返回boolean函数
 * @param <T> 参数泛型
 */
@SuppressWarnings("unused")
@FunctionalInterface
public interface BooleanFunction<T> extends Function<T, Boolean> {

}
