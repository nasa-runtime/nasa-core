package com.nasa.runtime.core.utils;

import java.util.Objects;

/**
 * Nasa
 * Comparable 比较工具类
 */
public abstract class Compares {


    /**
     * 等于
     */
    public static <T extends Comparable<T>> boolean eq(T n1, T n2) {
        if (Objects.isNull(n1) || Objects.isNull(n2)) {
            return false;
        }
        return n1.compareTo(n2) == 0;
    }


    /**
     * 不等于
     */
    public static <T extends Comparable<T>> boolean ne(T n1, T n2) {
        return !eq(n1, n2);
    }


    /**
     * 大于
     */
    public static <T extends Comparable<T>> boolean gt(T n1, T n2) {
        if (Objects.isNull(n1) || Objects.isNull(n2)) {
            return false;
        }
        return n1.compareTo(n2) > 0;
    }


    /**
     * 大于等于
     */
    public static <T extends Comparable<T>> boolean ge(T n1, T n2) {
        if (Objects.isNull(n1) || Objects.isNull(n2)) {
            return false;
        }
        return n1.compareTo(n2) > -1;
    }


    /**
     * 小于
     */
    public static <T extends Comparable<T>> boolean lt(T n1, T n2) {
        return !ge(n1, n2);
    }


    /**
     * 小于等于
     */
    public static <T extends Comparable<T>> boolean le(T n1, T n2) {
        return !gt(n1, n2);
    }

}
