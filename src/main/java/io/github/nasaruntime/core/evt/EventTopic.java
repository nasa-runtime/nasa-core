package io.github.nasaruntime.core.evt;

import io.github.nasaruntime.core.utils.ColUtils;
import io.github.nasaruntime.core.utils.StringUtils;

/**
 * Nasa
 * 集群事件驱动的主题
 */
public interface EventTopic {

    /**
     * 业务作用：给出本监听器订阅的全部 kafka topic 或 redis stream。
     * 默认实现把单值 topic() 提升为数组，需要订阅多个主题的实现可直接覆写本方法。
     *
     * 参数说明: 无。
     * 返回: 主题数组；topic() 为空白时返回 null，表示未声明订阅。
     */
    default String[] topics() {
        String topic = topic();
        if (StringUtils.isBlank(topic)) {
            return null;
        }
        return ColUtils.toArray(topic);
    }

    /**
     * 业务作用：给出单个订阅主题，是只监听一个主题时的简化入口。
     *
     * 参数说明: 无。
     * 返回: 主题名；默认返回 null，此时实现方应改为覆写 topics()。
     */
    default String topic() {
        return null;
    }

}
