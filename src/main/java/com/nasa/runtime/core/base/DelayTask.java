package com.nasa.runtime.core.base;

import com.nasa.runtime.core.function.Action;
import com.nasa.runtime.core.utils.ContextUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 延迟队列任务
 * @see java.util.concurrent.DelayQueue
 */
@Deprecated
@Getter
@Setter
public class DelayTask implements Delayed, ObjectPool.Recycler<DelayTask> {

    /* 过期时间 */
    private Long expireTime;
    /* 消费线程池 */
    private Executor executor;
    /* 无数据消费函数 */
    private Action action;

    private final ObjectPool.PooledHandle<DelayTask> handle = new ObjectPool.PooledHandle<>(delayRunner.getObjectPool());

    private DelayTask() {}

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(expireTime - System.currentTimeMillis(), unit);
    }

    @SuppressWarnings("all")
    @Override
    public int compareTo(Delayed o) {
        return Long.compare(this.expireTime, ((DelayTask) o).getExpireTime());
    }

    @Override
    public ObjectPool.PooledHandle<DelayTask> handle() {
        return this.handle;
    }

    @Override
    public void restore() {
        this.expireTime = null;
        this.executor = null;
        this.action = null;
    }

    /**
     * get DelayTask from object pool
     */
    public static DelayTask of() {
        return delayRunner.getObjectPool().get();
    }

    /**
     * get DelayTask from object pool with parameters
     */
    public static DelayTask of(long expireTime, Executor executor, Action action) {
        DelayTask delayTask = of();
        delayTask.expireTime = expireTime;
        delayTask.executor = executor;
        delayTask.action = action;
        return delayTask;
    }

    /* 延迟队列执行器 */
    private static final DelayRunner delayRunner = new DelayRunner();
    private static final ReentrantLock startLock = new ReentrantLock();

    /**
     * 启动延迟队列消费
     */
    private static void start() {
        startLock.lock();
        try {
            if (!delayRunner.isStarted()) delayRunner.start();
        } finally {
            startLock.unlock();
        }
    }

    /**
     * @param delayTime 延迟时间 ms
     * @param action 具体消费函数
     */
    public static void exec(long delayTime, Action action) {
        exec(null, delayTime, action);
    }

    /**
     * @param executor 消费线程池
     * @param delayTime 延迟时间 ms
     * @param action 具体消费函数
     */
    public static void exec(Executor executor, long delayTime, Action action) {
        if (!delayRunner.isStarted()) start();
        delayRunner.add(delayTime, executor, action);
    }

    @Slf4j
    static class DelayRunner {

        /* object pool creator */
        @Getter
        private final ObjectPool<DelayTask> objectPool = new ObjectPool<>(
                ContextUtils.getPropertyInt("nasa.object-pool.delay-task-capacity", 10000)) {
            @Override
            public DelayTask newObject() {
                return new DelayTask();
            }
        };
        /* 延迟队列 */
        private final DelayQueue<DelayTask> delayQueue = new DelayQueue<>();
//        private final Lock lock = new ReentrantLock();
        @Getter
        private boolean started = false;

        /**
         * 启动延迟队列消费
         */
        void start() {
            this.started = true;
            new Thread("DelayRunner") {
                @Override
                public void run() {
                    log.info("Delayed consumption queue startup is completed.");
                    while (true) {
                        try {
                            DelayTask task = delayQueue.take();
                            Executor executor = task.getExecutor();
                            Action action = task.getAction();
                            // object pool recycle
                            task.recycle();

                            // exec task
                            if (Objects.isNull(executor)) {
                                action.run();
                                continue;
                            }
                            try {
                                executor.execute(action);
                            } catch (RejectedExecutionException e) {
                                // 如果任务被拒绝，由 DelayRunner 负责继续执行
                                action.run();
                            }
                        } catch (InterruptedException ignore) {
                            log.info("Delayed consumption queue has been closed.");
                            break;
                        }
                    }
                }
            }.start();
        }

        /**
         * 添加延迟任务
         */
        void add(long delayTime, Executor executor, Action action) {
            delayQueue.offer(of(delayTime, executor, action));
        }
    }
}
