package com.nasa.runtime.core.socket.cloud;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.nasa.runtime.core.base.ObjectPool;
import com.nasa.runtime.core.evt.EvtData;
import com.nasa.runtime.core.socket.entity.Message;
import com.nasa.runtime.core.utils.ContextUtils;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;

/**
 * Nasa
 * 集群事件驱动消息体
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@Setter
@Getter
public class EventMessage<T> extends EvtData<Message<T>> implements ObjectPool.Recycler<EventMessage<T>>, Serializable {

    @Serial
    private static final long serialVersionUID = -8163570706993970846L;

    /* 对象池 */
    static final ObjectPool<EventMessage<Object>> POOL = new ObjectPool<>(
            ContextUtils.getPropertyInt("nasa.object-pool.event-message-capacity", 10000)) {
        @Override
        public EventMessage<Object> newObject() {
            return new EventMessage<>();
        }
    };

    private final ObjectPool.PooledHandle<EventMessage<T>> handle = (ObjectPool.PooledHandle) new ObjectPool.PooledHandle<>(POOL);

    /* redis 集群通信才有，指定是哪个redisProxy来执行 */
    private String qualifier;
    /* 回调 topic */
    private String callbackTopic;
    /* 回调 event */
    private String callbackEvent;
    /* 集群通信发送/响应事件 */
    private String cloudEvent;

    /**
     * 从对象池中获取一个对象
     * JsonCreator 这个注解让jackson反序列化框架默认通过这个静态方法创建对象，而不再是默认的空构造方法
     */
    @JsonCreator
    public static <T> EventMessage<T> of() {
        return (EventMessage<T>) POOL.get();
    }

    public static <T> EventMessage<T> of(String cloudEvent, Message<T> message) {
        return of(null, cloudEvent, message);
    }

    public static <T> EventMessage<T> of(String callbackTopic, String cloudEvent, Message<T> message) {
        return of(callbackTopic, null, cloudEvent, message);
    }

    public static <T> EventMessage<T> of(String callbackTopic, String callbackEvent, String cloudEvent, Message<T> message) {
        EventMessage<T> em = (EventMessage<T>) POOL.get();
        em.callbackTopic = callbackTopic;
        em.callbackEvent = callbackEvent;
        em.cloudEvent = cloudEvent;
        em.setData(message);
        return em;
    }

    @Override
    public ObjectPool.PooledHandle<EventMessage<T>> handle() {
        return this.handle;
    }

    @Override
    public void restore() {
        super.setTopic(null);
        super.setEvent(null);
        super.setData(null);
        this.qualifier = null;
        this.callbackTopic = null;
        this.callbackEvent = null;
        this.cloudEvent = null;
    }
}
