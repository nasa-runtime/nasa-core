package com.nasa.runtime.core.copier.string.concat;


import java.util.Objects;

/**
 * Nasa
 */
@SuppressWarnings("unused")
public class CharacterArrayCopier extends ACopier {

    @Override
    public String copier() {
        return "Character[]";
    }

    @Override
    public int length(Object source) {
        if (Objects.isNull(source)) {
            return 0;
        }
        return ((Character[]) source).length;
    }

    @Override
    public int copyToCharArray(Object source, char[] cs, int start) {
        if (Objects.isNull(source)) {
            return start;
        }
        Character[] chars = (Character[]) source;
        for (Character c : chars) {
            cs[start++] = c;
        }
        return start;
    }
}
