package io.github.nasaruntime.core.evt;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.github.nasaruntime.core.base.ObjectPool;
import io.github.nasaruntime.core.utils.ContextUtils;

import java.io.Serial;

/**
 * {@link EvtData} 的池化版本。继承 EvtData 复用 topic + event + data 字段, 自带对象池回收。
 * <p>
 * 使用方式:
 * <pre>
 * PooledEvtData pm = PooledEvtData.of("topic", "event", data);
 * try {
 *     // 使用 pm ...
 * } finally {
 *     pm.recycle();
 * }
 * </pre>
 */
public class PooledEvtData extends EvtData<Object> implements ObjectPool.Recycler<PooledEvtData> {

    @Serial
    private static final long serialVersionUID = -4383992825483944168L;

    public static final String FIELD_TOPIC = "topic";
    public static final String FIELD_EVENT = "event";
    public static final String FIELD_DATA = "data";
    public static final String FIELD_PASSTHROUGH = "passthrough";

    static final ObjectPool<PooledEvtData> POOL = new ObjectPool<>(
            ContextUtils.getPropertyInt("nasa.object-pool.evt-data-capacity", 1000)) {
        /**
         * 业务作用：池空时创建新的事件数据载体。
         *
         * 参数说明: 无。
         * 返回: 字段均为初始值的新实例。
         */
        @Override
        public PooledEvtData newObject() {
            return new PooledEvtData();
        }
    };

    private final ObjectPool.PooledHandle<PooledEvtData> handle = new ObjectPool.PooledHandle<>(POOL);

    /**
     * 业务作用：从对象池取得一个空载体。标注 {@code @JsonCreator} 使 Jackson 反序列化时
     * 也走对象池而不是默认空构造，保证反序列化路径同样复用对象。
     *
     * 参数说明: 无。
     * 返回: 已复位、可直接填充的实例；用完必须调用 recycle 归池。
     */
    @JsonCreator
    public static PooledEvtData of() {
        return POOL.get();
    }

    /**
     * 业务作用：从对象池取得载体并一次性填好三个必需字段，供发布方直接使用。
     *
     * @param topic 目标主题
     * @param event 事件名
     * @param data 事件载荷
     * 返回: 已填充的实例；用完必须调用 recycle 归池，否则对象脱池泄漏。
     */
    public static PooledEvtData of(String topic, String event, Object data) {
        PooledEvtData e = of();
        e.setTopic(topic);
        e.setEvent(event);
        e.setData(data);
        return e;
    }

    /**
     * 业务作用：暴露池化身份，启用重复归池的 CAS 防御。
     *
     * 参数说明: 无。
     * 返回: 构造时绑定本类对象池的 handle。
     */
    @Override
    public ObjectPool.PooledHandle<PooledEvtData> handle() {
        return this.handle;
    }

    /**
     * 业务作用：归池前清空全部字段，防止上一条事件的数据泄漏给下一个借用方。
     * passthrough 只置空引用而不级联回收，这是刻意的：该引用可能已被 caller 取走，
     * 一刀切级联回收会把 caller 仍在使用的 map 清空。所有权约定为——
     * 发布端在 pm.recycle 之后自行回收 passthrough；
     * 消费端由 dispatch 方在 listener 执行完毕后回收。
     *
     * 参数说明: 无。
     * 返回: 无返回值；执行后实例可安全交给下一个借用方。
     */
    @Override
    public void restore() {
        // passthrough 所有权由 caller 显式管理: pm 只持有引用, 不在此 cascade recycle.
        // 设计原因: 一刀切 cascade 会把已被 caller 拿到的 passthrough 引用同时清空 (entries clear), 破坏 caller 后续使用.
        // publish 端: caller 在 pm.recycle 后自己 recycle passthrough (RedisProxyHolder.passthrough() 池借出来的).
        // consume 端: Jackson 经 RecycleModule 路由产生池借 RecycleLinkedMap, caller (RedisPartition.dispatch) 在 listener 执行完后 recycle.
        super.setPassthrough(null);
        super.setTopic(null);
        super.setEvent(null);
        super.setData(null);
    }
}
