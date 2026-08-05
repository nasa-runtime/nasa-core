package com.nasa.runtime.core.exception;

import java.io.Serial;

/**
 * Nasa
 * 用于在特定条件下结束当前业务流程，同时向上层返回可正常展示的提示信息。
 * 是否回滚由使用方的事务边界按异常类型自行决定。
 */
@SuppressWarnings("unused")
public class NoRollbackException extends TranslateException {

    @Serial
    private static final long serialVersionUID = -5992616560505614115L;

    /**
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public NoRollbackException(String msg, Object... params) {
        this(200, msg, params);
    }

    /**
     * @param code 异常状态码
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public NoRollbackException(Integer code, String msg, Object... params) {
        super(code, msg, params);
    }
}
