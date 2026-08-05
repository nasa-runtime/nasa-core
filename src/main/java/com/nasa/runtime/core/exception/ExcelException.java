package com.nasa.runtime.core.exception;

import java.io.Serial;

/**
 * Nasa
 * excel处理异常
 */
@SuppressWarnings("unused")
public class ExcelException extends BaseException {

    @Serial
    private static final long serialVersionUID = 528060151429213857L;

    public ExcelException() {}

    public ExcelException(String msg) {
        super(msg);
    }

    /**
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public ExcelException(String msg, Object... params) {
        super(msg, params);
    }

    /**
     * @param code 异常状态码
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public ExcelException(Integer code, String msg, Object... params) {
        super(code, msg, params);
    }

}
