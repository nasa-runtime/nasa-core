package com.nasa.runtime.core.base;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.*;

/**
 * Nasa 条件事件框架
 * 为可观察值提供 when-then-otherwise 条件链。值变更后调用 {@link WhenChain#fire(Object)} 触发所有已注册的条件。
 * 实现者只需提供 {@link #chain()} 方法，其余 API 通过 default 方法自动获得：
 *
 * <h2>使用示例</h2>
 * {@snippet :
 * public class MyCounter implements When<Integer> {
 *     private final WhenChain<Integer> chain = WhenChain.of();
 *     private int count;
 *
 *     @Override
 *     public WhenChain<Integer> chain() { return chain; }
 *
 *     public void increment() {
 *         count++;
 *         chain.fire(count);  // 触发条件链
 *     }
 * }
 *
 * // 使用
 * counter.when(v -> v == 100).then(v -> checkpoint());
 * counter.when(v -> v % 10 == 0).then(v -> checkpoint()).otherwise(v -> skip());
 * }
 *
 * @param <T> 被观察值的类型
 */
public interface When<T> {

    /**
     * 获取条件链
     */
    WhenChain<T> chain();

    /**
     * 注册一个条件，返回 {@link WhenChain.Clause} 用于设置 then/otherwise。
     */
    default WhenChain.Clause<T> when(Predicate<T> condition) {
        return chain().when(condition);
    }

    /**
     * 清除所有已注册的条件
     */
    default void clearChain() {
        chain().clearChain();
    }

    // ==================== WhenChain：条件链管理器 ====================

    /**
     * 条件链：管理一组Clause，按注册顺序依次求值。
     * 使用 plain array 存储，零额外方法调用开销，利于 JIT 内联优化。
     */
    class WhenChain<T> {

        private static final int INIT_CAPACITY = 4;

        private Clause<T>[] data;
        private int size;

        @SuppressWarnings("unchecked")
        private WhenChain() {
            data = new Clause[INIT_CAPACITY];
        }

        public static <T> WhenChain<T> of() {
            return new WhenChain<>();
        }

        /**
         * 注册一个条件
         */
        public Clause<T> when(Predicate<T> condition) {
            Objects.requireNonNull(condition);
            Clause<T> clause = new Clause<>(this, condition);
            if (size == data.length) data = Arrays.copyOf(data, size << 1);
            data[size++] = clause;
            return clause;
        }

        /**
         * 触发条件链：对每个 Clause 求值，执行 then 或 otherwise。
         * 方法体尽量小，便于 JIT 完全内联。
         */
        public void fire(T value) {
            final int n = size;
            if (n == 0) return;
            final Clause<T>[] cs = data;
            for (int i = 0; i < n; i++) {
                Clause<T> c = cs[i];
                if (c.condition.test(value)) {
                    if (c.thenAction != null) c.thenAction.accept(value);
                } else {
                    if (c.otherwiseAction != null) c.otherwiseAction.accept(value);
                }
            }
        }

        /**
         * 移除指定条件
         */
        public boolean remove(Clause<T> clause) {
            for (int i = 0; i < size; i++) {
                if (data[i] == clause) {
                    int moved = size - i - 1;
                    if (moved > 0) System.arraycopy(data, i + 1, data, i, moved);
                    data[--size] = null;
                    return true;
                }
            }
            return false;
        }

        /**
         * 清除所有条件
         */
        public void clearChain() {
            Arrays.fill(data, 0, size, null);
            size = 0;
        }

        public boolean isEmpty() {
            return size == 0;
        }

        public int size() {
            return size;
        }

        // ==================== Clause：单个条件单元 ====================

        /**
         * 一个 when-then-otherwise 条件单元。
         * 支持链式注册多个条件：
         * observable.when(cond1).then(act1)
         *           .when(cond2).then(act2).otherwise(act3);
         */
        public static class Clause<T> {

            private final WhenChain<T> chain;
            private final Predicate<T> condition;
            private Consumer<T> thenAction;
            private Consumer<T> otherwiseAction;

            Clause(WhenChain<T> chain, Predicate<T> condition) {
                this.chain = chain;
                this.condition = condition;
            }

            /**
             * 条件满足时执行
             */
            public Clause<T> then(Consumer<T> action) {
                this.thenAction = Objects.requireNonNull(action);
                return this;
            }

            /**
             * 条件不满足时执行
             */
            public Clause<T> otherwise(Consumer<T> action) {
                this.otherwiseAction = Objects.requireNonNull(action);
                return this;
            }

            /**
             * 在同一条件链上注册下一个条件（链式调用入口）
             */
            public Clause<T> when(Predicate<T> condition) {
                return chain.when(condition);
            }

            /**
             * 从条件链中移除自身
             */
            public boolean remove() {
                return chain.remove(this);
            }
        }
    }

    // ==================== IntWhenChain：int 特化，零装箱 ====================

    /**
     * int 特化条件链，使用 {@link IntPredicate} / {@link IntConsumer} 避免 int → Integer 装箱。
     * 用于 {@link RingInteger} 等 int 值的观察场景。
     */
    class IntWhenChain {

        private static final int INIT_CAPACITY = 4;

        private IntClause[] data;
        private int size;

        private IntWhenChain() {
            data = new IntClause[INIT_CAPACITY];
        }

        public static IntWhenChain of() {
            return new IntWhenChain();
        }

        public IntClause when(IntPredicate condition) {
            Objects.requireNonNull(condition);
            IntClause clause = new IntClause(this, condition);
            if (size == data.length) data = Arrays.copyOf(data, size << 1);
            data[size++] = clause;
            return clause;
        }

        public void fire(int value) {
            final int n = size;
            if (n == 0) return;
            final IntClause[] cs = data;
            for (int i = 0; i < n; i++) {
                IntClause c = cs[i];
                if (c.condition.test(value)) {
                    if (c.thenAction != null) c.thenAction.accept(value);
                } else {
                    if (c.otherwiseAction != null) c.otherwiseAction.accept(value);
                }
            }
        }

        public boolean remove(IntClause clause) {
            for (int i = 0; i < size; i++) {
                if (data[i] == clause) {
                    int moved = size - i - 1;
                    if (moved > 0) System.arraycopy(data, i + 1, data, i, moved);
                    data[--size] = null;
                    return true;
                }
            }
            return false;
        }

        public void clear() {
            Arrays.fill(data, 0, size, null);
            size = 0;
        }

        public boolean isEmpty() { return size == 0; }

        public int size() { return size; }

        public static class IntClause {

            private final IntWhenChain chain;
            final IntPredicate condition;
            IntConsumer thenAction;
            IntConsumer otherwiseAction;

            IntClause(IntWhenChain chain, IntPredicate condition) {
                this.chain = chain;
                this.condition = condition;
            }

            public IntClause then(IntConsumer action) {
                this.thenAction = Objects.requireNonNull(action);
                return this;
            }

            public IntClause otherwise(IntConsumer action) {
                this.otherwiseAction = Objects.requireNonNull(action);
                return this;
            }

            public IntClause when(IntPredicate condition) {
                return chain.when(condition);
            }

            public boolean remove() {
                return chain.remove(this);
            }
        }
    }

    // ==================== LongWhenChain：long 特化，零装箱 ====================

    /**
     * long 特化条件链，使用 {@link LongPredicate} / {@link LongConsumer} 避免 long → Long 装箱。
     * 用于 {@link RingLong} 等 long 值的观察场景。
     */
    class LongWhenChain {

        private static final int INIT_CAPACITY = 4;

        private LongClause[] data;
        private int size;

        private LongWhenChain() {
            data = new LongClause[INIT_CAPACITY];
        }

        public static LongWhenChain of() {
            return new LongWhenChain();
        }

        public LongClause when(LongPredicate condition) {
            Objects.requireNonNull(condition);
            LongClause clause = new LongClause(this, condition);
            if (size == data.length) data = Arrays.copyOf(data, size << 1);
            data[size++] = clause;
            return clause;
        }

        public void fire(long value) {
            final int n = size;
            if (n == 0) return;
            final LongClause[] cs = data;
            for (int i = 0; i < n; i++) {
                LongClause c = cs[i];
                if (c.condition.test(value)) {
                    if (c.thenAction != null) c.thenAction.accept(value);
                } else {
                    if (c.otherwiseAction != null) c.otherwiseAction.accept(value);
                }
            }
        }

        public boolean remove(LongClause clause) {
            for (int i = 0; i < size; i++) {
                if (data[i] == clause) {
                    int moved = size - i - 1;
                    if (moved > 0) System.arraycopy(data, i + 1, data, i, moved);
                    data[--size] = null;
                    return true;
                }
            }
            return false;
        }

        public void clear() {
            Arrays.fill(data, 0, size, null);
            size = 0;
        }

        public boolean isEmpty() { return size == 0; }

        public int size() { return size; }

        public static class LongClause {

            private final LongWhenChain chain;
            final LongPredicate condition;
            LongConsumer thenAction;
            LongConsumer otherwiseAction;

            LongClause(LongWhenChain chain, LongPredicate condition) {
                this.chain = chain;
                this.condition = condition;
            }

            public LongClause then(LongConsumer action) {
                this.thenAction = Objects.requireNonNull(action);
                return this;
            }

            public LongClause otherwise(LongConsumer action) {
                this.otherwiseAction = Objects.requireNonNull(action);
                return this;
            }

            public LongClause when(LongPredicate condition) {
                return chain.when(condition);
            }

            public boolean remove() {
                return chain.remove(this);
            }
        }
    }
}
