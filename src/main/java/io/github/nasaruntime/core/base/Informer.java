package io.github.nasaruntime.core.base;

import io.github.nasaruntime.core.exception.BaseException;
import io.github.nasaruntime.core.utils.ColUtils;
import io.github.nasaruntime.core.utils.StringUtils;

import java.util.Objects;

/**
 * Nasa
 * 消息通知
 */
public interface Informer extends Initialization {

    /**
     * 业务作用：通知接口
     *
     * @param msg 见上述说明
     * @param params 见上述说明
     * 返回: 无返回值。
     */
    default void inform(String msg, Object... params) {
        if (ColUtils.isEmpty(params)) {
            this.inform(msg);
            return;
        }
        msg = StringUtils.format(msg, params);
        Object first = ColUtils.first(params, t -> t instanceof Throwable);
        if (Objects.isNull(first)) {
            this.inform(msg);
            return;
        }
        this.inform(msg + "\n" + BaseException.stackToString((Throwable) first));
    }

    /**
     * 业务作用：通知接口
     *
     * @param msg 见上述说明
     * 返回: 无返回值。
     */
    void inform(String msg);

}
