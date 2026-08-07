package io.github.nasaruntime.core.function;

/**
 * Nasa
 * 3个参数的映射函数
 */
@FunctionalInterface
public interface Function3<T1, T2, T3, R> {

    /**
     * 业务作用：接收三个入参并计算结果，补足 JDK 只提供到 BiFunction 的空缺。
     *
     * @param t1 第一个入参
     * @param t2 第二个入参
     * @param t3 第三个入参
     * 返回: 计算结果。
     */
    R apply(T1 t1, T2 t2, T3 t3);

}
