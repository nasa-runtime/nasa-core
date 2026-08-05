package com.nasa.runtime.core.exception;

import java.io.Serial;

/**
 * Nasa
 * Telegram处理异常
 */
@SuppressWarnings("unused")
public class TelegramException extends BaseException {

    @Serial
    private static final long serialVersionUID = -2288627862658240414L;

    public TelegramException() {}

    public TelegramException(String msg) {
        super(msg);
    }

    /**
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public TelegramException(String msg, Object... params) {
        super(msg, params);
    }

    /**
     * @param code 异常状态码
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public TelegramException(Integer code, String msg, Object... params) {
        super(code, msg, params);
    }

}
