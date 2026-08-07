package io.github.nasaruntime.core.base;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;

/**
 * Nasa
 * 枚举序列化接口：ordinal() 作为 JSON / DB 序列化值
 * 枚举 implements SerialEnum 即可，无需手写 serial() 和 of()
 */
public interface SerialEnum {

    /**
     * 业务作用：给出该枚举常量的序列化编号，是持久化与协议编码的唯一依据。编号一经发布不可修改，否则历史数据会解析错位。
     *
     * 参数说明: 无。
     * 返回: 序列化编号。
     */
    @JsonValue
    default int serial() {
        return ((Enum<?>) this).ordinal();
    }

    /**
     * 业务作用：按序列化编号还原枚举常量。
     *
     * @param type 目标类型
     * @param ordinal 见上述说明
     * 返回: 对应的枚举常量；编号无法匹配时返回 null。
     */
    static <E extends Enum<E> & SerialEnum> E of(Class<E> type, Integer ordinal) {
        if (Objects.isNull(ordinal)) return null;
        for (E e : type.getEnumConstants()) if (e.serial() == ordinal) return e;
        return null;
    }
}
