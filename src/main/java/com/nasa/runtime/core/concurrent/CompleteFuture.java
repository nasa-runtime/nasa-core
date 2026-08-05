package com.nasa.runtime.core.concurrent;

import com.nasa.runtime.core.exception.BaseException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

public class CompleteFuture<V> extends CompletableFuture<V> {

    /**
     * 获取最终的值
     */
    public <T> T getFinally() {
        return getFinally(this);
    }

    public static <T> T get(Future<T> f) {
        try {
            return f.get();
        } catch (Throwable t) {
            Throwable cause = BaseException.cause(t);
            throw new IllegalCallerException(cause.getMessage(), cause);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> T getFinally(Future<?> f) {
        Object o = get(f);
        if (o == null) return null;
        return o instanceof Future f2 ? getFinally(f2) : (T) o;
    }

    public static <T> CompleteFuture<T> of() {
        return new CompleteFuture<T>();
    }

    public static <T> CompleteFuture<T> of(T t) {
        CompleteFuture<T> cf = new CompleteFuture<>();
        cf.complete(t);
        return cf;
    }
}
