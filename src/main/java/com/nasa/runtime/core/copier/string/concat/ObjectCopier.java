package com.nasa.runtime.core.copier.string.concat;

import com.nasa.runtime.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * Nasa
 */
@Slf4j
public class ObjectCopier extends ACopier {

    public static final String Copier = "Object";

    @Override
    public String copier() {
        return Copier;
    }

    @Override
    public int length(Object source) {
        if (Objects.isNull(source)) {
            return 0;
        }
        return StringUtils.toString(source).length();
    }

    @Override
    public int copyToCharArray(Object source, char[] cs, int start) {
        if (Objects.isNull(source)) {
            return start;
        }
        String val = StringUtils.toString(source);
        StringUtils.copyToCharArray(val, cs, start);
        return start + val.length();
    }
}
