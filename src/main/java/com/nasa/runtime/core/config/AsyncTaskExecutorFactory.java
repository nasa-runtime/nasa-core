package com.nasa.runtime.core.config;

import com.nasa.runtime.core.concurrent.ThreadPoolUtils;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * 按配置创建异步任务执行器。
 */
public final class AsyncTaskExecutorFactory {

    private AsyncTaskExecutorFactory() {
    }

    /**
     * 业务作用: 在启用异步执行器时创建命名虚拟线程池，关闭时明确返回空值供装配层跳过注册。
     *
     * @param properties 虚拟线程与异步执行器参数
     * @return 已启用时返回新的虚拟线程执行器，否则返回 {@code null}
     */
    public static ExecutorService create(VirtualThreadProperties properties) {
        Objects.requireNonNull(properties, "properties");
        VirtualProperties executor = properties.getExecutor();
        if (!executor.isEnable()) {
            return null;
        }
        if (Objects.isNull(executor.getVirtualName())) {
            executor.setVirtualName("Virtual-Pool");
        }
        return ThreadPoolUtils.newVirtualThreadPool(
                true, executor.getVirtualName(), executor.getVirtualStart());
    }
}
