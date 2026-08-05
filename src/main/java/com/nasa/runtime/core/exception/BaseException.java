package com.nasa.runtime.core.exception;

import com.nasa.runtime.core.utils.ColUtils;
import com.nasa.runtime.core.utils.StringUtils;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

/**
 * Nasa
 */
@Setter
@Getter
public class BaseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = -6839240304550785245L;

    private int code = 400;
    /* 是否需要打印日志 */
    private boolean echoLog = true;

    public BaseException() {}

    public BaseException(Throwable e) {
        super(e.getMessage(), e);
    }

    /**
     * @param message 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public BaseException(String message, Object... params) {
        super(format(message, params));
        if (ColUtils.isEmpty(params)) {
            return;
        }
        Throwable e = ColUtils.first(params, t -> t instanceof Throwable, t -> (Throwable) t);
        if (Objects.nonNull(e)) {
            super.initCause(e);
        }
    }

    /**
     * @param code 异常状态码
     * @param message 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public BaseException(Integer code, String message, Object... params) {
        this(message, params);
        this.code = code;
    }


    /**
     * 字符串格式化输出
     * @param message 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    protected static String format(String message, Object... params) {
        if (ColUtils.isEmpty(params)) {
            return message;
        }
        List<Object> objects = ColUtils.toList(params, t -> !(t instanceof Throwable), t -> t);
        if (ColUtils.isEmpty(objects)) {
            return message;
        }
        return StringUtils.format(message, objects.toArray());
    }


    @Override
    public String toString() {
        return this.getMessage() == null ? "null" : StringUtils.concat(
                getClass().getName()
                , ": {\"code\":"
                , this.code
                , ", \"message\":"
                , '"', this.getMessage(), '"'
                , '}');
    }

    /**
     * 获取最原始的异常
     */
    public static Throwable cause(Throwable t) {
        Throwable cause = t.getCause();
        return Objects.isNull(cause) ? t : cause(cause);
    }

    /**
     * 格式化异常堆栈
     */
    public static String stackToString(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement stack : t.getStackTrace()) {
            sb.append("at ")
                    .append(stack.getClassName())
                    .append(".")
                    .append(stack.getMethodName())
                    .append("(")
                    .append(stack.getLineNumber())
                    .append(")")
                    .append("\n");
            if (sb.length() > 60000) {
                sb.append("... common frames omitted");
                break;
            }
        }
        return sb.toString();
    }

}
