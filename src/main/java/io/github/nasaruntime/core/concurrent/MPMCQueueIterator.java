package io.github.nasaruntime.core.concurrent;

import java.util.Iterator;
import java.util.NoSuchElementException;

// MPMCLinkedQueue 的弱一致快照迭代器, 拆顶级 package-private (项目规范: 不写内部类)
// 构造时快照 [start, end) 区间与起始 chunk, 沿 next 前向遍历; 跳过尚未发布(null)的槽位
@SuppressWarnings("all")
final class MPMCQueueIterator<E> implements Iterator<E> {

    private MPMCChunk<E> chunk;
    private long cursor;
    private final long end;
    private E nextElem;
    private boolean fetched;

    /**
     * 业务作用：按给定参数构造 MPMCQueueIterator 实例。
     *
     * @param startChunk 见上述说明
     * @param start 见上述说明
     * @param end 见上述说明
     * 返回: 构造完成后可直接使用的实例。
     */
    MPMCQueueIterator(MPMCChunk<E> startChunk, long start, long end) {
        this.chunk = startChunk;
        this.cursor = start;
        this.end = end;
    }

    /**
     * 业务作用：按块序号沿链定位到目标块。
     *
     * @param chunkId 块序号
     * 返回: 目标块；已超出链尾时返回 null。
     */
    private MPMCChunk<E> chunkFor(long chunkId) {
        MPMCChunk<E> c = this.chunk;
        // 起始 chunk 可能落后于 cursor 所在 chunk, 沿 next 前向推进; 链未建好则终止遍历
        while (c != null && c.index < chunkId) {
            c = c.lvNext();
        }
        if (c == null || c.index != chunkId) return null;
        return c;
    }

    /**
     * 业务作用：向前扫描直到取到下一个非空槽位，跳过并发出队留下的空位。
     *
     * 参数说明: 无。
     * 返回: 无返回值；结果暂存在迭代器内部，供 hasNext 与 next 复用。
     */
    private void fetch() {
        this.fetched = true;
        this.nextElem = null;
        while (this.cursor < this.end) {
            long i = this.cursor;
            MPMCChunk<E> c = this.chunkFor(i >> MPMCChunk.CHUNK_SHIFT);
            if (c == null) {
                // 链尚未延伸到此 (并发快照), 提前结束
                this.cursor = this.end;
                return;
            }
            this.chunk = c;
            Object o = c.lvElementRaw((int) (i & MPMCChunk.CHUNK_MASK));
            this.cursor = i + 1;
            // 跳过未发布(null)、已消费(TOMBSTONE)、发布失败(POISON)的槽位
            if (o != null && o != MPMCChunk.TOMBSTONE && o != MPMCChunk.POISON) {
                this.nextElem = (E) o;
                return;
            }
        }
    }

    /**
     * 业务作用：判断迭代是否还有下一个元素；必要时先向前探测一格。
     *
     * 参数说明: 无。
     * 返回: 还有元素返回 true。
     */
    @Override
    public boolean hasNext() {
        if (!this.fetched) this.fetch();
        return this.nextElem != null;
    }

    /**
     * 业务作用：返回下一个元素并前移游标。
     *
     * 参数说明: 无。
     * 返回: 下一个元素；已到末尾时抛出 NoSuchElementException。
     */
    @Override
    public E next() {
        if (!this.fetched) this.fetch();
        E e = this.nextElem;
        if (e == null) throw new NoSuchElementException();
        this.fetched = false;
        return e;
    }
}
