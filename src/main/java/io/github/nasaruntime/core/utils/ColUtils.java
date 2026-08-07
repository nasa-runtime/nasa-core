package io.github.nasaruntime.core.utils;

import io.github.nasaruntime.core.annotation.Order;
import io.github.nasaruntime.core.enums.Sort;

import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Nasa
 * 集合数组工具
 */
@SuppressWarnings({"unused", "unchecked", "rawtypes"})
public abstract class ColUtils {

    // 缓存常用 lambda, 避免每次调用创建新实例
    private static final Function IDENTITY = t -> t;
    private static final Predicate NON_NULL = Objects::nonNull;
    private static final BinaryOperator LAST_WIN = (u, v) -> v;
    private static final Predicate PREDICATE_TRUE = t -> true;

    /**
     * 业务作用：返回恒等映射函数，供需要显式传入映射函数但实际不做转换的场景使用。
     *
     * 参数说明: 无。
     * 返回: 恒等函数。
     */
    public static <T> Function<T, T> identity() {
        return IDENTITY;
    }

    /**
     * 业务作用：返回「非 null」判定条件，供筛选场景直接复用。
     *
     * 参数说明: 无。
     * 返回: 判定元素非 null 的条件。
     */
    public static <T> Predicate<T> nonNull() {
        return NON_NULL;
    }

    /**
     * 业务作用：返回「后者覆盖前者」的合并策略，供转 Map 时处理键冲突。
     *
     * 参数说明: 无。
     * 返回: 取后者的合并函数。
     */
    public static <V> BinaryOperator<V> lastWin() {
        return LAST_WIN;
    }

    /**
     * 业务作用：返回恒为真的条件，供需要显式传入条件但实际不过滤的场景使用。
     *
     * 参数说明: 无。
     * 返回: 恒返回 true 的条件。
     */
    public static <T> Predicate<T> predicateTrue() {
        return PREDICATE_TRUE;
    }

    /**
     * 业务作用：判断集合为空
     *
     * @param col 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static <T> boolean isEmpty(Collection<T> col) {
        return Objects.isNull(col) || col.isEmpty();
    }

    /**
     * 业务作用：判断集合不为空
     *
     * @param col 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static <T> boolean isNotEmpty(Collection<T> col) {
        return !isEmpty(col);
    }

    /**
     * 业务作用：判断数组为空，数组内所有元素均为空
     *
     * @param arr 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static <T> boolean isBlank(T[] arr) {
        if (Objects.isNull(arr)) {
            return true;
        }
        for (T t : arr) {
            if (Objects.nonNull(t)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 业务作用：判断数组不为空
     *
     * @param arr 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static <T> boolean isNotBlank(T[] arr) {
        return !isBlank(arr);
    }

    /**
     * 业务作用：判断数组为空
     *
     * @param arr 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static <T> boolean isEmpty(T[] arr) {
        return Objects.isNull(arr) || arr.length == 0;
    }

    /**
     * 业务作用：判断数组不为空
     *
     * @param arr 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static <T> boolean isNotEmpty(T[] arr) {
        return !isEmpty(arr);
    }

    /**
     * 业务作用：判断数组为空
     *
     * @param arr 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isEmpty(short[] arr) {
        return Objects.isNull(arr) || arr.length == 0;
    }

    /**
     * 业务作用：判断数组为空
     *
     * @param arr 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isNotEmpty(short[] arr) {
        return !isEmpty(arr);
    }

    /**
     * 业务作用：判断数组为空
     *
     * @param arr 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isEmpty(byte[] arr) {
        return Objects.isNull(arr) || arr.length == 0;
    }

    /**
     * 业务作用：判断数组为空
     *
     * @param arr 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isNotEmpty(byte[] arr) {
        return !isEmpty(arr);
    }

    /**
     * 业务作用：判断数组为空
     *
     * @param arr 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isEmpty(int[] arr) {
        return Objects.isNull(arr) || arr.length == 0;
    }

    /**
     * 业务作用：判断数组为空
     *
     * @param arr 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isNotEmpty(int[] arr) {
        return !isEmpty(arr);
    }

    /**
     * 业务作用：判断数组为空
     *
     * @param arr 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isEmpty(long[] arr) {
        return Objects.isNull(arr) || arr.length == 0;
    }

    /**
     * 业务作用：判断数组为空
     *
     * @param arr 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isNotEmpty(long[] arr) {
        return !isEmpty(arr);
    }

    /**
     * 业务作用：判断数组为空
     *
     * @param arr 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isEmpty(float[] arr) {
        return Objects.isNull(arr) || arr.length == 0;
    }

    /**
     * 业务作用：判断数组为空
     *
     * @param arr 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isNotEmpty(float[] arr) {
        return !isEmpty(arr);
    }

    /**
     * 业务作用：判断数组为空
     *
     * @param arr 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isEmpty(double[] arr) {
        return Objects.isNull(arr) || arr.length == 0;
    }

    /**
     * 业务作用：判断数组为空
     *
     * @param arr 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isNotEmpty(double[] arr) {
        return !isEmpty(arr);
    }

    /**
     * 业务作用：判断数组为空
     *
     * @param arr 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isEmpty(boolean[] arr) {
        return Objects.isNull(arr) || arr.length == 0;
    }

    /**
     * 业务作用：判断数组为空
     *
     * @param arr 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isNotEmpty(boolean[] arr) {
        return !isEmpty(arr);
    }

    /**
     * 业务作用：判断数组为空
     *
     * @param arr 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isEmpty(char[] arr) {
        return Objects.isNull(arr) || arr.length == 0;
    }

    /**
     * 业务作用：判断数组为空
     *
     * @param arr 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isNotEmpty(char[] arr) {
        return !isEmpty(arr);
    }

    /**
     * 业务作用：把集合先按条件筛选、再按映射函数转换，最后收集到调用方指定类型的容器中，一次遍历完成筛选、转换与收集三步。
     *
     * 集合转集合
     *
     * @param col       集合
     * @param predicate 过滤函数
     * @param mapper    映射函数
     * @param collector 收集函数
     * 返回: 收集后的容器，类型由收集器决定。
     */
    public static <E, T, A, R extends Collection<E>> R toCollection(
            Collection<T> col
            , Predicate<T> predicate
            , Function<T, E> mapper
            , Collector<E, A, R> collector) {
        if (Objects.isNull(col)) {
            return null;
        }
        Stream<T> stream = col.stream();
        if (Objects.nonNull(predicate)) {
            stream = stream.filter(predicate);
        }
        return stream.map(mapper).collect(collector);
    }

    /**
     * 业务作用：把集合先按条件筛选、再按映射函数转换，最后收集到调用方指定类型的容器中，一次遍历完成筛选、转换与收集三步。
     *
     * 数组转集合
     *
     * @param arr       数组
     * @param predicate 过滤函数
     * @param mapper    映射函数
     * @param collector 收集函数
     * 返回: 收集后的容器，类型由收集器决定。
     */
    public static <E, T, A, R extends Collection<E>> R toCollection(
            T[] arr
            , Predicate<T> predicate
            , Function<T, E> mapper
            , Collector<E, A, R> collector) {
        if (Objects.isNull(arr)) {
            return null;
        }
        Stream<T> stream = Stream.of(arr);
        if (Objects.nonNull(predicate)) {
            stream = stream.filter(predicate);
        }
        return stream.map(mapper).collect(collector);
    }

    /**
     * 业务作用：把集合按映射函数转换到调用方指定类型的容器中。
     *
     * @param col 源集合
     * @param mapper 元素到目标值的映射函数
     * @param collector 见上述说明
     * 返回: 调用方指定类型的容器。
     */
    public static <E, T, A, R extends Collection<E>> R toCollection(
            Collection<T> col
            , Function<T, E> mapper
            , Collector<E, A, R> collector) {
        return toCollection(col, nonNull(), mapper, collector);
    }

    /**
     * 业务作用：把集合按映射函数转换到调用方指定类型的容器中。
     *
     * @param arr 源数组
     * @param mapper 元素到目标值的映射函数
     * @param collector 见上述说明
     * 返回: 调用方指定类型的容器。
     */
    public static <E, T, A, R extends Collection<E>> R toCollection(T[] arr, Function<T, E> mapper, Collector<E, A, R> collector) {
        return toCollection(arr, nonNull(), mapper, collector);
    }

    /**
     * 业务作用：把集合按映射函数转换成 List，保留原始顺序且不去重。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 转换后的列表。
     */
    public static <E, T> List<E> toList(Collection<T> col, Predicate<T> predicate, Function<T, E> mapper) {
        if (Objects.isNull(col)) return null;
        ArrayList<E> list = new ArrayList<>(col.size());
        for (T t : col) {
            if (predicate.test(t)) list.add(mapper.apply(t));
        }
        return list;
    }

    /**
     * 业务作用：把集合按映射函数转换成 List，保留原始顺序且不去重。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 转换后的列表。
     */
    public static <E, T> List<E> toList(T[] arr, Predicate<T> predicate, Function<T, E> mapper) {
        if (Objects.isNull(arr)) return null;
        ArrayList<E> list = new ArrayList<>(arr.length);
        for (T t : arr) {
            if (predicate.test(t)) list.add(mapper.apply(t));
        }
        return list;
    }

    /**
     * 业务作用：把集合按映射函数转换成 List，保留原始顺序且不去重。
     *
     * @param col 源集合
     * @param mapper 元素到目标值的映射函数
     * 返回: 转换后的列表。
     */
    public static <E, T> List<E> toList(Collection<T> col, Function<T, E> mapper) {
        return toList(col, nonNull(), mapper);
    }

    /**
     * 业务作用：把集合按映射函数转换成 List，保留原始顺序且不去重。
     *
     * @param arr 源数组
     * @param mapper 元素到目标值的映射函数
     * 返回: 转换后的列表。
     */
    public static <E, T> List<E> toList(T[] arr, Function<T, E> mapper) {
        return toList(arr, nonNull(), mapper);
    }

    /**
     * 业务作用：把集合按映射函数转换成 List，保留原始顺序且不去重。
     *
     * @param arr 源数组
     * 返回: 转换后的列表。
     */
    public static <T> List<T> toList(T... arr) {
        return toList(arr, identity());
    }

    /**
     * 业务作用：把数组包装成不可修改的列表，用于对外暴露只读视图。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 不可修改列表；任何修改操作都会抛 UnsupportedOperationException。
     */
    public static <E, T> List<E> unmodifiableList(T[] arr, Predicate<T> predicate, Function<T, E> mapper) {
        List<E> list = toList(arr, predicate, mapper);
        if (Objects.isNull(list)) {
            list = Collections.emptyList();
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * 业务作用：把数组包装成不可修改的列表，用于对外暴露只读视图。
     *
     * @param arr 源数组
     * @param mapper 元素到目标值的映射函数
     * 返回: 不可修改列表；任何修改操作都会抛 UnsupportedOperationException。
     */
    public static <E, T> List<E> unmodifiableList(T[] arr, Function<T, E> mapper) {
        return unmodifiableList(arr, nonNull(), mapper);
    }

    /**
     * 业务作用：把集合按映射函数转换成 HashSet，转换过程顺带去重。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 转换后的集合；元素顺序不保证。
     */
    public static <E, T> Set<E> toSet(Collection<T> col, Predicate<T> predicate, Function<T, E> mapper) {
        if (Objects.isNull(col)) return null;
        HashSet<E> set = new HashSet<>(col.size());
        for (T t : col) {
            if (predicate.test(t)) set.add(mapper.apply(t));
        }
        return set;
    }

    /**
     * 业务作用：把数组包装成不可修改的列表，用于对外暴露只读视图。
     *
     * @param arr 源数组
     * 返回: 不可修改列表；任何修改操作都会抛 UnsupportedOperationException。
     */
    public static <T> List<T> unmodifiableList(T... arr) {
        return unmodifiableList(arr, identity());
    }

    /**
     * 业务作用：把集合按映射函数转换成 HashSet，转换过程顺带去重。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 转换后的集合；元素顺序不保证。
     */
    public static <E, T> Set<E> toSet(T[] arr, Predicate<T> predicate, Function<T, E> mapper) {
        if (Objects.isNull(arr)) return null;
        HashSet<E> set = new HashSet<>(arr.length);
        for (T t : arr) {
            if (predicate.test(t)) set.add(mapper.apply(t));
        }
        return set;
    }

    /**
     * 业务作用：把集合按映射函数转换成 HashSet，转换过程顺带去重。
     *
     * @param col 源集合
     * @param mapper 元素到目标值的映射函数
     * 返回: 转换后的集合；元素顺序不保证。
     */
    public static <E, T> Set<E> toSet(Collection<T> col, Function<T, E> mapper) {
        return toSet(col, nonNull(), mapper);
    }

    /**
     * 业务作用：把集合按映射函数转换成 HashSet，转换过程顺带去重。
     *
     * @param col 源集合
     * 返回: 转换后的集合；元素顺序不保证。
     */
    public static <T> Set<T> toSet(Collection<T> col) {
        return toSet(col, identity());
    }

    /**
     * 业务作用：把集合按映射函数转换成 HashSet，转换过程顺带去重。
     *
     * @param arr 源数组
     * @param mapper 元素到目标值的映射函数
     * 返回: 转换后的集合；元素顺序不保证。
     */
    public static <E, T> Set<E> toSet(T[] arr, Function<T, E> mapper) {
        return toSet(arr, nonNull(), mapper);
    }

    /**
     * 业务作用：把集合按映射函数转换成 HashSet，转换过程顺带去重。
     *
     * @param arr 源数组
     * 返回: 转换后的集合；元素顺序不保证。
     */
    public static <T> Set<T> toSet(T... arr) {
        return toSet(arr, identity());
    }

    /**
     * 业务作用：把集合按映射函数转换成 LinkedHashSet，去重的同时保留原始顺序。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 保序且去重的集合。
     */
    public static <E, T> Set<E> toLinkedSet(Collection<T> col, Predicate<T> predicate, Function<T, E> mapper) {
        if (Objects.isNull(col)) return null;
        LinkedHashSet<E> set = new LinkedHashSet<>(col.size());
        for (T t : col) {
            if (predicate.test(t)) set.add(mapper.apply(t));
        }
        return set;
    }

    /**
     * 业务作用：把集合按映射函数转换成 LinkedHashSet，去重的同时保留原始顺序。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 保序且去重的集合。
     */
    public static <E, T> Set<E> toLinkedSet(T[] arr, Predicate<T> predicate, Function<T, E> mapper) {
        if (Objects.isNull(arr)) return null;
        LinkedHashSet<E> set = new LinkedHashSet<>(arr.length);
        for (T t : arr) {
            if (predicate.test(t)) set.add(mapper.apply(t));
        }
        return set;
    }

    /**
     * 业务作用：把集合按映射函数转换成 LinkedHashSet，去重的同时保留原始顺序。
     *
     * @param col 源集合
     * @param mapper 元素到目标值的映射函数
     * 返回: 保序且去重的集合。
     */
    public static <E, T> Set<E> toLinkedSet(Collection<T> col, Function<T, E> mapper) {
        return toLinkedSet(col, nonNull(), mapper);
    }

    /**
     * 业务作用：把集合按映射函数转换成 LinkedHashSet，去重的同时保留原始顺序。
     *
     * @param col 源集合
     * 返回: 保序且去重的集合。
     */
    public static <T> Set<T> toLinkedSet(Collection<T> col) {
        return toLinkedSet(col, identity());
    }

    /**
     * 业务作用：把集合按映射函数转换成 LinkedHashSet，去重的同时保留原始顺序。
     *
     * @param arr 源数组
     * @param mapper 元素到目标值的映射函数
     * 返回: 保序且去重的集合。
     */
    public static <E, T> Set<E> toLinkedSet(T[] arr, Function<T, E> mapper) {
        return toLinkedSet(arr, nonNull(), mapper);
    }

    /**
     * 业务作用：把集合按映射函数转换成 LinkedHashSet，去重的同时保留原始顺序。
     *
     * @param arr 源数组
     * 返回: 保序且去重的集合。
     */
    public static <T> Set<T> toLinkedSet(T... arr) {
        return toLinkedSet(arr, identity());
    }

    /**
     * 业务作用：把数组包装成不可修改的集合，用于对外暴露只读视图。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 不可修改集合；任何修改操作都会抛 UnsupportedOperationException。
     */
    public static <E, T> Set<E> unmodifiableSet(T[] arr, Predicate<T> predicate, Function<T, E> mapper) {
        Set<E> set = toSet(arr, predicate, mapper);
        if (Objects.isNull(set)) {
            set = Collections.emptySet();
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * 业务作用：把数组包装成不可修改的集合，用于对外暴露只读视图。
     *
     * @param arr 源数组
     * @param mapper 元素到目标值的映射函数
     * 返回: 不可修改集合；任何修改操作都会抛 UnsupportedOperationException。
     */
    public static <E, T> Set<E> unmodifiableSet(T[] arr, Function<T, E> mapper) {
        return unmodifiableSet(arr, nonNull(), mapper);
    }

    /**
     * 业务作用：把数组包装成不可修改的集合，用于对外暴露只读视图。
     *
     * @param arr 源数组
     * 返回: 不可修改集合；任何修改操作都会抛 UnsupportedOperationException。
     */
    public static <T> Set<T> unmodifiableSet(T... arr) {
        return unmodifiableSet(arr, identity());
    }

    /**
     * 业务作用：过滤集合，并执行消费
     *
     * @param col 集合
     * @param predicate 过滤函数
     * @param function 映射函数
     * @param consumer 消费函数
     * 返回: 无返回值。
     */
    public static <T, E> void consume(Collection<T> col, Predicate<T> predicate, Function<T, E> function, Consumer<E> consumer) {
        if (isEmpty(col)) return;
        for (T t : col) {
            if (predicate == null || predicate.test(t)) consumer.accept(function.apply(t));
        }
    }

    /**
     * 业务作用：过滤集合，并执行消费
     *
     * @param col 集合
     * @param predicate 过滤函数
     * @param consumer 消费函数
     * 返回: 无返回值。
     */
    public static <T> void consume(Collection<T> col, Predicate<T> predicate, Consumer<T> consumer) {
        consume(col, predicate, identity(), consumer);
    }

    /**
     * 业务作用：过滤集合，并执行消费
     *
     * @param col 集合
     * @param consumer 消费函数
     * 返回: 无返回值。
     */
    public static <T> void consume(Collection<T> col, Consumer<T> consumer) {
        consume(col, nonNull(), consumer);
    }

    /**
     * 业务作用：过滤数组，并执行消费
     *
     * @param arr 集合
     * @param predicate 过滤函数
     * @param function 映射函数
     * @param consumer 消费函数
     * 返回: 无返回值。
     */
    public static <T, E> void consume(T[] arr, Predicate<T> predicate, Function<T, E> function, Consumer<E> consumer) {
        if (isEmpty(arr)) return;
        for (T t : arr) {
            if (predicate == null || predicate.test(t)) consumer.accept(function.apply(t));
        }
    }

    /**
     * 业务作用：过滤数组，并执行消费
     *
     * @param arr 集合
     * @param predicate 过滤函数
     * @param consumer 消费函数
     * 返回: 无返回值。
     */
    public static <T> void consume(T[] arr, Predicate<T> predicate, Consumer<T> consumer) {
        consume(arr, predicate, identity(), consumer);
    }

    /**
     * 业务作用：过滤数组，并执行消费
     *
     * @param arr 集合
     * @param consumer 消费函数
     * 返回: 无返回值。
     */
    public static <T> void consume(T[] arr, Consumer<T> consumer) {
        consume(arr, nonNull(), consumer);
    }

    /**
     * 业务作用：按条件筛选集合元素。
     *
     * @param col 源集合
     * @param predicate 筛选条件
     * @param collector 见上述说明
     * 返回: 满足条件的元素组成的新集合。
     */
    public static <T, A, R extends Collection<T>> R filter(
            Collection<T> col
            , Predicate<T> predicate
            , Collector<T, A, R> collector) {
        return toCollection(col, predicate, identity(), collector);
    }

    /**
     * 业务作用：按条件筛选集合元素。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param collector 见上述说明
     * 返回: 满足条件的元素组成的新集合。
     */
    public static <T, A, R extends Collection<T>> R filter(T[] arr, Predicate<T> predicate, Collector<T, A, R> collector) {
        return toCollection(arr, predicate, identity(), collector);
    }

    /**
     * 业务作用：按条件筛选并收集成 Set，顺带去重。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * 返回: 满足条件的元素集合。
     */
    public static <T> Set<T> filterToSet(Collection<T> col, Predicate<T> predicate) {
        return toSet(col, predicate, identity());
    }

    /**
     * 业务作用：按条件筛选并收集成 Set，顺带去重。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * 返回: 满足条件的元素集合。
     */
    public static <T> Set<T> filterToSet(T[] arr, Predicate<T> predicate) {
        return toSet(arr, predicate, identity());
    }

    /**
     * 业务作用：按条件筛选并收集成 List，保留原始顺序。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * 返回: 满足条件的元素列表。
     */
    public static <T> List<T> filterToList(Collection<T> col, Predicate<T> predicate) {
        return toList(col, predicate, identity());
    }

    /**
     * 业务作用：按条件筛选并收集成 List，保留原始顺序。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * 返回: 满足条件的元素列表。
     */
    public static <T> List<T> filterToList(T[] arr, Predicate<T> predicate) {
        return toList(arr, predicate, identity());
    }

    /**
     * 业务作用：把集合转成映射，键与值分别由映射函数产出。键冲突时由冲突函数决定取舍，不指定则后者覆盖前者；容器类型由构造器决定。
     *
     * 集合转Map
     *
     * @param col         集合
     * @param predicate   过滤函数
     * @param keyMapper   key映射函数
     * @param valueMapper value映射函数
     * @param operator    key冲突处理函数
     * @param supplier    生成Map实例函数
     * 返回: 转换后的映射，容器类型由构造器决定；键冲突按取舍函数处理。
     */
    public static <T, K, V, R extends Map<K, V>> R toMap(
            Collection<T> col
            , Predicate<T> predicate
            , Function<T, K> keyMapper
            , Function<T, V> valueMapper
            , BinaryOperator<V> operator
            , Supplier<R> supplier) {
        R map = supplier.get();
        for (T t : col) {
            if (predicate != null && !predicate.test(t)) continue;
            K key = keyMapper.apply(t);
            V val = valueMapper.apply(t);
            map.merge(key, val, operator);
        }
        return map;
    }

    /**
     * 业务作用：把集合转成映射，键与值分别由映射函数产出。键冲突时由冲突函数决定取舍，不指定则后者覆盖前者；容器类型由构造器决定。
     *
     * 数组转Map
     *
     * @param arr         数组
     * @param predicate   过滤函数
     * @param keyMapper   key映射函数
     * @param valueMapper value映射函数
     * @param operator    key冲突处理函数
     * @param supplier    生成Map实例函数
     * 返回: 转换后的映射，容器类型由构造器决定；键冲突按取舍函数处理。
     */
    public static <T, K, V, R extends Map<K, V>> R toMap(
            T[] arr
            , Predicate<T> predicate
            , Function<T, K> keyMapper
            , Function<T, V> valueMapper
            , BinaryOperator<V> operator
            , Supplier<R> supplier) {
        R map = supplier.get();
        for (T t : arr) {
            if (predicate != null && !predicate.test(t)) continue;
            K key = keyMapper.apply(t);
            V val = valueMapper.apply(t);
            map.merge(key, val, operator);
        }
        return map;
    }

    /**
     * 业务作用：把集合转成映射，键与值分别由映射函数产出。键冲突时由冲突函数决定取舍，不指定则后者覆盖前者；容器类型由构造器决定。
     *
     * 集合转Map
     *
     * @param col         集合
     * @param keyMapper   key映射函数
     * @param valueMapper value映射函数
     * @param supplier    生成Map实例函数
     * 返回: 转换后的映射，容器类型由构造器决定；键冲突按取舍函数处理。
     */
    public static <T, K, V, R extends Map<K, V>> R toMap(
            Collection<T> col
            , Function<T, K> keyMapper
            , Function<T, V> valueMapper
            , Supplier<R> supplier) {
        // (u, v) -> v
        // 表示key冲突时覆盖
        return toMap(col, nonNull(), keyMapper, valueMapper, lastWin(), supplier);
    }

    /**
     * 业务作用：把集合转成映射，键与值分别由映射函数产出。键冲突时由冲突函数决定取舍，不指定则后者覆盖前者；容器类型由构造器决定。
     *
     * 数组转Map
     *
     * @param arr         数组
     * @param keyMapper   key映射函数
     * @param valueMapper value映射函数
     * @param supplier    生成Map实例函数
     * 返回: 转换后的映射，容器类型由构造器决定；键冲突按取舍函数处理。
     */
    public static <T, K, V, R extends Map<K, V>> R toMap(
            T[] arr
            , Function<T, K> keyMapper
            , Function<T, V> valueMapper
            , Supplier<R> supplier) {
        // (u, v) -> v
        // 表示key冲突时覆盖
        return toMap(arr, nonNull(), keyMapper, valueMapper, lastWin(), supplier);
    }

    /**
     * 业务作用：把集合转成以元素自身为值的映射，供按键快速定位原элемент。
     *
     * @param col 源集合
     * @param keyMapper 元素到键的映射函数
     * @param supplier 目标容器的构造器
     * 返回: 键到元素自身的映射。
     */
    public static <T, K, R extends Map<K, T>> R toMap(Collection<T> col, Function<T, K> keyMapper, Supplier<R> supplier) {
        return toMap(col, keyMapper, identity(), supplier);
    }

    /**
     * 业务作用：把集合转成以元素自身为值的映射，供按键快速定位原элемент。
     *
     * @param arr 源数组
     * @param keyMapper 元素到键的映射函数
     * @param supplier 目标容器的构造器
     * 返回: 键到元素自身的映射。
     */
    public static <T, K, R extends Map<K, T>> R toMap(T[] arr, Function<T, K> keyMapper, Supplier<R> supplier) {
        return toMap(arr, keyMapper, identity(), supplier);
    }

    /**
     * 业务作用：把集合转成 HashMap，键与值分别由映射函数产出。
     *
     * 集合转 HashMap
     *
     * @param col         集合
     * @param predicate   过滤函数
     * @param keyMapper   key映射函数
     * @param valueMapper value映射函数
     * 返回: 转换后的映射；键顺序不保证。
     */
    public static <T, K, V> HashMap<K, V> toHashMap(
            Collection<T> col
            , Predicate<T> predicate
            , Function<T, K> keyMapper
            , Function<T, V> valueMapper) {
        // (u, v) -> v
        // 表示key冲突时覆盖
        return toMap(col, predicate, keyMapper, valueMapper, lastWin(), HashMap::new);
    }

    /**
     * 业务作用：把集合转成 HashMap，键与值分别由映射函数产出。
     *
     * 数组转 HashMap
     *
     * @param arr         数组
     * @param predicate   过滤函数
     * @param keyMapper   key映射函数
     * @param valueMapper value映射函数
     * 返回: 转换后的映射；键顺序不保证。
     */
    public static <T, K, V> HashMap<K, V> toHashMap(
            T[] arr
            , Predicate<T> predicate
            , Function<T, K> keyMapper
            , Function<T, V> valueMapper) {
        // (u, v) -> v
        // 表示key冲突时覆盖
        return toMap(arr, predicate, keyMapper, valueMapper, lastWin(), HashMap::new);
    }

    /**
     * 业务作用：把集合转成 HashMap。键重复时的取舍由合并策略决定，未指定时后者覆盖前者。
     *
     * @param col 源集合
     * @param keyMapper 元素到键的映射函数
     * @param valueMapper 元素到值的映射函数
     * 返回: 转换后的映射；键顺序不保证。
     */
    public static <T, K, V> HashMap<K, V> toHashMap(Collection<T> col, Function<T, K> keyMapper, Function<T, V> valueMapper) {
        return toHashMap(col, nonNull(), keyMapper, valueMapper);
    }

    /**
     * 业务作用：把集合转成 HashMap。键重复时的取舍由合并策略决定，未指定时后者覆盖前者。
     *
     * @param arr 源数组
     * @param keyMapper 元素到键的映射函数
     * @param valueMapper 元素到值的映射函数
     * 返回: 转换后的映射；键顺序不保证。
     */
    public static <T, K, V> HashMap<K, V> toHashMap(T[] arr, Function<T, K> keyMapper, Function<T, V> valueMapper) {
        return toHashMap(arr, nonNull(), keyMapper, valueMapper);
    }

    /**
     * 业务作用：把集合转成 HashMap。键重复时的取舍由合并策略决定，未指定时后者覆盖前者。
     *
     * @param col 源集合
     * @param keyMapper 元素到键的映射函数
     * 返回: 转换后的映射；键顺序不保证。
     */
    public static <T, K> HashMap<K, T> toHashMap(Collection<T> col, Function<T, K> keyMapper) {
        return toHashMap(col, keyMapper, identity());
    }

    /**
     * 业务作用：把集合转成 HashMap。键重复时的取舍由合并策略决定，未指定时后者覆盖前者。
     *
     * @param arr 源数组
     * @param keyMapper 元素到键的映射函数
     * 返回: 转换后的映射；键顺序不保证。
     */
    public static <T, K> HashMap<K, T> toHashMap(T[] arr, Function<T, K> keyMapper) {
        return toHashMap(arr, keyMapper, identity());
    }

    /**
     * 业务作用：把集合转成 LinkedHashMap，保留元素的原始顺序。
     *
     * 集合转 LinkedHashMap
     *
     * @param col         集合
     * @param predicate   过滤函数
     * @param keyMapper   key映射函数
     * @param valueMapper value映射函数
     * 返回: 保序的映射。
     */
    public static <T, K, V> LinkedHashMap<K, V> toLinkedMap(
            Collection<T> col
            , Predicate<T> predicate
            , Function<T, K> keyMapper
            , Function<T, V> valueMapper) {
        // (u, v) -> v
        // 表示key冲突时覆盖
        return toMap(col, predicate, keyMapper, valueMapper, lastWin(), LinkedHashMap::new);
    }

    /**
     * 业务作用：把集合转成 LinkedHashMap，保留元素的原始顺序。
     *
     * 数组转 LinkedHashMap
     *
     * @param arr         数组
     * @param predicate   过滤函数
     * @param keyMapper   key映射函数
     * @param valueMapper value映射函数
     * 返回: 保序的映射。
     */
    public static <T, K, V> LinkedHashMap<K, V> toLinkedMap(
            T[] arr
            , Predicate<T> predicate
            , Function<T, K> keyMapper
            , Function<T, V> valueMapper) {
        // (u, v) -> v
        // 表示key冲突时覆盖
        return toMap(arr, predicate, keyMapper, valueMapper, lastWin(), LinkedHashMap::new);
    }

    /**
     * 业务作用：把集合转成 LinkedHashMap，保留元素的原始顺序。
     *
     * @param col 源集合
     * @param keyMapper 元素到键的映射函数
     * @param valueMapper 元素到值的映射函数
     * 返回: 保序的映射。
     */
    public static <T, K, V> LinkedHashMap<K, V> toLinkedMap(
            Collection<T> col
            , Function<T, K> keyMapper
            , Function<T, V> valueMapper) {
        return toLinkedMap(col, nonNull(), keyMapper, valueMapper);
    }

    /**
     * 业务作用：把集合转成 LinkedHashMap，保留元素的原始顺序。
     *
     * @param arr 源数组
     * @param keyMapper 元素到键的映射函数
     * @param valueMapper 元素到值的映射函数
     * 返回: 保序的映射。
     */
    public static <T, K, V> LinkedHashMap<K, V> toLinkedMap(T[] arr, Function<T, K> keyMapper, Function<T, V> valueMapper) {
        return toLinkedMap(arr, nonNull(), keyMapper, valueMapper);
    }

    /**
     * 业务作用：把集合转成 LinkedHashMap，保留元素的原始顺序。
     *
     * @param col 源集合
     * @param keyMapper 元素到键的映射函数
     * 返回: 保序的映射。
     */
    public static <T, K> LinkedHashMap<K, T> toLinkedMap(Collection<T> col, Function<T, K> keyMapper) {
        return toLinkedMap(col, keyMapper, identity());
    }

    /**
     * 业务作用：把集合转成 LinkedHashMap，保留元素的原始顺序。
     *
     * @param arr 源数组
     * @param keyMapper 元素到键的映射函数
     * 返回: 保序的映射。
     */
    public static <T, K> LinkedHashMap<K, T> toLinkedMap(T[] arr, Function<T, K> keyMapper) {
        return toLinkedMap(arr, keyMapper, identity());
    }

    /**
     * 业务作用：把集合转成 ConcurrentHashMap，供后续并发读写。
     *
     * 集合转 ConcurrentHashMap
     *
     * @param col         集合
     * @param predicate   过滤函数
     * @param keyMapper   key映射函数
     * @param valueMapper value映射函数
     * 返回: 线程安全的映射；注意其不接受 null 键与 null 值。
     */
    public static <T, K, V> ConcurrentMap<K, V> toConcurrentMap(
            Collection<T> col
            , Predicate<T> predicate
            , Function<T, K> keyMapper
            , Function<T, V> valueMapper) {
        // (u, v) -> v
        // 表示key冲突时覆盖
        return toMap(col, predicate, keyMapper, valueMapper, lastWin(), ConcurrentHashMap::new);
    }

    /**
     * 业务作用：把集合转成 ConcurrentHashMap，供后续并发读写。
     *
     * 数组转 ConcurrentHashMap
     *
     * @param arr         数组
     * @param predicate   过滤函数
     * @param keyMapper   key映射函数
     * @param valueMapper value映射函数
     * 返回: 线程安全的映射；注意其不接受 null 键与 null 值。
     */
    public static <T, K, V> ConcurrentMap<K, V> toConcurrentMap(
            T[] arr
            , Predicate<T> predicate
            , Function<T, K> keyMapper
            , Function<T, V> valueMapper) {
        // (u, v) -> v
        // 表示key冲突时覆盖
        return toMap(arr, predicate, keyMapper, valueMapper, lastWin(), ConcurrentHashMap::new);
    }

    /**
     * 业务作用：把集合转成 ConcurrentHashMap，供后续并发读写。
     *
     * @param col 源集合
     * @param keyMapper 元素到键的映射函数
     * @param valueMapper 元素到值的映射函数
     * 返回: 线程安全的映射；注意其不接受 null 键与 null 值。
     */
    public static <T, K, V> ConcurrentMap<K, V> toConcurrentMap(
            Collection<T> col
            , Function<T, K> keyMapper
            , Function<T, V> valueMapper) {
        return toConcurrentMap(col, nonNull(), keyMapper, valueMapper);
    }

    /**
     * 业务作用：把集合转成 ConcurrentHashMap，供后续并发读写。
     *
     * @param arr 源数组
     * @param keyMapper 元素到键的映射函数
     * @param valueMapper 元素到值的映射函数
     * 返回: 线程安全的映射；注意其不接受 null 键与 null 值。
     */
    public static <T, K, V> ConcurrentMap<K, V> toConcurrentMap(T[] arr, Function<T, K> keyMapper, Function<T, V> valueMapper) {
        return toConcurrentMap(arr, nonNull(), keyMapper, valueMapper);
    }

    /**
     * 业务作用：把集合转成 ConcurrentHashMap，供后续并发读写。
     *
     * @param col 源集合
     * @param keyMapper 元素到键的映射函数
     * 返回: 线程安全的映射；注意其不接受 null 键与 null 值。
     */
    public static <T, K> ConcurrentMap<K, T> toConcurrentMap(Collection<T> col, Function<T, K> keyMapper) {
        return toConcurrentMap(col, keyMapper, identity());
    }

    /**
     * 业务作用：把集合转成 ConcurrentHashMap，供后续并发读写。
     *
     * @param arr 源数组
     * @param keyMapper 元素到键的映射函数
     * 返回: 线程安全的映射；注意其不接受 null 键与 null 值。
     */
    public static <T, K> ConcurrentMap<K, T> toConcurrentMap(T[] arr, Function<T, K> keyMapper) {
        return toConcurrentMap(arr, keyMapper, identity());
    }

    /**
     * 业务作用：取出集合中每个元素的指定属性并用分隔符拼接成一个字符串，避免调用方手工拼接与处理首尾分隔符。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * @param delimiter 各段之间的分隔符
     * 返回: 拼接后的字符串；集合为空时返回空串。
     */
    public static <T> String join(Collection<T> col, Predicate<T> predicate, Function<T, String> mapper, CharSequence delimiter) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (T t : col) {
            if (predicate != null && !predicate.test(t)) continue;
            if (!first) sb.append(delimiter);
            sb.append(mapper.apply(t));
            first = false;
        }
        return sb.toString();
    }

    /**
     * 业务作用：取出集合中每个元素的指定属性并用分隔符拼接成一个字符串，避免调用方手工拼接与处理首尾分隔符。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * @param delimiter 各段之间的分隔符
     * 返回: 拼接后的字符串；集合为空时返回空串。
     */
    public static <T> String join(T[] arr, Predicate<T> predicate, Function<T, String> mapper, CharSequence delimiter) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (T t : arr) {
            if (predicate != null && !predicate.test(t)) continue;
            if (!first) sb.append(delimiter);
            sb.append(mapper.apply(t));
            first = false;
        }
        return sb.toString();
    }

    /**
     * 业务作用：取出集合中每个元素的指定属性并用分隔符拼接成一个字符串，避免调用方手工拼接与处理首尾分隔符。
     *
     * @param col 源集合
     * @param mapper 元素到目标值的映射函数
     * @param delimiter 各段之间的分隔符
     * 返回: 拼接后的字符串；集合为空时返回空串。
     */
    public static <T> String join(Collection<T> col, Function<T, String> mapper, CharSequence delimiter) {
        return join(col, nonNull(), mapper, delimiter);
    }

    /**
     * 业务作用：取出集合中每个元素的指定属性并用分隔符拼接成一个字符串，避免调用方手工拼接与处理首尾分隔符。
     *
     * @param arr 源数组
     * @param mapper 元素到目标值的映射函数
     * @param delimiter 各段之间的分隔符
     * 返回: 拼接后的字符串；集合为空时返回空串。
     */
    public static <T> String join(T[] arr, Function<T, String> mapper, CharSequence delimiter) {
        return join(arr, nonNull(), mapper, delimiter);
    }

    /**
     * 业务作用：取出集合中每个元素的指定属性并用分隔符拼接成一个字符串，避免调用方手工拼接与处理首尾分隔符。
     *
     * @param col 源集合
     * @param delimiter 各段之间的分隔符
     * 返回: 拼接后的字符串；集合为空时返回空串。
     */
    public static <T> String join(Collection<T> col, CharSequence delimiter) {
        return join(col, ObjMprUtils::toString, delimiter);
    }

    /**
     * 业务作用：取出集合中每个元素的指定属性并用分隔符拼接成一个字符串，避免调用方手工拼接与处理首尾分隔符。
     *
     * @param arr 源数组
     * @param delimiter 各段之间的分隔符
     * 返回: 拼接后的字符串；集合为空时返回空串。
     */
    public static <T> String join(T[] arr, CharSequence delimiter) {
        return join(arr, ObjMprUtils::toString, delimiter);
    }

    /**
     * 业务作用：取出集合中每个元素的指定属性并用分隔符拼接成一个字符串，避免调用方手工拼接与处理首尾分隔符。
     *
     * @param col 源集合
     * 返回: 拼接后的字符串；集合为空时返回空串。
     */
    public static <T> String join(Collection<T> col) {
        return join(col, StringUtils.EMPTY);
    }

    /**
     * 业务作用：取出集合中每个元素的指定属性并用分隔符拼接成一个字符串，避免调用方手工拼接与处理首尾分隔符。
     *
     * @param arr 源数组
     * 返回: 拼接后的字符串；集合为空时返回空串。
     */
    public static <T> String join(T... arr) {
        return join(arr, StringUtils.EMPTY);
    }

    /**
     * 业务作用：取出集合中每个元素的指定属性并用分隔符拼接成一个字符串，避免调用方手工拼接与处理首尾分隔符。
     *
     * @param arr 源数组
     * @param delimiter 各段之间的分隔符
     * 返回: 拼接后的字符串；集合为空时返回空串。
     */
    public static String join(byte[] arr, CharSequence delimiter) {
        if (arr.length == 0) return "";
        StringBuilder sb = new StringBuilder(arr.length * 4);
        sb.append(arr[0]);
        for (int i = 1; i < arr.length; i++) sb.append(delimiter).append(arr[i]);
        return sb.toString();
    }

    /**
     * 业务作用：取出集合中每个元素的指定属性并用分隔符拼接成一个字符串，避免调用方手工拼接与处理首尾分隔符。
     *
     * @param arr 源数组
     * @param delimiter 各段之间的分隔符
     * 返回: 拼接后的字符串；集合为空时返回空串。
     */
    public static String join(short[] arr, CharSequence delimiter) {
        if (arr.length == 0) return "";
        StringBuilder sb = new StringBuilder(arr.length * 6);
        sb.append(arr[0]);
        for (int i = 1; i < arr.length; i++) sb.append(delimiter).append(arr[i]);
        return sb.toString();
    }

    /**
     * 业务作用：取出集合中每个元素的指定属性并用分隔符拼接成一个字符串，避免调用方手工拼接与处理首尾分隔符。
     *
     * @param arr 源数组
     * @param delimiter 各段之间的分隔符
     * 返回: 拼接后的字符串；集合为空时返回空串。
     */
    public static String join(int[] arr, CharSequence delimiter) {
        if (arr.length == 0) return "";
        StringBuilder sb = new StringBuilder(arr.length * 8);
        sb.append(arr[0]);
        for (int i = 1; i < arr.length; i++) sb.append(delimiter).append(arr[i]);
        return sb.toString();
    }

    /**
     * 业务作用：取出集合中每个元素的指定属性并用分隔符拼接成一个字符串，避免调用方手工拼接与处理首尾分隔符。
     *
     * @param arr 源数组
     * @param delimiter 各段之间的分隔符
     * 返回: 拼接后的字符串；集合为空时返回空串。
     */
    public static String join(long[] arr, CharSequence delimiter) {
        if (arr.length == 0) return "";
        StringBuilder sb = new StringBuilder(arr.length * 12);
        sb.append(arr[0]);
        for (int i = 1; i < arr.length; i++) sb.append(delimiter).append(arr[i]);
        return sb.toString();
    }

    /**
     * 业务作用：取出集合中每个元素的指定属性并用分隔符拼接成一个字符串，避免调用方手工拼接与处理首尾分隔符。
     *
     * @param arr 源数组
     * @param delimiter 各段之间的分隔符
     * 返回: 拼接后的字符串；集合为空时返回空串。
     */
    public static String join(float[] arr, CharSequence delimiter) {
        if (arr.length == 0) return "";
        StringBuilder sb = new StringBuilder(arr.length * 10);
        sb.append(arr[0]);
        for (int i = 1; i < arr.length; i++) sb.append(delimiter).append(arr[i]);
        return sb.toString();
    }

    /**
     * 业务作用：取出集合中每个元素的指定属性并用分隔符拼接成一个字符串，避免调用方手工拼接与处理首尾分隔符。
     *
     * @param arr 源数组
     * @param delimiter 各段之间的分隔符
     * 返回: 拼接后的字符串；集合为空时返回空串。
     */
    public static String join(double[] arr, CharSequence delimiter) {
        if (arr.length == 0) return "";
        StringBuilder sb = new StringBuilder(arr.length * 12);
        sb.append(arr[0]);
        for (int i = 1; i < arr.length; i++) sb.append(delimiter).append(arr[i]);
        return sb.toString();
    }

    /**
     * 业务作用：按分组函数对集合分组，组内结果再由收集器归约，映射容器类型由工厂决定。
     *
     * 集合分组
     *
     * @param col        集合
     * @param predicate  过滤函数
     * @param mapper     分组函数
     * @param mapFactory Map创建函数
     * @param collector  分组后结果收集函数
     * 返回: 分组键到归约结果的映射。
     */
    public static <T, K, A, D, M extends Map<K, D>> Map<K, D> groupingBy(
            Collection<T> col
            , Predicate<T> predicate
            , Function<T, K> mapper
            , Supplier<M> mapFactory
            , Collector<T, A, D> collector) {
        M map = mapFactory.get();
        BiConsumer<A, T> accumulator = collector.accumulator();
        Function<A, D> finisher = collector.finisher();
        Map<K, A> accMap = new LinkedHashMap<>();
        for (T t : col) {
            if (predicate != null && !predicate.test(t)) continue;
            K key = mapper.apply(t);
            A container = accMap.computeIfAbsent(key, k -> collector.supplier().get());
            accumulator.accept(container, t);
        }
        for (Map.Entry<K, A> e : accMap.entrySet()) {
            map.put(e.getKey(), finisher.apply(e.getValue()));
        }
        return map;
    }

    /**
     * 业务作用：按分组函数对集合分组，组内结果再由收集器归约，映射容器类型由工厂决定。
     *
     * 数组分组
     *
     * @param arr        数组
     * @param predicate  过滤函数
     * @param mapper     分组函数
     * @param mapFactory Map创建函数
     * @param collector  分组后结果收集函数
     * 返回: 分组键到归约结果的映射。
     */
    public static <T, K, A, D, M extends Map<K, D>> Map<K, D> groupingBy(
            T[] arr
            , Predicate<T> predicate
            , Function<T, K> mapper
            , Supplier<M> mapFactory
            , Collector<T, A, D> collector) {
        M map = mapFactory.get();
        BiConsumer<A, T> accumulator = collector.accumulator();
        Function<A, D> finisher = collector.finisher();
        Map<K, A> accMap = new LinkedHashMap<>();
        for (T t : arr) {
            if (predicate != null && !predicate.test(t)) continue;
            K key = mapper.apply(t);
            A container = accMap.computeIfAbsent(key, k -> collector.supplier().get());
            accumulator.accept(container, t);
        }
        for (Map.Entry<K, A> e : accMap.entrySet()) {
            map.put(e.getKey(), finisher.apply(e.getValue()));
        }
        return map;
    }

    /**
     * 业务作用：按分组键对集合分组。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * @param collector 见上述说明
     * 返回: 分组键到元素集合的映射。
     */
    public static <T, K, A, D> Map<K, D> groupingBy(Collection<T> col, Predicate<T> predicate, Function<T, K> mapper, Collector<T, A, D> collector) {
        return groupingBy(col, predicate, mapper, LinkedHashMap::new, collector);
    }

    /**
     * 业务作用：按分组键对集合分组。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * @param collector 见上述说明
     * 返回: 分组键到元素集合的映射。
     */
    public static <T, K, A, D> Map<K, D> groupingBy(T[] arr, Predicate<T> predicate, Function<T, K> mapper, Collector<T, A, D> collector) {
        return groupingBy(arr, predicate, mapper, LinkedHashMap::new, collector);
    }

    /**
     * 业务作用：按分组键对集合分组。
     *
     * @param col 源集合
     * @param mapper 元素到目标值的映射函数
     * @param collector 见上述说明
     * 返回: 分组键到元素集合的映射。
     */
    public static <T, K, A, D> Map<K, D> groupingBy(Collection<T> col, Function<T, K> mapper, Collector<T, A, D> collector) {
        return groupingBy(col, nonNull(), mapper, collector);
    }

    /**
     * 业务作用：按分组键对集合分组。
     *
     * @param arr 源数组
     * @param mapper 元素到目标值的映射函数
     * @param collector 见上述说明
     * 返回: 分组键到元素集合的映射。
     */
    public static <T, K, A, D> Map<K, D> groupingBy(T[] arr, Function<T, K> mapper, Collector<T, A, D> collector) {
        return groupingBy(arr, nonNull(), mapper, collector);
    }

    /**
     * 业务作用：按分组键把元素归入各自的 List，同组内保留原始顺序。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * @param valer 见上述说明
     * 返回: 分组键到元素列表的映射。
     */
    public static <T, K, E> Map<K, List<E>> groupToList(Collection<T> col, Predicate<T> predicate, Function<T, K> mapper, Function<T, E> valer) {
        return groupingBy(col, predicate, mapper, Collector.of(ArrayList::new, (c, t) -> c.add(valer.apply(t)), (left, right) -> {
            left.addAll(right);
            return left;
        }));
    }

    /**
     * 业务作用：按分组键把元素归入各自的 List，同组内保留原始顺序。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * @param valer 见上述说明
     * 返回: 分组键到元素列表的映射。
     */
    public static <T, K, E> Map<K, List<E>> groupToList(T[] arr, Predicate<T> predicate, Function<T, K> mapper, Function<T, E> valer) {
        return groupingBy(arr, predicate, mapper, Collector.of(ArrayList::new, (c, t) -> c.add(valer.apply(t)), (left, right) -> {
            left.addAll(right);
            return left;
        }));
    }

    /**
     * 业务作用：按分组键把元素归入各自的 List，同组内保留原始顺序。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 分组键到元素列表的映射。
     */
    public static <T, K> Map<K, List<T>> groupToList(Collection<T> col, Predicate<T> predicate, Function<T, K> mapper) {
        return groupingBy(col, predicate, mapper, Collectors.toList());
    }

    /**
     * 业务作用：按分组键把元素归入各自的 List，同组内保留原始顺序。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 分组键到元素列表的映射。
     */
    public static <T, K> Map<K, List<T>> groupToList(T[] arr, Predicate<T> predicate, Function<T, K> mapper) {
        return groupingBy(arr, predicate, mapper, Collectors.toList());
    }

    /**
     * 业务作用：按分组键把元素归入各自的 List，同组内保留原始顺序。
     *
     * @param col 源集合
     * @param mapper 元素到目标值的映射函数
     * 返回: 分组键到元素列表的映射。
     */
    public static <T, K> Map<K, List<T>> groupToList(Collection<T> col, Function<T, K> mapper) {
        return groupToList(col, nonNull(), mapper);
    }

    /**
     * 业务作用：按分组键把元素归入各自的 List，同组内保留原始顺序。
     *
     * @param arr 源数组
     * @param mapper 元素到目标值的映射函数
     * 返回: 分组键到元素列表的映射。
     */
    public static <T, K> Map<K, List<T>> groupToList(T[] arr, Function<T, K> mapper) {
        return groupToList(arr, nonNull(), mapper);
    }

    /**
     * 业务作用：按分组键把元素归入各自的 Set，同组内自动去重。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * @param valer 见上述说明
     * 返回: 分组键到元素集合的映射。
     */
    public static <T, K, E> Map<K, Set<E>> groupToSet(Collection<T> col, Predicate<T> predicate, Function<T, K> mapper, Function<T, E> valer) {
        return groupingBy(col, predicate, mapper, Collector.of(HashSet::new, (c, t) -> c.add(valer.apply(t)), (left, right) -> {
            left.addAll(right);
            return left;
        }));
    }

    /**
     * 业务作用：按分组键把元素归入各自的 Set，同组内自动去重。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * @param valer 见上述说明
     * 返回: 分组键到元素集合的映射。
     */
    public static <T, K, E> Map<K, Set<E>> groupToSet(T[] arr, Predicate<T> predicate, Function<T, K> mapper, Function<T, E> valer) {
        return groupingBy(arr, predicate, mapper, Collector.of(HashSet::new, (c, t) -> c.add(valer.apply(t)), (left, right) -> {
            left.addAll(right);
            return left;
        }));
    }

    /**
     * 业务作用：按分组键把元素归入各自的 Set，同组内自动去重。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 分组键到元素集合的映射。
     */
    public static <T, K> Map<K, Set<T>> groupToSet(Collection<T> col, Predicate<T> predicate, Function<T, K> mapper) {
        return groupingBy(col, predicate, mapper, Collectors.toSet());
    }

    /**
     * 业务作用：按分组键把元素归入各自的 Set，同组内自动去重。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 分组键到元素集合的映射。
     */
    public static <T, K> Map<K, Set<T>> groupToSet(T[] arr, Predicate<T> predicate, Function<T, K> mapper) {
        return groupingBy(arr, predicate, mapper, Collectors.toSet());
    }

    /**
     * 业务作用：按分组键把元素归入各自的 Set，同组内自动去重。
     *
     * @param col 源集合
     * @param mapper 元素到目标值的映射函数
     * 返回: 分组键到元素集合的映射。
     */
    public static <T, K> Map<K, Set<T>> groupToSet(Collection<T> col, Function<T, K> mapper) {
        return groupToSet(col, nonNull(), mapper);
    }

    /**
     * 业务作用：按分组键把元素归入各自的 Set，同组内自动去重。
     *
     * @param arr 源数组
     * @param mapper 元素到目标值的映射函数
     * 返回: 分组键到元素集合的映射。
     */
    public static <T, K> Map<K, Set<T>> groupToSet(T[] arr, Function<T, K> mapper) {
        return groupToSet(arr, nonNull(), mapper);
    }

    /**
     * 业务作用：把任意数值对象转换为 BigDecimal，供统一走高精度累加。
     *
     * @param e 见上述说明
     * 返回: 转换后的 BigDecimal；入参为 null 时返回 null。
     */
    private static <E extends Number> BigDecimal toBigDecimal(E e) {
        if (e instanceof BigDecimal) {
            return (BigDecimal) e;
        }
        if (e instanceof Long || e instanceof Integer || e instanceof Byte) {
            return BigDecimal.valueOf(e.longValue());
        }
        return new BigDecimal(e.toString());
    }

    /**
     * 业务作用：对集合中每个元素的指定属性求和，用 BigDecimal 累加以避免浮点误差。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 求和结果；集合为空或全部属性为 null 时返回 BigDecimal.ZERO。
     */
    public static <T, E extends Number> BigDecimal sumBigDecimal(Collection<T> col, Predicate<T> predicate, Function<T, E> mapper) {
        if (isEmpty(col)) {
            return BigDecimal.ZERO;
        }
        Stream<T> stream = col.stream();
        if (Objects.nonNull(predicate)) {
            stream = stream.filter(predicate);
        }
        return stream.map(mapper).map(ColUtils::toBigDecimal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 业务作用：对集合中每个元素的指定属性求和，用 BigDecimal 累加以避免浮点误差。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 求和结果；集合为空或全部属性为 null 时返回 BigDecimal.ZERO。
     */
    public static <T, E extends Number> BigDecimal sumBigDecimal(T[] arr, Predicate<T> predicate, Function<T, E> mapper) {
        if (isEmpty(arr)) {
            return BigDecimal.ZERO;
        }
        Stream<T> stream = Stream.of(arr);
        if (Objects.nonNull(predicate)) {
            stream = stream.filter(predicate);
        }
        return stream.map(mapper).map(ColUtils::toBigDecimal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 业务作用：对集合中每个元素的指定属性求和，用 BigDecimal 累加以避免浮点误差。
     *
     * @param col 源集合
     * @param mapper 元素到目标值的映射函数
     * 返回: 求和结果；集合为空或全部属性为 null 时返回 BigDecimal.ZERO。
     */
    public static <T, E extends Number> BigDecimal sumBigDecimal(Collection<T> col, Function<T, E> mapper) {
        return sumBigDecimal(col, nonNull(), mapper);
    }

    /**
     * 业务作用：对集合中每个元素的指定属性求和，用 BigDecimal 累加以避免浮点误差。
     *
     * @param arr 源数组
     * @param mapper 元素到目标值的映射函数
     * 返回: 求和结果；集合为空或全部属性为 null 时返回 BigDecimal.ZERO。
     */
    public static <T, E extends Number> BigDecimal sumBigDecimal(T[] arr, Function<T, E> mapper) {
        return sumBigDecimal(arr, nonNull(), mapper);
    }

    /**
     * 业务作用：对集合中每个元素的指定属性求和，用 BigDecimal 累加以避免浮点误差。
     *
     * @param col 源集合
     * 返回: 求和结果；集合为空或全部属性为 null 时返回 BigDecimal.ZERO。
     */
    public static <T extends Number> BigDecimal sumBigDecimal(Collection<T> col) {
        return sumBigDecimal(col, identity());
    }

    /**
     * 业务作用：对集合中每个元素的指定属性求和，用 BigDecimal 累加以避免浮点误差。
     *
     * @param arr 源数组
     * 返回: 求和结果；集合为空或全部属性为 null 时返回 BigDecimal.ZERO。
     */
    public static <T extends Number> BigDecimal sumBigDecimal(T[] arr) {
        return sumBigDecimal(arr, identity());
    }

    /**
     * 业务作用：对集合中每个元素的指定属性按 long 求和。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 求和结果；集合为空时返回 0。
     */
    public static <T, E extends Number> Long sumLong(Collection<T> col, Predicate<T> predicate, Function<T, E> mapper) {
        if (isEmpty(col)) {
            return 0L;
        }
        Stream<T> stream = col.stream();
        if (Objects.nonNull(predicate)) {
            stream = stream.filter(predicate);
        }
        return stream.map(mapper).map(t -> (Long) t).reduce(0L, Long::sum);
    }

    /**
     * 业务作用：对集合中每个元素的指定属性按 long 求和。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 求和结果；集合为空时返回 0。
     */
    public static <T, E extends Number> Long sumLong(T[] arr, Predicate<T> predicate, Function<T, E> mapper) {
        if (isEmpty(arr)) {
            return 0L;
        }
        Stream<T> stream = Stream.of(arr);
        if (Objects.nonNull(predicate)) {
            stream = stream.filter(predicate);
        }
        return stream.map(mapper).map(t -> (Long) t).reduce(0L, Long::sum);
    }

    /**
     * 业务作用：对集合中每个元素的指定属性按 long 求和。
     *
     * @param col 源集合
     * @param mapper 元素到目标值的映射函数
     * 返回: 求和结果；集合为空时返回 0。
     */
    public static <T, E extends Number> Long sumLong(Collection<T> col, Function<T, E> mapper) {
        return sumLong(col, nonNull(), mapper);
    }

    /**
     * 业务作用：对集合中每个元素的指定属性按 long 求和。
     *
     * @param arr 源数组
     * @param mapper 元素到目标值的映射函数
     * 返回: 求和结果；集合为空时返回 0。
     */
    public static <T, E extends Number> Long sumLong(T[] arr, Function<T, E> mapper) {
        return sumLong(arr, nonNull(), mapper);
    }

    /**
     * 业务作用：对集合中每个元素的指定属性按 long 求和。
     *
     * @param col 源集合
     * 返回: 求和结果；集合为空时返回 0。
     */
    public static <T extends Number> Long sumLong(Collection<T> col) {
        return sumLong(col, identity());
    }

    /**
     * 业务作用：对集合中每个元素的指定属性按 long 求和。
     *
     * @param arr 源数组
     * 返回: 求和结果；集合为空时返回 0。
     */
    public static <T extends Number> Long sumLong(T[] arr) {
        return sumLong(arr, identity());
    }

    /**
     * 业务作用：对集合中每个元素的指定属性按 int 求和。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 求和结果；集合为空时返回 0。
     */
    public static <T, E extends Number> Integer sumInteger(Collection<T> col, Predicate<T> predicate, Function<T, E> mapper) {
        if (isEmpty(col)) {
            return 0;
        }
        Stream<T> stream = col.stream();
        if (Objects.nonNull(predicate)) {
            stream = stream.filter(predicate);
        }
        return stream.map(mapper).map(t -> (Integer) t).reduce(0, Integer::sum);
    }

    /**
     * 业务作用：对集合中每个元素的指定属性按 int 求和。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 求和结果；集合为空时返回 0。
     */
    public static <T, E extends Number> Integer sumInteger(T[] arr, Predicate<T> predicate, Function<T, E> mapper) {
        if (isEmpty(arr)) {
            return 0;
        }
        Stream<T> stream = Stream.of(arr);
        if (Objects.nonNull(predicate)) {
            stream = stream.filter(predicate);
        }
        return stream.map(mapper).map(t -> (Integer) t).reduce(0, Integer::sum);
    }

    /**
     * 业务作用：对集合中每个元素的指定属性按 int 求和。
     *
     * @param col 源集合
     * @param mapper 元素到目标值的映射函数
     * 返回: 求和结果；集合为空时返回 0。
     */
    public static <T, E extends Number> Integer sumInteger(Collection<T> col, Function<T, E> mapper) {
        return sumInteger(col, nonNull(), mapper);
    }

    /**
     * 业务作用：对集合中每个元素的指定属性按 int 求和。
     *
     * @param arr 源数组
     * @param mapper 元素到目标值的映射函数
     * 返回: 求和结果；集合为空时返回 0。
     */
    public static <T, E extends Number> Integer sumInteger(T[] arr, Function<T, E> mapper) {
        return sumInteger(arr, nonNull(), mapper);
    }

    /**
     * 业务作用：对集合中每个元素的指定属性按 int 求和。
     *
     * @param col 源集合
     * 返回: 求和结果；集合为空时返回 0。
     */
    public static <T extends Number> Integer sumInteger(Collection<T> col) {
        return sumInteger(col, identity());
    }

    /**
     * 业务作用：对集合中每个元素的指定属性按 int 求和。
     *
     * @param arr 源数组
     * 返回: 求和结果；集合为空时返回 0。
     */
    public static <T extends Number> Integer sumInteger(T[] arr) {
        return sumInteger(arr, identity());
    }

    /**
     * 业务作用：对集合中每个元素的指定属性求和。
     *
     * @param arr 源数组
     * 返回: 求和结果；集合为空时返回零值。
     */
    public static int sum(byte... arr) {
        int mnt = 0;
        for (int a : arr) {
            mnt += a;
        }
        return mnt;
    }

    /**
     * 业务作用：对集合中每个元素的指定属性求和。
     *
     * @param arr 源数组
     * 返回: 求和结果；集合为空时返回零值。
     */
    public static int sum(short... arr) {
        int mnt = 0;
        for (int a : arr) {
            mnt += a;
        }
        return mnt;
    }

    /**
     * 业务作用：对集合中每个元素的指定属性求和。
     *
     * @param arr 源数组
     * 返回: 求和结果；集合为空时返回零值。
     */
    public static int sum(int... arr) {
        int mnt = 0;
        for (int a : arr) {
            mnt += a;
        }
        return mnt;
    }

    /**
     * 业务作用：对集合中每个元素的指定属性求和。
     *
     * @param arr 源数组
     * 返回: 求和结果；集合为空时返回零值。
     */
    public static long sum(long... arr) {
        long mnt = 0;
        for (long a : arr) {
            mnt += a;
        }
        return mnt;
    }

    /**
     * 业务作用：对集合中每个元素的指定属性求和。
     *
     * @param arr 源数组
     * 返回: 求和结果；集合为空时返回零值。
     */
    public static BigDecimal sum(float... arr) {
        BigDecimal mnt = BigDecimal.ZERO;
        for (float a : arr) {
            mnt = mnt.add(new BigDecimal(String.valueOf(a)));
        }
        return mnt;
    }

    /**
     * 业务作用：对集合中每个元素的指定属性求和。
     *
     * @param arr 源数组
     * 返回: 求和结果；集合为空时返回零值。
     */
    public static BigDecimal sum(double... arr) {
        BigDecimal mnt = BigDecimal.ZERO;
        for (double a : arr) {
            mnt = mnt.add(new BigDecimal(String.valueOf(a)));
        }
        return mnt;
    }

    /**
     * 业务作用：按固定长度把集合切成若干子集合，用于分批提交、批量落库等场景。
     *
     * @param iter 见上述说明
     * @param length 每片的元素个数
     * 返回: 子集合列表；最后一片可能不足指定长度。
     */
    public static <T, C extends Collection<T>> ArrayList<C> sliceByLength(Iterable<T> iter, int length) {
        C col;
        if (iter instanceof Collection) {
            col = (C) iter;
        } else {
            col = (C) new ArrayList<>();
            // 将非Collection的元素写入ArrayList
            iter.forEach(col::add);
        }
        return sliceByLength(col, length);
    }

    /**
     * 业务作用：按固定长度把集合切成若干子集合，用于分批提交、批量落库等场景。
     *
     * @param col 源集合
     * @param length 每片的元素个数
     * 返回: 子集合列表；最后一片可能不足指定长度。
     */
    public static <T, C extends Collection<T>> ArrayList<C> sliceByLength(C col, int length) {

        ArrayList<C> cs = new ArrayList<>();
        if (isEmpty(col) || col.size() <= length) {
            cs.add(col);
            return cs;
        }

        // 数组长度
        int arrLen;
        if (col.size() % length == 0) {
            arrLen = col.size() / length;
        } else {
            arrLen = col.size() / length + 1;
        }

        List<T> list;
        if (col instanceof List) {
            list = (List<T>) col;
        } else {
            list = new ArrayList<>(col);
        }

        for (int i = 0; i < arrLen; i++) {
            // 创建集合
            C c = (C) ReflectUtils.newInstance(col.getClass());
            for (int j = i * length, index = (i + 1) * length; j < index && j < list.size(); j++) {
                c.add(list.get(j));
            }
            // 赋值
            cs.add(c);
        }
        return cs;
    }

    /**
     * 业务作用：按下标区间截取数组或集合的一段，用于分页与分批处理。
     *
     * @param arr 源数组
     * @param start 见上述说明
     * 返回: 截取出的新集合；区间为空时返回空集合，不返回 null。
     */
    public static <T> T[] slice(T[] arr, int start) {
        return slice(arr, start, arr.length);
    }

    /**
     * 业务作用：按下标区间截取数组或集合的一段，用于分页与分批处理。
     *
     * @param arr 源数组
     * @param start 见上述说明
     * @param end 见上述说明
     * 返回: 截取出的新集合；区间为空时返回空集合，不返回 null。
     */
    public static <T> T[] slice(T[] arr, int start, int end) {
        if (start >= arr.length || start >= end) {
            return ReflectUtils.newArray(arr.getClass().getComponentType(), 0);
        }
        int length = Math.min(end, arr.length) - start;
        T[] as = ReflectUtils.newArray(arr.getClass().getComponentType(), length);
        System.arraycopy(arr, start, as, 0, length);
        return as;
    }

    /**
     * 业务作用：按下标区间截取数组或集合的一段，用于分页与分批处理。
     *
     * @param arr 源数组
     * @param start 见上述说明
     * 返回: 截取出的新集合；区间为空时返回空集合，不返回 null。
     */
    public static long[] slice(long[] arr, int start) {
        return slice(arr, start, arr.length);
    }

    /**
     * 业务作用：按下标区间截取数组或集合的一段，用于分页与分批处理。
     *
     * @param arr 源数组
     * @param start 见上述说明
     * @param end 见上述说明
     * 返回: 截取出的新集合；区间为空时返回空集合，不返回 null。
     */
    public static long[] slice(long[] arr, int start, int end) {
        if (start >= arr.length || start >= end) {
            return new long[0];
        }
        int length = Math.min(end, arr.length) - start;
        long[] as = new long[length];
        System.arraycopy(arr, start, as, 0, length);
        return as;
    }

    /**
     * 业务作用：按下标区间截取数组或集合的一段，用于分页与分批处理。
     *
     * @param arr 源数组
     * @param start 见上述说明
     * 返回: 截取出的新集合；区间为空时返回空集合，不返回 null。
     */
    public static int[] slice(int[] arr, int start) {
        return slice(arr, start, arr.length);
    }

    /**
     * 业务作用：按下标区间截取数组或集合的一段，用于分页与分批处理。
     *
     * @param arr 源数组
     * @param start 见上述说明
     * @param end 见上述说明
     * 返回: 截取出的新集合；区间为空时返回空集合，不返回 null。
     */
    public static int[] slice(int[] arr, int start, int end) {
        if (start >= arr.length || start >= end) {
            return new int[0];
        }
        int length = Math.min(end, arr.length) - start;
        int[] as = new int[length];
        System.arraycopy(arr, start, as, 0, length);
        return as;
    }

    /**
     * 业务作用：按下标区间截取数组或集合的一段，用于分页与分批处理。
     *
     * @param arr 源数组
     * @param start 见上述说明
     * 返回: 截取出的新集合；区间为空时返回空集合，不返回 null。
     */
    public static byte[] slice(byte[] arr, int start) {
        return slice(arr, start, arr.length);
    }

    /**
     * 业务作用：按下标区间截取数组或集合的一段，用于分页与分批处理。
     *
     * @param arr 源数组
     * @param start 见上述说明
     * @param end 见上述说明
     * 返回: 截取出的新集合；区间为空时返回空集合，不返回 null。
     */
    public static byte[] slice(byte[] arr, int start, int end) {
        if (start >= arr.length || start >= end) {
            return new byte[0];
        }
        int length = Math.min(end, arr.length) - start;
        byte[] as = new byte[length];
        System.arraycopy(arr, start, as, 0, length);
        return as;
    }

    /**
     * 业务作用：按下标区间截取数组或集合的一段，用于分页与分批处理。
     *
     * @param arr 源数组
     * @param start 见上述说明
     * 返回: 截取出的新集合；区间为空时返回空集合，不返回 null。
     */
    public static short[] slice(short[] arr, int start) {
        return slice(arr, start, arr.length);
    }

    /**
     * 业务作用：按下标区间截取数组或集合的一段，用于分页与分批处理。
     *
     * @param arr 源数组
     * @param start 见上述说明
     * @param end 见上述说明
     * 返回: 截取出的新集合；区间为空时返回空集合，不返回 null。
     */
    public static short[] slice(short[] arr, int start, int end) {
        if (start >= arr.length || start >= end) {
            return new short[0];
        }
        int length = Math.min(end, arr.length) - start;
        short[] as = new short[length];
        System.arraycopy(arr, start, as, 0, length);
        return as;
    }

    /**
     * 业务作用：按下标区间截取数组或集合的一段，用于分页与分批处理。
     *
     * @param arr 源数组
     * @param start 见上述说明
     * 返回: 截取出的新集合；区间为空时返回空集合，不返回 null。
     */
    public static double[] slice(double[] arr, int start) {
        return slice(arr, start, arr.length);
    }

    /**
     * 业务作用：按下标区间截取数组或集合的一段，用于分页与分批处理。
     *
     * @param arr 源数组
     * @param start 见上述说明
     * @param end 见上述说明
     * 返回: 截取出的新集合；区间为空时返回空集合，不返回 null。
     */
    public static double[] slice(double[] arr, int start, int end) {
        if (start >= arr.length || start >= end) {
            return new double[0];
        }
        int length = Math.min(end, arr.length) - start;
        double[] as = new double[length];
        System.arraycopy(arr, start, as, 0, length);
        return as;
    }

    /**
     * 业务作用：按下标区间截取数组或集合的一段，用于分页与分批处理。
     *
     * @param arr 源数组
     * @param start 见上述说明
     * 返回: 截取出的新集合；区间为空时返回空集合，不返回 null。
     */
    public static float[] slice(float[] arr, int start) {
        return slice(arr, start, arr.length);
    }

    /**
     * 业务作用：按下标区间截取数组或集合的一段，用于分页与分批处理。
     *
     * @param arr 源数组
     * @param start 见上述说明
     * @param end 见上述说明
     * 返回: 截取出的新集合；区间为空时返回空集合，不返回 null。
     */
    public static float[] slice(float[] arr, int start, int end) {
        if (start >= arr.length || start >= end) {
            return new float[0];
        }
        int length = Math.min(end, arr.length) - start;
        float[] as = new float[length];
        System.arraycopy(arr, start, as, 0, length);
        return as;
    }

    /**
     * 业务作用：按下标区间截取数组或集合的一段，用于分页与分批处理。
     *
     * @param arr 源数组
     * @param start 见上述说明
     * 返回: 截取出的新集合；区间为空时返回空集合，不返回 null。
     */
    public static char[] slice(char[] arr, int start) {
        return slice(arr, start, arr.length);
    }

    /**
     * 业务作用：按下标区间截取数组或集合的一段，用于分页与分批处理。
     *
     * @param arr 源数组
     * @param start 见上述说明
     * @param end 见上述说明
     * 返回: 截取出的新集合；区间为空时返回空集合，不返回 null。
     */
    public static char[] slice(char[] arr, int start, int end) {
        if (start >= arr.length || start >= end) {
            return new char[0];
        }
        int length = Math.min(end, arr.length) - start;
        char[] as = new char[length];
        System.arraycopy(arr, start, as, 0, length);
        return as;
    }

    /**
     * 业务作用：取出集合中第一个满足条件的元素，命中即停止遍历。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 首个命中元素；无命中时返回 null。
     */
    @SuppressWarnings("unchecked")
    public static <T, E> E first(Collection<T> col, Predicate<T> predicate, Function<T, E> mapper) {
        if (Objects.isNull(col)) return null;
        if (predicate == null) predicate = predicateTrue();
        if (mapper == null) mapper = (Function<T, E>) identity();
        for (T t : col) {
            if (predicate.test(t)) return mapper.apply(t);
        }
        return null;
    }

    /**
     * 业务作用：取出集合中第一个满足条件的元素，命中即停止遍历。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 首个命中元素；无命中时返回 null。
     */
    public static <T, E> E first(T[] arr, Predicate<T> predicate, Function<T, E> mapper) {
        if (Objects.isNull(arr)) return null;
        if (predicate == null) predicate = predicateTrue();
        if (mapper == null) mapper = (Function<T, E>) identity();

        for (T t : arr) {
            if (predicate.test(t)) return mapper.apply(t);
        }
        return null;
    }

    /**
     * 业务作用：取出集合中第一个满足条件的元素，命中即停止遍历。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * 返回: 首个命中元素；无命中时返回 null。
     */
    public static <T> T first(Collection<T> col, Predicate<T> predicate) {
        return first(col, predicate, identity());
    }

    /**
     * 业务作用：取出集合中第一个满足条件的元素，命中即停止遍历。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * 返回: 首个命中元素；无命中时返回 null。
     */
    public static <T> T first(T[] arr, Predicate<T> predicate) {
        return first(arr, predicate, identity());
    }

    /**
     * 业务作用：取出集合中第一个满足条件的元素，命中即停止遍历。
     *
     * @param col 源集合
     * 返回: 首个命中元素；无命中时返回 null。
     */
    public static <T> T first(Collection<T> col) {
        if (isEmpty(col)) {
            return null;
        }
        return col.iterator().next();
    }

    /**
     * 业务作用：取出集合中第一个满足条件的元素，命中即停止遍历。
     *
     * @param arr 源数组
     * 返回: 首个命中元素；无命中时返回 null。
     */
    public static <T> T first(T[] arr) {
        if (isEmpty(arr)) {
            return null;
        }
        return arr[0];
    }

    /**
     * 业务作用：按给定属性取出集合中的最大元素。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 最大元素；集合为空时返回 null。
     */
    public static <T, E extends Comparable<E>> T max(Collection<T> col, Predicate<T> predicate, Function<T, E> mapper) {
        Stream<T> stream = col.stream();
        if (Objects.nonNull(predicate)) {
            stream = stream.filter(predicate);
        }
        return stream.reduce((a, b) -> Compares.gt(mapper.apply(a), mapper.apply(b)) ? a : b).orElse(null);
    }

    /**
     * 业务作用：按给定属性取出集合中的最大元素。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 最大元素；集合为空时返回 null。
     */
    public static <T, E extends Comparable<E>> T max(T[] arr, Predicate<T> predicate, Function<T, E> mapper) {
        Stream<T> stream = Stream.of(arr);
        if (Objects.nonNull(predicate)) {
            stream = stream.filter(predicate);
        }
        return stream.reduce((a, b) -> Compares.gt(mapper.apply(a), mapper.apply(b)) ? a : b).orElse(null);
    }

    /**
     * 业务作用：按给定属性取出集合中的最大元素。
     *
     * @param col 源集合
     * @param mapper 元素到目标值的映射函数
     * 返回: 最大元素；集合为空时返回 null。
     */
    public static <T, E extends Comparable<E>> T max(Collection<T> col, Function<T, E> mapper) {
        return max(col, nonNull(), mapper);
    }

    /**
     * 业务作用：按给定属性取出集合中的最大元素。
     *
     * @param arr 源数组
     * @param mapper 元素到目标值的映射函数
     * 返回: 最大元素；集合为空时返回 null。
     */
    public static <T, E extends Comparable<E>> T max(T[] arr, Function<T, E> mapper) {
        return max(arr, nonNull(), mapper);
    }

    /**
     * 业务作用：按给定属性取出集合中的最大元素。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * 返回: 最大元素；集合为空时返回 null。
     */
    public static <T extends Comparable<T>> T max(Collection<T> col, Predicate<T> predicate) {
        return max(col, predicate, identity());
    }

    /**
     * 业务作用：按给定属性取出集合中的最大元素。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * 返回: 最大元素；集合为空时返回 null。
     */
    public static <T extends Comparable<T>> T max(T[] arr, Predicate<T> predicate) {
        return max(arr, predicate, identity());
    }

    /**
     * 业务作用：按给定属性取出集合中的最大元素。
     *
     * @param col 源集合
     * 返回: 最大元素；集合为空时返回 null。
     */
    public static <T extends Comparable<T>> T max(Collection<T> col) {
        return max(col, nonNull());
    }

    /**
     * 业务作用：按给定属性取出集合中的最大元素。
     *
     * @param arr 源数组
     * 返回: 最大元素；集合为空时返回 null。
     */
    public static <T extends Comparable<T>> T max(T... arr) {
        return max(arr, nonNull());
    }

    /**
     * 业务作用：按给定属性取出集合中的最小元素。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 最小元素；集合为空时返回 null。
     */
    public static <T, E extends Comparable<E>> T min(Collection<T> col, Predicate<T> predicate, Function<T, E> mapper) {
        Stream<T> stream = col.stream();
        if (Objects.nonNull(predicate)) {
            stream = stream.filter(predicate);
        }
        return stream.reduce((a, b) -> Compares.lt(mapper.apply(a), mapper.apply(b)) ? a : b).orElse(null);
    }

    /**
     * 业务作用：按给定属性取出集合中的最小元素。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 最小元素；集合为空时返回 null。
     */
    public static <T, E extends Comparable<E>> T min(T[] arr, Predicate<T> predicate, Function<T, E> mapper) {
        Stream<T> stream = Stream.of(arr);
        if (Objects.nonNull(predicate)) {
            stream = stream.filter(predicate);
        }
        return stream.reduce((a, b) -> Compares.lt(mapper.apply(a), mapper.apply(b)) ? a : b).orElse(null);
    }

    /**
     * 业务作用：按给定属性取出集合中的最小元素。
     *
     * @param col 源集合
     * @param mapper 元素到目标值的映射函数
     * 返回: 最小元素；集合为空时返回 null。
     */
    public static <T, E extends Comparable<E>> T min(Collection<T> col, Function<T, E> mapper) {
        return min(col, nonNull(), mapper);
    }

    /**
     * 业务作用：按给定属性取出集合中的最小元素。
     *
     * @param arr 源数组
     * @param mapper 元素到目标值的映射函数
     * 返回: 最小元素；集合为空时返回 null。
     */
    public static <T, E extends Comparable<E>> T min(T[] arr, Function<T, E> mapper) {
        return min(arr, nonNull(), mapper);
    }

    /**
     * 业务作用：按给定属性取出集合中的最小元素。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * 返回: 最小元素；集合为空时返回 null。
     */
    public static <T extends Comparable<T>> T min(Collection<T> col, Predicate<T> predicate) {
        return min(col, predicate, identity());
    }

    /**
     * 业务作用：按给定属性取出集合中的最小元素。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * 返回: 最小元素；集合为空时返回 null。
     */
    public static <T extends Comparable<T>> T min(T[] arr, Predicate<T> predicate) {
        return min(arr, predicate, identity());
    }

    /**
     * 业务作用：按给定属性取出集合中的最小元素。
     *
     * @param col 源集合
     * 返回: 最小元素；集合为空时返回 null。
     */
    public static <T extends Comparable<T>> T min(Collection<T> col) {
        return min(col, nonNull());
    }

    /**
     * 业务作用：按给定属性取出集合中的最小元素。
     *
     * @param arr 源数组
     * 返回: 最小元素；集合为空时返回 null。
     */
    public static <T extends Comparable<T>> T min(T... arr) {
        return min(arr, nonNull());
    }

    /**
     * 业务作用：判断集合是否满足指定条件
     *
     * @param col 集合
     * @param predicate 过滤函数
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static <T> boolean predicate(Collection<T> col, Predicate<T> predicate) {
        for (T t : col) if (predicate.test(t)) return true;
        return false;
    }

    /**
     * 业务作用：判断数组是否满足指定条件
     *
     * @param arr 数组
     * @param predicate 过滤函数
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static <T> boolean predicate(T[] arr, Predicate<T> predicate) {
        for (T t : arr) if (predicate.test(t)) return true;
        return false;
    }

    /**
     * 业务作用：判断集合中是否存在指定属性值的元素。
     *
     * @param col 源集合
     * @param mapper 元素到目标值的映射函数
     * @param source 见上述说明
     * 返回: 存在返回 true。
     */
    public static <T, E extends Comparable<E>> boolean contains(Collection<T> col, Function<T, E> mapper, E source) {
        if (Objects.isNull(source)) {
            return false;
        }
        return predicate(col, t -> Compares.eq(source, mapper.apply(t)));
    }

    /**
     * 业务作用：判断集合中是否存在指定属性值的元素。
     *
     * @param arr 源数组
     * @param mapper 元素到目标值的映射函数
     * @param source 见上述说明
     * 返回: 存在返回 true。
     */
    public static <T, E extends Comparable<E>> boolean contains(T[] arr, Function<T, E> mapper, E source) {
        if (Objects.isNull(source)) {
            return false;
        }
        return predicate(arr, t -> Compares.eq(source, mapper.apply(t)));
    }

    /**
     * 业务作用：判断集合中是否存在指定属性值的元素。
     *
     * @param col 源集合
     * @param source 见上述说明
     * 返回: 存在返回 true。
     */
    public static <T extends Comparable<T>> boolean contains(Collection<T> col, T source) {
        return contains(col, identity(), source);
    }

    /**
     * 业务作用：判断集合中是否存在指定属性值的元素。
     *
     * @param arr 源数组
     * @param source 见上述说明
     * 返回: 存在返回 true。
     */
    public static <T extends Comparable<T>> boolean contains(T[] arr, T source) {
        return contains(arr, identity(), source);
    }

    /**
     * 业务作用：判断集合中是否不存在指定属性值的元素，供条件表达式直接写成肯定式。
     *
     * @param col 源集合
     * @param mapper 元素到目标值的映射函数
     * @param source 见上述说明
     * 返回: 不存在返回 true。
     */
    public static <T, E extends Comparable<E>> boolean notContains(Collection<T> col, Function<T, E> mapper, E source) {
        return !contains(col, mapper, source);
    }

    /**
     * 业务作用：判断集合中是否不存在指定属性值的元素，供条件表达式直接写成肯定式。
     *
     * @param arr 源数组
     * @param mapper 元素到目标值的映射函数
     * @param source 见上述说明
     * 返回: 不存在返回 true。
     */
    public static <T, E extends Comparable<E>> boolean notContains(T[] arr, Function<T, E> mapper, E source) {
        return !contains(arr, mapper, source);
    }

    /**
     * 业务作用：判断集合中是否不存在指定属性值的元素，供条件表达式直接写成肯定式。
     *
     * @param col 源集合
     * @param source 见上述说明
     * 返回: 不存在返回 true。
     */
    public static <T extends Comparable<T>> boolean notContains(Collection<T> col, T source) {
        return !contains(col, source);
    }

    /**
     * 业务作用：判断集合中是否不存在指定属性值的元素，供条件表达式直接写成肯定式。
     *
     * @param arr 源数组
     * @param source 见上述说明
     * 返回: 不存在返回 true。
     */
    public static <T extends Comparable<T>> boolean notContains(T[] arr, T source) {
        return !contains(arr, source);
    }

    /**
     * 业务作用：判断int数组是否含有指定值
     *
     * @param arr 数组
     * @param source 目标元素
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean contains(int[] arr, int source) {
        for (int a : arr) {
            if (a == source) {
                return true;
            }
        }
        return false;
    }

    /**
     * 业务作用：判断long数组是否含有指定值
     *
     * @param arr 数组
     * @param source 目标元素
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean contains(long[] arr, long source) {
        for (long a : arr) {
            if (a == source) {
                return true;
            }
        }
        return false;
    }

    /**
     * 业务作用：判断int数组是否不含有指定值
     *
     * @param arr 数组
     * @param source 目标元素
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean notContains(int[] arr, int source) {
        return !contains(arr, source);
    }

    /**
     * 业务作用：判断int数组是否不含有指定值
     *
     * @param arr 数组
     * @param source 目标元素
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean notContains(long[] arr, int source) {
        return !contains(arr, source);
    }

    /**
     * 业务作用：统计集合中满足条件的元素个数。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * 返回: 满足条件的元素数量；集合为空时返回 0。
     */
    public static <T> int count(Collection<T> col, Predicate<T> predicate) {
        if (predicate == null) predicate = predicateTrue();
        int n = 0;
        for (T t : col) if (predicate.test(t)) n++;
        return n;
    }

    /**
     * 业务作用：统计集合中满足条件的元素个数。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * 返回: 满足条件的元素数量；集合为空时返回 0。
     */
    public static <T> int count(T[] arr, Predicate<T> predicate) {
        if (predicate == null) predicate = predicateTrue();
        int n = 0;
        for (T t : arr) if (predicate.test(t)) n++;
        return n;
    }

    /**
     * 业务作用：把集合转成数组，供需要数组入参的 API 使用。
     *
     * @param clazz 目标类型
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 元素数组；源集合为空时返回长度为 0 的数组。
     */
    @SuppressWarnings("unchecked")
    public static <T, E> E[] toArray(Class<?> clazz, Collection<T> col, Predicate<T> predicate, Function<T, E> mapper) {
        if (predicate == null) predicate = predicateTrue();
        if (mapper == null) mapper = (Function<T, E>) identity();

        int length = count(col, predicate);
        E[] arr = ReflectUtils.newArray(clazz, length);
        int index = 0;
        for (T t : col) {
            if (predicate.test(t)) arr[index++] = mapper.apply(t);
        }
        return arr;
    }

    /**
     * 业务作用：把集合转成数组，供需要数组入参的 API 使用。
     *
     * @param clazz 目标类型
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * 返回: 元素数组；源集合为空时返回长度为 0 的数组。
     */
    public static <T> T[] toArray(Class<?> clazz, Collection<T> col, Predicate<T> predicate) {
        return toArray(clazz, col, predicate, identity());
    }

    /**
     * 业务作用：把集合转成数组，供需要数组入参的 API 使用。
     *
     * @param clazz 目标类型
     * @param col 源集合
     * 返回: 元素数组；源集合为空时返回长度为 0 的数组。
     */
    public static <T> T[] toArray(Class<?> clazz, Collection<T> col) {
        return toArray(clazz, col, nonNull());
    }

    /**
     * 业务作用：把集合转成数组，供需要数组入参的 API 使用。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 元素数组；源集合为空时返回长度为 0 的数组。
     */
    public static <T, E> E[] toArray(Collection<T> col, Predicate<T> predicate, Function<T, E> mapper) {
        return toArray(mapper.apply(first(col)).getClass(), col, predicate, mapper);
    }

    /**
     * 业务作用：把集合转成数组，供需要数组入参的 API 使用。
     *
     * @param col 源集合
     * @param predicate 筛选条件，返回 true 才纳入结果
     * 返回: 元素数组；源集合为空时返回长度为 0 的数组。
     */
    public static <T> T[] toArray(Collection<T> col, Predicate<T> predicate) {
        return toArray(col, predicate, identity());
    }

    /**
     * 业务作用：按调用方指定的数组类型导出元素快照。
     *
     * @param col 见方法语义
     * 返回: 装有采样瞬间元素的数组。
     */
    public static <T> T[] toArray(Collection<T> col) {
        return toArray(col, nonNull());
    }

    /**
     * 业务作用：把集合转成数组，供需要数组入参的 API 使用。
     *
     * @param clazz 目标类型
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 元素数组；源集合为空时返回长度为 0 的数组。
     */
    @SuppressWarnings("unchecked")
    public static <T, E> E[] toArray(Class<?> clazz, T[] arr, Predicate<T> predicate, Function<T, E> mapper) {
        if (predicate == null) predicate = predicateTrue();
        if (mapper == null) mapper = (Function<T, E>) identity();

        int length = count(arr, predicate);
        E[] result = ReflectUtils.newArray(clazz, length);
        int index = 0;
        for (T t : arr) {
            if (predicate.test(t)) result[index++] = mapper.apply(t);
        }
        return result;
    }

    /**
     * 业务作用：把集合转成数组，供需要数组入参的 API 使用。
     *
     * @param clazz 目标类型
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * 返回: 元素数组；源集合为空时返回长度为 0 的数组。
     */
    public static <T> T[] toArray(Class<?> clazz, T[] arr, Predicate<T> predicate) {
        return toArray(clazz, arr, predicate, identity());
    }

    /**
     * 业务作用：把集合转成数组，供需要数组入参的 API 使用。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * @param mapper 元素到目标值的映射函数
     * 返回: 元素数组；源集合为空时返回长度为 0 的数组。
     */
    public static <T, E> E[] toArray(T[] arr, Predicate<T> predicate, Function<T, E> mapper) {
        return toArray(mapper.apply(first(arr)).getClass(), arr, predicate, mapper);
    }

    /**
     * 业务作用：把集合转成数组，供需要数组入参的 API 使用。
     *
     * @param arr 源数组
     * @param predicate 筛选条件，返回 true 才纳入结果
     * 返回: 元素数组；源集合为空时返回长度为 0 的数组。
     */
    public static <T> T[] toArray(T[] arr, Predicate<T> predicate) {
        return toArray(arr, predicate, identity());
    }

    /**
     * 业务作用：按调用方指定的数组类型导出元素快照。
     *
     * @param arr 见方法语义
     * 返回: 装有采样瞬间元素的数组。
     */
    public static <T> T[] toArray(T... arr) {
        return arr;
    }

    /**
     * 业务作用：把可变参数转成数组并丢弃其中的 null 元素，避免 null 混入下游处理。
     *
     * @param arr 源数组
     * 返回: 不含 null 的数组。
     */
    public static <T> T[] toArrayIgnoreNull(T... arr) {
        if (isEmpty(arr)) {
            return arr;
        }
        // 非null数量
        int count = count(arr, nonNull());
        // 全部非null
        if (count == arr.length) {
            return arr;
        }
        // 反射创建数组
        T[] result = ReflectUtils.newArray(arr.getClass().getComponentType(), count);

        int index = 0;
        for (int i = 0; i < count; i++) {
            while (index < arr.length) {
                T t = arr[index++];
                if (Objects.isNull(t)) {
                    continue;
                }
                // 非null的设置到新数组
                result[i] = t;
                break;
            }
        }
        return result;
    }

    /**
     * 业务作用：按调用方指定的数组类型导出元素快照。
     *
     * @param arr 见方法语义
     * 返回: 装有采样瞬间元素的数组。
     */
    public static char[] toArray(char... arr) {
        return arr;
    }

    /**
     * 业务作用：是否为数组，数组返回true，非数组返回false
     *
     * @param obj 实例对象
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isArray(Object obj) {
        return obj.getClass().isArray();
    }

    /**
     * 业务作用：是否为非数组，非数组返回true，数组返回false
     *
     * @param obj 实例对象
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isNotArray(Object obj) {
        return !isArray(obj);
    }

    /**
     * 业务作用：浅复制数组：新建数组但元素仍指向原对象，用于需要独立数组结构而不需要独立元素的场景。
     *
     * @param arr 源数组
     * 返回: 与源等长的新数组。
     */
    public static <T> T[] copy(T[] arr) {
        // 反射创建数组
        T[] result = ReflectUtils.newArray(arr.getClass().getComponentType(), arr.length);
        System.arraycopy(arr, 0, result, 0, arr.length);
        return result;
    }

    /**
     * 业务作用：按映射属性对集合去重，重复时由取舍函数决定保留哪一个。
     * 元素本身为 null 或映射属性为 null 的都被丢弃，避免 null 参与相等判定。
     * 取舍函数需返回两个入参之一：返回前者则丢弃后者，否则丢弃前者。
     *
     * @param col 源集合
     * @param mapper 元素到去重键的映射函数
     * @param operator 键冲突时的取舍函数，须返回两个入参之一
     * 返回: 去重后的集合。入参是 List 时原地去重并返回同一实例，否则返回新建的列表。
     */
    public static <T, E extends Comparable<E>, C extends Collection<T>> C distinct(
            C col, Function<T, E> mapper, BinaryOperator<T> operator) {
        if (isEmpty(col)) {
            return col;
        }
        List<T> list;
        if (col instanceof List) {
            list = (List<T>) col;
        } else {
            list = new ArrayList<>(col);
        }

        for (int i = 0; i < list.size() - 1; i++) {
            T ti = list.get(i);
            if (Objects.isNull(ti)) {
                // 元素为null，将ti删除
                list.remove(i--);
                continue;
            }
            E tie = mapper.apply(ti);
            if (Objects.isNull(tie)) {
                // 元素映射属性为null，将ti删除
                list.remove(i--);
                continue;
            }
            for (int j = i + 1; j < list.size(); j++) {
                T tj = list.get(j);
                if (Objects.isNull(tj)) {
                    // 元素为null，将tj删除
                    list.remove(j--);
                    continue;
                }
                E tje = mapper.apply(tj);
                if (Objects.isNull(tje)) {
                    // 元素映射属性为null，将tj删除
                    list.remove(j--);
                    continue;
                }
                if (Compares.ne(tie, tje)) {
                    continue;
                }
                // 存在重复，将tj删除
                T apply = operator.apply(ti, tj);
                if (apply == ti) {
                    // 丢弃后面的
                    list.remove(j--);
                } else {
                    // 覆盖前面的
                    list.remove(i--);
                    break;
                }
            }
        }

        // List集合直接返回原集合
        if (col instanceof List) {
            return col;
        }
        // 非List重置集合元素，并返回原集合
        col.clear();
        col.addAll(list);
        return col;
    }

    /**
     * 业务作用：按映射属性对集合去重，重复时保留先出现的那个。
     * 元素本身为 null 或映射属性为 null 的都被丢弃，避免 null 参与相等判定。
     *
     * @param col 源集合
     * @param mapper 元素到去重键的映射函数
     * 返回: 去重后的集合。入参是 List 时原地去重并返回同一实例，否则返回新建的列表。
     */
    public static <T, E extends Comparable<E>, C extends Collection<T>> C distinct(C col, Function<T, E> mapper) {
        // 覆盖前面的
        return distinct(col, mapper, (u, v) -> v);
    }

    /**
     * 业务作用：按映射属性对集合去重。元素本身为 null 或映射属性为 null 的都被丢弃，避免 null 参与相等判定。
     *
     * @param col 源集合
     * 返回: 去重后的集合，保留每个键首次出现的元素。
     */
    public static <T extends Comparable<T>, C extends Collection<T>> C distinct(C col) {
        return distinct(col, identity());
    }

    /**
     * 业务作用：按映射属性对集合去重。元素本身为 null 或映射属性为 null 的都被丢弃，避免 null 参与相等判定。
     *
     * @param source 见上述说明
     * @param mapper 元素到目标值的映射函数
     * @param operator 见上述说明
     * 返回: 去重后的集合，保留每个键首次出现的元素。
     */
    public static <T, E extends Comparable<E>> T[] distinct(T[] source, Function<T, E> mapper, BinaryOperator<T> operator) {
        if (Objects.isNull(source)) {
            return null;
        }
        T[] arr = copy(source);
        if (isEmpty(arr)) {
            return arr;
        }
        for (int i = 0; i < arr.length - 1; i++) {
            T ti = arr[i];
            if (Objects.isNull(ti)) {
                continue;
            }
            E tie = mapper.apply(ti);
            if (Objects.isNull(tie)) {
                // 将ti置为null
                arr[i] = null;
                continue;
            }
            for (int j = i + 1; j < arr.length; j++) {
                T tj = arr[j];
                if (Objects.isNull(tj)) {
                    continue;
                }
                E tje = mapper.apply(tj);
                if (Objects.isNull(tje)) {
                    // 将tj置为null
                    arr[j] = null;
                    continue;
                }
                if (Compares.ne(tie, tje)) {
                    continue;
                }

                T apply = operator.apply(ti, tj);
                if (apply == ti) {
                    // 丢弃后面的
                    arr[j] = null;
                } else {
                    // 覆盖前面的
                    arr[i] = null;
                    break;
                }
            }
        }
        return toArrayIgnoreNull(arr);
    }

    /**
     * 业务作用：按映射属性对集合去重。元素本身为 null 或映射属性为 null 的都被丢弃，避免 null 参与相等判定。
     *
     * @param arr 源数组
     * @param mapper 元素到目标值的映射函数
     * 返回: 去重后的集合，保留每个键首次出现的元素。
     */
    public static <T, E extends Comparable<E>> T[] distinct(T[] arr, Function<T, E> mapper) {
        // 覆盖前面的
        return distinct(arr, mapper, (u, v) -> v);
    }

    /**
     * 业务作用：按映射属性对集合去重。元素本身为 null 或映射属性为 null 的都被丢弃，避免 null 参与相等判定。
     *
     * @param arr 源数组
     * 返回: 去重后的集合，保留每个键首次出现的元素。
     */
    public static <T extends Comparable<T>> T[] distinct(T... arr) {
        return distinct(arr, identity());
    }

    /**
     * 业务作用：把多个元素批量加入目标集合。
     *
     * @param col 源集合
     * @param cs 见上述说明
     * 返回: 目标集合本身，供链式调用。
     */
    @SuppressWarnings("rawtypes")
    public static <C extends Collection<E>, E> C addAll(C col, Collection... cs) {
        if (isEmpty(cs)) {
            return col;
        }
        for (Collection c : cs) {
            if (c != null) col.addAll(c);
        }
        return col;
    }

    /**
     * 业务作用：把多个元素批量加入目标集合。
     *
     * @param col 源集合
     * @param es 源数组
     * 返回: 目标集合本身，供链式调用。
     */
    @SuppressWarnings("all")
    public static <C extends Collection<E>, E> C addAll(C col, E... es) {
        if (isEmpty(es)) {
            return col;
        }
        for (E e : es) {
            col.add(e);
        }
        return col;
    }

    /**
     * 业务作用：按元素的指定属性和给定方向对列表原地排序。
     *
     * @param list 源列表
     * @param mapper 元素到目标值的映射函数
     * @param sort 见上述说明
     * 返回: 无返回值；排序直接作用于传入的列表。
     */
    public static <T, R extends Comparable<R>> List<T> sortType(List<T> list, Function<T, R> mapper, Sort sort) {
        list.sort((a, b) -> compareTo(a, b, mapper, sort));
        return list;
    }

    /**
     * 业务作用：按元素的指定属性对列表原地升序排序。
     *
     * @param list 源列表
     * @param mapper 元素到目标值的映射函数
     * 返回: 无返回值；排序直接作用于传入的列表。
     */
    public static <T, R extends Comparable<R>> List<T> ascType(List<T> list, Function<T, R> mapper) {
        return sortType(list, mapper, Sort.ASC);
    }

    /**
     * 业务作用：按元素的指定属性对列表原地升序排序。
     *
     * @param list 源列表
     * 返回: 无返回值；排序直接作用于传入的列表。
     */
    public static <T extends Comparable<T>> List<T> ascType(List<T> list) {
        return ascType(list, identity());
    }

    /**
     * 业务作用：按元素的指定属性对列表原地降序排序。
     *
     * @param list 源列表
     * @param mapper 元素到目标值的映射函数
     * 返回: 无返回值；排序直接作用于传入的列表。
     */
    public static <T, R extends Comparable<R>> List<T> descType(List<T> list, Function<T, R> mapper) {
        return sortType(list, mapper, Sort.DESC);
    }

    /**
     * 业务作用：按元素的指定属性对列表原地降序排序。
     *
     * @param list 源列表
     * 返回: 无返回值；排序直接作用于传入的列表。
     */
    public static <T extends Comparable<T>> List<T> descType(List<T> list) {
        return descType(list, identity());
    }

    /**
     * 业务作用: 按指定注解属性为扩展实例排序，并将未声明注解的实例稳定置于末尾。
     *
     * @param list   排序集合
     * @param clazz  注解类
     * @param mapper 注解中方法映射函数
     * @param sort   排序类型
     * 返回: 原地排序后的同一个列表实例。
     */
    public static <T, A extends Annotation, R extends Comparable<R>> List<T> sortAnnotation(
            List<T> list, Class<A> clazz, Function<A, R> mapper, Sort sort) {
        list.sort((a, b) -> {
            if (Objects.isNull(a) && Objects.isNull(b)) {
                return 0;
            }
            if (Objects.isNull(a)) {
                // 为空的全部排在后面
                return 1;
            }
            if (Objects.isNull(b)) {
                // 为空的全部排在后面
                return -1;
            }
            A ao = ReflectUtils.targetClass(a).getAnnotation(clazz);
            A bo = ReflectUtils.targetClass(b).getAnnotation(clazz);
            return compareTo(ao, bo, mapper, sort);
        });
        return list;
    }

    /**
     * 业务作用：按可比较语义比较两个值，并统一处理 null，避免调用方各自实现 null 排序规则。
     *
     * @param a 左操作数
     * @param b 右操作数
     * @param mapper 元素到目标值的映射函数
     * @param sort 见上述说明
     * 返回: 左小于右返回负数，相等返回 0，左大于右返回正数。
     */
    private static <T, R extends Comparable<R>> int compareTo(T a, T b, Function<T, R> mapper, Sort sort) {
        if (Objects.isNull(a) && Objects.isNull(b)) {
            return 0;
        }
        if (Objects.isNull(a)) {
            // 为空的全部排在后面
            return 1;
        }
        if (Objects.isNull(b)) {
            // 为空的全部排在后面
            return -1;
        }
        R aComp = mapper.apply(a);
        R bComp = mapper.apply(b);
        if (Objects.isNull(aComp) && Objects.isNull(bComp)) {
            return 0;
        }
        if (Objects.isNull(aComp)) {
            // 为空的全部排在后面
            return 1;
        }
        if (Objects.isNull(bComp)) {
            // 为空的全部排在后面
            return -1;
        }
        return sort == Sort.ASC ? aComp.compareTo(bComp) : bComp.compareTo(aComp);
    }

    /**
     * 业务作用: 按 {@link Order} 声明的优先级升序排列扩展实例。
     *
     * @param list 排序集合
     * 返回: 原地排序后的同一个列表实例。
     */
    public static <T> List<T> ascOrder(List<T> list) {
        return sortAnnotation(list, Order.class, Order::value, Sort.ASC);
    }

    /**
     * 业务作用: 按 {@link Order} 声明的优先级降序排列扩展实例。
     *
     * @param list 排序集合
     * 返回: 原地排序后的同一个列表实例。
     */
    public static <T> List<T> descOrder(List<T> list) {
        return sortAnnotation(list, Order.class, Order::value, Sort.DESC);
    }

}
