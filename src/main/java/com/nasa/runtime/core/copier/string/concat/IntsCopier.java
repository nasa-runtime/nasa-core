package com.nasa.runtime.core.copier.string.concat;

import java.util.Objects;


/**
 * Nasa
 */
@SuppressWarnings("unused")
public class IntsCopier extends ACopier {

    private static ACopier copier;

    @Override
    public String copier() {
        return "int[]";
    }

    @Override
    public int length(Object sources) {
        if (Objects.isNull(sources)) {
            return 0;
        }
        initCopier();

        int[] ss = (int[]) sources;
        int length = 0;
        for (int source : ss) {
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

        int[] ss = (int[]) sources;
        for (int source : ss) {
            start = copier.copyToCharArray(source, cs, start);
        }
        return start;
    }


    private void initCopier() {
        if (Objects.isNull(copier)) {
            copier = ACopier.getCopier(Integer.class);
        }
    }


}
