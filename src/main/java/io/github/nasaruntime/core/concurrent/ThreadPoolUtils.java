package io.github.nasaruntime.core.concurrent;

import io.github.nasaruntime.core.annotation.ThreadPool;
import io.github.nasaruntime.core.exception.ReflectException;
import io.github.nasaruntime.core.utils.MapUtils;
import io.github.nasaruntime.core.utils.ReflectUtils;
import io.github.nasaruntime.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * Nasa
 * 线程池工具
 */
@SuppressWarnings("unused")
@Slf4j
public abstract class ThreadPoolUtils {

    /* 注解属性值缓存 */
    private static final Map<String, Object> cache = new HashMap<>(2);

    /* 单列线程池缓存 */
    private static final Map<String, ExecutorService> singletonThreadPoolMap = new ConcurrentHashMap<>(2);

    /* 线程池拒绝策略 */
    private static final Map<Class<?>, RejectedExecutionHandler> rejectedHandlerMap = new HashMap<>(4);

    static {
        rejectedHandlerMap.put(ThreadPoolExecutor.CallerRunsPolicy.class, new ThreadPoolExecutor.CallerRunsPolicy());
        rejectedHandlerMap.put(ThreadPoolExecutor.AbortPolicy.class, new ThreadPoolExecutor.AbortPolicy());
        rejectedHandlerMap.put(ThreadPoolExecutor.DiscardPolicy.class, new ThreadPoolExecutor.DiscardPolicy());
    }


    /**
     * 业务作用：按配置的实现类构造拒绝策略。
     *
     * @param clazz 拒绝策略实现类
     * 返回: 拒绝策略实例；构造失败时回退到默认策略。
     */
    private static RejectedExecutionHandler rejectedHandler(Class<? extends RejectedExecutionHandler> clazz) {
        var rejectedHandler = rejectedHandlerMap.get(clazz);
        if (Objects.nonNull(rejectedHandler)) {
            return rejectedHandler;
        }

        rejectedHandler = ReflectUtils.newInstance(clazz);

        rejectedHandlerMap.put(clazz, rejectedHandler);

        return rejectedHandler;
    }


    /**
     * 业务作用：添加缓存
     *
     * @param scenes 场景
     * @param attributes ThreadPool注解中的属性值
     * 返回: 无返回值。
     */
    public static void addAttributes(String scenes, Map<String, Object> attributes) {
        log.info("添加线程池场景：scenes:{},attributes:{}", scenes, StringUtils.toString(attributes));
        cache.put(scenes, attributes);
    }


    /**
     * 业务作用：添加缓存
     *
     * @param scenes 场景
     * @param threadPool 注解
     * 返回: 无返回值。
     */
    public static void addAttributes(String scenes, ThreadPool threadPool) {
        log.info("添加线程池场景：scenes:{},threadPool:{}", scenes, StringUtils.toString(threadPool));
        cache.put(scenes, threadPool);
    }


    /**
     * 业务作用：获取指定场景下是否支持线程池状态
     *
     * @param scenes 场景
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    @SuppressWarnings("unchecked")
    public static boolean async(String scenes) {
        Object obj = cache.get(scenes);
        if (Objects.isNull(obj)) {
            return false;
        }
        if (obj instanceof Map) {
            return (boolean) ((Map<String, Object>) obj).get("async");
        }
        return ((ThreadPool) obj).async();
    }


    /**
     * 业务作用：按给定配置创建线程池。
     *
     * @param scenes 锁场景，不同场景互不影响
     * 返回: 线程池实例。
     */
    @SuppressWarnings("unchecked")
    public static ExecutorService newThreadPool(String scenes) {
        Object obj = cache.get(scenes);
        if (Objects.isNull(obj)) {
            throw new ReflectException("请配置线程池[{}]的参数：ThreadPoolUtils.addAttributes", scenes);
        }
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            if (MapUtils.getBoolean(map, "enableVirtualThread", false)) {
                return newVirtualThreadPool(map);
            }
            return newThreadPool((Map<String, Object>) obj);
        }
        ThreadPool threadPool = (ThreadPool) obj;
        if (threadPool.enableVirtualThread()) {
            return newVirtualThreadPool(threadPool);
        }
        return newThreadPool(threadPool);
    }


    /**
     * 业务作用：按给定配置创建线程池。
     *
     * @param threadPool 线程池注解配置
     * 返回: 线程池实例。
     */
    public static ExecutorService newThreadPool(ThreadPool threadPool) {
        if (!threadPool.async()) {
            return null;
        }

        if (threadPool.enableVirtualThread()) {
            // 是虚拟线程
            return newVirtualThreadPool(threadPool);
        }

        // 线程池名称
        String poolName = threadPool.value();

        // 是否是单列线程池
        if (threadPool.singleton()) {
            ExecutorService executor = singletonThreadPoolMap.get(poolName);
            if (Objects.nonNull(executor)) {
                return executor;
            }
        }

        // 核心线程数
        int corePoolSize = threadPool.corePoolSize();
        if (corePoolSize <= 0) {
            corePoolSize = Runtime.getRuntime().availableProcessors() << 1;
        }

        // 最大线程数
        int maximumPoolSize = threadPool.maximumPoolSize();
        if (maximumPoolSize <= 0) {
            maximumPoolSize = corePoolSize << 1;
        }

        // 线程池中线程保活时间，单位 ms
        long keepAliveTime = threadPool.keepAliveTime();

        // 线程池队列大小
        int capacity = threadPool.capacity();
        if (capacity <= 0) {
            capacity = corePoolSize << 6;
        }

        // 创建线程池
        return newThreadPool(threadPool.singleton(), corePoolSize, maximumPoolSize
                , keepAliveTime, capacity, poolName, threadPool.rejectedHandler());
    }


    /**
     * 根据 @ThreadPool 注解，生成线程池
     * @param attributes ThreadPool注解中的属性值
     */
    @SuppressWarnings({"unchecked", "ConstantConditions"}) // 抑制有可能产生空指针警告
    /**
     * 业务作用：按给定配置创建线程池。
     *
     * @param attributes 线程池参数表
     * 返回: 线程池实例。
     */
    public static ExecutorService newThreadPool(Map<String, Object> attributes) {

        if (Objects.isNull(attributes)) {
            return null;
        }

        if (!MapUtils.getBoolean(attributes, "async")) {
            return null;
        }

        if (MapUtils.getBoolean(attributes, "enableVirtualThread")) {
            // 是虚拟线程
            return newVirtualThreadPool(attributes);
        }

        // 线程池名称
        String poolName = MapUtils.getString(attributes, "value");

        // 是否是单列线程池
        boolean singleton = MapUtils.getBoolean(attributes, "singleton");
        if (singleton) {
            ExecutorService executor = singletonThreadPoolMap.get(poolName);
            if (Objects.nonNull(executor)) {
                return executor;
            }
        }

        // 核心线程数
        int corePoolSize = MapUtils.getInteger(attributes, "corePoolSize");
        if (corePoolSize <= 0) {
            corePoolSize = Runtime.getRuntime().availableProcessors() << 1;
        }

        // 最大线程数
        int maximumPoolSize = MapUtils.getInteger(attributes, "maximumPoolSize");
        if (maximumPoolSize <= 0) {
            maximumPoolSize = corePoolSize << 1;
        }

        // 线程池中线程保活时间，单位 ms
        long keepAliveTime = MapUtils.getLong(attributes, "keepAliveTime");

        // 线程池队列大小
        int capacity = MapUtils.getInteger(attributes, "capacity");
        if (capacity <= 0) {
            capacity = corePoolSize << 6;
        }

        return newThreadPool(singleton, corePoolSize, maximumPoolSize
                , keepAliveTime, capacity, poolName
                , (Class<? extends RejectedExecutionHandler>) attributes.get("rejectedHandler"));
    }


    /**
     * 业务作用：按完整参数创建平台线程池，是本类各 newThreadPool 重载的统一收口。
     * 单例模式下按池名复用已创建的实例，避免同一用途重复建池耗尽线程资源。
     *
     * @param singleton 为 true 时按池名复用已有实例
     * @param corePoolSize 核心线程数
     * @param maximumPoolSize 最大线程数
     * @param keepAliveTime 非核心线程的空闲存活毫秒数
     * @param capacity 任务队列容量；队列满后才会扩到最大线程数
     * @param poolName 池名，同时用作线程名前缀与单例复用的键
     * @param clazz 队列与线程都用尽时的拒绝策略实现类
     * 返回: 按给定参数创建的线程池；单例模式下同名返回已有实例。
     */
    public static ThreadPoolExecutor newThreadPool(
            boolean singleton
            , int corePoolSize
            , int maximumPoolSize
            , long keepAliveTime
            , int capacity
            , String poolName
            , Class<? extends RejectedExecutionHandler> clazz) {

        if (singleton) {
            ThreadPoolExecutor executor = (ThreadPoolExecutor) singletonThreadPoolMap.get(poolName);
            if (Objects.nonNull(executor)) {
                return executor;
            }
        }

        log.info("创建线程池{}： corePoolSize:{}, maximumPoolSize:{}, keepAliveTime:{}, capacity:{}, rejectedHandler:{}"
                , poolName, corePoolSize, maximumPoolSize, keepAliveTime, capacity, clazz.getName());

        // 创建线程池
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize
                , maximumPoolSize
                , keepAliveTime
                , TimeUnit.MILLISECONDS
                , new LinkedBlockingQueue<>(capacity)
                , new NameThreadFactory(poolName)
                , rejectedHandler(clazz));

        // 添加单列线程池缓存
        if (singleton) {
            singletonThreadPoolMap.put(poolName, executor);
        }

        return executor;
    }


    /**
     * 业务作用：按给定配置创建线程池。
     *
     * @param singleton 为 true 时复用同名单例池
     * @param poolName 线程池名
     * 返回: 线程池实例。
     */
    public static ThreadPoolExecutor newThreadPool(boolean singleton, String poolName) {
        // 默认CPU线程数 * 2
        int corePoolSize = Runtime.getRuntime().availableProcessors() << 1;
        int maximumPoolSize = corePoolSize << 1;
        // 默认核心线程数 * 64
        int capacity = corePoolSize << 6;

        return newThreadPool(singleton, corePoolSize, maximumPoolSize
                , 0L, capacity, poolName, ThreadPoolExecutor.CallerRunsPolicy.class);
    }


    /**
     * 业务作用：按给定配置创建线程池。
     *
     * @param enableVirtualThread 为 true 时使用虚拟线程
     * @param singleton 为 true 时复用同名单例池
     * @param poolName 线程池名
     * 返回: 线程池实例。
     */
    public static ExecutorService newThreadPool(boolean enableVirtualThread, boolean singleton, String poolName) {
        if (enableVirtualThread) {
            return newVirtualThreadPool(singleton, poolName);
        }
        return newThreadPool(singleton, poolName);
    }


    /**
     * 业务作用：创建基于虚拟线程的执行器，适合大量阻塞型任务。虚拟线程不适合 CPU 密集任务，也不应在其中持有长期的 synchronized 锁。
     *
     * @param threadPool 线程池注解配置
     * 返回: 虚拟线程执行器。
     */
    public static ExecutorService newVirtualThreadPool(ThreadPool threadPool) {

        if (!threadPool.async()) {
            return null;
        }

        // 线程池名称
        var poolName = threadPool.value();

        return newVirtualThreadPool(threadPool.singleton(), poolName);
    }


    /**
     * 根据 @ThreadPool 注解，生成线程池
     * @param attributes ThreadPool注解中的属性值
     */
    @SuppressWarnings("ConstantConditions") // 抑制有可能产生空指针警告
    /**
     * 业务作用：创建基于虚拟线程的执行器，适合大量阻塞型任务。虚拟线程不适合 CPU 密集任务，也不应在其中持有长期的 synchronized 锁。
     *
     * @param attributes 线程池参数表
     * 返回: 虚拟线程执行器。
     */
    public static ExecutorService newVirtualThreadPool(Map<String, Object> attributes) {
        if (!MapUtils.getBoolean(attributes, "async", false)) {
            return null;
        }

        // 线程池名称
        var poolName = MapUtils.getString(attributes, "value");

        // 是否是单列线程池
        boolean singleton = MapUtils.getBoolean(attributes, "singleton");

        return newVirtualThreadPool(singleton, poolName);
    }


    /**
     * 业务作用：创建基于虚拟线程的执行器，适合大量阻塞型任务。虚拟线程不适合 CPU 密集任务，也不应在其中持有长期的 synchronized 锁。
     *
     * @param singleton 为 true 时复用同名单例池
     * @param poolName 线程池名
     * 返回: 虚拟线程执行器。
     */
    public static ExecutorService newVirtualThreadPool(boolean singleton, String poolName) {
        return newVirtualThreadPool(singleton, poolName, null);
    }


    /**
     * 业务作用：创建基于虚拟线程的执行器，适合大量阻塞型任务。虚拟线程不适合 CPU 密集任务，也不应在其中持有长期的 synchronized 锁。
     *
     * @param singleton 为 true 时复用同名单例池
     * @param poolName 线程池名
     * @param start 虚拟线程调度器并行度
     * 返回: 虚拟线程执行器。
     */
    public static ExecutorService newVirtualThreadPool(boolean singleton, String poolName, Long start) {

        if (singleton) {
            var executor = singletonThreadPoolMap.get(poolName);
            if (Objects.nonNull(executor)) {
                return executor;
            }
        }

        // 开启虚拟线程-协程
        // 指定线程池的核心数需要设置VM参数，启动参数设置虚拟线程池核心数
        // -Djdk.virtualThreadScheduler.parallelism=16 核心线程数
        // -Djdk.virtualThreadScheduler.maxPoolSize=16 最大线程数
        ThreadFactory threadFactory;
        if (Objects.isNull(start)) {
            threadFactory = Thread.ofVirtual().name(poolName).factory();
            log.info("创建虚拟线程池：" + poolName);
        } else {
            threadFactory = Thread.ofVirtual().name(poolName + "-", start).factory();
            log.info("创建虚拟线程池：{}，下标开始值：{}", poolName, start);
        }
        var executor = Executors.newThreadPerTaskExecutor(threadFactory);

        if (singleton) {
            singletonThreadPoolMap.put(poolName, executor);
        }

        return executor;
    }


    /**
     * 业务作用：按名取得单例线程池，同名返回同一实例，避免重复创建。
     *
     * @param poolName 线程池名
     * 返回: 该名字对应的线程池；不存在时返回 null。
     */
    public static ExecutorService getSingletonPool(String poolName) {
        return singletonThreadPoolMap.get(poolName);
    }

    /**
     * 业务作用：清理已停止的线程池
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    public static void clearShutdown() {
        singletonThreadPoolMap.forEach((k, executor) -> {
            if (executor.isShutdown()) singletonThreadPoolMap.remove(k);
        });
    }

    /**
     * 业务作用：优雅停机，关闭所有线程池
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    public static void gracefulShutdown() {
        singletonThreadPoolMap.forEach((k, executor) -> {
            // 1. 阻止接收新任务
            executor.shutdown();
            try {
                // 2. 等待当前任务完成 (例如等待 20 秒)
                if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                    // 3. 如果超时仍未完成，强制关闭
                    executor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                // 重新设置中断状态
                Thread.currentThread().interrupt();
            }
        });
    }

}
