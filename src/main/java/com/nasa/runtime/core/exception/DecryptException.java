package com.nasa.runtime.core.exception;

import java.io.Serial;

/**
 * Nasa
 * 解密操作异常
 */
@SuppressWarnings("unused")
public class DecryptException extends BaseException {

    @Serial
    private static final long serialVersionUID = 196781214156964314L;

    public DecryptException() {}

    public DecryptException(Throwable e) {
        super(e);
    }

    /**
     * @param message 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public DecryptException(String message, Object... params) {
        super(message, params);
    }

    /**
     * @param code 异常状态码
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public DecryptException(Integer code, String msg, Object... params) {
        super(code, msg, params);
    }
}
