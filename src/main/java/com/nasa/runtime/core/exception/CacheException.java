package com.nasa.runtime.core.exception;

import java.io.Serial;

/**
 * Nasa
 * 缓存异常
 */
@SuppressWarnings("unused")
public class CacheException extends BaseException {

    @Serial
    private static final long serialVersionUID = 8701712004781396351L;

    public CacheException() {}

    /**
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public CacheException(String msg, Object... params) {
        super(msg, params);
    }

    /**
     * @param code 异常状态码
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public CacheException(Integer code, String msg, Object... params) {
        super(code, msg, params);
    }

}
