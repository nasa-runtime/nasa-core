package io.github.nasaruntime.core.exception;

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
     * 业务作用：以默认状态码 200 结束当前业务流程，同时向上层返回可正常展示的提示。
     * 刻意用 200 而非 400，表示这是一次「按预期终止」而不是错误；
     * 是否回滚由使用方的事务边界按异常类型自行决定，本类不做任何事务控制。
     *
     * @param msg 面向使用方展示的提示，可含 {} 占位符
     * @param params 占位符替换值；其中的 Throwable 将被识别为根因
     * 返回: 状态码为 200、描述经父类翻译与格式化的异常实例。
     */
    public NoRollbackException(String msg, Object... params) {
        this(200, msg, params);
    }

    /**
     * 业务作用：以指定状态码结束当前业务流程并返回可展示提示，供需要区分终止原因的场景使用。
     *
     * @param code 业务状态码
     * @param msg 面向使用方展示的提示，可含 {} 占位符
     * @param params 占位符替换值；其中的 Throwable 将被识别为根因
     * 返回: 携带指定状态码、描述经父类翻译与格式化的异常实例。
     */
    public NoRollbackException(Integer code, String msg, Object... params) {
        super(code, msg, params);
    }
}
