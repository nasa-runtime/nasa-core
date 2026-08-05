package com.nasa.runtime.core.exception;

import com.nasa.runtime.core.utils.ColUtils;
import com.nasa.runtime.core.utils.ObjMprUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.Serial;
import java.util.List;

/**
 * Nasa
 * 反射处理异常
 */
@Slf4j
@SuppressWarnings("unused")
public class ReflectException extends BaseException {

    @Serial
    private static final long serialVersionUID = 5492818712271296367L;

    public ReflectException() {}

    public ReflectException(String msg) {
        super(msg);
        this.stack();
    }

    /**
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public ReflectException(String msg, Object... params) {
        super(msg, params);
        this.stack();
        Throwable e = ColUtils.first(params, t -> t instanceof Throwable, t -> (Throwable) t);
        List<Object> list = ColUtils.filterToList(params, t -> !(t instanceof Throwable));
        if (e == null) {
            log.error(" msg: {}, params: {}", msg, ObjMprUtils.toString(list));
        } else {
            log.error(" msg: {}, params: {}", msg, ObjMprUtils.toString(list), e);
        }
    }

    /**
     * @param code 异常状态码
     * @param msg 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public ReflectException(Integer code, String msg, Object... params) {
        super(code, msg, params);
        this.stack();
    }

    void stack() {
        try {
            throw new NullPointerException();
        } catch (NullPointerException e) {
            String s = stackToString(e);
            log.error(s);
        }
    }

}
