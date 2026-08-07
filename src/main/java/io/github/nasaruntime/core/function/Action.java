package io.github.nasaruntime.core.function;

import io.github.nasaruntime.core.base.AnyHolder;
import io.github.nasaruntime.core.utils.LogUtils;

/**
 * Nasa
 * 没有参数和返回值的执行器函数
 */
@FunctionalInterface
public interface Action extends Runnable {

    /**
     * 业务作用：作为 Runnable 交给线程池执行时的入口。相对直接调用 action 多做两件事：
     * 捕获并记录全部异常，避免单个任务的失败杀死线程池工作线程；
     * 以及在 finally 清空 AnyHolder 线程上下文——线程池会复用线程，
     * 不清空会让本次任务的上下文泄漏给下一个任务，既是内存泄漏也是数据串号。
     *
     * 参数说明: 无。
     * 返回: 无返回值；异常在此被吞掉，需要感知异常的调用方应直接调用 action。
     */
    default void run() {
        try {
            this.action();
        } catch (Throwable t) {
            LogUtils.getLogger(Action.class).error(t.getMessage(), t);
        } finally {
            // 防止内存泄漏
            AnyHolder.clear();
        }
    }

    /**
     * 业务作用：由实现方给出的实际动作，无参数也无返回值。
     * 直接调用时异常向上传播；经 run 调用时异常被记录并吞掉。
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    void action();

}
