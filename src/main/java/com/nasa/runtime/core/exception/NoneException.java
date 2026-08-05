package com.nasa.runtime.core.exception;

import java.io.Serial;

/**
 * 不需要任何操作
 */
public class NoneException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 4088463878980568632L;

    private NoneException() {}

    public static final NoneException NONE = new NoneException();
}
