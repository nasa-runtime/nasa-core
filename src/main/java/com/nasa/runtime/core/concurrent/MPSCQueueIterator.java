package com.nasa.runtime.core.concurrent;

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

    MPSCQueueIterator(MPSCChunk<E> startChunk, long start, long end) {
        this.chunk = startChunk;
        this.cursor = start;
        this.end = end;
    }

    private MPSCChunk<E> chunkFor(long chunkId) {
        MPSCChunk<E> c = this.chunk;
        while (c != null && c.index < chunkId) {
            c = c.lvNext();
        }
        if (c == null || c.index != chunkId) return null;
        return c;
    }

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

    @Override
    public boolean hasNext() {
        if (!this.fetched) this.fetch();
        return this.nextElem != null;
    }

    @Override
    public E next() {
        if (!this.fetched) this.fetch();
        E e = this.nextElem;
        if (e == null) throw new NoSuchElementException();
        this.fetched = false;
        return e;
    }
}
