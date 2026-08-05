package com.nasa.runtime.core.concurrent;

// ============================================================================
// QPad0 — Disruptor 风格前置 padding, MPMC/MPSC 两个队列的消费端继承链共用.
// ----------------------------------------------------------------------------
// 作用: 在子类首个热字段 (consumerIndex) 之前垫 7 个 long, 隔离它与对象头 / 内存前驱对象的伪共享.
// 可共用的原因: 它位于继承链根部 (extends Object), 无热字段、无泛型, 两队列完全相同。
// 为何只有 Pad0 能共用: Pad1/Pad2 夹在各自 queue 专属的 Consumer/Producer 之间 (布局链中部,
//   Pad1 extends XxxQConsumer<E>, Pad2 extends XxxQProducer<E>), 与类型相关热字段交错排列, 无法抽公共。
// ============================================================================
@SuppressWarnings("all")
abstract class QPad0 {
    protected long p00, p01, p02, p03, p04, p05, p06;
}
