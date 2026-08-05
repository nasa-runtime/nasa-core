package com.nasa.runtime.core.copier.string.concat;


import java.util.Objects;

/**
 * Nasa
 */
@SuppressWarnings("unused")
public class CharacterCopier extends ACopier {

    @Override
    public String copier() {
        return "Character";
    }

    @Override
    public int length(Object source) {
        return Objects.isNull(source) ? 0 : 1;
    }

    @Override
    public int copyToCharArray(Object source, char[] cs, int start) {
        if (Objects.isNull(source)) {
            return start;
        }
        cs[start++] = (Character) source;
        return start;
    }
}
