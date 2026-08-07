package io.github.nasaruntime.core.exception;

import java.io.Serial;

/**
 * Nasa
 * 缓存异常
 */
@SuppressWarnings("unused")
public class CacheException extends BaseException {

    @Serial
    private static final long serialVersionUID = 8701712004781396351L;

    /**
     * 业务作用：构造不带描述的缓存读写异常，供调用方随后自行补充 code 与 message。
     *
     * 参数说明: 无。
     * 返回: 状态码为父类默认值 400 的异常实例。
     */
    public CacheException() {}

    /**
     * 业务作用：以带占位符的描述报告缓存读写失败，用于缓存组件无法完成加载、写入或失效操作。
     * 描述中的 {} 会被 params 依次替换；params 中若含 Throwable 会被取出作为 cause，不参与占位符替换。
     *
     * @param msg 异常描述，可含 {} 占位符
     * @param params 占位符替换值；其中的 Throwable 将被识别为根因
     * 返回: 携带格式化后描述的异常实例；未传 params 时描述原样保留。
     */
    public CacheException(String msg, Object... params) {
        super(msg, params);
    }

    /**
     * 业务作用：在描述之外同时指定业务状态码，供上层按 code 分支处理缓存读写失败。
     *
     * @param code 业务状态码，覆盖父类默认的 400
     * @param msg 异常描述，可含 {} 占位符
     * @param params 占位符替换值；其中的 Throwable 将被识别为根因
     * 返回: 携带指定状态码与格式化描述的异常实例。
     */
    public CacheException(Integer code, String msg, Object... params) {
        super(code, msg, params);
    }

}
