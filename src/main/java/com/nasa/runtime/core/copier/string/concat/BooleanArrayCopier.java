package com.nasa.runtime.core.copier.string.concat;

import java.util.Objects;

/**
 * Nasa
 */
@SuppressWarnings("unused")
public class BooleanArrayCopier extends ACopier {

    private static ACopier copier;

    @Override
    public String copier() {
        return "Boolean[]";
    }

    @Override
    public int length(Object sources) {
        if (Objects.isNull(sources)) {
            return 0;
        }
        initCopier();

        int length = 0;
        Boolean[] ss = (Boolean[]) sources;
        for (Boolean source : ss) {
            length += copier.length(source);
        }
        return length;
    }

    @Override
    public int copyToCharArray(Object sources, char[] cs, int start) {
        if (Objects.isNull(sources)) {
            return start;
        }
        initCopier();

        Boolean[] ss = (Boolean[]) sources;
        for (Boolean source : ss) {
            start = copier.copyToCharArray(source, cs, start);
        }
        return start;
    }


    private void initCopier() {
        if (Objects.isNull(copier)) {
            copier = ACopier.getCopier(Boolean.class);
        }
    }

}
