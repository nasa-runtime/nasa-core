package com.nasa.runtime.core.copier.string.concat;

import com.nasa.runtime.core.utils.StringUtils;

import java.util.Objects;

/**
 * Nasa
 */
@SuppressWarnings("unused")
public class StringCopier extends ACopier {

    @Override
    public String copier() {
        return "String";
    }

    @Override
    public int length(Object source) {
        if (Objects.isNull(source)) {
            return 0;
        }
        return ((String) source).length();
    }

    @Override
    public int copyToCharArray(Object source, char[] cs, int start) {
        if (Objects.isNull(source)) {
            return start;
        }
        String val = (String) source;
        StringUtils.copyToCharArray(val, cs, start);
        return start + val.length();
    }
}
