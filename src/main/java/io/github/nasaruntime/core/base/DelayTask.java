package io.github.nasaruntime.core.base;

import io.github.nasaruntime.core.function.Action;
import io.github.nasaruntime.core.utils.ContextUtils;
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

    /**
     * 业务作用：构造实例。字段取默认值。
     *
     * 参数说明: 无。
     * 返回: 构造完成后可直接使用的实例。
     */
    private DelayTask() {}

    /**
     * 业务作用：给出距到期还剩多久，供 DelayQueue 判定任务是否可出队。
     *
     * @param unit 时间单位
     * 返回: 剩余时长；已到期时为非正数。
     */
    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(expireTime - System.currentTimeMillis(), unit);
    }

    /**
     * 业务作用：按到期时刻排序，使 DelayQueue 总是先弹出最早到期的任务。
     *
     * @param o 取值
     * 返回: 本任务更早到期返回负数，相同返回 0，更晚返回正数。
     */
    @SuppressWarnings("all")
    @Override
    public int compareTo(Delayed o) {
        return Long.compare(this.expireTime, ((DelayTask) o).getExpireTime());
    }

    /**
     * 业务作用：暴露池化身份，启用重复归池的 CAS 防御。
     *
     * 参数说明: 无。
     * 返回: 构造时绑定本类对象池的 handle。
     */
    @Override
    public ObjectPool.PooledHandle<DelayTask> handle() {
        return this.handle;
    }

    /**
     * 业务作用：归池前清空到期时刻、执行器与动作引用，防止上一代任务泄漏给下一个借用方。
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    @Override
    public void restore() {
        this.expireTime = null;
        this.executor = null;
        this.action = null;
    }

    /**
     * 业务作用：从对象池借出延迟任务并绑定到期时刻、执行器与动作。
     *
     * 参数说明: 无。
     * 返回: 已绑定的任务；执行后自动归池。
     */
    public static DelayTask of() {
        return delayRunner.getObjectPool().get();
    }

    /**
     * 业务作用：从对象池借出延迟任务并绑定到期时刻、执行器与动作。
     *
     * @param expireTime 到期时刻
     * @param executor 执行器
     * @param action 条件成立时执行的动作
     * 返回: 已绑定的任务；执行后自动归池。
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
     * 业务作用：启动延迟队列消费
     *
     * 参数说明: 无。
     * 返回: 无返回值。
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
     * 业务作用：登记一个延迟执行的动作。
     *
     * @param delayTime 延迟毫秒数
     * @param action 条件成立时执行的动作
     * 返回: 无返回值。
     */
    public static void exec(long delayTime, Action action) {
        exec(null, delayTime, action);
    }

    /**
     * 业务作用：登记一个延迟执行的动作。
     *
     * @param executor 执行器
     * @param delayTime 延迟毫秒数
     * @param action 条件成立时执行的动作
     * 返回: 无返回值。
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
            /**
             * 业务作用：池空时创建新实例。
             *
             * 参数说明: 无。
             * 返回: 字段均为初始值的新实例。
             */
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
         * 业务作用：启动延迟队列消费
         *
         * 参数说明: 无。
         * 返回: 无返回值。
         */
        void start() {
            this.started = true;
            new Thread("DelayRunner") {
                /**
                 * 业务作用：到期回调：执行业务动作并在结束后归池，异常被记录而不向上传播，避免单个任务的失败影响调度线程。
                 *
                 * 参数说明: 无。
                 * 返回: 无返回值。
                 */
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
         * 业务作用：添加延迟任务
         *
         * @param delayTime 见上述说明
         * @param executor 见上述说明
         * @param action 见上述说明
         * 返回: 无返回值。
         */
        void add(long delayTime, Executor executor, Action action) {
            delayQueue.offer(of(delayTime, executor, action));
        }
    }
}
