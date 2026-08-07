package io.github.nasaruntime.core.annotation;

import io.github.nasaruntime.core.config.ThreadPoolInitializer;

import java.lang.annotation.*;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Nasa
 * 线程池注解配置
 * 当注解 Field 和 setter 时，如果 ThreadPoolUtils 的缓存中没有对应的线程池时，
 * 会自动创建线程池并赋值，如果已存在，则根据 singleton 来判断是否新建线程池
 * @see ThreadPoolInitializer 由这个类处理属性和 setter 方法的设置
 */
@SuppressWarnings("unused")
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ThreadPool {


    /**
     * 线程池名称
     */
    String value() default "Pool";


    /**
     * 是否异步执行
     */
    boolean async() default true;


    /**
     * 是否是单列线程池
     * true：如果存在同样的 value()，则返回缓存中的线程池
     * false：每次创建线程池时都创建新的
     */
    boolean singleton() default true;


    /**
     * 是否开启虚拟线程（协程），开启后下面的配置都将无效
     */
    boolean enableVirtualThread() default true;


    /**
     * 核心线程数，0 = Runtime.getRuntime().availableProcessors() * 2
     */
    int corePoolSize() default 0;


    /**
     * 最大线程数，0 = corePoolSize * 2
     */
    int maximumPoolSize() default 0;


    /**
     * 线程池中线程保活时间，单位 ms
     * 线程从队列 poll 任务时，如果在 keepAliveTime 的时间内 poll 不到任务，那么这条线程就结束了
     */
    long keepAliveTime() default 0L;


    /**
     * 线程池队列大小，0 = corePoolSize * 64
     */
    int capacity() default 0;


    /**
     * 拒绝策略
     */
    Class<? extends RejectedExecutionHandler> rejectedHandler() default ThreadPoolExecutor.CallerRunsPolicy.class;

}
