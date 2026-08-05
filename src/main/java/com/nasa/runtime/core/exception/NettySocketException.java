package com.nasa.runtime.core.exception;

import java.io.Serial;

/**
 * Nasa
 * socket-center处理异常
 */
@SuppressWarnings("unused")
public class NettySocketException extends BaseException {

    @Serial
    private static final long serialVersionUID = 5961192268386723264L;

    public NettySocketException() {}

    public NettySocketException(String msg) {
        super(msg);
    }

    /**
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public NettySocketException(String msg, Object... params) {
        super(msg, params);
    }

    /**
     * @param code 异常状态码
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public NettySocketException(Integer code, String msg, Object... params) {
        super(code, msg, params);
    }

}
