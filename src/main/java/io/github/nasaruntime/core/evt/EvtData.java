package io.github.nasaruntime.core.evt;

import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 通用事件数据载体。承载 topic + event + data 三元组, 不绑定任何传输层。
 */
@Getter
@Setter
public class EvtData<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1874840441200160113L;

    private String topic;
    private String event;
    private T data;
    /* 透传信息封装 */
    private Map<String, Object> passthrough;
}
