package com.nasa.runtime.core.exception;

import java.io.Serial;

/**
 * Nasa
 * google author验证器异常
 */
@SuppressWarnings("unused")
public class GoogleAuthorException extends BaseException {

    @Serial
    private static final long serialVersionUID = -6265141718768680580L;

    public GoogleAuthorException() {}

    /**
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public GoogleAuthorException(String msg, Object... params) {
        super(msg, params);
    }

    /**
     * @param code 异常状态码
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public GoogleAuthorException(Integer code, String msg, Object... params) {
        super(code, msg, params);
    }

}
