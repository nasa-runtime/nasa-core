package com.nasa.runtime.core.base;

import com.nasa.runtime.core.utils.ContextUtils;

import java.util.Map;
import java.util.Objects;

@SuppressWarnings("all")
public interface RecycleLinked<K, V> {

    class NodePool<K, V> extends ObjectPool<Node<K, V>> {

        /**
         * ⏺ 1024K = 1,048,576 个 Node：
         *
         * <pre>
         *   ┌───────────────────┬─────────────────┬───────┐
         *   │       部分         │      计算       │ 大小  │
         *   ├───────────────────┼─────────────────┼───────┤
         *   │ Ring 数组（引用）   │ 1024K × 8 字节   │ 8 MB  │
         *   ├───────────────────┼─────────────────┼───────┤
         *   │ Node 对象          │ 1024K × 32 字节 │ 32 MB │
         *   ├───────────────────┼─────────────────┼───────┤
         *   │ 合计               │                 │ 40 MB │
         *   └───────────────────┴─────────────────┴───────┘
         * </pre>
         */
        public NodePool() {
            super(ContextUtils.getPropertyInt("nasa.object-pool.recycle-linked-node-capacity", 1048576));
        }

        @Override
        public Node<K, V> newObject() {
            return new Node<>();
        }

        /**
         * 批量归还节点链: 逐个 offer 到 ring pool。
         * 调用前节点的 key/value/prev/bucketNext 已被清理; next 仍然形成链表。
         * 归还完成后所有 next 也会被置 null。
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

        private Node() {}

        @Override
        public K getKey() { return key; }

        @Override
        public V getValue() { return value; }

        @Override
        public V setValue(V v) {
            V old = value;
            value = v;
            return old;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(key) ^ Objects.hashCode(value);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Map.Entry<?, ?> e)) return false;
            return Objects.equals(key, e.getKey()) && Objects.equals(value, e.getValue());
        }

        public static <K, V> Node<K, V> of() {
            return (Node<K, V>) NODE_POOL.get();
        }

        public static <K, V> Node<K, V> of(K k, V v) {
            Node<K, V> node = of();
            node.key = k;
            node.value = v;
            return node;
        }

        @Override
        public ObjectPool<Node<K, V>> objectPool() {
            return NODE_POOL;
        }

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
     * 返回的头不能被recycle
     */
    Node<K, V> clearRHead();
    
}
