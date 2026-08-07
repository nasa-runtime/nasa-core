package io.github.nasaruntime.core.exception;

import io.github.nasaruntime.core.utils.ColUtils;
import io.github.nasaruntime.core.utils.StringUtils;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

/**
 * Nasa
 */
@Setter
@Getter
public class BaseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = -6839240304550785245L;

    private int code = 400;
    /* 是否需要打印日志 */
    private boolean echoLog = true;

    /**
     * 业务作用：构造不带描述的基础业务异常，供子类或调用方随后自行补充 code 与 message。
     *
     * 参数说明: 无。
     * 返回: 状态码为默认 400、echoLog 为 true 的异常实例。
     */
    public BaseException() {}

    /**
     * 业务作用：包装底层异常为统一业务异常，保留原始 message 与 cause，
     * 使上层只需感知本异常体系而不必依赖底层实现类型。
     *
     * @param e 被包装的原始异常
     * 返回: message 取自原异常、cause 指向原异常的实例。
     */
    public BaseException(Throwable e) {
        super(e.getMessage(), e);
    }

    /**
     * 业务作用：本异常体系的主构造入口。描述中的 {} 按序被 params 替换；
     * params 中若含 Throwable 会被取出作为 cause 而不参与占位符替换，
     * 因此调用方可以把根因和格式化参数混在同一个变长列表里传入。
     *
     * @param message 异常描述，可含 {} 占位符
     * @param params 占位符替换值；其中的 Throwable 将被识别为根因
     * 返回: 携带格式化后描述的异常实例；params 为空时描述原样保留，不做格式化。
     */
    public BaseException(String message, Object... params) {
        super(format(message, params));
        if (ColUtils.isEmpty(params)) {
            return;
        }
        Throwable e = ColUtils.first(params, t -> t instanceof Throwable, t -> (Throwable) t);
        if (Objects.nonNull(e)) {
            super.initCause(e);
        }
    }

    /**
     * 业务作用：在描述之外同时指定业务状态码，供上层按 code 分支处理而不是解析文案。
     *
     * @param code 业务状态码，覆盖默认的 400
     * @param message 异常描述，可含 {} 占位符
     * @param params 占位符替换值；其中的 Throwable 将被识别为根因
     * 返回: 携带指定状态码与格式化描述的异常实例。
     */
    public BaseException(Integer code, String message, Object... params) {
        this(message, params);
        this.code = code;
    }


    /**
     * 业务作用：把描述与占位符参数合成最终文案。先剔除 params 中的 Throwable
     * （它们属于根因而非展示数据），剩余项才参与替换，避免把堆栈对象拼进用户可见文案。
     *
     * @param message 异常描述，可含 {} 占位符
     * @param params 混合了根因与展示数据的变长参数
     * 返回: 格式化后的描述；params 为空或剔除 Throwable 后为空时原样返回 message。
     */
    protected static String format(String message, Object... params) {
        if (ColUtils.isEmpty(params)) {
            return message;
        }
        List<Object> objects = ColUtils.toList(params, t -> !(t instanceof Throwable), t -> t);
        if (ColUtils.isEmpty(objects)) {
            return message;
        }
        return StringUtils.format(message, objects.toArray());
    }


    /**
     * 业务作用：以 JSON 片段形式输出状态码与描述，便于日志检索时直接按 code 过滤。
     *
     * 参数说明: 无。
     * 返回: 形如 {@code 类名: {"code":400, "message":"..."}} 的字符串；message 为 null 时返回 "null"。
     */
    @Override
    public String toString() {
        return this.getMessage() == null ? "null" : StringUtils.concat(
                getClass().getName()
                , ": {\"code\":"
                , this.code
                , ", \"message\":"
                , '"', this.getMessage(), '"'
                , '}');
    }

    /**
     * 业务作用：沿 cause 链递归下钻到最原始的异常，供日志和排障定位真正的失败点，
     * 而不是停在最外层的包装异常上。
     *
     * @param t 起点异常
     * 返回: 链路最末端、自身不再有 cause 的异常；t 本身无 cause 时返回 t。
     */
    public static Throwable cause(Throwable t) {
        Throwable cause = t.getCause();
        return Objects.isNull(cause) ? t : cause(cause);
    }

    /**
     * 业务作用：把异常堆栈渲染成可直接落日志的文本。设有 60000 字符上限，
     * 超限即截断并追加省略标记，防止单条异常日志撑爆日志管道或磁盘。
     *
     * @param t 待渲染堆栈的异常
     * 返回: 逐帧的堆栈文本；超过长度上限时以 "... common frames omitted" 结尾。
     */
    public static String stackToString(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement stack : t.getStackTrace()) {
            sb.append("at ")
                    .append(stack.getClassName())
                    .append(".")
                    .append(stack.getMethodName())
                    .append("(")
                    .append(stack.getLineNumber())
                    .append(")")
                    .append("\n");
            if (sb.length() > 60000) {
                sb.append("... common frames omitted");
                break;
            }
        }
        return sb.toString();
    }

}
