package com.nasa.runtime.core.copier.string.concat;

import java.util.Collection;
import java.util.Objects;

/**
 * Nasa
 */
@SuppressWarnings({"unused", "rawtypes"})
public class CollectionCopier extends ACopier {

    public static final String Copier = "Collection";

    @Override
    public String copier() {
        return Copier;
    }

    @Override
    public int length(Object sources) {
        if (Objects.isNull(sources)) {
            return 0;
        }
        int length = 0;
        Collection col = (Collection) sources;
        for (Object source : col) {
            length += ACopier.getCopier(source.getClass()).length(source);
        }
        return length;
    }

    @Override
    public int copyToCharArray(Object sources, char[] cs, int start) {
        if (Objects.isNull(sources)) {
            return start;
        }
        Collection col = (Collection) sources;
        for (Object source : col) {
            start = ACopier.getCopier(source.getClass()).copyToCharArray(source, cs, start);
        }
        return start;
    }
}
