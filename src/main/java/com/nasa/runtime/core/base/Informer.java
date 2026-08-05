package com.nasa.runtime.core.base;

import com.nasa.runtime.core.exception.BaseException;
import com.nasa.runtime.core.utils.ColUtils;
import com.nasa.runtime.core.utils.StringUtils;

import java.util.Objects;

/**
 * Nasa
 * 消息通知
 */
public interface Informer extends Initialization {

    /**
     * 通知接口
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
     * 通知接口
     */
    void inform(String msg);

}
