package io.github.nasaruntime.core.function;

import java.util.function.BiFunction;

/**
 * Nasa
 * 返回boolean函数
 */
@FunctionalInterface
public interface BooleanBiFunction<T, U> extends BiFunction<T, U, Boolean> {

}
