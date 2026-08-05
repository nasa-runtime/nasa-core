package com.nasa.runtime.core.exception;

import java.io.Serial;

/**
 * Nasa
 * 函数操作异常
 */
@SuppressWarnings("unused")
public class FunctionException extends BaseException {

    @Serial
    private static final long serialVersionUID = -8488755623572910411L;

    public FunctionException() {}

    public FunctionException(Throwable e) {
        super(e);
    }

    /**
     * @param message 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public FunctionException(String message, Object... params) {
        super(message, params);
    }

    /**
     * @param code 异常状态码
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public FunctionException(Integer code, String msg, Object... params) {
        super(code, msg, params);
    }
}
