package io.github.nasaruntime.core.base;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 纯 JDK 雪花 ID 生成器。
 * <p>
 * 基于相对时间戳、workerId 与毫秒内序列号生成分布式标识，不依赖 Spring、Redis 或其它运行时容器。
 * 序列号完整使用 {@code [0, 2^seqBits)} 区间；本地时钟回拨或当前毫秒序列耗尽时向未来借用 1ms，
 * 保证同一实例内严格递增。不同实例必须使用互不相同的 workerId，否则无法保证全局唯一。
 *
 * <h2>ID 位布局</h2>
 * <pre>
 *   ┌───────────────────────┬──────────────┬────────────┐
 *   │ timestamp (ms)        │ workerId     │ sequence   │
 *   │ 64 - shift 位         │ workerIdBits │ seqBits    │
 *   └───────────────────────┴──────────────┴────────────┘
 *   shift = workerIdBits + seqBits
 * </pre>
 *
 * <p>默认参数可由集成层决定；本类只接受已经分配完成的 workerId，不负责节点协调。</p>
 *
 * @since 1.0.2
 */
public class JdkSnowflake implements IdGenerate {

    /* 时间戳左移总位数。 */
    private final int totalShift;
    /* 每毫秒允许使用的最大序列号。 */
    private final long maxSeq;
    /* ID 相对时间戳的起点，单位为毫秒。 */
    private final long baseTime;
    /* workerId 预先左移后的位段，生成时直接与时间戳、序列号合并。 */
    private final long shiftedWorkerId;

    /* 保护 lastTs 与 seq 的联合状态，使同一实例生成结果严格递增。 */
    private final ReentrantLock lock = new ReentrantLock();
    /* 上一次生成使用的相对时间戳。 */
    private long lastTs;
    /* 当前相对毫秒已经使用的序列号。 */
    private long seq;

    /**
     * 业务作用：使用已分配的 workerId 和位布局创建本地雪花 ID 生成器。
     * 集成层必须保证所有并行实例的 workerId 互不相同，并在同一数据域内使用一致的 baseTime 与位布局。
     *
     * @param workerId     机器码，范围为 {@code [0, 2^workerIdBits)}
     * @param baseTime     基础时间戳，单位为毫秒
     * @param workerIdBits 机器码位长，范围为 {@code [1, 15]}
     * @param seqBits      序列号位长，范围为 {@code [3, 21]}
     */
    public JdkSnowflake(long workerId, long baseTime, int workerIdBits, int seqBits) {
        if (workerIdBits < 1 || workerIdBits > 15) {
            throw new IllegalArgumentException("workerIdBits must be [1, 15], got " + workerIdBits);
        }
        if (seqBits < 3 || seqBits > 21) {
            throw new IllegalArgumentException("seqBits must be [3, 21], got " + seqBits);
        }
        // 为时间戳保留至少 42 位，使相同位布局可以覆盖约 139 年。
        if (workerIdBits + seqBits > 22) {
            throw new IllegalArgumentException(
                    "workerIdBits + seqBits must <= 22, got " + (workerIdBits + seqBits));
        }
        long maxWorkerId = (1L << workerIdBits) - 1;
        if (workerId < 0 || workerId > maxWorkerId) {
            throw new IllegalArgumentException("workerId must be [0, " + maxWorkerId + "], got " + workerId);
        }

        this.totalShift = workerIdBits + seqBits;
        this.maxSeq = (1L << seqBits) - 1;
        this.baseTime = baseTime;
        this.shiftedWorkerId = workerId << seqBits;
    }

    /**
     * 业务作用：在调用方已经持有实例锁时推进时间戳和序列号，生成下一个严格递增标识。
     * 当前时间前进时从序列号 0 开始；时间未前进且序列未满时递增序列；时钟回拨或序列耗尽时向未来借 1ms。
     *
     * <p>参数说明: 无。</p>
     *
     * @return 下一个雪花 ID。
     */
    private long nextId() {
        long now = System.currentTimeMillis() - baseTime;
        if (now > lastTs) {
            lastTs = now;
            seq = 0;
        } else if (seq < maxSeq) {
            seq++;
        } else {
            // 不等待墙上时钟追平，避免回拨或突发流量使生成线程阻塞。
            lastTs++;
            seq = 0;
        }
        return (lastTs << totalShift) | shiftedWorkerId | seq;
    }

    /**
     * 业务作用：生成一个在当前实例内严格递增、在 workerId 不重复前提下全局唯一的标识。
     * 跨节点结果只大致有序，不能用来判定分布式事件的真实先后关系。
     *
     * <p>参数说明: 无。</p>
     *
     * @return 新生成的雪花 ID。
     */
    @Override
    public long generate() {
        lock.lock();
        try {
            return nextId();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：在一次加锁期间批量生成组内严格递增的雪花 ID，降低批量写入场景的锁竞争。
     *
     * @param c 需要生成的标识数量，必须大于 0
     * @return 长度为 c 的 ID 数组，元素严格递增。
     */
    @Override
    public long[] generate(int c) {
        if (c < 1) {
            throw new IllegalArgumentException("c must be greater than 0");
        }
        long[] ids = new long[c];
        lock.lock();
        try {
            for (int i = 0; i < c; i++) {
                ids[i] = nextId();
            }
            return ids;
        } finally {
            lock.unlock();
        }
    }
}
