package com.nasa.runtime.core.config;

import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

/**
 * Nasa
 * 虚拟线程参数配置
 */
@Setter
@Getter
public class VirtualThreadProperties {

    /* 调度器的并行数，默认平台线程数 * 2 */
    private int parallelism = Runtime.getRuntime().availableProcessors() << 1;
    /* 维持目标并行度允许的额外线程的最大数量，默认256，当比parallelism小时，parallelism会被重置为maxPoolSize */
    private Integer maxPoolSize;
    /* 最小可运行任务数，默认parallelism/2 */
    private Integer minRunnable;
    /* 平台线程调度器最大线程数，默认1 */
    private Integer unparkerMaxPoolSize;
    /* 异步执行器使用的虚拟线程参数 */
    private final VirtualProperties executor = new VirtualProperties();

    /**
     * 业务作用: 将当前配置发布为 JDK 虚拟线程调度器的系统属性，调用方应在创建首个虚拟线程前执行。
     * <p>
     * 参数说明: 无。
     * <p>
     * 返回: 无；成功后更新当前 JVM 的虚拟线程调度参数。
     */
    public void apply() {
        System.setProperty("jdk.virtualThreadScheduler.parallelism", String.valueOf(parallelism));
        if (Objects.nonNull(maxPoolSize)) {
            System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", maxPoolSize.toString());
        }
        if (Objects.nonNull(minRunnable)) {
            System.setProperty("jdk.virtualThreadScheduler.minRunnable", minRunnable.toString());
        }
        if (Objects.nonNull(unparkerMaxPoolSize)) {
            System.setProperty("jdk.unparker.maxPoolSize", unparkerMaxPoolSize.toString());
        }
    }
}
