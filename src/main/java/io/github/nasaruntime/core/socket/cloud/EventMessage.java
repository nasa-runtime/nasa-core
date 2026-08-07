package io.github.nasaruntime.core.socket.cloud;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.github.nasaruntime.core.base.ObjectPool;
import io.github.nasaruntime.core.evt.EvtData;
import io.github.nasaruntime.core.socket.entity.Message;
import io.github.nasaruntime.core.utils.ContextUtils;
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
        /**
         * 业务作用：池空时创建新的集群事件消息载体。
         *
         * 参数说明: 无。
         * 返回: 字段均为初始值的新实例。
         */
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
     * 业务作用：从对象池取得一个空载体。标注 {@code @JsonCreator} 使 Jackson 反序列化时
     * 也走对象池而不是默认空构造，保证反序列化路径同样复用对象。
     *
     * 参数说明: 无。
     * 返回: 已复位、可直接填充的实例；用完必须 recycle 归池。
     */
    @JsonCreator
    public static <T> EventMessage<T> of() {
        return (EventMessage<T>) POOL.get();
    }

    /**
     * 业务作用：构造不需要回调的集群事件消息，用于单向通知。
     *
     * @param cloudEvent 集群通信事件名
     * @param message 业务消息体
     * 返回: 已填充的实例；用完必须 recycle 归池。
     */
    public static <T> EventMessage<T> of(String cloudEvent, Message<T> message) {
        return of(null, cloudEvent, message);
    }

    /**
     * 业务作用：构造带回调主题的集群事件消息，供对端把响应投回指定主题。
     *
     * @param callbackTopic 回调主题
     * @param cloudEvent 集群通信事件名
     * @param message 业务消息体
     * 返回: 已填充的实例；用完必须 recycle 归池。
     */
    public static <T> EventMessage<T> of(String callbackTopic, String cloudEvent, Message<T> message) {
        return of(callbackTopic, null, cloudEvent, message);
    }

    /**
     * 业务作用：构造同时指定回调主题与回调事件的集群事件消息，是另外两个重载的统一收口。
     *
     * @param callbackTopic 回调主题
     * @param callbackEvent 回调事件名
     * @param cloudEvent 集群通信事件名
     * @param message 业务消息体
     * 返回: 已填充的实例；用完必须 recycle 归池，否则对象脱池泄漏。
     */
    public static <T> EventMessage<T> of(String callbackTopic, String callbackEvent, String cloudEvent, Message<T> message) {
        EventMessage<T> em = (EventMessage<T>) POOL.get();
        em.callbackTopic = callbackTopic;
        em.callbackEvent = callbackEvent;
        em.cloudEvent = cloudEvent;
        em.setData(message);
        return em;
    }

    /**
     * 业务作用：暴露池化身份，启用重复归池的 CAS 防御。
     *
     * 参数说明: 无。
     * 返回: 构造时绑定本类对象池的 handle。
     */
    @Override
    public ObjectPool.PooledHandle<EventMessage<T>> handle() {
        return this.handle;
    }

    /**
     * 业务作用：归池前清空全部字段，包括父类的 topic/event/data 与本类的回调与集群路由字段，
     * 防止上一条消息的目标信息泄漏给下一个借用方并造成错投。
     *
     * 参数说明: 无。
     * 返回: 无返回值；执行后实例可安全交给下一个借用方。
     */
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
