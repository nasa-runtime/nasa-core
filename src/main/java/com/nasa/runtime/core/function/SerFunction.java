package com.nasa.runtime.core.function;

import java.io.Serializable;
import java.util.function.Function;

/**
 * Nasa
 * 当一个Function继承了Serializable接口后，jvm会根据这个Function生成一个
 * SerializedLambda对象，可以根据这个对象获取Function
 * 具体执行的对象方法的一些参数，比如：参数类型、参数名称等等
 * @param <T> 具体对象的泛型
 * @param <R> 对象的方法返回值泛型
 */
@FunctionalInterface
public interface SerFunction<T, R> extends Function<T, R>, Serializable {

}
