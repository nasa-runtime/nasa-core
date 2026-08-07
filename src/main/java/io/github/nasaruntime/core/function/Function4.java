package io.github.nasaruntime.core.function;

/**
 * Nasa
 * 4个参数的映射函数
 */
@FunctionalInterface
public interface Function4<T1, T2, T3, T4, R> {

    /**
     * 业务作用：接收四个入参并计算结果，补足 JDK 只提供到 BiFunction 的空缺。
     *
     * @param t1 第一个入参
     * @param t2 第二个入参
     * @param t3 第三个入参
     * @param t4 第四个入参
     * 返回: 计算结果。
     */
    R apply(T1 t1, T2 t2, T3 t3, T4 t4);

}
