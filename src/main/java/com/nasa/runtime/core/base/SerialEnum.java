package com.nasa.runtime.core.base;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;

/**
 * Nasa
 * 枚举序列化接口：ordinal() 作为 JSON / DB 序列化值
 * 枚举 implements SerialEnum 即可，无需手写 serial() 和 of()
 */
public interface SerialEnum {

    @JsonValue
    default int serial() {
        return ((Enum<?>) this).ordinal();
    }

    /**
     * 通用反序列化: ordinal → 枚举实例
     */
    static <E extends Enum<E> & SerialEnum> E of(Class<E> type, Integer ordinal) {
        if (Objects.isNull(ordinal)) return null;
        for (E e : type.getEnumConstants()) if (e.serial() == ordinal) return e;
        return null;
    }
}
