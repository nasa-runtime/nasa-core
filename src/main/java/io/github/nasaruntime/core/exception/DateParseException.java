package io.github.nasaruntime.core.exception;

import java.io.Serial;

/**
 * Nasa
 * 日期转换异常
 */
@SuppressWarnings("unused")
public class DateParseException extends BaseException {

    @Serial
    private static final long serialVersionUID = 5602966591388061125L;

    /**
     * 业务作用：构造不带描述的日期解析与格式化异常，供调用方随后自行补充 code 与 message。
     *
     * 参数说明: 无。
     * 返回: 状态码为父类默认值 400 的异常实例。
     */
    public DateParseException() {}

    /**
     * 业务作用：以明确描述报告日期解析与格式化失败，用于输入串不匹配任何已知日期格式，或格式化目标非法。
     *
     * @param msg 异常描述
     * 返回: 携带该描述、状态码为默认 400 的异常实例。
     */
    public DateParseException(String msg) {
        super(msg);
    }

    /**
     * 业务作用：以带占位符的描述报告日期解析与格式化失败，用于输入串不匹配任何已知日期格式，或格式化目标非法。
     * 描述中的 {} 会被 params 依次替换；params 中若含 Throwable 会被取出作为 cause，不参与占位符替换。
     *
     * @param msg 异常描述，可含 {} 占位符
     * @param params 占位符替换值；其中的 Throwable 将被识别为根因
     * 返回: 携带格式化后描述的异常实例；未传 params 时描述原样保留。
     */
    public DateParseException(String msg, Object... params) {
        super(msg, params);
    }

    /**
     * 业务作用：在描述之外同时指定业务状态码，供上层按 code 分支处理日期解析与格式化失败。
     *
     * @param code 业务状态码，覆盖父类默认的 400
     * @param msg 异常描述，可含 {} 占位符
     * @param params 占位符替换值；其中的 Throwable 将被识别为根因
     * 返回: 携带指定状态码与格式化描述的异常实例。
     */
    public DateParseException(Integer code, String msg, Object... params) {
        super(code, msg, params);
    }

}
