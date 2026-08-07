package io.github.nasaruntime.core.copier.string.concat;

import java.util.Objects;

/**
 * Nasa
 */
@SuppressWarnings("unused")
public class BooleanCopier extends ACopier {

    private static final char[] True = "true".toCharArray();
    private static final char[] False = "false".toCharArray();

    /**
     * 业务作用：给出本复制器在注册表中的类型键，Boolean 类型的源数据据此路由到本实现。
     * 该键必须与源数据 {@code getClass().getSimpleName()} 一致，否则注册后永远匹配不上。
     *
     * 参数说明: 无。
     * 返回: 类型键 Boolean。
     */
    @Override
    public String copier() {
        return "Boolean";
    }

    /**
     * 业务作用：预先算出单个布尔值拼接成字符串后占用的字符数，供拼接方一次性分配足够大的字符数组，
     * 避免边拼边扩容。
     * @param source 待测量的源数据，允许为 null
     * 返回: 所需字符数；源为 null 时返回 0，即 null 不占用任何字符。
     */
    @Override
    public int length(Object source) {
        if (Objects.isNull(source)) {
            return 0;
        }
        return (boolean) source ? 4 : 5;
    }

    /**
     * 业务作用：把单个布尔值的字符表示写入目标字符数组的指定位置，是零中间字符串拼接的实际执行步骤。
     * 调用方必须先按 length 的结果分配数组，本方法不做边界检查。
     *
     * @param source 待写入的源数据，允许为 null
     * @param cs 目标字符数组
     * @param start 本次写入的起始下标
     * 返回: 写入结束后的下标，即下一段内容应当开始的位置；源为 null 时原样返回 start。
     */
    @Override
    public int copyToCharArray(Object source, char[] cs, int start) {
        if (Objects.isNull(source)) {
            return start;
        }
        if ((boolean) source) {
            System.arraycopy(True, 0, cs, start, 4);
            start += 4;
        } else {
            System.arraycopy(False, 0, cs, start, 5);
            start += 5;
        }
        return start;
    }
}
