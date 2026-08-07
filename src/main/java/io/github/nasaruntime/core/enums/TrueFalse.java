package io.github.nasaruntime.core.enums;

import io.github.nasaruntime.core.base.SerialEnum;

/**
 * Nasa
 */
@SuppressWarnings("unused")
public enum TrueFalse implements SerialEnum {

    FALSE, TRUE;

    /**
     * 业务作用：判断是否为真值，供调用方避免直接比较枚举常量。
     *
     * 参数说明: 无。
     * 返回: 当前为 TRUE 返回 true。
     */
    public boolean isTrue() {
        return this == TRUE;
    }

    /**
     * 业务作用：判断是否为假值，供调用方避免直接比较枚举常量。
     *
     * 参数说明: 无。
     * 返回: 当前为 FALSE 返回 true。
     */
    public boolean isFalse() {
        return this == FALSE;
    }

    /**
     * 业务作用：取当前值的逻辑反值，用于开关取反等场景。
     *
     * 参数说明: 无。
     * 返回: TRUE 与 FALSE 互换后的常量；不修改当前实例。
     */
    public TrueFalse reversed() {
        return this == TRUE ? FALSE : TRUE;
    }

    /**
     * 业务作用：把原生布尔值提升为可序列化的枚举表示。
     *
     * @param b 原生布尔值
     * 返回: true 对应 TRUE，false 对应 FALSE。
     */
    public static TrueFalse of(boolean b) {
        return b ? TRUE : FALSE;
    }

    /**
     * 业务作用：把原生布尔值取反后提升为枚举表示，省去调用方先取反再转换。
     *
     * @param b 原生布尔值
     * 返回: true 对应 FALSE，false 对应 TRUE。
     */
    public static TrueFalse reversed(boolean b) {
        return b ? FALSE : TRUE;
    }

    /**
     * 业务作用：按 SerialEnum 的序列化契约还原布尔枚举，供协议编解码使用。
     * 序号与常量声明顺序绑定：0 为 FALSE、1 为 TRUE，因此常量顺序一经发布不可调整。
     *
     * @param o 序列化序号，可为 null
     * 返回: 对应的枚举常量；无法匹配时由 SerialEnum 决定结果。
     */
    public static TrueFalse of(Integer o) {
        return SerialEnum.of(TrueFalse.class, o);
    }

}
