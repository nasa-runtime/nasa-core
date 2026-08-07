package io.github.nasaruntime.core.exception;

import io.github.nasaruntime.core.utils.Translator;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;

/**
 * Nasa
 * message会执行Google翻译
 */
@SuppressWarnings("unused")
@Setter
@Getter
public class TranslateException extends BaseException {

    @Serial
    private static final long serialVersionUID = -5291084810513068561L;

    /**
     * 业务作用：构造不带描述的可翻译异常，供调用方随后自行补充 code 与 message。
     *
     * 参数说明: 无。
     * 返回: 状态码为默认 400 的异常实例。
     */
    public TranslateException() {}

    /**
     * 业务作用：包装底层异常并关闭日志回显。此路径的 message 直接取自原异常、不做翻译，
     * 因为原始技术性文案翻译后既无收益又会掩盖检索关键词。
     *
     * @param e 被包装的原始异常
     * 返回: message 取自原异常、cause 指向原异常、echoLog 为 false 的实例。
     */
    public TranslateException(Throwable e) {
        super(e.getMessage(), e);
        super.setEchoLog(false);
    }

    /**
     * 业务作用：面向使用方的可展示异常。message 先经 Translator 翻译再交父类做占位符替换，
     * 因此翻译作用于模板而非替换后的结果，业务数据不会被误翻。同时关闭日志回显，
     * 因为这类异常属于预期内的业务提示，不应污染错误日志。
     *
     * @param message 待翻译的异常描述，可含 {} 占位符
     * @param params 占位符替换值；其中的 Throwable 将被识别为根因
     * 返回: 描述已翻译并格式化、echoLog 为 false 的异常实例。
     */
    public TranslateException(String message, Object... params) {
        super(Translator.translate(message), params);
        super.setEchoLog(false);
    }

    /**
     * 业务作用：在可翻译描述之外同时指定业务状态码，供上层按 code 分支处理。
     * 翻译同样作用于模板而非替换后的结果，并关闭日志回显。
     *
     * @param code 业务状态码，覆盖默认的 400
     * @param message 待翻译的异常描述，可含 {} 占位符
     * @param params 占位符替换值；其中的 Throwable 将被识别为根因
     * 返回: 携带指定状态码、描述已翻译并格式化、echoLog 为 false 的异常实例。
     */
    public TranslateException(Integer code, String message, Object... params) {
        super(code, Translator.translate(message), params);
        super.setEchoLog(false);
    }

}
