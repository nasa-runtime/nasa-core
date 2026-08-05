package com.nasa.runtime.core.socket.entity;

import com.nasa.runtime.core.utils.ObjMprUtils;
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


    public Message<T> client(String client) {
        this.client = client;
        return this;
    }


    public Message<T> fromUid(String fromUid) {
        this.fromUid = fromUid;
        return this;
    }


    public Message<T> routers(String... routers) {
        this.routers = routers;
        return this;
    }


    public Message<T> events(String... events) {
        this.events = events;
        return this;
    }


    public Message<T> group(String group) {
        this.group = group;
        return this;
    }


    public Message<T> uids(String... uids) {
        this.uids = uids;
        return this;
    }


    public Message<T> excludes(String... excludes) {
        this.excludes = excludes;
        return this;
    }


    public Message<T> message(T message) {
        this.message = message;
        return this;
    }


    @Override
    public String toString() {
        return ObjMprUtils.toString(this);
    }

}
