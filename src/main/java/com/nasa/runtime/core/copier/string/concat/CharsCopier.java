package com.nasa.runtime.core.copier.string.concat;


import java.util.Objects;

/**
 * Nasa
 */
@SuppressWarnings("unused")
public class CharsCopier extends ACopier {

    @Override
    public String copier() {
        return "char[]";
    }

    @Override
    public int length(Object source) {
        if (Objects.isNull(source)) {
            return 0;
        }
        return ((char[]) source).length;
    }

    @Override
    public int copyToCharArray(Object source, char[] cs, int start) {
        if (Objects.isNull(source)) {
            return start;
        }
        char[] chars = (char[]) source;
        for (Character c : chars) {
            cs[start++] = c;
        }
        return start;
    }
}
