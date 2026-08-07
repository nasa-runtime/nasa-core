package io.github.nasaruntime.core.function;

/**
 * Nasa
 * 3个参数的消费函数
 */
@SuppressWarnings("unused")
@FunctionalInterface
public interface Consumer3<T1, T2, T3> {

    /**
     * 业务作用：消费三个入参，补足 JDK 只提供到 BiConsumer 的空缺。
     *
     * @param t1 第一个入参
     * @param t2 第二个入参
     * @param t3 第三个入参
     * 返回: 无返回值。
     */
    void accept(T1 t1, T2 t2, T3 t3);

}
