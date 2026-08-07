package io.github.nasaruntime.core.concurrent;

import io.github.nasaruntime.core.base.ObjectPool;
import io.github.nasaruntime.core.exception.LocalLockException;
import io.github.nasaruntime.core.utils.ContextUtils;
import lombok.Getter;
import lombok.Setter;

import java.io.Closeable;
import java.io.Serial;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Nasa
 * 注意：此锁不能用于Condition条件唤醒，只能加锁解锁
 */
@SuppressWarnings("all")
public class LocalLock extends ReentrantLock implements ObjectPool.Recycler<LocalLock>, Closeable {

    @Serial
    private static final long serialVersionUID = -40349292638916773L;

    /* 公平锁对象池 */
    private static final LocalLockPool FAIR_POOL = new LocalLockPool(true);
    /* 非公平锁对象池 */
    private static final LocalLockPool UNFAIR_POOL = new LocalLockPool(false);

    /* 对执行lock和tryLock成功的线程计数，表示当前有多少线程正在使用这把锁，锁重入也会加1 */
    private final AtomicInteger counter = new AtomicInteger(0);
    /* SyncLock.getLock 已返回但尚未开始 lock/tryLock 的借用保留，防止窗口期被回收改绑 */
    private final AtomicInteger reservations = new AtomicInteger(0);
    /* 锁标记 */
    @Setter
    @Getter
    private String lockKey;
    /* per-instance Handle, 池引用按 fair flag 锁定到对应 FAIR/UNFAIR pool */
    private final ObjectPool.PooledHandle<LocalLock> handle;

    /**
     * 业务作用：创建与指定公平策略绑定的可池化本地锁，并固定其所属对象池。
     *
     * @param fair true 使用公平锁，false 使用非公平锁
     * 返回: 构造完成后即可加锁；公平性在实例生命周期内不变。
     */
    private LocalLock(boolean fair) {
        super(fair);
        this.handle = new ObjectPool.PooledHandle<>(pool(fair));
    }

    /**
     * 业务作用：开始一次加锁尝试并把借用保留转为使用计数，使锁在等待和持有期间均不可回收。
     *
     * 参数说明: 无。
     * 返回: 无返回值；方法返回时当前线程已经获得锁。
     */
    @Override
    public void lock() {
        counter.incrementAndGet();
        this.consumeReservation();
        super.lock();
    }

    /**
     * 业务作用：以可中断方式开始加锁，并在等待前关闭借用引用被回收的窗口。
     *
     * 参数说明: 无。
     * 返回: 无返回值；成功时当前线程获得锁，中断时恢复使用计数后抛出异常。
     */
    @Override
    public void lockInterruptibly() throws InterruptedException {
        counter.incrementAndGet();
        this.consumeReservation();
        try {
            super.lockInterruptibly();
        } catch (InterruptedException e) {
            counter.decrementAndGet();
            throw e;
        }
    }

    /**
     * 业务作用：立即尝试取得锁，并把一次借用保留安全地转入本次尝试，失败后允许锁继续回收复用。
     *
     * 参数说明: 无。
     * 返回: 成功取得锁返回 true；锁正在使用时返回 false。
     */
    @Override
    public boolean tryLock() {
        counter.incrementAndGet();
        this.consumeReservation();
        boolean success = super.tryLock();
        if (!success) {
            counter.decrementAndGet();
        }
        return success;
    }

    /**
     * 业务作用：在限定时间内尝试取得锁，并保证等待期间锁身份不会被回收或改绑。
     *
     * @param timeout 最大等待时长
     * @param unit 等待时长单位
     * @return 成功取得锁返回 true；超时返回 false
     */
    @Override
    public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
        counter.incrementAndGet();
        this.consumeReservation();
        boolean success = false;
        try {
            return success = super.tryLock(timeout, unit);
        } finally {
            if (!success) counter.decrementAndGet();
        }
    }

    /**
     * 业务作用：释放当前线程持有的一层锁，并减少使用计数，使最后一个使用者退出后允许回收或改绑。
     *
     * 参数说明: 无。
     * 返回: 无返回值；当前线程未持锁时沿用 {@link ReentrantLock} 语义抛出异常。
     */
    @Override
    public void unlock() {
        super.unlock();
        counter.decrementAndGet();
    }

    /**
     * 业务作用：拒绝创建条件队列，防止对象池复用锁时遗留 Condition 等待关系。
     *
     * 参数说明: 无。
     * 返回: 不返回 Condition，始终抛出 {@link LocalLockException}。
     */
    @Override
    public Condition newCondition() {
        throw new LocalLockException("LocalLock 类不能使用Condition条件");
    }

    /**
     * 业务作用：登记一次尚未开始加锁的借用，使场景回收线程无法在调用窗口内改绑该锁。
     *
     * 参数说明: 无。
     * 返回: 无返回值；下一次加锁尝试会消费一份保留。
     */
    void reserve() {
        this.reservations.incrementAndGet();
    }

    /**
     * 业务作用：在使用计数已经建立后消费一份借用保留，完成从查表引用到加锁尝试的安全交接。
     *
     * 参数说明: 无。
     * 返回: 无返回值；不存在保留时保持原值，兼容直接创建的 LocalLock。
     */
    private void consumeReservation() {
        int current = this.reservations.get();
        while (current > 0) {
            if (this.reservations.compareAndSet(current, current - 1)) return;
            current = this.reservations.get();
        }
    }

    /**
     * 是否空闲
     *
     * 业务作用：读取底层锁当前是否没有持有线程，仅用于观察锁状态，不作为回收和改绑的唯一条件。
     *
     * 参数说明: 无。
     * 返回: 底层锁未被任何线程持有时返回 true，否则返回 false。
     */
    public boolean isUnlocked() {
        return !super.isLocked();
    }

    /**
     * 是否有线程正在 lock 和 tryLock
     *
     * 业务作用：判断锁是否存在尚未开始的借用、等待线程或持有线程，作为回收和改绑的安全门禁。
     *
     * 参数说明: 无。
     * 返回: 存在借用、等待或持有关系时返回 true，否则返回 false。
     */
    public boolean isUsed() {
        // 先读 reservations，再读 counter，与 counter++ → reservations-- 的交接顺序配对，避免观察到假空闲。
        return reservations.get() > 0 || counter.get() > 0;
    }

    /**
     * 是否空闲
     * 如果所有线程都 unlock 了，那么这个值一定会是0
     * 当前实现还要求没有尚未开始加锁的借用保留。
     *
     * 业务作用：判断锁是否可以安全回收或改绑，要求借用保留和实际使用计数同时归零。
     *
     * 参数说明: 无。
     * 返回: 可以安全回收或改绑时返回 true，否则返回 false。
     */
    public boolean isUnused() {
        return reservations.get() == 0 && counter.get() == 0;
    }

    /**
     * 实现 Closeable 接口，实现自动解锁
     *      try (LocalLock lock = new LocalLock()) {
     *          lock.lock();
     *      }
     * 以上try块执行结束，会自动调用close()方法实现自动解锁
     *
     * 业务作用：关闭当前借用；当前线程已经取得锁时自动解锁，尚未开始加锁时撤销一次借用保留。
     *
     * 参数说明: 无。
     * 返回: 无返回值；不会尝试解开其他线程持有的锁。
     */
    @Override
    public void close() {
        if (super.isHeldByCurrentThread()) {
            this.unlock();
            return;
        }
        this.consumeReservation();
    }

    /**
     * 业务作用：暴露池化身份，启用重复归池的 CAS 防御。
     *
     * 参数说明: 无。
     * 返回: 构造时绑定本类对象池的 handle。
     */
    @Override
    public ObjectPool.PooledHandle<LocalLock> handle() {
        return this.handle;
    }

    /**
     * 业务作用：在锁归还对象池前清除业务 key、使用计数和借用保留，避免状态泄漏到下一场景。
     *
     * 参数说明: 无。
     * 返回: 无返回值；恢复后该实例只能由对象池重新派发。
     */
    @Override
    public void restore() {
        this.lockKey = null;
        this.counter.set(0);
        this.reservations.set(0);
    }
    
    /**
     * 业务作用：按公平性取得对应的锁对象池。公平锁与非公平锁语义不同，不能混用同一个池。
     *
     * @param fair 为 true 时使用公平锁
     * 返回: 该公平性对应的对象池。
     */
    private static ObjectPool<LocalLock> pool(boolean fair) {
        return fair ? FAIR_POOL : UNFAIR_POOL;
    }
    
    /**
     * 业务作用：从对象池借出一把锁并绑定锁键。
     *
     * @param fair 为 true 时使用公平锁
     * 返回: 可用的锁实例；用完必须归池。
     */
    public static LocalLock fromPool(boolean fair) {
        return pool(fair).get();
    }

    /**
     * 业务作用：从对象池借出一把锁并绑定锁键。
     *
     * @param fair 为 true 时使用公平锁
     * @param lockKey 锁键，同键串行
     * 返回: 可用的锁实例；用完必须归池。
     */
    public static LocalLock fromPool(boolean fair, String lockKey) {
        LocalLock lock = fromPool(fair);
        lock.setLockKey(lockKey);
        return lock;
    }

    /**
     * 业务作用：从对象池借出一把锁并绑定锁键。
     *
     * @param lockKey 锁键，同键串行
     * 返回: 可用的锁实例；用完必须归池。
     */
    public static LocalLock fromPool(String lockKey) {
        return fromPool(false, lockKey);
    }

    /**
     * 锁对象池
     */
    static class LocalLockPool extends ObjectPool<LocalLock> {

        private final boolean fair;

        /**
         * 业务作用：按给定参数构造 LocalLockPool 实例。
         *
         * @param fair 见上述说明
         * 返回: 构造完成后可直接使用的实例。
         */
        public LocalLockPool(boolean fair) {
            super(ContextUtils.getPropertyInt("nasa.object-pool.local-lock-capacity", 1000));
            this.fair = fair;
        }

        /**
         * 业务作用：池空时创建新实例。
         *
         * 参数说明: 无。
         * 返回: 字段均为初始值的新实例。
         */
        @Override
        public LocalLock newObject() {
            return new LocalLock(fair);
        }

    }
}
