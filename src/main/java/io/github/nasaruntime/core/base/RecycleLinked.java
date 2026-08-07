package io.github.nasaruntime.core.base;

import io.github.nasaruntime.core.utils.ContextUtils;

import java.util.Map;
import java.util.Objects;

@SuppressWarnings("all")
public interface RecycleLinked<K, V> {

    class NodePool<K, V> extends ObjectPool<Node<K, V>> {

        /**
         * 业务作用：构造实例。字段取默认值。
         *
         * 参数说明: 无。
         * 返回: 构造完成后可直接使用的实例。
         */
        public NodePool() {
            super(ContextUtils.getPropertyInt("nasa.object-pool.recycle-linked-node-capacity", 1048576));
        }

        /**
         * 业务作用：池空时创建新实例。
         *
         * 参数说明: 无。
         * 返回: 字段均为初始值的新实例。
         */
        @Override
        public Node<K, V> newObject() {
            return new Node<>();
        }

        /**
         * 业务作用：批量归还节点链: 逐个 offer 到 ring pool。
         * 调用前节点的 key/value/prev/bucketNext 已被清理; next 仍然形成链表。
         * 归还完成后所有 next 也会被置 null。
         *
         * @param head 见上述说明
         * 返回: 无返回值。
         */
        public void bulkRecycleChain(Node<K, V> head) {
            Node<K, V> n = head;
            while (n != null) {
                Node<K, V> next = n.next;
                n.next = null;
                pool.offer(n); // 满了返回 false, 交给 GC
                n = next;
            }
        }
    }

    public class Node<K, V> implements ObjectPool.Recycler<Node<K, V>>, Map.Entry<K, V> {

        static final NodePool NODE_POOL = new NodePool<>();

        public K key;
        public V value;
        public Node<K, V> prev, next;
        /** hash 桶冲突链，仅 RecycleLinkedMap 使用 */
        public Node<K, V> bucketNext;

        /**
         * 业务作用：构造实例。字段取默认值。
         *
         * 参数说明: 无。
         * 返回: 构造完成后可直接使用的实例。
         */
        private Node() {}

        /**
         * 业务作用：读取本条目的键。键在条目生命周期内不可变。
         *
         * 参数说明: 无。
         * 返回: 条目的键。
         */
        @Override
        public K getKey() { return key; }

        /**
         * 业务作用：读取本条目当前的值。
         *
         * 参数说明: 无。
         * 返回: 条目的值。
         */
        @Override
        public V getValue() { return value; }

        /**
         * 业务作用：原地修改本条目的值，修改直接作用于底层映射。
         *
         * @param v 见方法语义
         * 返回: 被替换的旧值。
         */
        @Override
        public V setValue(V v) {
            V old = value;
            value = v;
            return old;
        }

        /**
         * 业务作用：按键值计算哈希，使节点可直接放入基于哈希的容器。
         *
         * 参数说明: 无。
         * 返回: 哈希值。
         */
        @Override
        public int hashCode() {
            return Objects.hashCode(key) ^ Objects.hashCode(value);
        }

        /**
         * 业务作用：按键值判等，语义与 Map.Entry 一致。
         *
         * @param o 取值
         * 返回: 键与值都相等时返回 true。
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Map.Entry<?, ?> e)) return false;
            return Objects.equals(key, e.getKey()) && Objects.equals(value, e.getValue());
        }

        /**
         * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
         *
         * 参数说明: 无。
         * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
         */
        public static <K, V> Node<K, V> of() {
            return (Node<K, V>) NODE_POOL.get();
        }

        /**
         * 业务作用：从对象池借出实例并按入参完成初始化，替代直接 new 以复用对象、降低分配率。
         *
         * @param k 键
         * @param v 值
         * 返回: 可直接使用的实例；用完必须调用 recycle 归池，否则该对象永久脱池。归池后实例可能立即被其它线程借走，调用方不得继续持有旧引用。
         */
        public static <K, V> Node<K, V> of(K k, V v) {
            Node<K, V> node = of();
            node.key = k;
            node.value = v;
            return node;
        }

        /**
         * 业务作用：给出节点所属的对象池，供归池路径定位目标池。
         *
         * 参数说明: 无。
         * 返回: 节点对象池。
         */
        @Override
        public ObjectPool<Node<K, V>> objectPool() {
            return NODE_POOL;
        }

        /**
         * 业务作用：归池前清空全部字段与节点引用，防止上一代数据泄漏给下一个借用方，并把持有的池化节点级联归还。
         *
         * 参数说明: 无。
         * 返回: 无返回值；执行后实例可安全交给下一个借用方。
         */
        @Override
        public void restore() {
            key = null;
            value = null;
            prev = null;
            next = null;
            bucketNext = null;
        }
    }

    /**
     * 业务作用：清空容器自身字段并把整条节点链交还给调用方，节点的归池责任随之移交。用于调用方需要复用或延迟释放节点链的场景。
     *
     * 参数说明: 无。
     * 返回: 原来的头节点；调用方负责逐个归还这些节点。
     */
    Node<K, V> clearRHead();
    
}
