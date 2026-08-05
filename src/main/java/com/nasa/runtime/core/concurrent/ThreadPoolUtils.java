package com.nasa.runtime.core.concurrent;

import com.nasa.runtime.core.annotation.ThreadPool;
import com.nasa.runtime.core.exception.ReflectException;
import com.nasa.runtime.core.utils.MapUtils;
import com.nasa.runtime.core.utils.ReflectUtils;
import com.nasa.runtime.core.utils.StringUtils;
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
     * 获取一个拒绝策略
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
     * 添加缓存
     * @param scenes 场景
     * @param attributes ThreadPool注解中的属性值
     */
    public static void addAttributes(String scenes, Map<String, Object> attributes) {
        log.info("添加线程池场景：scenes:{},attributes:{}", scenes, StringUtils.toString(attributes));
        cache.put(scenes, attributes);
    }


    /**
     * 添加缓存
     * @param scenes 场景
     * @param threadPool 注解
     */
    public static void addAttributes(String scenes, ThreadPool threadPool) {
        log.info("添加线程池场景：scenes:{},threadPool:{}", scenes, StringUtils.toString(threadPool));
        cache.put(scenes, threadPool);
    }


    /**
     * 获取指定场景下是否支持线程池状态
     * @param scenes 场景
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
     * 生成一个线程池对象
     * @param scenes 场景
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
     * 根据 @ThreadPool 注解，生成线程池
     * @param threadPool 注解
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
     * 创建一个线程池
     * @param singleton 是否单例
     * @param poolName 虚拟线程池名称
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
     * 创建一个ThreadPoolExecutor线程池
     * @param singleton 是否单例
     * @param poolName 虚拟线程池名称
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
     * 创建一个虚拟线程池或者ThreadPoolExecutor线程池
     * @param enableVirtualThread 是否是虚拟线程（协程）
     * @param singleton 是否单例
     * @param poolName 虚拟线程池名称
     */
    public static ExecutorService newThreadPool(boolean enableVirtualThread, boolean singleton, String poolName) {
        if (enableVirtualThread) {
            return newVirtualThreadPool(singleton, poolName);
        }
        return newThreadPool(singleton, poolName);
    }


    /**
     * 根据 @ThreadPool 注解，生成线程池
     * @param threadPool 注解
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
     * 创建一个虚拟线程池
     * @param singleton 是否单例
     * @param poolName 虚拟线程池名称
     */
    public static ExecutorService newVirtualThreadPool(boolean singleton, String poolName) {
        return newVirtualThreadPool(singleton, poolName, null);
    }


    /**
     * 创建一个虚拟线程池
     * @param singleton 是否单例
     * @param poolName 虚拟线程池名称
     * @param start 虚拟线程池名称开始值
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
     * 获取一个已有线程池
     * @param poolName 线程池名称
     */
    public static ExecutorService getSingletonPool(String poolName) {
        return singletonThreadPoolMap.get(poolName);
    }

    /**
     * 清理已停止的线程池
     */
    public static void clearShutdown() {
        singletonThreadPoolMap.forEach((k, executor) -> {
            if (executor.isShutdown()) singletonThreadPoolMap.remove(k);
        });
    }

    /**
     * 优雅停机，关闭所有线程池
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
