package io.github.nasaruntime.core.socket.entity;

import io.github.nasaruntime.core.utils.ObjMprUtils;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;

/**
 * Nasa
 */
@Getter
@Setter
public class Message<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = -4382511952827234252L;

    /**
     * 消息客户端连接 SocketIOClient.toString()
     * 用于排除发送消息的 SocketIOClient 本身
     */
    private String client;

    /* 消息来源的uid */
    private String fromUid;

    /* 路由，为null表示当前路由 */
    private String[] routers;

    /* 事件，为null表示当前事件 */
    private String[] events;

    /* 群聊的群id */
    private String group;

    /**
     * 单聊的用户id
     * 如果 {@code group == null && uids == null}
     * 表示当前方法接收到消息的用户client
     * 如果 group != null，表示群聊，uids条件作废
     */
    private String[] uids;

    /* 排除的uid，同时对group和uids生效 */
    private String[] excludes;

    /* 消息 */
    private T message;


    /**
     * 业务作用：链式设置消息来源的客户端连接标识。该标识用于在广播时排除发送者本身，
     * 避免消息回弹到自己那条连接。
     *
     * @param client 客户端连接标识，取自 SocketIOClient.toString()
     * 返回: 当前实例，供链式调用。
     */
    public Message<T> client(String client) {
        this.client = client;
        return this;
    }


    /**
     * 业务作用：链式设置消息发送方的用户标识，供接收端展示来源和做权限判定。
     *
     * @param fromUid 发送方用户 id
     * 返回: 当前实例，供链式调用。
     */
    public Message<T> fromUid(String fromUid) {
        this.fromUid = fromUid;
        return this;
    }


    /**
     * 业务作用：链式设置目标路由。不设置表示投递到当前路由。
     *
     * @param routers 目标路由名；不传或传 null 表示沿用当前路由
     * 返回: 当前实例，供链式调用。
     */
    public Message<T> routers(String... routers) {
        this.routers = routers;
        return this;
    }


    /**
     * 业务作用：链式设置目标事件名。不设置表示沿用当前事件。
     *
     * @param events 目标事件名；不传或传 null 表示沿用当前事件
     * 返回: 当前实例，供链式调用。
     */
    public Message<T> events(String... events) {
        this.events = events;
        return this;
    }


    /**
     * 业务作用：链式设置群聊目标。一旦设置群 id，本条消息按群广播，
     * {@code uids} 指定的单聊目标随即作废，两者不叠加。
     *
     * @param group 群 id
     * 返回: 当前实例，供链式调用。
     */
    public Message<T> group(String group) {
        this.group = group;
        return this;
    }


    /**
     * 业务作用：链式设置单聊目标用户。仅在未设置 {@code group} 时生效；
     * 若 group 与 uids 都未设置，表示投递给当前收到消息的那条客户端连接。
     *
     * @param uids 目标用户 id
     * 返回: 当前实例，供链式调用。
     */
    public Message<T> uids(String... uids) {
        this.uids = uids;
        return this;
    }


    /**
     * 业务作用：链式设置排除的用户。排除对群聊和单聊同时生效，
     * 用于群发时跳过特定成员。
     *
     * @param excludes 需要排除的用户 id
     * 返回: 当前实例，供链式调用。
     */
    public Message<T> excludes(String... excludes) {
        this.excludes = excludes;
        return this;
    }


    /**
     * 业务作用：链式设置消息体。
     *
     * @param message 业务消息内容
     * 返回: 当前实例，供链式调用。
     */
    public Message<T> message(T message) {
        this.message = message;
        return this;
    }


    /**
     * 业务作用：输出全部字段的可读形式，供投递链路排障。
     *
     * 参数说明: 无。
     * 返回: 由 ObjMprUtils 渲染的字段快照。
     */
    @Override
    public String toString() {
        return ObjMprUtils.toString(this);
    }

}
