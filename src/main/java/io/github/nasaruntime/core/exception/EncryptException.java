package io.github.nasaruntime.core.exception;

import java.io.Serial;

/**
 * Nasa
 * 加密操作异常
 */
@SuppressWarnings("unused")
public class EncryptException extends BaseException {

    @Serial
    private static final long serialVersionUID = -9145265514055692851L;

    /**
     * 业务作用：构造不带描述的加密异常，供调用方随后自行补充 code 与 message。
     *
     * 参数说明: 无。
     * 返回: 状态码为父类默认值 400 的异常实例。
     */
    public EncryptException() {}

    /**
     * 业务作用：包装底层异常为加密异常，保留原始 message 与 cause，使调用方不必感知底层实现类型。
     *
     * @param e 被包装的原始异常
     * 返回: message 取自原异常、cause 指向原异常的实例。
     */
    public EncryptException(Throwable e) {
        super(e);
    }

    /**
     * 业务作用：以带占位符的描述报告加密失败，用于明文、密钥或算法参数不合法导致加密无法完成。
     * 描述中的 {} 会被 params 依次替换；params 中若含 Throwable 会被取出作为 cause，不参与占位符替换。
     *
     * @param message 异常描述，可含 {} 占位符
     * @param params 占位符替换值；其中的 Throwable 将被识别为根因
     * 返回: 携带格式化后描述的异常实例；未传 params 时描述原样保留。
     */
    public EncryptException(String message, Object... params) {
        super(message, params);
    }

    /**
     * 业务作用：在描述之外同时指定业务状态码，供上层按 code 分支处理加密失败。
     *
     * @param code 业务状态码，覆盖父类默认的 400
     * @param msg 异常描述，可含 {} 占位符
     * @param params 占位符替换值；其中的 Throwable 将被识别为根因
     * 返回: 携带指定状态码与格式化描述的异常实例。
     */
    public EncryptException(Integer code, String msg, Object... params) {
        super(code, msg, params);
    }
}
