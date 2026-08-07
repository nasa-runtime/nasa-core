package io.github.nasaruntime.core.base;

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
 */
public interface When<T> {

    /**
     * 业务作用：暴露内部 When 事件链，使条件回调可以挂载到本容器的状态变化上。
     *
     * 参数说明: 无。
     * 返回: 本实例独有的事件链。
     */
    WhenChain<T> chain();

    /**
     * 业务作用：在条件链上追加一个条件，返回可继续挂载动作的子句。
     *
     * @param condition 触发条件
     * 返回: 该条件对应的子句。
     */
    default WhenChain.Clause<T> when(Predicate<T> condition) {
        return chain().when(condition);
    }

    /**
     * 业务作用：清除所有已注册的条件
     *
     * 参数说明: 无。
     * 返回: 无返回值。
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

        /**
         * 业务作用：构造实例。字段取默认值。
         *
         * 参数说明: 无。
         * 返回: 构造完成后可直接使用的实例。
         */
        @SuppressWarnings("unchecked")
        private WhenChain() {
            data = new Clause[INIT_CAPACITY];
        }

        /**
         * 业务作用：创建一条空的条件链。
         *
         * 参数说明: 无。
         * 返回: 新的条件链实例。
         */
        public static <T> WhenChain<T> of() {
            return new WhenChain<>();
        }

        /**
         * 业务作用：在条件链上追加一个条件，返回可继续挂载动作的子句。
         *
         * @param condition 触发条件
         * 返回: 该条件对应的子句。
         */
        public Clause<T> when(Predicate<T> condition) {
            Objects.requireNonNull(condition);
            Clause<T> clause = new Clause<>(this, condition);
            if (size == data.length) data = Arrays.copyOf(data, size << 1);
            data[size++] = clause;
            return clause;
        }

        /**
         * 业务作用：触发条件链：对每个 Clause 求值，执行 then 或 otherwise。
         * 方法体尽量小，便于 JIT 完全内联。
         *
         * @param value 见上述说明
         * 返回: 无返回值。
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
         * 业务作用：移除指定条件
         *
         * @param clause 见上述说明
         * 返回: 满足上述判定条件时返回 true，否则返回 false。
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
         * 业务作用：清除所有条件
         *
         * 参数说明: 无。
         * 返回: 无返回值。
         */
        public void clearChain() {
            Arrays.fill(data, 0, size, null);
            size = 0;
        }

        /**
         * 业务作用：判断容器当前是否为空。
         *
         * 参数说明: 无。
         * 返回: 为空返回 true；并发下为弱一致结果，仅反映采样瞬间的状态。不能作为后续操作必定成功的依据。
         */
        public boolean isEmpty() {
            return size == 0;
        }

        /**
         * 业务作用：报告当前元素个数，供容量观测与遍历前的预分配。
         *
         * 参数说明: 无。
         * 返回: 元素个数；并发下为弱一致结果，仅反映采样瞬间的状态。
         */
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

            /**
             * 业务作用：按给定参数构造 Clause 实例。
             *
             * @param chain 见上述说明
             * @param condition 见上述说明
             * 返回: 构造完成后可直接使用的实例。
             */
            Clause(WhenChain<T> chain, Predicate<T> condition) {
                this.chain = chain;
                this.condition = condition;
            }

            /**
             * 业务作用：为当前条件挂上成立时执行的动作。
             *
             * @param action 条件成立时执行的动作
             * 返回: 当前子句，供继续链式配置。
             */
            public Clause<T> then(Consumer<T> action) {
                this.thenAction = Objects.requireNonNull(action);
                return this;
            }

            /**
             * 业务作用：为当前条件挂上不成立时执行的动作。
             *
             * @param action 条件成立时执行的动作
             * 返回: 当前子句，供继续链式配置。
             */
            public Clause<T> otherwise(Consumer<T> action) {
                this.otherwiseAction = Objects.requireNonNull(action);
                return this;
            }

            /**
             * 业务作用：在条件链上追加一个条件，返回可继续挂载动作的子句。
             *
             * @param condition 触发条件
             * 返回: 该条件对应的子句。
             */
            public Clause<T> when(Predicate<T> condition) {
                return chain.when(condition);
            }

            /**
             * 业务作用：从条件链中移除自身
             *
             * 参数说明: 无。
             * 返回: 满足上述判定条件时返回 true，否则返回 false。
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

        /**
         * 业务作用：构造实例。字段取默认值。
         *
         * 参数说明: 无。
         * 返回: 构造完成后可直接使用的实例。
         */
        private IntWhenChain() {
            data = new IntClause[INIT_CAPACITY];
        }

        /**
         * 业务作用：创建一条空的条件链。
         *
         * 参数说明: 无。
         * 返回: 新的条件链实例。
         */
        public static IntWhenChain of() {
            return new IntWhenChain();
        }

        /**
         * 业务作用：在条件链上追加一个条件，返回可继续挂载动作的子句。
         *
         * @param condition 触发条件
         * 返回: 该条件对应的子句。
         */
        public IntClause when(IntPredicate condition) {
            Objects.requireNonNull(condition);
            IntClause clause = new IntClause(this, condition);
            if (size == data.length) data = Arrays.copyOf(data, size << 1);
            data[size++] = clause;
            return clause;
        }

        /**
         * 业务作用：以给定值逐条求值整条链并执行命中的动作。
         *
         * @param value 位集合当前值
         * 返回: 无返回值；某条动作抛出的异常会中断后续求值。
         */
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

        /**
         * 业务作用：从链上摘除条件子句，使其不再参与后续求值。
         *
         * @param clause 待移除的条件子句
         * 返回: 确实摘除了返回 true；不在链上返回 false。
         */
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

        /**
         * 业务作用：移除全部元素，解除容器对它们的强引用后交给 GC。
         *
         * 参数说明: 无。
         * 返回: 无返回值；并发写入仍在进行时不保证返回后容器为空。
         */
        public void clear() {
            Arrays.fill(data, 0, size, null);
            size = 0;
        }

        /**
         * 业务作用：判断容器当前是否为空。
         *
         * 参数说明: 无。
         * 返回: 为空返回 true；并发下为弱一致结果，仅反映采样瞬间的状态。不能作为后续操作必定成功的依据。
         */
        public boolean isEmpty() { return size == 0; }

        /**
         * 业务作用：报告当前元素个数，供容量观测与遍历前的预分配。
         *
         * 参数说明: 无。
         * 返回: 元素个数；并发下为弱一致结果，仅反映采样瞬间的状态。
         */
        public int size() { return size; }

        public static class IntClause {

            private final IntWhenChain chain;
            final IntPredicate condition;
            IntConsumer thenAction;
            IntConsumer otherwiseAction;

            /**
             * 业务作用：按给定参数构造 IntClause 实例。
             *
             * @param chain 见上述说明
             * @param condition 见上述说明
             * 返回: 构造完成后可直接使用的实例。
             */
            IntClause(IntWhenChain chain, IntPredicate condition) {
                this.chain = chain;
                this.condition = condition;
            }

            /**
             * 业务作用：为当前条件挂上成立时执行的动作。
             *
             * @param action 条件成立时执行的动作
             * 返回: 当前子句，供继续链式配置。
             */
            public IntClause then(IntConsumer action) {
                this.thenAction = Objects.requireNonNull(action);
                return this;
            }

            /**
             * 业务作用：为当前条件挂上不成立时执行的动作。
             *
             * @param action 条件成立时执行的动作
             * 返回: 当前子句，供继续链式配置。
             */
            public IntClause otherwise(IntConsumer action) {
                this.otherwiseAction = Objects.requireNonNull(action);
                return this;
            }

            /**
             * 业务作用：在条件链上追加一个条件，返回可继续挂载动作的子句。
             *
             * @param condition 触发条件
             * 返回: 该条件对应的子句。
             */
            public IntClause when(IntPredicate condition) {
                return chain.when(condition);
            }

            /**
             * 业务作用：从链上摘除条件子句，使其不再参与后续求值。
             *
             * 参数说明: 无。
             * 返回: 确实摘除了返回 true；不在链上返回 false。
             */
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

        /**
         * 业务作用：构造实例。字段取默认值。
         *
         * 参数说明: 无。
         * 返回: 构造完成后可直接使用的实例。
         */
        private LongWhenChain() {
            data = new LongClause[INIT_CAPACITY];
        }

        /**
         * 业务作用：创建一条空的条件链。
         *
         * 参数说明: 无。
         * 返回: 新的条件链实例。
         */
        public static LongWhenChain of() {
            return new LongWhenChain();
        }

        /**
         * 业务作用：在条件链上追加一个条件，返回可继续挂载动作的子句。
         *
         * @param condition 触发条件
         * 返回: 该条件对应的子句。
         */
        public LongClause when(LongPredicate condition) {
            Objects.requireNonNull(condition);
            LongClause clause = new LongClause(this, condition);
            if (size == data.length) data = Arrays.copyOf(data, size << 1);
            data[size++] = clause;
            return clause;
        }

        /**
         * 业务作用：以给定值逐条求值整条链并执行命中的动作。
         *
         * @param value 位集合当前值
         * 返回: 无返回值；某条动作抛出的异常会中断后续求值。
         */
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

        /**
         * 业务作用：从链上摘除条件子句，使其不再参与后续求值。
         *
         * @param clause 待移除的条件子句
         * 返回: 确实摘除了返回 true；不在链上返回 false。
         */
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

        /**
         * 业务作用：移除全部元素，解除容器对它们的强引用后交给 GC。
         *
         * 参数说明: 无。
         * 返回: 无返回值；并发写入仍在进行时不保证返回后容器为空。
         */
        public void clear() {
            Arrays.fill(data, 0, size, null);
            size = 0;
        }

        /**
         * 业务作用：判断容器当前是否为空。
         *
         * 参数说明: 无。
         * 返回: 为空返回 true；并发下为弱一致结果，仅反映采样瞬间的状态。不能作为后续操作必定成功的依据。
         */
        public boolean isEmpty() { return size == 0; }

        /**
         * 业务作用：报告当前元素个数，供容量观测与遍历前的预分配。
         *
         * 参数说明: 无。
         * 返回: 元素个数；并发下为弱一致结果，仅反映采样瞬间的状态。
         */
        public int size() { return size; }

        public static class LongClause {

            private final LongWhenChain chain;
            final LongPredicate condition;
            LongConsumer thenAction;
            LongConsumer otherwiseAction;

            /**
             * 业务作用：按给定参数构造 LongClause 实例。
             *
             * @param chain 见上述说明
             * @param condition 见上述说明
             * 返回: 构造完成后可直接使用的实例。
             */
            LongClause(LongWhenChain chain, LongPredicate condition) {
                this.chain = chain;
                this.condition = condition;
            }

            /**
             * 业务作用：为当前条件挂上成立时执行的动作。
             *
             * @param action 条件成立时执行的动作
             * 返回: 当前子句，供继续链式配置。
             */
            public LongClause then(LongConsumer action) {
                this.thenAction = Objects.requireNonNull(action);
                return this;
            }

            /**
             * 业务作用：为当前条件挂上不成立时执行的动作。
             *
             * @param action 条件成立时执行的动作
             * 返回: 当前子句，供继续链式配置。
             */
            public LongClause otherwise(LongConsumer action) {
                this.otherwiseAction = Objects.requireNonNull(action);
                return this;
            }

            /**
             * 业务作用：在条件链上追加一个条件，返回可继续挂载动作的子句。
             *
             * @param condition 触发条件
             * 返回: 该条件对应的子句。
             */
            public LongClause when(LongPredicate condition) {
                return chain.when(condition);
            }

            /**
             * 业务作用：从链上摘除条件子句，使其不再参与后续求值。
             *
             * 参数说明: 无。
             * 返回: 确实摘除了返回 true；不在链上返回 false。
             */
            public boolean remove() {
                return chain.remove(this);
            }
        }
    }
}
