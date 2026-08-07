package io.github.nasaruntime.core.utils;

import java.util.Objects;

/**
 * Nasa
 * Comparable 比较工具类
 */
public abstract class Compares {


    /**
     * 业务作用：判断两值相等，并统一处理 null，避免各处自行实现 null 比较规则。
     *
     * @param n1 见上述说明
     * @param n2 见上述说明
     * 返回: 相等返回 true。
     */
    public static <T extends Comparable<T>> boolean eq(T n1, T n2) {
        if (Objects.isNull(n1) || Objects.isNull(n2)) {
            return false;
        }
        return n1.compareTo(n2) == 0;
    }


    /**
     * 业务作用：判断两值不等。
     *
     * @param n1 见上述说明
     * @param n2 见上述说明
     * 返回: 不等返回 true。
     */
    public static <T extends Comparable<T>> boolean ne(T n1, T n2) {
        return !eq(n1, n2);
    }


    /**
     * 业务作用：判断左值大于右值。
     *
     * @param n1 见上述说明
     * @param n2 见上述说明
     * 返回: 左大于右返回 true。
     */
    public static <T extends Comparable<T>> boolean gt(T n1, T n2) {
        if (Objects.isNull(n1) || Objects.isNull(n2)) {
            return false;
        }
        return n1.compareTo(n2) > 0;
    }


    /**
     * 业务作用：判断左值大于等于右值。
     *
     * @param n1 见上述说明
     * @param n2 见上述说明
     * 返回: 左大于等于右返回 true。
     */
    public static <T extends Comparable<T>> boolean ge(T n1, T n2) {
        if (Objects.isNull(n1) || Objects.isNull(n2)) {
            return false;
        }
        return n1.compareTo(n2) > -1;
    }


    /**
     * 业务作用：判断左值小于右值。
     *
     * @param n1 见上述说明
     * @param n2 见上述说明
     * 返回: 左小于右返回 true。
     */
    public static <T extends Comparable<T>> boolean lt(T n1, T n2) {
        return !ge(n1, n2);
    }


    /**
     * 业务作用：判断左值小于等于右值。
     *
     * @param n1 见上述说明
     * @param n2 见上述说明
     * 返回: 左小于等于右返回 true。
     */
    public static <T extends Comparable<T>> boolean le(T n1, T n2) {
        return !gt(n1, n2);
    }

}
