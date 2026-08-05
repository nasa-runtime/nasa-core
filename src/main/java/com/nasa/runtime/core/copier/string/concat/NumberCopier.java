package com.nasa.runtime.core.copier.string.concat;

import com.nasa.runtime.core.utils.Numeric;

import java.util.Objects;

/**
 * Nasa
 * NumberCopier及其子类只能处理整数
 * double float BigDecimal等走ObjectCopier
 */
public abstract class NumberCopier extends ACopier {

    @Override
    public int length(Object source) {
        if (Objects.isNull(source)) {
            return 0;
        }
        return Numeric.stringSize((Number) source);
    }

    @Override
    public int copyToCharArray(Object source, char[] cs, int start) {
        if (Objects.isNull(source)) {
            return start;
        }
        start += Numeric.copyToCharArray(((Number) source).longValue(), cs, start);
        return start;
    }

}
