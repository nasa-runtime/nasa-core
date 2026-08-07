package io.github.nasaruntime.core.concurrent;

import io.github.nasaruntime.core.exception.BaseException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

public class CompleteFuture<V> extends CompletableFuture<V> {

    /**
     * 业务作用：取得结果并保证收尾动作一定执行，无论取值过程是否抛出异常。
     *
     * 参数说明: 无。
     * 返回: 已包装的结果。
     */
    public <T> T getFinally() {
        return getFinally(this);
    }

    /**
     * 业务作用：取得结果。因结果已就绪，本方法不会阻塞。
     *
     * @param f 见上述说明
     * 返回: 已包装的结果。
     */
    public static <T> T get(Future<T> f) {
        try {
            return f.get();
        } catch (Throwable t) {
            Throwable cause = BaseException.cause(t);
            throw new IllegalCallerException(cause.getMessage(), cause);
        }
    }

    /**
     * 业务作用：取得结果并保证收尾动作一定执行，无论取值过程是否抛出异常。
     *
     * @param f 见上述说明
     * 返回: 已包装的结果。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> T getFinally(Future<?> f) {
        Object o = get(f);
        if (o == null) return null;
        /**
         * 业务作用：取得结果并保证收尾动作一定执行，无论取值过程是否抛出异常。
         *
         * 参数说明: 无。
         * 返回: 已包装的结果。
         */
        return o instanceof Future f2 ? getFinally(f2) : (T) o;
    }

    /**
     * 业务作用：包装一个已完成的结果，供需要返回 Future 但结果已就绪的场景避免额外线程调度。
     *
     * 参数说明: 无。
     * 返回: 已完成的 Future。
     */
    public static <T> CompleteFuture<T> of() {
        return new CompleteFuture<T>();
    }

    /**
     * 业务作用：包装一个已完成的结果，供需要返回 Future 但结果已就绪的场景避免额外线程调度。
     *
     * @param t 元素
     * 返回: 已完成的 Future。
     */
    public static <T> CompleteFuture<T> of(T t) {
        CompleteFuture<T> cf = new CompleteFuture<>();
        cf.complete(t);
        return cf;
    }
}
