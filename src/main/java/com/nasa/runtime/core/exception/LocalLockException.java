package com.nasa.runtime.core.exception;

import com.nasa.runtime.core.utils.StringUtils;

import java.io.Serial;

/**
 * Nasa
 * 同步锁异常
 */
public class LocalLockException extends BaseException {

    @Serial
    private static final long serialVersionUID = 2178252983584015647L;

    public LocalLockException(String msg) {
        super(msg);
    }

    @Override
    public String toString() {
        String s = getClass().getName();
        String message = super.getLocalizedMessage();
        return message == null ? s : StringUtils.concat(s, ": ", message);
    }

}
