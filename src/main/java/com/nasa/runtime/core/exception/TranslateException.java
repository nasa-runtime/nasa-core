package com.nasa.runtime.core.exception;

import com.nasa.runtime.core.utils.Translator;
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

    public TranslateException() {}

    public TranslateException(Throwable e) {
        super(e.getMessage(), e);
        super.setEchoLog(false);
    }

    /**
     * @param message 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public TranslateException(String message, Object... params) {
        super(Translator.translate(message), params);
        super.setEchoLog(false);
    }

    /**
     * @param code 异常状态码
     * @param message 异常描述
     * @param params 替换异常描述中的{}字符组合
     */
    public TranslateException(Integer code, String message, Object... params) {
        super(code, Translator.translate(message), params);
        super.setEchoLog(false);
    }

}
