package com.nasa.runtime.core.enums;

import com.nasa.runtime.core.base.SerialEnum;

/**
 * Nasa
 */
@SuppressWarnings("unused")
public enum Sort implements SerialEnum {

    ASC, DESC;

    public static Sort of(int ordinal) {
        return ordinal == 0 ? ASC : DESC;
    }

    public static Sort of(String sort) {
        return "asc".equalsIgnoreCase(sort) ? ASC : DESC;
    }

    public boolean isAsc() {
        return this == ASC;
    }

    public boolean isDesc() {
        return this == DESC;
    }

    public static Sort of(Integer o) {
        return SerialEnum.of(Sort.class, o);
    }
}
