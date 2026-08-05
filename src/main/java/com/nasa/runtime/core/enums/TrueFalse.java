package com.nasa.runtime.core.enums;

import com.nasa.runtime.core.base.SerialEnum;

/**
 * Nasa
 */
@SuppressWarnings("unused")
public enum TrueFalse implements SerialEnum {

    FALSE, TRUE;

    public boolean isTrue() {
        return this == TRUE;
    }

    public boolean isFalse() {
        return this == FALSE;
    }

    public TrueFalse reversed() {
        return this == TRUE ? FALSE : TRUE;
    }

    public static TrueFalse of(boolean b) {
        return b ? TRUE : FALSE;
    }

    public static TrueFalse reversed(boolean b) {
        return b ? FALSE : TRUE;
    }

    public static TrueFalse of(Integer o) {
        return SerialEnum.of(TrueFalse.class, o);
    }

}
