package io.github.nasaruntime.core.copier.string.concat;

import io.github.nasaruntime.core.utils.Numeric;

import java.util.Objects;

/**
 * Nasa
 * NumberCopier及其子类只能处理整数
 * double float BigDecimal等走ObjectCopier
 */
public abstract class NumberCopier extends ACopier {

    /**
     * 业务作用：预先算出任意数值拼接成字符串后占用的字符数，供拼接方一次性分配足够大的字符数组，
     * 避免边拼边扩容。
     * @param source 待测量的源数据，允许为 null
     * 返回: 所需字符数；源为 null 时返回 0，即 null 不占用任何字符。
     */
    @Override
    public int length(Object source) {
        if (Objects.isNull(source)) {
            return 0;
        }
        return Numeric.stringSize((Number) source);
    }

    /**
     * 业务作用：把任意数值的字符表示写入目标字符数组的指定位置，是零中间字符串拼接的实际执行步骤。
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
        start += Numeric.copyToCharArray(((Number) source).longValue(), cs, start);
        return start;
    }

}
