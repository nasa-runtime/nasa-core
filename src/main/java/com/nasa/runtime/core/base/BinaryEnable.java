package com.nasa.runtime.core.base;

import com.nasa.runtime.core.utils.StringUtils;

/**
 * Nasa
 * 二进制开关处理
 */
@SuppressWarnings("unused")
public interface BinaryEnable<E extends Enum<E>> {

    /**
     * 开启开关
     */
    static <E extends Enum<E>> long enable(long value, BinaryEnable<E> binaryEnable) {
        return set(value, binaryEnable, true);
    }

    /**
     * 关闭开关
     */
    static <E extends Enum<E>> long disable(long value, BinaryEnable<E> binaryEnable) {
        return set(value, binaryEnable, false);
    }

    /**
     * 开启或者关闭开关
     * @param enable 0.关闭 1.开启
     */
    static <E extends Enum<E>> long set(long value, BinaryEnable<E> binaryEnable, int enable) {
        return set(value, binaryEnable, enable == 1);
    }

    /**
     * 开启或者关闭开关
     * @param enable false.关闭 true.开启
     */
    static <E extends Enum<E>> long set(long value, BinaryEnable<E> binaryEnable, boolean enable) {
        return enable ? value | binaryEnable.getBinary() : value & ~binaryEnable.getBinary();
    }

    /**
     * 获取开关的值：0 或 1
     */
    static <E extends Enum<E>> int get(long value, BinaryEnable<E> binaryEnable) {
        return (int) ((value & binaryEnable.getBinary()) >>> binaryEnable.ordinal());
    }

    /**
     * 是否开启
     */
    static <E extends Enum<E>> boolean isEnable(long value, BinaryEnable<E> binaryEnable) {
        return get(value, binaryEnable) == 1;
    }

    /**
     * 是否关闭
     */
    static <E extends Enum<E>> boolean isDisable(long value, BinaryEnable<E> binaryEnable) {
        return !isEnable(value, binaryEnable);
    }

    /**
     * 获取枚举的位
     */
    int ordinal();

    /**
     * 将1左移位
     */
    default long getBinary() {
        return 1L << this.ordinal();
    }

    /**
     * 开启开关
     */
    default long enable(long value) {
        return enable(value, this);
    }

    /**
     * 关闭开关
     */
    default long disable(long value) {
        return disable(value, this);
    }

    /**
     * 开启或者关闭开关
     * @param enable 0.关闭 1.开启
     */
    default long set(long value, int enable) {
        return set(value, this, enable);
    }

    /**
     * 开启或者关闭开关
     * @param enable false.关闭 true.开启
     */
    default long set(long value, boolean enable) {
        return set(value, this, enable);
    }

    /**
     * 获取开关的值：0 或 1
     */
    default int get(long value) {
        return get(value, this);
    }

    /**
     * 是否开启
     */
    default boolean isEnable(long value) {
        return isEnable(value, this);
    }

    /**
     * 是否关闭
     */
    default boolean isDisable(long value) {
        return isDisable(value, this);
    }

    /**
     * 获取数据库字段名
     */
    default String column() {
        return "binary_enable";
    }

    /**
     * 生成数据库查询条件
     * @param value 0.关闭 1.开启
     */
    default String select(int value) {
        return StringUtils.concat("(", this.column(), " & ", this.getBinary(), ") >> ", this.ordinal(), " = ", value);
    }

    /**
     * 生成数据库查询条件
     * @param value false.关闭 true.开启
     */
    default String select(boolean value) {
        return select(value ? 1 : 0);
    }

    /**
     * 生成数据库查询开启条件
     */
    default String selectEnable() {
        return select(1);
    }

    /**
     * 生成数据库查询关闭条件
     */
    default String selectDisable() {
        return select(0);
    }

    /**
     * 生成数据库设置 update table set ...
     * @param value 0.关闭 1.开启
     */
    default String update(int value) {
        return update(value == 1);
    }

    /**
     * 生成数据库设置 update table set ...
     * @param value false.关闭 true.开启
     */
    default String update(boolean value) {
        return StringUtils.concat(this.column(), " = ", this.column(), value ? " | " : " & ~", this.getBinary());
    }

    /**
     * 生成数据库开启设置 update table set ...
     */
    default String updateEnable() {
        return update(1);
    }

    /**
     * 生成数据库关闭设置 update table set ...
     */
    default String updateDisable() {
        return update(0);
    }

}
