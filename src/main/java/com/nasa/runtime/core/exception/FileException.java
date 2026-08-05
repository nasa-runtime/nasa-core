package com.nasa.runtime.core.exception;

import java.io.Serial;

/**
 * Nasa
 * 文件处理异常
 */
@SuppressWarnings("unused")
public class FileException extends BaseException {

    @Serial
    private static final long serialVersionUID = -5858325577204739891L;

    public FileException() {}

    public FileException(String msg) {
        super(msg);
    }

    /**
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public FileException(String msg, Object... params) {
        super(msg, params);
    }

    /**
     * @param code 异常状态码
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public FileException(Integer code, String msg, Object... params) {
        super(code, msg, params);
    }

}
