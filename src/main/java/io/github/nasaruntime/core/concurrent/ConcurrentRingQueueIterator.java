package io.github.nasaruntime.core.concurrent;

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

    /**
     * 业务作用：按给定参数构造 ConcurrentRingQueueIterator 实例。
     *
     * @param buf 见上述说明
     * @param mask 见上述说明
     * @param start 见上述说明
     * @param end 见上述说明
     * 返回: 构造完成后可直接使用的实例。
     */
    ConcurrentRingQueueIterator(Object[] buf, int mask, long start, long end) {
        this.buf = buf;
        this.mask = mask;
        this.cursor = start;
        this.end = end;
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
            Object o = this.buf[(int) (this.cursor++ & this.mask)];
            if (o != null) {
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
