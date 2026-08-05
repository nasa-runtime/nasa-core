package com.nasa.runtime.core.exception;

import java.io.Serial;

/**
 * Nasa
 * 序列化、反序列化处理异常
 */
@SuppressWarnings("unused")
public class JsonException extends BaseException {

    @Serial
    private static final long serialVersionUID = 4172952241333811104L;

    public JsonException() {}

    public JsonException(String msg) {
        super(msg);
    }

    /**
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public JsonException(String msg, Object... params) {
        super(msg, params);
    }

    /**
     * @param code 异常状态码
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public JsonException(Integer code, String msg, Object... params) {
        super(code, msg, params);
    }

}
