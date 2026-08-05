package com.nasa.runtime.core.evt;

import com.nasa.runtime.core.utils.ColUtils;
import com.nasa.runtime.core.utils.StringUtils;

/**
 * Nasa
 * 集群事件驱动的主题
 */
public interface EventTopic {

    /**
     * 监听的 kafka topic / redis stream
     */
    default String[] topics() {
        String topic = topic();
        if (StringUtils.isBlank(topic)) {
            return null;
        }
        return ColUtils.toArray(topic);
    }

    /**
     * 监听的 kafka topic / redis stream
     */
    default String topic() {
        return null;
    }

}
