package com.nasa.runtime.core.exception;

import java.io.Serial;

/**
 * Nasa
 * 加密操作异常
 */
@SuppressWarnings("unused")
public class EncryptException extends BaseException {

    @Serial
    private static final long serialVersionUID = -9145265514055692851L;

    public EncryptException() {}

    public EncryptException(Throwable e) {
        super(e);
    }

    /**
     * @param message 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public EncryptException(String message, Object... params) {
        super(message, params);
    }

    /**
     * @param code 异常状态码
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public EncryptException(Integer code, String msg, Object... params) {
        super(code, msg, params);
    }
}
