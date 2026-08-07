package io.github.nasaruntime.core.function;

import java.io.Serializable;
import java.util.function.Supplier;

@FunctionalInterface
public interface SerSupplier<T> extends Supplier<T>, Serializable {

}
