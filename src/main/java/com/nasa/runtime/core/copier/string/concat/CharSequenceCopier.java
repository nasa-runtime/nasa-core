package com.nasa.runtime.core.copier.string.concat;

import com.nasa.runtime.core.utils.StringUtils;

import java.util.Objects;

/**
 * Nasa
 */
@SuppressWarnings("unused")
public class CharSequenceCopier extends ACopier {

    public static final String Copier = "CharSequence";

    @Override
    public String copier() {
        return Copier;
    }

    @Override
    public int length(Object source) {
        if (Objects.isNull(source)) {
            return 0;
        }
        return ((CharSequence) source).length();
    }

    @Override
    public int copyToCharArray(Object source, char[] cs, int start) {
        if (Objects.isNull(source)) {
            return start;
        }
        CharSequence sequence = (CharSequence) source;
        StringUtils.copyToCharArray(sequence, cs, start);
        return start + sequence.length();
    }
}
