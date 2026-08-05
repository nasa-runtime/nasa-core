package com.nasa.runtime.core.concurrent;

import com.nasa.runtime.core.base.TimingWheel;
import com.nasa.runtime.core.function.Action;
import com.nasa.runtime.core.utils.ColUtils;
import com.nasa.runtime.core.utils.ContextUtils;
import com.nasa.runtime.core.utils.ReflectUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Nasa
 * 锁同步工具
 */
@SuppressWarnings("unused")
@Slf4j
public abstract class SyncLock {

    private SyncLock() {}

    /* 场景缓存池 */
    private static final Map<String, SyncLocalLock> scenesLockMap = new HashMap<>();
    /* 默认场景 */
    private static final String Default_scenes = Object.class.getName();
    /* 默认场景下的最大锁数量 */
    private static final int Default_maxLocks = Runtime.getRuntime().availableProcessors() << 1;

    /**
     * 非公平锁
     * 构建默认场景锁缓存池
     */
    public static SyncLocalLock dft() {
        return scenes(Default_scenes);
    }

    /**
     * 非公平锁
     * 根据场景构建锁缓存池
     * @param scenes 场景
     */
    public static SyncLocalLock scenes(String scenes) {
        return scenes(scenes, Default_maxLocks);
    }

    /**
     * 非公平锁
     * 根据场景构建锁缓存池
     * 建议设置为主要业务线程池的最大并发数
     * @param scenes 场景
     * @param maxLocks 锁对象数量最大值
     */
    public static SyncLocalLock scenes(String scenes, int maxLocks) {
        return scenes(scenes, maxLocks, false);
    }

    /**
     * 根据场景构建锁缓存池
     * 建议设置为主要业务线程池的最大并发数
     * @param scenes 场景
     * @param maxLocks 锁对象数量最大值
     * @param fair true 公平锁  false 非公平锁
     */
    private static final ReentrantLock scenesLock = new ReentrantLock();

    public static SyncLocalLock scenes(String scenes, int maxLocks, boolean fair) {
        SyncLocalLock lock = scenesLockMap.get(scenes);
        if (Objects.nonNull(lock)) return lock;
        scenesLock.lock();
        try {
            if (Objects.nonNull(lock = scenesLockMap.get(scenes))) return lock;
            SyncLocalLock sll = new SyncLocalLock(maxLocks, fair);
            scenesLockMap.put(scenes, sll);
            return sll;
        } finally {
            scenesLock.unlock();
        }
    }

    /**
     * 获取锁
     * @param lockKey 加锁的key
     */
    public static Lock getLock(String lockKey) {
        return getLock(Default_scenes, Default_maxLocks, lockKey);
    }

    /**
     * 获取锁
     * @param scenes 场景
     * @param lockKey 加锁的key
     */
    public static LocalLock getLock(String scenes, String lockKey) {
        return scenes(scenes).getLock(lockKey);
    }

    /**
     * 获取锁
     * @param scenes 场景
     * @param maxLocks 锁对象数量最大值
     * @param lockKey 加锁的key
     */
    public static LocalLock getLock(String scenes, int maxLocks, String lockKey) {
        return scenes(scenes, maxLocks).getLock(lockKey);
    }

    /**
     * 没有参数，有返回值的同步方法
     * @param key 加锁的key
     * @param param 函数的参数
     * @param function 函数
     * @param <T> 需要返回的值的泛型
     * @param <P> 函数参数的泛型
     */
    public static <P, T> T lock(String key, P param, Function<P, T> function) {
        return lock(Default_scenes, Default_maxLocks, key, param, function);
    }

    /**
     * 没有参数，有返回值的同步方法
     * @param key 加锁的key
     * @param param 函数的参数
     * @param function 函数
     * @param tryFail tryLock失败执行函数
     * @param <T> 需要返回的值的泛型
     * @param <P> 函数参数的泛型
     */
    public static <P, T> T tryLock(String key, P param, Function<P, T> function, Function<P, T> tryFail) {
        return tryLock(Default_scenes, Default_maxLocks, key, param, function, tryFail);
    }

    /**
     * 没有参数，没有返回值的同步方法
     * @param key 加锁的key
     * @param param 函数的参数
     * @param consumer 消费函数
     * @param <P> 函数参数的泛型
     */
    public static <P> void lock(String key, P param, Consumer<P> consumer) {
        lock(Default_scenes, Default_maxLocks, key, param, consumer);
    }

    /**
     * 尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     * @param key 加锁的key
     * @param param 函数的参数
     * @param consumer 消费函数
     * @param <P> 函数参数的泛型
     */
    public static <P> void tryLock(String key, P param, Consumer<P> consumer) {
        tryLock(Default_scenes, Default_maxLocks, key, param, consumer);
    }

    /**
     * 尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     * @param key 加锁的key
     * @param param 函数的参数
     * @param consumer 消费函数
     * @param tryFail tryLock失败执行函数
     * @param <P> 函数参数的泛型
     */
    public static <P> void tryLock(String key, P param, Consumer<P> consumer, Consumer<P> tryFail) {
        tryLock(Default_scenes, Default_maxLocks, key, param, consumer, tryFail);
    }

    /**
     * 没有参数，有返回值的同步方法
     * @param key 加锁的key
     * @param supplier 函数
     * @param <T> 需要返回的值的泛型
     */
    public static <T> T lock(String key, Supplier<T> supplier) {
        return lock(Default_scenes, Default_maxLocks, key, supplier);
    }

    /**
     * 没有参数，有返回值的同步方法
     * @param key 加锁的key
     * @param supplier 函数
     * @param tryFail tryLock失败执行函数
     * @param <T> 需要返回的值的泛型
     */
    public static <T> T tryLock(String key, Supplier<T> supplier, Supplier<T> tryFail) {
        return tryLock(Default_scenes, Default_maxLocks, key, supplier, tryFail);
    }

    /**
     * 没有参数，没有返回值的同步方法
     * @param key 加锁的key
     * @param action 函数
     */
    public static void lock(String key, Action action) {
        lock(Default_scenes, Default_maxLocks, key, action);
    }

    /**
     * 尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     * @param key 加锁的key
     * @param action 函数
     */
    public static void tryLock(String key, Action action) {
        tryLock(Default_scenes, Default_maxLocks, key, action);
    }

    /**
     * 尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     * @param key 加锁的key
     * @param action 函数
     * @param tryFail tryLock失败执行函数
     */
    public static void tryLock(String key, Action action, Action tryFail) {
        tryLock(Default_scenes, Default_maxLocks, key, action, tryFail);
    }

    /**
     * 没有参数，有返回值的同步方法
     * @param scenes 场景
     * @param key 加锁的key
     * @param param 函数的参数
     * @param function 函数
     * @param <T> 需要返回的值的泛型
     * @param <P> 函数参数的泛型
     */
    public static <P, T> T lock(String scenes, String key, P param, Function<P, T> function) {
        return scenes(scenes).lock(key, param, function);
    }

    /**
     * 没有参数，有返回值的同步方法
     * @param scenes 场景
     * @param maxLocks 锁对象数量最大值
     * @param key 加锁的key
     * @param param 函数的参数
     * @param function 函数
     * @param <T> 需要返回的值的泛型
     * @param <P> 函数参数的泛型
     */
    public static <P, T> T lock(String scenes, int maxLocks, String key, P param, Function<P, T> function) {
        return scenes(scenes, maxLocks).lock(key, param, function);
    }

    /**
     * 没有参数，有返回值的同步方法
     * @param scenes 场景
     * @param key 加锁的key
     * @param param 函数的参数
     * @param function 函数
     * @param tryFail tryLock失败执行函数
     * @param <T> 需要返回的值的泛型
     * @param <P> 函数参数的泛型
     */
    public static <P, T> T tryLock(String scenes, String key, P param, Function<P, T> function, Function<P, T> tryFail) {
        return scenes(scenes).tryLock(key, param, function, tryFail);
    }

    /**
     * 没有参数，有返回值的同步方法
     * @param scenes 场景
     * @param maxLocks 锁对象数量最大值
     * @param key 加锁的key
     * @param param 函数的参数
     * @param function 函数
     * @param tryFail tryLock失败执行函数
     * @param <T> 需要返回的值的泛型
     * @param <P> 函数参数的泛型
     */
    public static <P, T> T tryLock(String scenes, int maxLocks, String key, P param, Function<P, T> function, Function<P, T> tryFail) {
        return scenes(scenes, maxLocks).tryLock(key, param, function, tryFail);
    }

    /**
     * 没有参数，没有返回值的同步方法
     * @param scenes 场景
     * @param key 加锁的key
     * @param param 函数的参数
     * @param consumer 消费函数
     * @param <P> 函数参数的泛型
     */
    public static <P> void lock(String scenes, String key, P param, Consumer<P> consumer) {
        scenes(scenes).lock(key, param, consumer);
    }

    /**
     * 没有参数，没有返回值的同步方法
     * @param scenes 场景
     * @param maxLocks 锁对象数量最大值
     * @param key 加锁的key
     * @param param 函数的参数
     * @param consumer 消费函数
     * @param <P> 函数参数的泛型
     */
    public static <P> void lock(String scenes, int maxLocks, String key, P param, Consumer<P> consumer) {
        scenes(scenes, maxLocks).lock(key, param, consumer);
    }

    /**
     * 尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     * @param scenes 场景
     * @param key 加锁的key
     * @param param 函数的参数
     * @param consumer 消费函数
     * @param <P> 函数参数的泛型
     */
    public static <P> void tryLock(String scenes, String key, P param, Consumer<P> consumer) {
        scenes(scenes).tryLock(key, param, consumer);
    }

    /**
     * 尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     * @param scenes 场景
     * @param key 加锁的key
     * @param param 函数的参数
     * @param consumer 消费函数
     * @param tryFail tryLock失败执行函数
     * @param <P> 函数参数的泛型
     */
    public static <P> void tryLock(String scenes, String key, P param, Consumer<P> consumer, Consumer<P> tryFail) {
        scenes(scenes).tryLock(key, param, consumer, tryFail);
    }

    /**
     * 尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     * @param scenes 场景
     * @param maxLocks 锁对象数量最大值
     * @param key 加锁的key
     * @param param 函数的参数
     * @param consumer 消费函数
     * @param <P> 函数参数的泛型
     */
    public static <P> void tryLock(String scenes, int maxLocks, String key, P param, Consumer<P> consumer) {
        scenes(scenes, maxLocks).tryLock(key, param, consumer);
    }

    /**
     * 尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     * @param scenes 场景
     * @param maxLocks 锁对象数量最大值
     * @param key 加锁的key
     * @param param 函数的参数
     * @param consumer 消费函数
     * @param tryFail tryLock失败执行函数
     * @param <P> 函数参数的泛型
     */
    public static <P> void tryLock(String scenes, int maxLocks, String key, P param, Consumer<P> consumer, Consumer<P> tryFail) {
        scenes(scenes, maxLocks).tryLock(key, param, consumer, tryFail);
    }

    /**
     * 没有参数，有返回值的同步方法
     * @param scenes 场景
     * @param key 加锁的key
     * @param supplier 函数
     * @param <T> 需要返回的值的泛型
     */
    public static <T> T lock(String scenes, String key, Supplier<T> supplier) {
        return scenes(scenes).lock(key, supplier);
    }

    /**
     * 没有参数，有返回值的同步方法
     * @param scenes 场景
     * @param maxLocks 锁对象数量最大值
     * @param key 加锁的key
     * @param supplier 函数
     * @param <T> 需要返回的值的泛型
     */
    public static <T> T lock(String scenes, int maxLocks, String key, Supplier<T> supplier) {
        return scenes(scenes, maxLocks).lock(key, supplier);
    }

    /**
     * 没有参数，有返回值的同步方法
     * @param scenes 场景
     * @param key 加锁的key
     * @param supplier 函数
     * @param tryFail tryLock失败执行函数
     * @param <T> 需要返回的值的泛型
     */
    public static <T> T tryLock(String scenes, String key, Supplier<T> supplier, Supplier<T> tryFail) {
        return scenes(scenes).tryLock(key, supplier, tryFail);
    }

    /**
     * 没有参数，有返回值的同步方法
     * @param scenes 场景
     * @param maxLocks 锁对象数量最大值
     * @param key 加锁的key
     * @param supplier 函数
     * @param tryFail tryLock失败执行函数
     * @param <T> 需要返回的值的泛型
     */
    public static <T> T tryLock(String scenes, int maxLocks, String key, Supplier<T> supplier, Supplier<T> tryFail) {
        return scenes(scenes, maxLocks).tryLock(key, supplier, tryFail);
    }

    /**
     * 没有参数，没有返回值的同步方法
     * @param scenes 场景
     * @param key 加锁的key
     * @param action 函数
     */
    public static void lock(String scenes, String key, Action action) {
        scenes(scenes).lock(key, action);
    }

    /**
     * 没有参数，没有返回值的同步方法
     * @param scenes 场景
     * @param maxLocks 锁对象数量最大值
     * @param key 加锁的key
     * @param action 函数
     */
    public static void lock(String scenes, int maxLocks, String key, Action action) {
        scenes(scenes, maxLocks).lock(key, action);
    }

    /**
     * 尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     * @param scenes 场景
     * @param key 加锁的key
     * @param action 函数
     */
    public static void tryLock(String scenes, String key, Action action) {
        scenes(scenes).tryLock(key, action);
    }

    /**
     * 尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     * @param scenes 场景
     * @param key 加锁的key
     * @param action 函数
     * @param tryFail tryLock失败执行函数
     */
    public static void tryLock(String scenes, String key, Action action, Action tryFail) {
        scenes(scenes).tryLock(key, action, tryFail);
    }

    /**
     * 尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     * @param scenes 场景
     * @param maxLocks 锁对象数量最大值
     * @param key 加锁的key
     * @param action 函数
     */
    public static void tryLock(String scenes, int maxLocks, String key, Action action) {
        scenes(scenes, maxLocks).tryLock(key, action);
    }

    /**
     * 尝试去获取锁，没有获取成功则返回
     * 没有参数，没有返回值的同步方法
     * @param scenes 场景
     * @param maxLocks 锁对象数量最大值
     * @param key 加锁的key
     * @param action 函数
     * @param tryFail tryLock失败执行函数
     */
    public static void tryLock(String scenes, int maxLocks, String key, Action action, Action tryFail) {
        scenes(scenes, maxLocks).tryLock(key, action, tryFail);
    }

    public static class SyncLocalLock {

        private static boolean recycleIsStarted = false;
        private static final ReentrantLock recycleLock = new ReentrantLock();

        /**
         * 时间轮定时扫描空闲锁，对象池回收
         */
        private static void startRecycle() {
            if (recycleIsStarted) return;
            recycleLock.lock();
            try {
                if (recycleIsStarted) return;
                recycleIsStarted = true;
                // 启动时间轮任务
                if (!TimingWheel.isStarted()) {
                    TimingWheel.startTimingWheel();
                }
                long period = ContextUtils.getPropertyLong("nasa.local-lock.idle-lock-recycle-time", 10000L);
                List<String> scenes = new CopyOnWriteArrayList<>();

                TimingWheel.exec(period, period, SyncLocalLock.class.getSimpleName(), () -> {
                    scenes.clear();
                    // 将scenes缓存所有空闲锁全部回收
                    scenes.addAll(scenesLockMap.keySet());
                    for (String scene : scenes) {

                        SyncLocalLock sll = scenesLockMap.get(scene);
                        if (!sll.globalLock.tryLock()) continue;

                        try {
                            Map<String, LocalLock> lockMap = sll.lockMap;
                            Set<String> lockKeys = ColUtils.toSet(lockMap.values(), LocalLock::isUnused, LocalLock::getLockKey);
                            if (ColUtils.isEmpty(lockKeys)) continue;

                            for (String key : lockKeys) {

                                // 从场景缓存池移除
                                LocalLock lock = lockMap.remove(key);
                                if (lock.isUsed()) {
                                    // 在remove瞬间又被其他线程占用，再次放入锁缓存池
                                    lockMap.put(key, lock);
                                    continue;
                                }

                                log.debug("锁缓存池 --> 将scenes[{}]下的空闲锁lockKey[{}]回收", scene, lock.getLockKey());

                                // 对象池回收
                                lock.recycle();
                            }
                        } finally {
                            sll.globalLock.unlock();
                        }
                    }
                });
            } finally {
                recycleLock.unlock();
            }
        }

        /**
         * 缓存池中锁对象数量创建的最大值
         * 默认值为主要业务线程池的最大并发数
         * 建议设置为主要业务线程池的最大并发数
         */
        @Getter
        private final int maxLocks;
        /* 锁的缓存池 */
        private final Map<String, LocalLock> lockMap;
        private final boolean fair;
        /* 清理时的全局锁 */
        private final Lock globalLock = new ReentrantLock();
        /* new 一个新的锁 */
        private final Function<String, LocalLock> newLockFunc;

        public SyncLocalLock(int maxLocks, boolean fair) {
            this.maxLocks = maxLocks;
            this.fair = fair;
            lockMap = new HashMap<>(this.maxLocks);
            newLockFunc = lockKey -> LocalLock.fromPool(this.fair, lockKey);
            startRecycle();
        }

        /**
         * 获取锁
         * @param lockKey 加锁的key
         */
        public LocalLock getLock(String lockKey) {

            // 存在的锁，直接返回
            LocalLock localLock = lockMap.get(lockKey);
            if (Objects.nonNull(localLock)) return localLock;

            // 锁缓存池已满，从锁缓冲池中获取一个未被占用的锁，其他空闲锁被对象池回收
            globalLock.lock();
            try {

                localLock = lockMap.get(lockKey);
                if (Objects.nonNull(localLock)) return localLock;

                // 不存在，且锁缓存池未满
                if (lockMap.size() < maxLocks) {
                    // 锁缓存池没有满，则创建，空闲锁等待时间轮任务回收
                    return lockMap.computeIfAbsent(lockKey, newLockFunc);
                }

                // 一直循环到获取到锁
                while (true) {

                    Set<String> lockKeys = ColUtils.toSet(lockMap.values(), LocalLock::isUnused, LocalLock::getLockKey);

                    if (ColUtils.isEmpty(lockKeys)) {
                        // 没有空闲锁，睡眠5ms，等待其他线程unlock
                        ReflectUtils.sleep(5);
                        continue;
                    }

                    // 当前scenes存在空闲锁
                    for (String key : lockKeys) {

                        if (Objects.isNull(key)) continue;

                        // 从scenes缓存池移除
                        LocalLock lock = lockMap.remove(key);
                        if (lock.isUsed()) {
                            // remove瞬间又被其他线程占用，重新放回scenes缓存池
                            lockMap.put(key, lock);
                            continue;
                        }

                        if (Objects.isNull(localLock)) {
                            // remove后任然未被使用，lockKey使用
                            localLock = lock;
                        } else {

                            log.debug("锁缓存池 --> 将空闲锁lockKey[{}]回收", key);

                            // 对象池回收
                            lock.recycle();
                        }
                    }

                    if (Objects.isNull(localLock)) {
                        // 所有场景中都没有空闲锁，等5ms再次尝试
                        ReflectUtils.sleep(5);
                        continue;
                    }

                    String before = localLock.getLockKey();
                    localLock.setLockKey(lockKey);

                    lockMap.put(lockKey, localLock);

                    log.debug("锁缓存池 --> 将空闲锁lockKey[{}]修改为[{}]，并更新锁缓存池", before, lockKey);

                    return localLock;
                }
            } finally {
                globalLock.unlock();
            }
        }

        /**
         * 没有参数，有返回值的同步方法
         * @param key 加锁的key
         * @param supplier 函数
         * @param <T> 需要返回的值的泛型
         */
        public <T> T lock(String key, Supplier<T> supplier) {
            try (LocalLock lock = getLock(key)) {
                lock.lock();
                return supplier.get();
            }
        }

        /**
         * 没有参数，有返回值的同步方法
         * @param key 加锁的key
         * @param supplier 函数
         * @param tryFail tryLock失败执行函数
         * @param <T> 需要返回的值的泛型
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
         * 没有参数，没有返回值的同步方法
         * @param key 加锁的key
         * @param action 函数
         */
        public void lock(String key, Action action) {
            try (LocalLock lock = getLock(key)) {
                lock.lock();
                action.action();
            }
        }

        /**
         * 尝试去获取锁，没有获取成功则返回
         * 没有参数，没有返回值的同步方法
         * @param key 加锁的key
         * @param action 函数
         */
        public void tryLock(String key, Action action) {
            tryLock(key, action, (Action) null);
        }

        /**
         * 尝试去获取锁，没有获取成功则返回
         * 没有参数，没有返回值的同步方法
         * @param key 加锁的key
         * @param action 函数
         * @param tryFail tryLock失败执行函数
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
         * 没有参数，有返回值的同步方法
         * @param key 加锁的key
         * @param param 函数参数
         * @param function 函数
         * @param <T> 需要返回的值的泛型
         * @param <P> 函数参数的泛型
         */
        public <P, T> T lock(String key, P param, Function<P, T> function) {
            try (LocalLock lock = getLock(key)) {
                lock.lock();
                return function.apply(param);
            }
        }

        /**
         * 没有参数，有返回值的同步方法
         * @param key 加锁的key
         * @param param 函数参数
         * @param function 函数
         * @param tryFail tryLock失败执行函数
         * @param <T> 需要返回的值的泛型
         * @param <P> 函数参数的泛型
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
         * 没有参数，没有返回值的同步方法
         * @param key 加锁的key
         * @param param 参数
         * @param consumer 消费函数
         * @param <P> 函数参数的泛型
         */
        public <P> void lock(String key, P param, Consumer<P> consumer) {
            try (LocalLock lock = getLock(key)) {
                lock.lock();
                consumer.accept(param);
            }
        }

        /**
         * 尝试去获取锁，没有获取成功则返回
         * 没有参数，没有返回值的同步方法
         * @param key 加锁的key
         * @param param 参数
         * @param consumer 消费函数
         * @param <P> 函数参数的泛型
         */
        public <P> void tryLock(String key, P param, Consumer<P> consumer) {
            this.tryLock(key, param, consumer, null);
        }

        /**
         * 尝试去获取锁，没有获取成功则返回
         * 没有参数，没有返回值的同步方法
         * @param key 加锁的key
         * @param param 参数
         * @param consumer 消费函数
         * @param tryFail tryLock失败执行函数
         * @param <P> 函数参数的泛型
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
