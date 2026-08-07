package io.github.nasaruntime.core.concurrent;

import java.util.Iterator;
import java.util.NoSuchElementException;

// MPSCLinkedQueue 的弱一致快照迭代器 (密集 MPSCChunk 版). 拆顶级 package-private (项目规范: 不写内部类).
// 构造时快照 [start, end) 与起始 chunk, 沿 next 前向遍历; 跳过 null(未发布/已消费) 与 POISON(发布失败)。
@SuppressWarnings("all")
final class MPSCQueueIterator<E> implements Iterator<E> {

    private MPSCChunk<E> chunk;
    private long cursor;
    private final long end;
    private E nextElem;
    private boolean fetched;

    /**
     * 业务作用：按给定参数构造 MPSCQueueIterator 实例。
     *
     * @param startChunk 见上述说明
     * @param start 见上述说明
     * @param end 见上述说明
     * 返回: 构造完成后可直接使用的实例。
     */
    MPSCQueueIterator(MPSCChunk<E> startChunk, long start, long end) {
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
    private MPSCChunk<E> chunkFor(long chunkId) {
        MPSCChunk<E> c = this.chunk;
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
            MPSCChunk<E> c = this.chunkFor(i >> MPSCChunk.CHUNK_SHIFT);
            if (c == null) {
                this.cursor = this.end;
                return;
            }
            this.chunk = c;
            Object o = c.lvElement((int) (i & MPSCChunk.CHUNK_MASK));
            this.cursor = i + 1;
            if (o != null && o != MPSCChunk.POISON) {
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
