package com.nasa.runtime.core.exception;

import java.io.Serial;

/**
 * Nasa
 * zip处理异常
 */
@SuppressWarnings("unused")
public class ZipOprException extends BaseException {

    @Serial
    private static final long serialVersionUID = 5239367351017727361L;

    public ZipOprException() {}

    public ZipOprException(String msg) {
        super(msg);
    }

    /**
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public ZipOprException(String msg, Object... params) {
        super(msg, params);
    }

    /**
     * @param code 异常状态码
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public ZipOprException(Integer code, String msg, Object... params) {
        super(code, msg, params);
    }

}
