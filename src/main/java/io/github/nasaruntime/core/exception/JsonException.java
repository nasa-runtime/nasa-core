package io.github.nasaruntime.core.exception;

import java.io.Serial;

/**
 * Nasa
 * 序列化、反序列化处理异常
 */
@SuppressWarnings("unused")
public class JsonException extends BaseException {

    @Serial
    private static final long serialVersionUID = 4172952241333811104L;

    /**
     * 业务作用：构造不带描述的JSON 序列化与反序列化异常，供调用方随后自行补充 code 与 message。
     *
     * 参数说明: 无。
     * 返回: 状态码为父类默认值 400 的异常实例。
     */
    public JsonException() {}

    /**
     * 业务作用：以明确描述报告JSON 序列化与反序列化失败，用于结构不匹配、类型不兼容或输入非法 JSON。
     *
     * @param msg 异常描述
     * 返回: 携带该描述、状态码为默认 400 的异常实例。
     */
    public JsonException(String msg) {
        super(msg);
    }

    /**
     * 业务作用：以带占位符的描述报告JSON 序列化与反序列化失败，用于结构不匹配、类型不兼容或输入非法 JSON。
     * 描述中的 {} 会被 params 依次替换；params 中若含 Throwable 会被取出作为 cause，不参与占位符替换。
     *
     * @param msg 异常描述，可含 {} 占位符
     * @param params 占位符替换值；其中的 Throwable 将被识别为根因
     * 返回: 携带格式化后描述的异常实例；未传 params 时描述原样保留。
     */
    public JsonException(String msg, Object... params) {
        super(msg, params);
    }

    /**
     * 业务作用：在描述之外同时指定业务状态码，供上层按 code 分支处理JSON 序列化与反序列化失败。
     *
     * @param code 业务状态码，覆盖父类默认的 400
     * @param msg 异常描述，可含 {} 占位符
     * @param params 占位符替换值；其中的 Throwable 将被识别为根因
     * 返回: 携带指定状态码与格式化描述的异常实例。
     */
    public JsonException(Integer code, String msg, Object... params) {
        super(code, msg, params);
    }

}
