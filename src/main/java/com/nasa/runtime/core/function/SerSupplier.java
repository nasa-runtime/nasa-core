package com.nasa.runtime.core.function;

import java.io.Serializable;
import java.util.function.Supplier;

@FunctionalInterface
public interface SerSupplier<T> extends Supplier<T>, Serializable {

}
