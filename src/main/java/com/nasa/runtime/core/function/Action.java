package com.nasa.runtime.core.function;

import com.nasa.runtime.core.base.AnyHolder;
import com.nasa.runtime.core.utils.LogUtils;

/**
 * Nasa
 * 没有参数和返回值的执行器函数
 */
@FunctionalInterface
public interface Action extends Runnable {

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
     * 没有参数和返回值的执行器函数
     */
    void action();

}
