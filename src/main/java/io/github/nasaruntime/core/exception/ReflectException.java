package io.github.nasaruntime.core.exception;

import java.io.Serial;

/**
 * Nasa
 * 反射处理异常
 * <p>
 * 本类只负责抛出，不产生任何日志副作用。构造异常时既记录日志又向上抛出属于重复上报：
 * 调用方可能已经捕获并降级（例如探测可选依赖是否存在），此时日志纯属噪音；
 * 而在热路径上反复失败时，逐次渲染并打印完整调用栈足以压垮日志管道。
 * 是否记录、以什么级别记录，由真正掌握业务上下文的捕获方决定。
 */
@SuppressWarnings("unused")
public class ReflectException extends BaseException {

    @Serial
    private static final long serialVersionUID = 5492818712271296367L;

    /**
     * 业务作用：构造不带描述的反射调用异常，供调用方随后自行补充 code 与 message。
     *
     * 参数说明: 无。
     * 返回: 状态码为父类默认值 400 的异常实例。
     */
    public ReflectException() {}

    /**
     * 业务作用：以明确描述报告反射调用失败，用于类、字段或方法无法定位，或反射调用本身抛错。
     *
     * @param msg 异常描述
     * 返回: 携带该描述、状态码为默认 400 的异常实例。
     */
    public ReflectException(String msg) {
        super(msg);
    }

    /**
     * 业务作用：以带占位符的描述报告反射调用失败，用于类、字段或方法无法定位，或反射调用本身抛错。
     * 描述中的 {} 会被 params 依次替换；params 中若含 Throwable 会被父类取出作为 cause，
     * 不参与占位符替换，因此调用方可以把根因和格式化参数混在同一个变长列表里传入。
     *
     * @param msg 异常描述，可含 {} 占位符
     * @param params 占位符替换值；其中的 Throwable 将被识别为根因
     * 返回: 携带格式化后描述的异常实例；未传 params 时描述原样保留。
     */
    public ReflectException(String msg, Object... params) {
        super(msg, params);
    }

    /**
     * 业务作用：在描述之外同时指定业务状态码，供上层按 code 分支处理反射调用失败。
     *
     * @param code 业务状态码，覆盖父类默认的 400
     * @param msg 异常描述，可含 {} 占位符
     * @param params 占位符替换值；其中的 Throwable 将被识别为根因
     * 返回: 携带指定状态码与格式化描述的异常实例。
     */
    public ReflectException(Integer code, String msg, Object... params) {
        super(code, msg, params);
    }

}
