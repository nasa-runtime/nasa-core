package io.github.nasaruntime.core.copier.string.concat;

import java.util.Collection;
import java.util.Objects;

/**
 * Nasa
 */
@SuppressWarnings({"unused", "rawtypes"})
public class CollectionCopier extends ACopier {

    public static final String Copier = "Collection";

    /**
     * 业务作用：给出本复制器在注册表中的类型键，Collection 类型的源数据据此路由到本实现。
     * 该键必须与源数据 {@code getClass().getSimpleName()} 一致，否则注册后永远匹配不上。
     *
     * 参数说明: 无。
     * 返回: 类型键 Collection。
     */
    @Override
    public String copier() {
        return Copier;
    }

    /**
     * 业务作用：预先算出任意集合拼接成字符串后占用的字符数，供拼接方一次性分配足够大的字符数组，
     * 避免边拼边扩容。逐元素累加各元素的字符长度。
     *
     * @param sources 待测量的源数据，允许为 null
     * 返回: 所需字符数；源为 null 时返回 0，即 null 不占用任何字符。
     */
    @Override
    public int length(Object sources) {
        if (Objects.isNull(sources)) {
            return 0;
        }
        int length = 0;
        Collection col = (Collection) sources;
        for (Object source : col) {
            length += ACopier.getCopier(source.getClass()).length(source);
        }
        return length;
    }

    /**
     * 业务作用：把任意集合的字符表示写入目标字符数组的指定位置，是零中间字符串拼接的实际执行步骤。
     * 逐元素依次写入，前一个元素的返回下标即下一个元素的起始下标。
     * 调用方必须先按 length 的结果分配数组，本方法不做边界检查。
     *
     * @param sources 待写入的源数据，允许为 null
     * @param cs 目标字符数组
     * @param start 本次写入的起始下标
     * 返回: 写入结束后的下标，即下一段内容应当开始的位置；源为 null 时原样返回 start。
     */
    @Override
    public int copyToCharArray(Object sources, char[] cs, int start) {
        if (Objects.isNull(sources)) {
            return start;
        }
        Collection col = (Collection) sources;
        for (Object source : col) {
            start = ACopier.getCopier(source.getClass()).copyToCharArray(source, cs, start);
        }
        return start;
    }
}
