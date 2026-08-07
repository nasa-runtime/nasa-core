package io.github.nasaruntime.core.concurrent;

import io.github.nasaruntime.core.base.TimingWheel;
import io.github.nasaruntime.core.function.Action;
import io.github.nasaruntime.core.utils.ContextUtils;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Nasa
 * 锁同步工具
 *
 * 按场景隔离的有界锁同步工具。
 *
 * <h2>有界复用语义</h2>
 * 每个场景最多缓存 {@code maxLocks} 把锁；新 key 可以复用本场景空闲锁，定时回收后的锁还可以通过全局对象池
 * 被其他场景复用。{@code getLock()} 返回前会登记借用保留，真正开始 {@code lock/tryLock} 时再转为使用计数，
 * 回收线程只有在保留计数和使用计数都为零时才能移除锁，避免锁引用尚未开始加锁就被回收改绑。
 */
@SuppressWarnings("unused")
public abstract class SyncLock {

    /**
     * 业务作用：构造实例。字段取默认值。
     *
     * 参数说明: 无。
     * 返回: 构造完成后可直接使用的实例。
     */
    private SyncLock() {}

    /* 场景缓存池 */
    private static final ConcurrentHashMap<String, SyncLocalLock> scenesLockMap = new ConcurrentHashMap<>();
    /* 默认场景 */
    private static final String Default_scenes = Object.class.getName();
    /* 默认场景下的最大锁数量 */
    private static final int Default_maxLocks = Runtime.getRuntime().availableProcessors() << 1;

    /**
     * 业务作用：取得默认场景的锁实例。
     *
     * 参数说明: 无。
     * 返回: 默认场景的锁。
     */
    public static SyncLocalLock dft() {
        return scenes(Default_scenes);
    }

    /**
     * 业务作用：取得指定场景的锁实例。不同场景各自持有独立锁池，互不阻塞。
     *
     * @param scenes 锁场景，不同场景互不影响
     * 返回: 该场景的锁实例。
     */
    public static SyncLocalLock scenes(String scenes) {
        return scenes(scenes, Default_maxLocks);
    }

    /**
     * 业务作用：取得指定场景的锁实例。不同场景各自持有独立锁池，互不阻塞。
     *
     * @param scenes 锁场景，不同场景互不影响
     * @param maxLocks 该场景的锁池上限
     * 返回: 该场景的锁实例。
     */
    public static SyncLocalLock scenes(String scenes, int maxLocks) {
        return scenes(scenes, maxLocks, false);
    }

    /**
     * 根据场景构建锁缓存池
     * 建议设置为主要业务线程池的最大并发数
     *
     * 业务作用：获取指定场景的有界锁缓存池，同一场景首次创建后保持配置不变；缓存中的空闲锁仍可改绑并跨场景复用。
     *
     * @param scenes 场景
     * @param maxLocks 锁对象数量最大值
     * @param fair true 公平锁  false 非公平锁
     * @return 已存在或本次原子创建的场景锁分片组
     */
    public static SyncLocalLock scenes(String scenes, int maxLocks, boolean fair) {
        Objects.requireNonNull(scenes, "scenes");
        if (maxLocks < 1) {
            throw new IllegalArgumentException("maxLocks must be greater than 0");
        }
        return scenesLockMap.computeIfAbsent(scenes, key -> new SyncLocalLock(maxLocks, fair));
    }

    /**
     * 业务作用：按键取得可重入锁。同一键返回同一把锁，从而让同键操作串行。锁对象来自有界池，键数超过池容量时会复用，表现为不同键之间出现伪竞争但不影响正确性。
     *
     * @param lockKey 锁键，同键串行
     * 返回: 该键对应的锁。
     */
    public static Lock getLock(String lockKey) {
        return getLock(Default_scenes, Default_maxLocks, lockKey);
    }

    /**
     * 业务作用：按键取得可重入锁。同一键返回同一把锁，从而让同键操作串行。锁对象来自有界池，键数超过池容量时会复用，表现为不同键之间出现伪竞争但不影响正确性。
     *
     * @param scenes 锁场景，不同场景互不影响
     * @param lockKey 锁键，同键串行
     * 返回: 该键对应的锁。
     */
    public static LocalLock getLock(String scenes, String lockKey) {
        return scenes(scenes).getLock(lockKey);
    }

    /**
     * 业务作用：按键取得可重入锁。同一键返回同一把锁，从而让同键操作串行。锁对象来自有界池，键数超过池容量时会复用，表现为不同键之间出现伪竞争但不影响正确性。
     *
     * @param scenes 锁场景，不同场景互不影响
     * @param maxLocks 该场景的锁池上限
     * @param lockKey 锁键，同键串行
     * 返回: 该键对应的锁。
     */
    public static LocalLock getLock(String scenes, int maxLocks, String lockKey) {
        return scenes(scenes, maxLocks).getLock(lockKey);
    }

    /**
     * 业务作用：取锁后执行业务逻辑，结束时无论正常还是异常都释放锁。同键调用串行执行。
     *
     * @param key 上下文键
     * @param param 传给业务函数的参数
     * @param function 取到锁后执行的业务函数
     * 返回: 业务逻辑的返回值；业务抛出的异常在释放锁后原样上抛。
     */
    public static <P, T> T lock(String key, P param, Function<P, T> function) {
        return lock(Default_scenes, Default_maxLocks, key, param, function);
    }

    /**
     * 业务作用：尝试取锁，取到则执行业务逻辑，取不到立刻走回退逻辑而不阻塞。用于宁可跳过也不愿排队的场景，例如幂等去重与限流。
     *
     * @param key 上下文键
     * @param param 传给业务函数的参数
     * @param function 取到锁后执行的业务函数
     * @param tryFail 抢锁失败时执行的回退逻辑
     * 返回: 取到锁时返回业务逻辑的结果，否则返回回退逻辑的结果。
     */
    public static <P, T> T tryLock(String key, P param, Function<P, T> function, Function<P, T> tryFail) {
        return tryLock(Default_scenes, Default_maxLocks, key, param, function, tryFail);
    }

    /**
     * 业务作用：没有参数，没有返回值的同步方法
     *
     * @param key 加锁的key
     * @param param 函数的参数
     * @param consumer 消费函数
     * 返回: 无返回值。
     */
    public static <P> void lock(String key, P param, Consumer<P> consumer) {
        lock(Default_scenes, Default_maxLocks, key, param, consumer);
    }

    /**
     * 业务作用：尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     *
     * @param key 加锁的key
     * @param param 函数的参数
     * @param consumer 消费函数
     * 返回: 无返回值。
     */
    public static <P> void tryLock(String key, P param, Consumer<P> consumer) {
        tryLock(Default_scenes, Default_maxLocks, key, param, consumer);
    }

    /**
     * 业务作用：尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     *
     * @param key 加锁的key
     * @param param 函数的参数
     * @param consumer 消费函数
     * @param tryFail tryLock失败执行函数
     * 返回: 无返回值。
     */
    public static <P> void tryLock(String key, P param, Consumer<P> consumer, Consumer<P> tryFail) {
        tryLock(Default_scenes, Default_maxLocks, key, param, consumer, tryFail);
    }

    /**
     * 业务作用：取锁后执行业务逻辑，结束时无论正常还是异常都释放锁。同键调用串行执行。
     *
     * @param key 上下文键
     * @param supplier 取到锁后执行的业务逻辑
     * 返回: 业务逻辑的返回值；业务抛出的异常在释放锁后原样上抛。
     */
    public static <T> T lock(String key, Supplier<T> supplier) {
        return lock(Default_scenes, Default_maxLocks, key, supplier);
    }

    /**
     * 业务作用：尝试取锁，取到则执行业务逻辑，取不到立刻走回退逻辑而不阻塞。用于宁可跳过也不愿排队的场景，例如幂等去重与限流。
     *
     * @param key 上下文键
     * @param supplier 取到锁后执行的业务逻辑
     * @param tryFail 抢锁失败时执行的回退逻辑
     * 返回: 取到锁时返回业务逻辑的结果，否则返回回退逻辑的结果。
     */
    public static <T> T tryLock(String key, Supplier<T> supplier, Supplier<T> tryFail) {
        return tryLock(Default_scenes, Default_maxLocks, key, supplier, tryFail);
    }

    /**
     * 业务作用：没有参数，没有返回值的同步方法
     *
     * @param key 加锁的key
     * @param action 函数
     * 返回: 无返回值。
     */
    public static void lock(String key, Action action) {
        lock(Default_scenes, Default_maxLocks, key, action);
    }

    /**
     * 业务作用：尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     *
     * @param key 加锁的key
     * @param action 函数
     * 返回: 无返回值。
     */
    public static void tryLock(String key, Action action) {
        tryLock(Default_scenes, Default_maxLocks, key, action);
    }

    /**
     * 业务作用：尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     *
     * @param key 加锁的key
     * @param action 函数
     * @param tryFail tryLock失败执行函数
     * 返回: 无返回值。
     */
    public static void tryLock(String key, Action action, Action tryFail) {
        tryLock(Default_scenes, Default_maxLocks, key, action, tryFail);
    }

    /**
     * 业务作用：取锁后执行业务逻辑，结束时无论正常还是异常都释放锁。同键调用串行执行。
     *
     * @param scenes 锁场景，不同场景互不影响
     * @param key 上下文键
     * @param param 传给业务函数的参数
     * @param function 取到锁后执行的业务函数
     * 返回: 业务逻辑的返回值；业务抛出的异常在释放锁后原样上抛。
     */
    public static <P, T> T lock(String scenes, String key, P param, Function<P, T> function) {
        return scenes(scenes).lock(key, param, function);
    }

    /**
     * 业务作用：取锁后执行业务逻辑，结束时无论正常还是异常都释放锁。同键调用串行执行。
     *
     * @param scenes 锁场景，不同场景互不影响
     * @param maxLocks 该场景的锁池上限
     * @param key 上下文键
     * @param param 传给业务函数的参数
     * @param function 取到锁后执行的业务函数
     * 返回: 业务逻辑的返回值；业务抛出的异常在释放锁后原样上抛。
     */
    public static <P, T> T lock(String scenes, int maxLocks, String key, P param, Function<P, T> function) {
        return scenes(scenes, maxLocks).lock(key, param, function);
    }

    /**
     * 业务作用：尝试取锁，取到则执行业务逻辑，取不到立刻走回退逻辑而不阻塞。用于宁可跳过也不愿排队的场景，例如幂等去重与限流。
     *
     * @param scenes 锁场景，不同场景互不影响
     * @param key 上下文键
     * @param param 传给业务函数的参数
     * @param function 取到锁后执行的业务函数
     * @param tryFail 抢锁失败时执行的回退逻辑
     * 返回: 取到锁时返回业务逻辑的结果，否则返回回退逻辑的结果。
     */
    public static <P, T> T tryLock(String scenes, String key, P param, Function<P, T> function, Function<P, T> tryFail) {
        return scenes(scenes).tryLock(key, param, function, tryFail);
    }

    /**
     * 业务作用：尝试取锁，取到则执行业务逻辑，取不到立刻走回退逻辑而不阻塞。用于宁可跳过也不愿排队的场景，例如幂等去重与限流。
     *
     * @param scenes 锁场景，不同场景互不影响
     * @param maxLocks 该场景的锁池上限
     * @param key 上下文键
     * @param param 传给业务函数的参数
     * @param function 取到锁后执行的业务函数
     * @param tryFail 抢锁失败时执行的回退逻辑
     * 返回: 取到锁时返回业务逻辑的结果，否则返回回退逻辑的结果。
     */
    public static <P, T> T tryLock(String scenes, int maxLocks, String key, P param, Function<P, T> function, Function<P, T> tryFail) {
        return scenes(scenes, maxLocks).tryLock(key, param, function, tryFail);
    }

    /**
     * 业务作用：没有参数，没有返回值的同步方法
     *
     * @param scenes 场景
     * @param key 加锁的key
     * @param param 函数的参数
     * @param consumer 消费函数
     * 返回: 无返回值。
     */
    public static <P> void lock(String scenes, String key, P param, Consumer<P> consumer) {
        scenes(scenes).lock(key, param, consumer);
    }

    /**
     * 业务作用：没有参数，没有返回值的同步方法
     *
     * @param scenes 场景
     * @param maxLocks 锁对象数量最大值
     * @param key 加锁的key
     * @param param 函数的参数
     * @param consumer 消费函数
     * 返回: 无返回值。
     */
    public static <P> void lock(String scenes, int maxLocks, String key, P param, Consumer<P> consumer) {
        scenes(scenes, maxLocks).lock(key, param, consumer);
    }

    /**
     * 业务作用：尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     *
     * @param scenes 场景
     * @param key 加锁的key
     * @param param 函数的参数
     * @param consumer 消费函数
     * 返回: 无返回值。
     */
    public static <P> void tryLock(String scenes, String key, P param, Consumer<P> consumer) {
        scenes(scenes).tryLock(key, param, consumer);
    }

    /**
     * 业务作用：尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     *
     * @param scenes 场景
     * @param key 加锁的key
     * @param param 函数的参数
     * @param consumer 消费函数
     * @param tryFail tryLock失败执行函数
     * 返回: 无返回值。
     */
    public static <P> void tryLock(String scenes, String key, P param, Consumer<P> consumer, Consumer<P> tryFail) {
        scenes(scenes).tryLock(key, param, consumer, tryFail);
    }

    /**
     * 业务作用：尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     *
     * @param scenes 场景
     * @param maxLocks 锁对象数量最大值
     * @param key 加锁的key
     * @param param 函数的参数
     * @param consumer 消费函数
     * 返回: 无返回值。
     */
    public static <P> void tryLock(String scenes, int maxLocks, String key, P param, Consumer<P> consumer) {
        scenes(scenes, maxLocks).tryLock(key, param, consumer);
    }

    /**
     * 业务作用：尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     *
     * @param scenes 场景
     * @param maxLocks 锁对象数量最大值
     * @param key 加锁的key
     * @param param 函数的参数
     * @param consumer 消费函数
     * @param tryFail tryLock失败执行函数
     * 返回: 无返回值。
     */
    public static <P> void tryLock(String scenes, int maxLocks, String key, P param, Consumer<P> consumer, Consumer<P> tryFail) {
        scenes(scenes, maxLocks).tryLock(key, param, consumer, tryFail);
    }

    /**
     * 业务作用：取锁后执行业务逻辑，结束时无论正常还是异常都释放锁。同键调用串行执行。
     *
     * @param scenes 锁场景，不同场景互不影响
     * @param key 上下文键
     * @param supplier 取到锁后执行的业务逻辑
     * 返回: 业务逻辑的返回值；业务抛出的异常在释放锁后原样上抛。
     */
    public static <T> T lock(String scenes, String key, Supplier<T> supplier) {
        return scenes(scenes).lock(key, supplier);
    }

    /**
     * 业务作用：取锁后执行业务逻辑，结束时无论正常还是异常都释放锁。同键调用串行执行。
     *
     * @param scenes 锁场景，不同场景互不影响
     * @param maxLocks 该场景的锁池上限
     * @param key 上下文键
     * @param supplier 取到锁后执行的业务逻辑
     * 返回: 业务逻辑的返回值；业务抛出的异常在释放锁后原样上抛。
     */
    public static <T> T lock(String scenes, int maxLocks, String key, Supplier<T> supplier) {
        return scenes(scenes, maxLocks).lock(key, supplier);
    }

    /**
     * 业务作用：尝试取锁，取到则执行业务逻辑，取不到立刻走回退逻辑而不阻塞。用于宁可跳过也不愿排队的场景，例如幂等去重与限流。
     *
     * @param scenes 锁场景，不同场景互不影响
     * @param key 上下文键
     * @param supplier 取到锁后执行的业务逻辑
     * @param tryFail 抢锁失败时执行的回退逻辑
     * 返回: 取到锁时返回业务逻辑的结果，否则返回回退逻辑的结果。
     */
    public static <T> T tryLock(String scenes, String key, Supplier<T> supplier, Supplier<T> tryFail) {
        return scenes(scenes).tryLock(key, supplier, tryFail);
    }

    /**
     * 业务作用：尝试取锁，取到则执行业务逻辑，取不到立刻走回退逻辑而不阻塞。用于宁可跳过也不愿排队的场景，例如幂等去重与限流。
     *
     * @param scenes 锁场景，不同场景互不影响
     * @param maxLocks 该场景的锁池上限
     * @param key 上下文键
     * @param supplier 取到锁后执行的业务逻辑
     * @param tryFail 抢锁失败时执行的回退逻辑
     * 返回: 取到锁时返回业务逻辑的结果，否则返回回退逻辑的结果。
     */
    public static <T> T tryLock(String scenes, int maxLocks, String key, Supplier<T> supplier, Supplier<T> tryFail) {
        return scenes(scenes, maxLocks).tryLock(key, supplier, tryFail);
    }

    /**
     * 业务作用：没有参数，没有返回值的同步方法
     *
     * @param scenes 场景
     * @param key 加锁的key
     * @param action 函数
     * 返回: 无返回值。
     */
    public static void lock(String scenes, String key, Action action) {
        scenes(scenes).lock(key, action);
    }

    /**
     * 业务作用：没有参数，没有返回值的同步方法
     *
     * @param scenes 场景
     * @param maxLocks 锁对象数量最大值
     * @param key 加锁的key
     * @param action 函数
     * 返回: 无返回值。
     */
    public static void lock(String scenes, int maxLocks, String key, Action action) {
        scenes(scenes, maxLocks).lock(key, action);
    }

    /**
     * 业务作用：尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     *
     * @param scenes 场景
     * @param key 加锁的key
     * @param action 函数
     * 返回: 无返回值。
     */
    public static void tryLock(String scenes, String key, Action action) {
        scenes(scenes).tryLock(key, action);
    }

    /**
     * 业务作用：尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     *
     * @param scenes 场景
     * @param key 加锁的key
     * @param action 函数
     * @param tryFail tryLock失败执行函数
     * 返回: 无返回值。
     */
    public static void tryLock(String scenes, String key, Action action, Action tryFail) {
        scenes(scenes).tryLock(key, action, tryFail);
    }

    /**
     * 业务作用：尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     *
     * @param scenes 场景
     * @param maxLocks 锁对象数量最大值
     * @param key 加锁的key
     * @param action 函数
     * 返回: 无返回值。
     */
    public static void tryLock(String scenes, int maxLocks, String key, Action action) {
        scenes(scenes, maxLocks).tryLock(key, action);
    }

    /**
     * 业务作用：尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     *
     * @param scenes 场景
     * @param maxLocks 锁对象数量最大值
     * @param key 加锁的key
     * @param action 函数
     * @param tryFail tryLock失败执行函数
     * 返回: 无返回值。
     */
    public static void tryLock(String scenes, int maxLocks, String key, Action action, Action tryFail) {
        scenes(scenes, maxLocks).tryLock(key, action, tryFail);
    }

    public static class SyncLocalLock {

        /**
         * 缓存池中锁对象数量创建的最大值
         * 默认值为主要业务线程池的最大并发数
         * 建议设置为主要业务线程池的最大并发数
         *
         * 业务作用：限制单个场景缓存的锁对象数量，同时限制该场景能够映射的锁并行度。
         */
        @Getter
        private final int maxLocks;
        /* 锁的缓存池 */
        /* 保存当前场景的 key 到锁映射，所有访问均受 lifecycleLock 保护 */
        private final Map<String, LocalLock> lockMap;
        private final boolean fair;
        /* 清理时的全局锁 */
        /* 读锁保护并发借用，写锁保护创建、改绑和对象池回收 */
        private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();

        private static volatile boolean recycleIsStarted;
        private static final ReentrantLock recycleLock = new ReentrantLock();

        /**
         * 时间轮定时扫描空闲锁，对象池回收
         *
         * 业务作用：启动全局空闲锁回收任务，把无借用者、无等待者且未被持有的锁归还对象池，供任意场景复用。
         *
         * 参数说明: 无。
         * 返回: 无返回值；重复调用只保留一个回收任务。
         */
        private static void startRecycle() {
            if (recycleIsStarted) return;
            recycleLock.lock();
            try {
                if (recycleIsStarted) return;
                recycleIsStarted = true;
                // 启动时间轮任务
                // 统一驱动所有场景的空闲锁回收。
                if (!TimingWheel.isStarted()) {
                    TimingWheel.startTimingWheel();
                }
                long period = ContextUtils.getPropertyLong("nasa.local-lock.idle-lock-recycle-time", 10_000L);
                ArrayList<String> scenes = new ArrayList<>();

                TimingWheel.exec(period, period, SyncLocalLock.class.getSimpleName(), () -> {
                    scenes.clear();
                    // 将scenes缓存所有空闲锁全部回收
                    // 将 scenes 缓存中的所有场景加入本轮扫描。
                    scenes.addAll(scenesLockMap.keySet());
                    for (String scene : scenes) {
                        SyncLocalLock syncLocalLock = scenesLockMap.get(scene);
                        if (syncLocalLock == null) continue;
                        Lock writeLock = syncLocalLock.lifecycleLock.writeLock();
                        if (!writeLock.tryLock()) continue;
                        try {
                            Iterator<Map.Entry<String, LocalLock>> iterator = syncLocalLock.lockMap.entrySet().iterator();
                            while (iterator.hasNext()) {
                                LocalLock lock = iterator.next().getValue();
                                if (lock.isUsed()) continue;
                                // 从场景缓存池移除
                                // 写锁排除了新的借用登记，确认空闲后才允许删除映射。
                                iterator.remove();
                                // 在remove瞬间又被其他线程占用，再次放入锁缓存池
                                // 上一行是原实现需要复验的竞态；生命周期写锁与借用保留已将该窗口关闭，
                                // 因此移除后可以直接执行对象池回收。
                                // 对象池回收
                                // 回收后，这把锁可服务其他场景和其他业务代码。
                                lock.recycle();
                            }
                        } finally {
                            writeLock.unlock();
                        }
                    }
                });
            } finally {
                recycleLock.unlock();
            }
        }

        /**
         * 构建场景锁缓存池。
         *
         * 业务作用：创建有界场景锁池，空闲锁可以在场景内改绑，并可由定时任务归还全局对象池。
         *
         * @param maxLocks 场景内最大锁数量，必须大于 0
         * @param fair 是否使用公平锁
         * 返回: 构造完成后即可按键取锁；场景与锁池上限在实例生命周期内不变。
         */
        public SyncLocalLock(int maxLocks, boolean fair) {
            if (maxLocks < 1) {
                throw new IllegalArgumentException("maxLocks must be greater than 0");
            }
            this.maxLocks = maxLocks;
            this.fair = fair;
            this.lockMap = new HashMap<>(maxLocks);
            startRecycle();
        }

        /**
         * 获取锁
         *
         * 业务作用：为业务 key 借用一把锁，并在返回引用前登记保留，阻止加锁前的回收和改绑窗口。
         * 每次调用都必须紧接一次 {@code lock/tryLock}，或者在放弃加锁时调用 {@code close()} 撤销借用保留。
         *
         * @param lockKey 加锁的 key
         * @return 已登记一次借用保留的锁；下一次 {@code lock/tryLock} 调用会消费该保留
         */
        public LocalLock getLock(String lockKey) {
            Objects.requireNonNull(lockKey, "lockKey");
            // 一直循环到获取到锁
            // 缓存达到上限且没有空闲锁时，等待其他线程 unlock 后重试。
            while (true) {
                Lock readLock = this.lifecycleLock.readLock();
                readLock.lock();
                try {
                    LocalLock existing = this.lockMap.get(lockKey);
                    if (existing != null) {
                        // 存在的锁，直接返回
                        // 返回前登记借用保留，避免 getLock() 与真正加锁之间被回收。
                        existing.reserve();
                        return existing;
                    }
                } finally {
                    readLock.unlock();
                }

                // key 不存在时进入锁缓存池的生命周期写区，统一处理创建、改绑和对象池回收。
                Lock writeLock = this.lifecycleLock.writeLock();
                writeLock.lock();
                try {
                    LocalLock existing = this.lockMap.get(lockKey);
                    if (existing != null) {
                        // 存在的锁，直接返回
                        // 等待写锁期间可能已有线程创建该 key，再次登记借用保留后直接返回。
                        existing.reserve();
                        return existing;
                    }

                    // 不存在，且锁缓存池未满
                    if (this.lockMap.size() < this.maxLocks) {
                        /* new 一个新的锁 */
                        // 锁缓存池没有满，则创建，空闲锁等待时间轮任务回收
                        // 优先从全局对象池取得，池中没有空闲对象时才真正创建。
                        LocalLock created = LocalLock.fromPool(this.fair, lockKey);
                        created.reserve();
                        this.lockMap.put(lockKey, created);
                        return created;
                    }

                    // 锁缓存池已满，从锁缓冲池中获取一个未被占用的锁，其他空闲锁被对象池回收
                    LocalLock localLock = null;
                    Iterator<Map.Entry<String, LocalLock>> iterator = this.lockMap.entrySet().iterator();
                    while (iterator.hasNext()) {
                        LocalLock reusable = iterator.next().getValue();
                        if (reusable.isUsed()) continue;

                        // 当前scenes存在空闲锁
                        // 从scenes缓存池移除
                        iterator.remove();
                        // remove瞬间又被其他线程占用，重新放回scenes缓存池
                        // 上一行是原实现需要复验的竞态；写锁和借用保留现已保证移除前后身份稳定。
                        if (localLock == null) {
                            // remove后任然未被使用，lockKey使用
                            // 保留给当前 lockKey 使用。
                            localLock = reusable;
                        } else {
                            // 对象池回收
                            // 回收多余的空闲锁，使其能够在其他场景和业务代码中复用。
                            reusable.recycle();
                        }
                    }

                    if (localLock != null) {
                        localLock.setLockKey(lockKey);
                        localLock.reserve();
                        this.lockMap.put(lockKey, localLock);
                        // 将空闲锁的原 lockKey 改为当前 lockKey，并更新锁缓存池。
                        return localLock;
                    }
                } finally {
                    writeLock.unlock();
                }

                // 没有空闲锁，睡眠5ms，等待其他线程unlock
                // 所有场景中都没有空闲锁，等5ms再次尝试
                LockSupport.parkNanos(5_000_000L);
            }
        }

        /**
         * 业务作用：取锁后执行业务逻辑，结束时无论正常还是异常都释放锁。同键调用串行执行。
         *
         * @param key 上下文键
         * @param supplier 取到锁后执行的业务逻辑
         * 返回: 业务逻辑的返回值；业务抛出的异常在释放锁后原样上抛。
         */
        public <T> T lock(String key, Supplier<T> supplier) {
            try (LocalLock lock = getLock(key)) {
                lock.lock();
                return supplier.get();
            }
        }

        /**
         * 业务作用：尝试取锁，取到则执行业务逻辑，取不到立刻走回退逻辑而不阻塞。用于宁可跳过也不愿排队的场景，例如幂等去重与限流。
         *
         * @param key 上下文键
         * @param supplier 取到锁后执行的业务逻辑
         * @param tryFail 抢锁失败时执行的回退逻辑
         * 返回: 取到锁时返回业务逻辑的结果，否则返回回退逻辑的结果。
         */
        public <T> T tryLock(String key, Supplier<T> supplier, Supplier<T> tryFail) {
            LocalLock lock = getLock(key);
            if (!lock.tryLock()) {
                return Objects.isNull(tryFail) ? null : tryFail.get();
            }
            try (lock) {
                return supplier.get();
            }
        }

        /**
         * 业务作用：没有参数，没有返回值的同步方法
         *
         * @param key 加锁的key
         * @param action 函数
         * 返回: 无返回值。
         */
        public void lock(String key, Action action) {
            try (LocalLock lock = getLock(key)) {
                lock.lock();
                action.action();
            }
        }

        /**
         * 业务作用：尝试去获取锁，没有获取成功则返回
         * 没有参数，没有返回值的同步方法
         *
         * @param key 加锁的key
         * @param action 函数
         * 返回: 无返回值。
         */
        public void tryLock(String key, Action action) {
            tryLock(key, action, (Action) null);
        }

        /**
         * 业务作用：尝试去获取锁，没有获取成功则返回
         * 没有参数，没有返回值的同步方法
         *
         * @param key 加锁的key
         * @param action 函数
         * @param tryFail tryLock失败执行函数
         * 返回: 无返回值。
         */
        public void tryLock(String key, Action action, Action tryFail) {
            LocalLock lock = getLock(key);
            if (!lock.tryLock()) {
                if (Objects.nonNull(tryFail)) tryFail.action();
                return ;
            }
            try (lock) {
                action.action();
            }
        }

        /**
         * 业务作用：取锁后执行业务逻辑，结束时无论正常还是异常都释放锁。同键调用串行执行。
         *
         * @param key 上下文键
         * @param param 传给业务函数的参数
         * @param function 取到锁后执行的业务函数
         * 返回: 业务逻辑的返回值；业务抛出的异常在释放锁后原样上抛。
         */
        public <P, T> T lock(String key, P param, Function<P, T> function) {
            try (LocalLock lock = getLock(key)) {
                lock.lock();
                return function.apply(param);
            }
        }

        /**
         * 业务作用：尝试取锁，取到则执行业务逻辑，取不到立刻走回退逻辑而不阻塞。用于宁可跳过也不愿排队的场景，例如幂等去重与限流。
         *
         * @param key 上下文键
         * @param param 传给业务函数的参数
         * @param function 取到锁后执行的业务函数
         * @param tryFail 抢锁失败时执行的回退逻辑
         * 返回: 取到锁时返回业务逻辑的结果，否则返回回退逻辑的结果。
         */
        public <P, T> T tryLock(String key, P param, Function<P, T> function, Function<P, T> tryFail) {
            LocalLock lock = getLock(key);
            if (!lock.tryLock()) {
                return Objects.isNull(tryFail) ? null : tryFail.apply(param);
            }
            try (lock) {
                return function.apply(param);
            }
        }

        /**
         * 业务作用：没有参数，没有返回值的同步方法
         *
         * @param key 加锁的key
         * @param param 参数
         * @param consumer 消费函数
         * 返回: 无返回值。
         */
        public <P> void lock(String key, P param, Consumer<P> consumer) {
            try (LocalLock lock = getLock(key)) {
                lock.lock();
                consumer.accept(param);
            }
        }

        /**
         * 业务作用：尝试去获取锁，没有获取成功则返回
         * 没有参数，没有返回值的同步方法
         *
         * @param key 加锁的key
         * @param param 参数
         * @param consumer 消费函数
         * 返回: 无返回值。
         */
        public <P> void tryLock(String key, P param, Consumer<P> consumer) {
            this.tryLock(key, param, consumer, null);
        }

        /**
         * 业务作用：尝试去获取锁，没有获取成功则返回
         * 没有参数，没有返回值的同步方法
         *
         * @param key 加锁的key
         * @param param 参数
         * @param consumer 消费函数
         * @param tryFail tryLock失败执行函数
         * 返回: 无返回值。
         */
        public <P> void tryLock(String key, P param, Consumer<P> consumer, Consumer<P> tryFail) {
            LocalLock lock = getLock(key);
            if (!lock.tryLock()) {
                if (Objects.nonNull(tryFail)) tryFail.accept(param);
                return ;
            }
            try (lock) {
                consumer.accept(param);
            }
        }
    }

}
