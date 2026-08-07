package io.github.nasaruntime.core.base;

import io.github.nasaruntime.core.function.SerFunction;
import io.github.nasaruntime.core.utils.*;
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

    /**
     * 业务作用：按给定参数构造 BaseResponse 实例。
     *
     * @param data 见上述说明
     * 返回: 构造完成后可直接使用的实例。
     */
    public BaseResponse(T data) {
        this.data = data;
    }

    /**
     * 业务作用：按给定参数构造 BaseResponse 实例。
     *
     * @param code 见上述说明
     * @param msg 见上述说明
     * 返回: 构造完成后可直接使用的实例。
     */
    public BaseResponse(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    /**
     * 业务作用：输出可读的元素快照，仅供诊断。
     *
     * 参数说明: 无。
     * 返回: 形如 [a, b, c] 的字符串；不保证与任何时刻的容器状态一致。
     */
    @Override
    public String toString() {
        return ObjMprUtils.toString(this);
    }

    /**
     * 业务作用：按给定参数构造 error 实例。
     *
     * @param code 见上述说明
     * @param message 见上述说明
     * @param params 见上述说明
     * 返回: 构造完成后可直接使用的实例。
     */
    public static BaseResponse<?> error(int code, String message, Object... params) {
        return new BaseResponse<>(code, StringUtils.format(message, params));
    }

    /**
     * 业务作用：按给定参数构造 errorTranslate 实例。
     *
     * @param code 见上述说明
     * @param message 见上述说明
     * @param params 见上述说明
     * 返回: 构造完成后可直接使用的实例。
     */
    public static BaseResponse<?> errorTranslate(int code, String message, Object... params) {
        return error(code, Translator.translate(message), params);
    }

    /**
     * 业务作用：构造实例。字段取默认值。
     *
     * 参数说明: 无。
     * 返回: 构造完成后可直接使用的实例。
     */
    public static BaseResponse<?> success() {
        return Objects.isNull(UNMODIFIABLE_RESPONSE) ? UNMODIFIABLE_RESPONSE = new UnmodifiableResponse() : UNMODIFIABLE_RESPONSE;
    }

    /**
     * 业务作用：构造成功响应并携带业务数据。
     *
     * @param data 响应数据
     * 返回: 状态码为成功、data 为给定数据的响应。
     */
    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(data);
    }

    /**
     * 业务作用：按键值成对的可变参数构造 HashMap 型成功响应，省去调用方先建 map 再包装。
     *
     * @param kvs 按键值成对给出的项
     * 返回: data 为 HashMap 的成功响应。
     */
    public static BaseResponse<HashMap<String, Object>> hashMap(Object... kvs) {
        return success(MapUtils.toHashMap(kvs));
    }

    /**
     * 业务作用：按键值成对的可变参数构造 LinkedHashMap 型成功响应，保留书写顺序。
     *
     * @param kvs 按键值成对给出的项
     * 返回: data 为 LinkedHashMap 的成功响应。
     */
    public static BaseResponse<LinkedHashMap<String, Object>> linkedMap(Object... kvs) {
        return success(MapUtils.toLinkedMap(kvs));
    }

    /**
     * 业务作用：把源对象经映射函数转换后包装成成功响应，避免调用方为一次转换写中间变量。
     *
     * @param o 取值
     * @param mapper 元素到目标值的映射函数
     * 返回: data 为转换结果的成功响应。
     */
    public static <O, T> BaseResponse<T> mapper(O o, Function<O, T> mapper) {
        return success(mapper.apply(o));
    }

    /**
     * 业务作用：把集合中每个元素按给定 getter 抽成 HashMap 并包装成成功响应，用于只返回部分字段的列表接口。
     *
     * @param c 源集合
     * @param getters 取值函数数组
     * 返回: data 为 HashMap 列表的成功响应。
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
     * 业务作用：把集合中每个元素按给定 getter 抽成 HashMap 并包装成成功响应，用于只返回部分字段的列表接口。
     *
     * @param arr 源数组
     * @param getters 取值函数数组
     * 返回: data 为 HashMap 列表的成功响应。
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
     * 业务作用：把集合中每个元素按给定 getter 抽成 LinkedHashMap 并包装成成功响应，字段顺序与 getter 顺序一致。
     *
     * @param c 源集合
     * @param getters 取值函数数组
     * 返回: data 为 LinkedHashMap 列表的成功响应。
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
     * 业务作用：把集合中每个元素按给定 getter 抽成 LinkedHashMap 并包装成成功响应，字段顺序与 getter 顺序一致。
     *
     * @param arr 源数组
     * @param getters 取值函数数组
     * 返回: data 为 LinkedHashMap 列表的成功响应。
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
     * 业务作用：筛选并映射集合元素后包装成列表型成功响应。
     *
     * @param col 源集合
     * @param predicate 筛选条件
     * @param mapper 元素到目标值的映射函数
     * 返回: data 为映射结果列表的成功响应。
     */
    public static <T, E> BaseResponse<List<E>> list(Collection<T> col, Predicate<T> predicate, Function<T, E> mapper) {
        return success(ColUtils.toList(col, predicate, mapper));
    }

    /**
     * 业务作用：筛选并映射集合元素后包装成列表型成功响应。
     *
     * @param arr 源数组
     * @param predicate 筛选条件
     * @param mapper 元素到目标值的映射函数
     * 返回: data 为映射结果列表的成功响应。
     */
    public static <T, E> BaseResponse<List<E>> list(T[] arr, Predicate<T> predicate, Function<T, E> mapper) {
        return success(ColUtils.toList(arr, predicate, mapper));
    }

    /**
     * 业务作用：筛选并映射集合元素后包装成列表型成功响应。
     *
     * @param col 源集合
     * @param mapper 元素到目标值的映射函数
     * 返回: data 为映射结果列表的成功响应。
     */
    public static <T, E> BaseResponse<List<E>> list(Collection<T> col, Function<T, E> mapper) {
        return list(col, ColUtils.nonNull(), mapper);
    }

    /**
     * 业务作用：筛选并映射集合元素后包装成列表型成功响应。
     *
     * @param arr 源数组
     * @param mapper 元素到目标值的映射函数
     * 返回: data 为映射结果列表的成功响应。
     */
    public static <T, E> BaseResponse<List<E>> list(T[] arr, Function<T, E> mapper) {
        return list(arr, ColUtils.nonNull(), mapper);
    }

    /**
     * 业务作用：筛选并映射集合元素后包装成集合型成功响应，顺带去重。
     *
     * @param col 源集合
     * @param predicate 筛选条件
     * @param mapper 元素到目标值的映射函数
     * 返回: data 为映射结果集合的成功响应。
     */
    public static <T, E> BaseResponse<Set<E>> set(Collection<T> col, Predicate<T> predicate, Function<T, E> mapper) {
        return success(ColUtils.toSet(col, predicate, mapper));
    }

    /**
     * 业务作用：筛选并映射集合元素后包装成集合型成功响应，顺带去重。
     *
     * @param arr 源数组
     * @param predicate 筛选条件
     * @param mapper 元素到目标值的映射函数
     * 返回: data 为映射结果集合的成功响应。
     */
    public static <T, E> BaseResponse<Set<E>> set(T[] arr, Predicate<T> predicate, Function<T, E> mapper) {
        return success(ColUtils.toSet(arr, predicate, mapper));
    }

    /**
     * 业务作用：筛选并映射集合元素后包装成集合型成功响应，顺带去重。
     *
     * @param col 源集合
     * @param mapper 元素到目标值的映射函数
     * 返回: data 为映射结果集合的成功响应。
     */
    public static <T, E> BaseResponse<Set<E>> set(Collection<T> col, Function<T, E> mapper) {
        return set(col, ColUtils.nonNull(), mapper);
    }

    /**
     * 业务作用：筛选并映射集合元素后包装成集合型成功响应，顺带去重。
     *
     * @param arr 源数组
     * @param mapper 元素到目标值的映射函数
     * 返回: data 为映射结果集合的成功响应。
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

        /**
         * 业务作用：设置业务状态码。
         *
         * @param o 取值
         * 返回: 无返回值。
         */
        @Override
        public void setCode(int o) {
            throw UOE;
        }

        /**
         * 业务作用：设置提示信息。
         *
         * @param o 取值
         * 返回: 无返回值。
         */
        @Override
        public void setMsg(String o) {
            throw UOE;
        }

        /**
         * 业务作用：设置加密标识，供网关判断响应体是否需要解密。
         *
         * @param o 取值
         * 返回: 无返回值。
         */
        @Override
        public void setAes(String o) {
            throw UOE;
        }

        /**
         * 业务作用：设置业务数据。
         *
         * @param o 取值
         * 返回: 无返回值。
         */
        @Override
        public void setData(Object o) {
            throw UOE;
        }
    }
}
