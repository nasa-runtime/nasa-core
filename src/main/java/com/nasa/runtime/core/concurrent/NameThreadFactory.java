package com.nasa.runtime.core.concurrent;

import com.nasa.runtime.core.utils.StringUtils;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Nasa
 * 指定线程池名称
 * 为平台线程提供稳定、可诊断的业务名称。
 */
public class NameThreadFactory implements ThreadFactory {

    private static final AtomicInteger poolNumber = new AtomicInteger(1);
    private final ThreadGroup group;
    private final AtomicInteger threadNumber = new AtomicInteger(1);
    private final String namePrefix;
    private final boolean onlyName;

    /**
     * 业务作用: 创建带业务名称前缀的平台线程工厂，并默认追加线程序号。
     *
     * @param namePrefix 线程名称前缀
     */
    public NameThreadFactory(String namePrefix) {
        this(namePrefix, false);
    }

    /**
     * 业务作用: 创建可控制是否追加序号的平台线程工厂，便于单线程和线程池采用一致命名策略。
     *
     * @param namePrefix 线程名称前缀
     * @param onlyName 为 {@code true} 时不追加线程序号
     */
    public NameThreadFactory(String namePrefix, boolean onlyName) {
        group = Thread.currentThread().getThreadGroup();
        if (StringUtils.isBlank(namePrefix)) {
            this.namePrefix = StringUtils.concat("pool-", poolNumber.getAndIncrement(), "-thread-");
        } else {
            this.namePrefix = namePrefix.trim();
        }
        this.onlyName = onlyName;
    }

    /**
     * 业务作用: 创建非守护、普通优先级的平台线程，避免后台任务因主线程结束而被静默丢弃。
     *
     * @param runnable 线程执行任务
     * @return 已命名但尚未启动的新线程
     */
    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(group, runnable,
                this.onlyName ? namePrefix : namePrefix + threadNumber.getAndIncrement(),
                0);
        if (thread.isDaemon()) {
            thread.setDaemon(false);
        }
        if (thread.getPriority() != Thread.NORM_PRIORITY) {
            thread.setPriority(Thread.NORM_PRIORITY);
        }
        return thread;
    }
}
