package com.nasa.runtime.core.evt;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.nasa.runtime.core.base.ObjectPool;
import com.nasa.runtime.core.utils.ContextUtils;

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
        @Override
        public PooledEvtData newObject() {
            return new PooledEvtData();
        }
    };

    private final ObjectPool.PooledHandle<PooledEvtData> handle = new ObjectPool.PooledHandle<>(POOL);

    @JsonCreator
    public static PooledEvtData of() {
        return POOL.get();
    }

    public static PooledEvtData of(String topic, String event, Object data) {
        PooledEvtData e = of();
        e.setTopic(topic);
        e.setEvent(event);
        e.setData(data);
        return e;
    }

    @Override
    public ObjectPool.PooledHandle<PooledEvtData> handle() {
        return this.handle;
    }

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
