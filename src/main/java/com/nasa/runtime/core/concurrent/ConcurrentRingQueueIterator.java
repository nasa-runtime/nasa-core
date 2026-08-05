package com.nasa.runtime.core.concurrent;

import java.util.Iterator;
import java.util.NoSuchElementException;

// ConcurrentRingQueue 的弱一致快照迭代器
// 拆顶级 package-private (项目规范: 不写内部类); 构造时快照 buf/mask/[start,end).
// 跳过 null 槽位 (并发消费会把槽置 null): spliterator 声明 NONNULL, 不能漏出 null. fetch-ahead 预取.
@SuppressWarnings("all")
final class ConcurrentRingQueueIterator<E> implements Iterator<E> {

    private final Object[] buf;
    private final int mask;
    private long cursor;
    private final long end;
    private E nextElem;
    private boolean fetched;

    ConcurrentRingQueueIterator(Object[] buf, int mask, long start, long end) {
        this.buf = buf;
        this.mask = mask;
        this.cursor = start;
        this.end = end;
    }

    private void fetch() {
        this.fetched = true;
        this.nextElem = null;
        while (this.cursor < this.end) {
            Object o = this.buf[(int) (this.cursor++ & this.mask)];
            if (o != null) {
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
