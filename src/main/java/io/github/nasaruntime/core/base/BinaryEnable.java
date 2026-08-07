package io.github.nasaruntime.core.base;

import io.github.nasaruntime.core.utils.StringUtils;

/**
 * Nasa
 * 二进制开关处理
 */
@SuppressWarnings("unused")
public interface BinaryEnable<E extends Enum<E>> {

    /**
     * 业务作用：把该位置 1，表示启用对应开关。多个开关压缩进一个 long，省去逐个布尔列。
     *
     * @param value 位集合当前值
     * @param binaryEnable 位定义
     * 返回: 置位后的新值；原值不变。
     */
    static <E extends Enum<E>> long enable(long value, BinaryEnable<E> binaryEnable) {
        return set(value, binaryEnable, true);
    }

    /**
     * 业务作用：把该位置 0，表示关闭对应开关。
     *
     * @param value 位集合当前值
     * @param binaryEnable 位定义
     * 返回: 清位后的新值；原值不变。
     */
    static <E extends Enum<E>> long disable(long value, BinaryEnable<E> binaryEnable) {
        return set(value, binaryEnable, false);
    }

    /**
     * 业务作用：按给定状态置位或清位。
     *
     * @param value 位集合当前值
     * @param binaryEnable 位定义
     * @param enable 启用状态
     * 返回: 调整后的新值；原值不变。
     */
    static <E extends Enum<E>> long set(long value, BinaryEnable<E> binaryEnable, int enable) {
        return set(value, binaryEnable, enable == 1);
    }

    /**
     * 业务作用：按给定状态置位或清位。
     *
     * @param value 位集合当前值
     * @param binaryEnable 位定义
     * @param enable 启用状态
     * 返回: 调整后的新值；原值不变。
     */
    static <E extends Enum<E>> long set(long value, BinaryEnable<E> binaryEnable, boolean enable) {
        return enable ? value | binaryEnable.getBinary() : value & ~binaryEnable.getBinary();
    }

    /**
     * 业务作用：取出该位的当前状态。
     *
     * @param value 位集合当前值
     * @param binaryEnable 位定义
     * 返回: 该位为 1 返回 1，为 0 返回 0。
     */
    static <E extends Enum<E>> int get(long value, BinaryEnable<E> binaryEnable) {
        return (int) ((value & binaryEnable.getBinary()) >>> binaryEnable.ordinal());
    }

    /**
     * 业务作用：判断该位是否已启用。
     *
     * @param value 位集合当前值
     * @param binaryEnable 位定义
     * 返回: 该位为 1 时返回 true。
     */
    static <E extends Enum<E>> boolean isEnable(long value, BinaryEnable<E> binaryEnable) {
        return get(value, binaryEnable) == 1;
    }

    /**
     * 业务作用：判断该位是否未启用。
     *
     * @param value 位集合当前值
     * @param binaryEnable 位定义
     * 返回: 该位为 0 时返回 true。
     */
    static <E extends Enum<E>> boolean isDisable(long value, BinaryEnable<E> binaryEnable) {
        return !isEnable(value, binaryEnable);
    }

    /**
     * 业务作用：给出该开关占用的位序号，是位运算与持久化的唯一依据。序号一经发布不可调整，否则历史数据的位含义会整体错位。
     *
     * 参数说明: 无。
     * 返回: 位序号，从 0 起。
     */
    int ordinal();

    /**
     * 业务作用：算出该开关对应的位掩码。
     *
     * 参数说明: 无。
     * 返回: 仅该位为 1 的掩码值。
     */
    default long getBinary() {
        return 1L << this.ordinal();
    }

    /**
     * 业务作用：把该位置 1，表示启用对应开关。多个开关压缩进一个 long，省去逐个布尔列。
     *
     * @param value 位集合当前值
     * 返回: 置位后的新值；原值不变。
     */
    default long enable(long value) {
        return enable(value, this);
    }

    /**
     * 业务作用：把该位置 0，表示关闭对应开关。
     *
     * @param value 位集合当前值
     * 返回: 清位后的新值；原值不变。
     */
    default long disable(long value) {
        return disable(value, this);
    }

    /**
     * 业务作用：按给定状态置位或清位。
     *
     * @param value 位集合当前值
     * @param enable 启用状态
     * 返回: 调整后的新值；原值不变。
     */
    default long set(long value, int enable) {
        return set(value, this, enable);
    }

    /**
     * 业务作用：按给定状态置位或清位。
     *
     * @param value 位集合当前值
     * @param enable 启用状态
     * 返回: 调整后的新值；原值不变。
     */
    default long set(long value, boolean enable) {
        return set(value, this, enable);
    }

    /**
     * 业务作用：取出该位的当前状态。
     *
     * @param value 位集合当前值
     * 返回: 该位为 1 返回 1，为 0 返回 0。
     */
    default int get(long value) {
        return get(value, this);
    }

    /**
     * 业务作用：是否开启
     *
     * @param value 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    default boolean isEnable(long value) {
        return isEnable(value, this);
    }

    /**
     * 业务作用：是否关闭
     *
     * @param value 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    default boolean isDisable(long value) {
        return isDisable(value, this);
    }

    /**
     * 业务作用：给出该开关在数据库中所属的列名，供拼装 SQL 片段。
     *
     * 参数说明: 无。
     * 返回: 列名。
     */
    default String column() {
        return "binary_enable";
    }

    /**
     * 业务作用：拼出按该开关过滤的 SQL 条件片段，用位运算在数据库侧完成筛选，避免取回后再过滤。
     *
     * @param value 位集合当前值
     * 返回: SQL 条件片段。
     */
    default String select(int value) {
        return StringUtils.concat("(", this.column(), " & ", this.getBinary(), ") >> ", this.ordinal(), " = ", value);
    }

    /**
     * 业务作用：拼出按该开关过滤的 SQL 条件片段，用位运算在数据库侧完成筛选，避免取回后再过滤。
     *
     * @param value 位集合当前值
     * 返回: SQL 条件片段。
     */
    default String select(boolean value) {
        return select(value ? 1 : 0);
    }

    /**
     * 业务作用：拼出「该开关已启用」的 SQL 条件片段。
     *
     * 参数说明: 无。
     * 返回: SQL 条件片段。
     */
    default String selectEnable() {
        return select(1);
    }

    /**
     * 业务作用：拼出「该开关未启用」的 SQL 条件片段。
     *
     * 参数说明: 无。
     * 返回: SQL 条件片段。
     */
    default String selectDisable() {
        return select(0);
    }

    /**
     * 业务作用：拼出设置该开关的 SQL 赋值片段，用位运算原地更新，避免读改写导致并发覆盖。
     *
     * @param value 位集合当前值
     * 返回: SQL 赋值片段。
     */
    default String update(int value) {
        return update(value == 1);
    }

    /**
     * 业务作用：拼出设置该开关的 SQL 赋值片段，用位运算原地更新，避免读改写导致并发覆盖。
     *
     * @param value 位集合当前值
     * 返回: SQL 赋值片段。
     */
    default String update(boolean value) {
        return StringUtils.concat(this.column(), " = ", this.column(), value ? " | " : " & ~", this.getBinary());
    }

    /**
     * 业务作用：拼出启用该开关的 SQL 赋值片段。
     *
     * 参数说明: 无。
     * 返回: SQL 赋值片段。
     */
    default String updateEnable() {
        return update(1);
    }

    /**
     * 业务作用：拼出关闭该开关的 SQL 赋值片段。
     *
     * 参数说明: 无。
     * 返回: SQL 赋值片段。
     */
    default String updateDisable() {
        return update(0);
    }

}
