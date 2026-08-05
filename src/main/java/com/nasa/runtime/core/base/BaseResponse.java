package com.nasa.runtime.core.base;

import com.nasa.runtime.core.function.SerFunction;
import com.nasa.runtime.core.utils.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Nasa
 * @param <T> 具体数据的泛型
 */
@SuppressWarnings("unused")
@Getter
@Setter
@NoArgsConstructor
public class BaseResponse<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1598196857883863172L;

    private int code = 200;
    /* 异常时返回的提示信息 */
    private String msg;
    /* 如果需要加密，这是AES的密钥 */
    private String aes;
    /* 具体数据 */
    private T data;

    public BaseResponse(T data) {
        this.data = data;
    }

    public BaseResponse(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public String toString() {
        return ObjMprUtils.toString(this);
    }

    /**
     * 异常信息
     */
    public static BaseResponse<?> error(int code, String message, Object... params) {
        return new BaseResponse<>(code, StringUtils.format(message, params));
    }

    /**
     * 异常信息
     */
    public static BaseResponse<?> errorTranslate(int code, String message, Object... params) {
        return error(code, Translator.translate(message), params);
    }

    /**
     * 操作成功
     */
    public static BaseResponse<?> success() {
        return Objects.isNull(UNMODIFIABLE_RESPONSE) ? UNMODIFIABLE_RESPONSE = new UnmodifiableResponse() : UNMODIFIABLE_RESPONSE;
    }

    /**
     * 操作成功
     */
    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(data);
    }

    /**
     * 操作成功
     */
    public static BaseResponse<HashMap<String, Object>> hashMap(Object... kvs) {
        return success(MapUtils.toHashMap(kvs));
    }

    /**
     * 操作成功
     */
    public static BaseResponse<LinkedHashMap<String, Object>> linkedMap(Object... kvs) {
        return success(MapUtils.toLinkedMap(kvs));
    }

    /**
     * 操作成功
     * @param o 参数
     * @param mapper 映射函数
     * @param <O> 参数泛型
     * @param <T> 映射值泛型
     */
    public static <O, T> BaseResponse<T> mapper(O o, Function<O, T> mapper) {
        return success(mapper.apply(o));
    }

    /**
     * 操作成功
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @SafeVarargs
    public static <T> BaseResponse<ArrayList<HashMap<String, Object>>> listHash(Collection<T> c, SerFunction<T, ?>... getters) {
        ArrayList<HashMap<String, Object>> list = new ArrayList<>();
        if (ColUtils.isEmpty(getters)) {
            return success(list);
        }
        c.forEach(e -> list.add(MapUtils.toHashMap(e, (SerFunction[]) getters)));
        return success(list);
    }

    /**
     * 操作成功
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> BaseResponse<ArrayList<HashMap<String, Object>>> listHash(T[] arr, SerFunction<T, ?>... getters) {
        ArrayList<HashMap<String, Object>> list = new ArrayList<>();
        if (ColUtils.isEmpty(getters)) {
            return success(list);
        }
        for (T t : arr) {
            list.add(MapUtils.toHashMap(t, (SerFunction[]) getters));
        }
        return success(list);
    }

    /**
     * 操作成功
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @SafeVarargs
    public static <T> BaseResponse<ArrayList<LinkedHashMap<String, Object>>> listLinked(Collection<T> c, SerFunction<T, ?>... getters) {
        ArrayList<LinkedHashMap<String, Object>> list = new ArrayList<>();
        if (ColUtils.isEmpty(getters)) {
            return success(list);
        }
        c.forEach(e -> list.add(MapUtils.toLinkedMap(e, (SerFunction[]) getters)));
        return success(list);
    }

    /**
     * 操作成功
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> BaseResponse<ArrayList<LinkedHashMap<String, Object>>> listLinked(T[] arr, SerFunction<T, ?>... getters) {
        ArrayList<LinkedHashMap<String, Object>> list = new ArrayList<>();
        if (ColUtils.isEmpty(getters)) {
            return success(list);
        }
        for (T t : arr) {
            list.add(MapUtils.toLinkedMap(t, (SerFunction[]) getters));
        }
        return success(list);
    }

    /**
     * 操作成功
     */
    public static <T, E> BaseResponse<List<E>> list(Collection<T> col, Predicate<T> predicate, Function<T, E> mapper) {
        return success(ColUtils.toList(col, predicate, mapper));
    }

    /**
     * 操作成功
     */
    public static <T, E> BaseResponse<List<E>> list(T[] arr, Predicate<T> predicate, Function<T, E> mapper) {
        return success(ColUtils.toList(arr, predicate, mapper));
    }

    /**
     * 操作成功
     */
    public static <T, E> BaseResponse<List<E>> list(Collection<T> col, Function<T, E> mapper) {
        return list(col, ColUtils.nonNull(), mapper);
    }

    /**
     * 操作成功
     */
    public static <T, E> BaseResponse<List<E>> list(T[] arr, Function<T, E> mapper) {
        return list(arr, ColUtils.nonNull(), mapper);
    }

    /**
     * 操作成功
     */
    public static <T, E> BaseResponse<Set<E>> set(Collection<T> col, Predicate<T> predicate, Function<T, E> mapper) {
        return success(ColUtils.toSet(col, predicate, mapper));
    }

    /**
     * 操作成功
     */
    public static <T, E> BaseResponse<Set<E>> set(T[] arr, Predicate<T> predicate, Function<T, E> mapper) {
        return success(ColUtils.toSet(arr, predicate, mapper));
    }

    /**
     * 操作成功
     */
    public static <T, E> BaseResponse<Set<E>> set(Collection<T> col, Function<T, E> mapper) {
        return set(col, ColUtils.nonNull(), mapper);
    }

    /**
     * 操作成功
     */
    public static <T, E> BaseResponse<Set<E>> set(T[] arr, Function<T, E> mapper) {
        return set(arr, ColUtils.nonNull(), mapper);
    }

    private static UnmodifiableResponse UNMODIFIABLE_RESPONSE;

    /**
     * 禁止setter操作
     */
    static class UnmodifiableResponse extends BaseResponse<Object> {

        private final UnsupportedOperationException UOE = new UnsupportedOperationException("The set operation is not supported.");

        @Override
        public void setCode(int o) {
            throw UOE;
        }

        @Override
        public void setMsg(String o) {
            throw UOE;
        }

        @Override
        public void setAes(String o) {
            throw UOE;
        }

        @Override
        public void setData(Object o) {
            throw UOE;
        }
    }
}
