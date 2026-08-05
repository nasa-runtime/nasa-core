package com.nasa.runtime.core.copier.string.concat;

import java.util.Objects;

/**
 * Nasa
 */
@SuppressWarnings("unused")
public class BooleanCopier extends ACopier {

    private static final char[] True = "true".toCharArray();
    private static final char[] False = "false".toCharArray();

    @Override
    public String copier() {
        return "Boolean";
    }

    @Override
    public int length(Object source) {
        if (Objects.isNull(source)) {
            return 0;
        }
        return (boolean) source ? 4 : 5;
    }

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
