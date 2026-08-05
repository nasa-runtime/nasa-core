package com.nasa.runtime.core.copier.string.concat;

import java.util.Objects;

/**
 * Nasa
 */
@SuppressWarnings("unused")
public class ObjectArrayCopier extends ACopier {

    @Override
    public String copier() {
        return "Object[]";
    }

    @Override
    public int length(Object sources) {
        if (Objects.isNull(sources)) {
            return 0;
        }
        Object[] ss = (Object[]) sources;
        int length = 0;
        for (Object source : ss) {
            length += ACopier.getCopier(source.getClass()).length(source);
        }
        return length;
    }

    @Override
    public int copyToCharArray(Object sources, char[] cs, int start) {
        if (Objects.isNull(sources)) {
            return start;
        }
        Object[] ss = (Object[]) sources;
        for (Object source : ss) {
            start = ACopier.getCopier(source.getClass()).copyToCharArray(source, cs, start);
        }
        return start;
    }


}
