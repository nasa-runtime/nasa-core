package io.github.nasaruntime.core.exception;

import io.github.nasaruntime.core.utils.StringUtils;

import java.io.Serial;

/**
 * Nasa
 * 同步锁异常
 */
public class LocalLockException extends BaseException {

    @Serial
    private static final long serialVersionUID = 2178252983584015647L;

    /**
     * 业务作用：报告本地同步锁获取、释放或状态校验失败。
     *
     * @param msg 异常描述
     * 返回: 携带该描述、状态码为默认 400 的异常实例。
     */
    public LocalLockException(String msg) {
        super(msg);
    }

    /**
     * 业务作用：覆盖父类的 JSON 式输出，改为「类名: 描述」的简洁形式。
     * 锁异常通常在密集重试路径上产生，简短文案可显著降低日志体积。
     *
     * 参数说明: 无。
     * 返回: 形如 {@code 类名: 描述} 的字符串；描述为 null 时只返回类名。
     */
    @Override
    public String toString() {
        String s = getClass().getName();
        String message = super.getLocalizedMessage();
        return message == null ? s : StringUtils.concat(s, ": ", message);
    }

}
