package io.github.nasaruntime.core.exception;

import java.io.Serial;

/**
 * 不需要任何操作
 */
public class NoneException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 4088463878980568632L;

    /**
     * 业务作用：私有化构造，强制通过单例常量 NONE 使用。
     * 本异常表示「无需任何操作」这一控制流信号而非真实故障，
     * 单例可避免为一个不携带任何信息的信号反复分配对象和采集堆栈。
     *
     * 参数说明: 无。
     * 返回: 唯一实例，仅由类内的 NONE 常量持有。
     */
    private NoneException() {}

    public static final NoneException NONE = new NoneException();
}
