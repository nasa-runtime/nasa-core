package io.github.nasaruntime.core.base;

import io.github.nasaruntime.core.annotation.Protocols;
import io.github.nasaruntime.core.enums.Reflect;
import io.github.nasaruntime.core.utils.ContextUtils;
import io.github.nasaruntime.core.utils.ReflectUtils;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Object[] 协议序列化接口. 元数据缓存 + VarHandle 零反射开销, 三种序列化模式可切换.
 *
 * <h2>序列化模式 ({@link Mode})</h2>
 *   <ul>
 *     <li>{@link Mode#DENSE} (默认) — 按字段顺序密集编码, null 占位. 输出 {@code [val0, val1, val2, ...]}.
 *         这是历史线上格式, 默认值不可随意改动: 一旦默认切到 BITMAP/TAG_VALUE, 所有未显式覆盖
 *         {@link #mode()} 的实现类输出结构立刻变化, 老客户端会静默解析错位.</li>
 *     <li>{@link Mode#BITMAP} — 段式 bitmap 稀疏编码, null 字段不传输. 输出
 *         {@code [bm_0, vals_seg0..., bm_1, vals_seg1..., ...]}, 每个 bitmap 控制 64 字段, 字段数无上限.
 *         稀疏字段场景节省 50%+ 带宽. 收发两端必须同时切换.</li>
 *     <li>{@link Mode#TAG_VALUE} — protobuf 风格 tag-value 交替, 跳过 null. 输出 {@code [tag0, val0, tag1, val1, ...]}.
 *         跨版本兼容: 新增字段不破坏老消费者 (老消费者跳过未知 tag).</li>
 *   </ul>
 * <p>
 * 使用方式: 实现类字段标注 {@link Protocols} 注解, 可选覆盖 {@link #mode()} 切换模式:
 * public class Sys implements Protocol {
 *     {@literal @}Protocols(10) private SysType type;
 *     {@literal @}Protocols(20) private Object data;
 *     {@literal @}Override public Mode mode() { return Mode.TAG_VALUE; }   // 默认 DENSE
 * }
 * <p>
 * Object[] encoded = sys.encode();
 * Sys decoded = new Sys();
 * decoded.decode(encoded);
 *
 * <h2>嵌套容器支持</h2>
 * 字段类型为 {@link List}/{@link Iterable}/{@code Protocol[]} 时, 元素若是 {@link Protocol}
 * 自动递归 encode/decode 还原元素业务类型 (通过 {@link Field#getGenericType()} 提取元素类型).
 * 泛型参数是类型变量 (如 {@code List<T>}) 时擦除后拿不到元素 Class, 此类字段必须用
 * {@link Protocols#converter()} 显式指定编解码方式, 否则元素按原值透传不递归.
 *
 * <h2>字段发现范围</h2>
 * 只认字段上的 {@link Protocols} 注解, 不扫描 getter/setter. 类上一个注解都没有时编码结果为空数组,
 * 不做"按字段顺序全量输出"的隐式回退 —— 隐式回退会让新增私有字段意外进入线路格式.
 *
 * <h2>线程安全</h2>
 * 多线程 encode 不同实例: 各自 ThreadLocal scratch (BITMAP 模式独有), 互不影响.
 * 同 Class 嵌套递归: 顶层用 ThreadLocal scratch 零 GC, 重入用局部数组 (per-Class inEncode flag).
 *
 * <h2>架构</h2>
 * Protocol 接口仅持有 META 缓存 + 公共 helper (encodeVal/decodeVal/buildMeta), 不做模式分发.
 * encode/decode 调用直接委托 {@link #mode()} 拿到的 Mode 枚举, 由 Mode 内 lambda 维护各自序列化逻辑.
 * <p>
 * 已知限制: 不支持写入 final 字段. {@link ReflectUtils#fieldSet} 与 VarHandle 都拒绝 final 写入,
 * 需要反序列化还原的字段不要声明为 final.
 */
public interface Protocol {

    /* ============================== 元数据缓存 ============================== */

    /**
     * 每个 Class 只反射一次.
     */
    ConcurrentHashMap<Class<?>, Meta> META = new ConcurrentHashMap<>();

    /**
     * 业务作用：单个字段的访问器。把 tag、VarHandle、字段类型、集合元素类型与转换器一次性解析好并缓存，使编解码热路径不必重复走反射。tag 是 TAG_VALUE 模式的查表键，发布后不可复用或修改。
     *
     * 单个字段的访问器: tag (用于 TAG_VALUE 模式查表) + VarHandle + 类型 + 元素类型 + 转换器.
     * @param tag 见上述说明
     * @param vh 见上述说明
     * @param type 目标类型
     * @param elementType 见上述说明
     * @param converters 见上述说明
     * 返回: 构造完成后不可变，可安全在多线程间共享。
     */
    record Accessor(int tag, VarHandle vh, Class<?> type, Class<?> elementType,
                    Class<? extends Converter<?>>[] converters) {
    }

    /**
     * 业务作用：单个消息类型的编解码元数据。每个 Class 只反射解析一次并缓存，其中 scratch 与 inEncode 用于复用编码期临时结构，避免逐条消息分配。
     *
     * 类级元数据.
     * <ul>
     *   <li>accessors — 按 tag 升序排列的字段访问器</li>
     *   <li>byTag — tag → Accessor 反查表 (TAG_VALUE 模式 decode 用)</li>
     *   <li>scratch — BITMAP 模式 ThreadLocal 暂存数组</li>
     *   <li>inEncode — BITMAP 模式同 Class 重入检测 flag</li>
     * </ul>
     * @param accessors 见上述说明
     * @param byTag 见上述说明
     * @param scratch 见上述说明
     * @param inEncode 见上述说明
     * 返回: 构造完成后仅 scratch 与 inEncode 可变，其余不可变。
     */
    record Meta(Accessor[] accessors, Map<Integer, Accessor> byTag,
                ThreadLocal<Object[]> scratch, ThreadLocal<Boolean> inEncode) {
    }

    /* ============================== 序列化模式 ============================== */

    /**
     * 三种序列化模式, 各自 lambda 维护自己的 encode/decode 逻辑.
     * <p>
     * encoder: 输入 Protocol 实例, 输出编码后的 Object[];
     * decoder: 输入 Protocol 实例 + 数据, 把 data 反序列化到 instance 字段.
     */
    enum Mode {

        /**
         * 业务作用：按字段声明顺序密排的历史默认格式。无 tag、无 bitmap，最紧凑，但字段增删会整体错位，收发两端必须同步升级。
         */
        DENSE(
                instance -> {
                    Meta meta = instance.meta();
                    Accessor[] accs = meta.accessors();
                    Object[] out = new Object[accs.length];
                    for (int i = 0; i < accs.length; i++) {
                        Object v = accs[i].vh().get(instance);
                        if (v != null) out[i] = encodeVal(v, accs[i]);
                    }
                    return out;
                },
                (instance, data) -> {
                    Meta meta = instance.meta();
                    Accessor[] accs = meta.accessors();
                    int len = Math.min(accs.length, data.length);
                    for (int i = 0; i < len; i++) {
                        if (data[i] != null) {
                            accs[i].vh().set(instance, decodeVal(data[i], accs[i]));
                        }
                    }
                }
        ),

        /**
         * 业务作用：用位图标记哪些字段有值，null 字段不占线路空间。适合稀疏对象，代价是多一个位图头。
         */
        BITMAP(
                instance -> {
                    Meta meta = instance.meta();
                    Accessor[] accs = meta.accessors();
                    int len = accs.length;
                    boolean reentrant = meta.inEncode().get();
                    Object[] buf = reentrant ? new Object[len] : meta.scratch().get();
                    if (!reentrant) meta.inEncode().set(Boolean.TRUE);
                    try {
                        int segments = (len + 63) >>> 6;
                        long[] bitmaps = new long[segments];
                        int nonNull = 0;
                        for (int i = 0; i < len; i++) {
                            Object v = accs[i].vh().get(instance);
                            if (v != null) {
                                buf[i] = encodeVal(v, accs[i]);
                                bitmaps[i >>> 6] |= (1L << (i & 63));
                                nonNull++;
                            }
                        }
                        Object[] out = new Object[segments + nonNull];
                        int outIdx = 0;
                        for (int s = 0; s < segments; s++) {
                            long bm = bitmaps[s];
                            out[outIdx++] = bm;
                            int base = s << 6;
                            int end = Math.min(base + 64, len);
                            for (int i = base; i < end; i++) {
                                if ((bm & (1L << (i & 63))) != 0) out[outIdx++] = buf[i];
                            }
                        }
                        return out;
                    } finally {
                        if (!reentrant) {
                            Arrays.fill(buf, null);
                            meta.inEncode().set(Boolean.FALSE);
                        }
                    }
                },
                (instance, data) -> {
                    Meta meta = instance.meta();
                    Accessor[] accs = meta.accessors();
                    int len = accs.length;
                    int segments = (len + 63) >>> 6;
                    int idx = 0;
                    for (int s = 0; s < segments && idx < data.length; s++) {
                        long bm = ((Number) data[idx++]).longValue();
                        int base = s << 6;
                        int end = Math.min(base + 64, len);
                        for (int i = base; i < end; i++) {
                            if ((bm & (1L << (i & 63))) != 0) {
                                // 数据被截断时停在已解出的字段, 不越界读: bitmap 声明的字段数可能
                                // 多于实际收到的值 (半包、被裁剪的消息), 越界会把可恢复的部分解析
                                // 变成 ArrayIndexOutOfBoundsException. 与 DENSE/TAG_VALUE 的截断处理一致.
                                if (idx >= data.length) return;
                                Object v = decodeVal(data[idx++], accs[i]);
                                accs[i].vh().set(instance, v);
                            }
                        }
                    }
                }
        ),

        /**
         * 业务作用：每个值前带字段 tag，字段可增删且顺序无关，兼容性最好，代价是线路体积最大。
         */
        TAG_VALUE(
                instance -> {
                    Meta meta = instance.meta();
                    Accessor[] accs = meta.accessors();
                    // 一遍遍历: count + 写入. 上限 = accs.length * 2, 末尾 copyOf 截断.
                    Object[] tmp = new Object[accs.length << 1];
                    int idx = 0;
                    for (Accessor acc : accs) {
                        Object v = acc.vh().get(instance);
                        if (v != null) {
                            tmp[idx++] = acc.tag();
                            tmp[idx++] = encodeVal(v, acc);
                        }
                    }
                    return idx == tmp.length ? tmp : Arrays.copyOf(tmp, idx);
                },
                (instance, data) -> {
                    if ((data.length & 1) != 0) {
                        // TAG_VALUE 必须成对出现；忽略孤立 tag 会把被截断消息伪装成成功解码。
                        throw new IllegalArgumentException("Protocol TAG_VALUE 数据长度必须为偶数: "
                                + data.length);
                    }
                    Meta meta = instance.meta();
                    Map<Integer, Accessor> byTag = meta.byTag();
                    for (int i = 0; i + 1 < data.length; i += 2) {
                        if (!(data[i] instanceof Number number)) {
                            throw new IllegalArgumentException("Protocol TAG_VALUE tag 必须是整数: " + data[i]);
                        }
                        long tagValue = exactLong(number, int.class);
                        if (tagValue <= 0 || tagValue > Integer.MAX_VALUE) {
                            throw new IllegalArgumentException("Protocol TAG_VALUE tag 超出正整数范围: "
                                    + number);
                        }
                        int tag = (int) tagValue;
                        Accessor acc = byTag.get(tag);
                        // 未知 tag 跳过 (跨版本兼容: 老消费者收到新字段直接忽略)
                        if (acc == null) continue;
                        Object v = decodeVal(data[i + 1], acc);
                        acc.vh().set(instance, v);
                    }
                }
        );

        private final Function<Protocol, Object[]> encoder;
        private final BiConsumer<Protocol, Object[]> decoder;

        /**
         * 业务作用：按给定参数构造 Mode 实例。
         *
         * @param encoder 见上述说明
         * @param decoder 见上述说明
         * 返回: 构造完成后可直接使用的实例。
         */
        Mode(Function<Protocol, Object[]> encoder, BiConsumer<Protocol, Object[]> decoder) {
            this.encoder = encoder;
            this.decoder = decoder;
        }

        /**
         * 业务作用: 按本模式的线路格式把实例编码成 Object[].
         * 参数说明: instance 待编码的协议实例.
         *
         * @param instance 目标消息实例
         * 返回: 该模式约定结构的数组; 不同模式结构不同, 收发两端必须用同一模式.
         */
        public Object[] encode(Protocol instance) {
            return encoder.apply(instance);
        }

        /**
         * 业务作用: 按本模式的线路格式把 data 回填到实例字段.
         * 参数说明: instance 待填充的协议实例; data 线路数据.
         *
         * @param instance 目标消息实例
         * @param data 线路字节
         * 返回: 无. 副作用是直接写 instance 的字段; data 结构与模式不匹配时结果错位或抛异常.
         */
        public void decode(Protocol instance, Object[] data) {
            decoder.accept(instance, data);
        }
    }

    /* ============================== 公共 API ============================== */

    /**
     * 业务作用: 声明本类型使用的线路格式, 业务类覆盖以切换.
     * 参数说明: 无。
     * 返回: 默认 {@link Mode#DENSE} —— 这是历史线上格式, 改默认值等于改所有实现类的线路协议.
     */
    default Mode mode() {
        return Mode.DENSE;
    }

    /**
     * 业务作用: 把本实例编码成可跨进程传输的 Object[], 委托给 {@link #mode()} 对应的 Mode.
     * 参数说明: 无。
     * 返回: 编码后的数组; 类上没有任何 {@link Protocols} 注解字段时返回长度为 0 的数组.
     */
    default Object[] encode() {
        return mode().encode(this);
    }

    /**
     * 业务作用: 把线路数据回填到本实例, 委托给 {@link #mode()} 对应的 Mode.
     * 参数说明: data 线路数据, 结构必须与本类型的 {@link #mode()} 一致.
     *
     * @param data 线路字节
     * 返回: this (支持链式). 副作用是直接写字段; final 字段会抛 UnsupportedOperationException.
     */
    @SuppressWarnings("unchecked")
    default <T extends Protocol> T decode(Object[] data) {
        mode().decode(this, data);
        return (T) this;
    }

    /**
     * 业务作用: 取当前 Class 的字段元数据, 首次构建后进程内长期缓存, 避免每条消息重复反射.
     * 保持 public (接口默认方法) 而非私有, 是因为 {@link Mode} 枚举内的 lambda 需要访问.
     * 参数说明: 无。
     * 返回: 该 Class 的 {@link Meta}; 字段 tag 冲突时构建阶段抛 IllegalStateException.
     */
    default Meta meta() {
        return META.computeIfAbsent(this.getClass(), Protocol::buildMeta);
    }

    /**
     * 业务作用: 反序列化入口 —— 新建目标类型实例并回填数据, 供接收端由 Class + 数据还原对象.
     * 参数说明: clazz 目标协议类型, 必须有可用的无参构造; data 线路数据.
     *
     * @param clazz 目标消息类型
     * @param data 线路字节
     * 返回: 已填充的新实例; clazz 无法实例化时由 {@link ReflectUtils#newInstance} 抛异常.
     */
    static <T extends Protocol> T of(Class<T> clazz, Object[] data) {
        return ReflectUtils.newInstance(clazz).decode(data);
    }

    /* ============================== 元数据构建 (仅首次) ============================== */

    /**
     * 业务作用: 反射扫描协议字段并固化成访问器数组, 是"字段 → 线路位置"的唯一映射来源.
     * 参数说明: clazz 待扫描的协议实现类.
     *
     * @param clazz 目标消息类型
     * 返回: 构建好的 {@link Meta}; tag 非正数或重复时抛 IllegalStateException, 因为非法 tag
     * 会让 TAG_VALUE 模式产生歧义, 必须在启动期暴露而不是运行期静默错.
     */
    private static Meta buildMeta(Class<?> clazz) {
        List<Field> fields = ReflectUtils.allField(clazz,
                Reflect.IsNotStatic.getApplier(),
                m -> ((AnnotatedElement) m).isAnnotationPresent(Protocols.class));

        fields.sort(Comparator.comparingInt(f -> f.getAnnotation(Protocols.class).value()));

        Accessor[] accs = new Accessor[fields.size()];
        Map<Integer, Accessor> byTag = HashMap.newHashMap(fields.size());
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(clazz, MethodHandles.lookup());
            for (int i = 0; i < fields.size(); i++) {
                Field f = fields.get(i);
                f.setAccessible(true);
                VarHandle vh = lookup.unreflectVarHandle(f);
                Protocols ann = f.getAnnotation(Protocols.class);
                int tag = ann.value();
                // tag 是跨版本线路标识，非正数没有合法语义，不能只把它当排序值继续编码。
                if (tag <= 0) {
                    throw new IllegalStateException("Protocol 字段 tag 必须大于 0: "
                            + clazz.getName() + "." + f.getName() + " tag=" + tag);
                }
                Class<?> elementType = extractElementType(f);
                Accessor acc = new Accessor(tag, vh, f.getType(), elementType, ann.converter());
                accs[i] = acc;
                Accessor prev = byTag.put(tag, acc);
                if (prev != null) {
                    throw new IllegalStateException("Protocol 字段 tag 冲突: " + clazz.getName()
                            + " tag=" + tag + " 同时用于多个字段");
                }
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Protocol 元数据构建失败: " + clazz.getName(), e);
        }

        int size = accs.length;
        ThreadLocal<Object[]> scratch = ThreadLocal.withInitial(() -> new Object[size]);
        ThreadLocal<Boolean> inEncode = ThreadLocal.withInitial(() -> Boolean.FALSE);
        return new Meta(accs, byTag, scratch, inEncode);
    }

    /**
     * 业务作用: 提取容器字段的元素类型, 决定嵌套 Protocol 元素能否自动递归还原.
     * 参数说明: f 目标字段.
     *
     * @param f 字段
     * 返回: {@code List<X>}/{@code Iterable<X>} → X.class; {@code X[]} → X.class;
     * 泛型是类型变量 (擦除后无 Class) 或非容器时返回 null, 调用方据此退化为原值透传.
     */
    private static Class<?> extractElementType(Field f) {
        Class<?> type = f.getType();
        if (type.isArray()) {
            return type.getComponentType();
        }
        if (Iterable.class.isAssignableFrom(type)) {
            Type gt = f.getGenericType();
            if (gt instanceof ParameterizedType pt) {
                Type[] args = pt.getActualTypeArguments();
                if (args.length > 0 && args[0] instanceof Class<?> ec) {
                    return ec;
                }
            }
        }
        return null;
    }

    /* ============================== 编码值处理 (三种模式共用) ============================== */

    /**
     * 业务作用: 把单个字段值转成线路可承载的形态 —— 自定义转换器优先, 其次递归展开嵌套协议对象.
     * 参数说明: v 非 null 的字段原值; acc 该字段的访问器.
     *
     * @param v 字段值
     * @param acc 字段访问器
     * 返回: 编码后的值; 无转换器且非协议对象时原值透传.
     */
    @SuppressWarnings("unchecked")
    private static Object encodeVal(Object v, Accessor acc) {
        // 转换器优先于内置规则: 业务显式指定的编码方式必须压过框架推断, 否则泛型擦除
        // 拿不到元素类型的字段 (如 List<T>) 会被静默按原值透传, 线路上出现无法解析的对象
        if (acc.converters().length > 0) {
            Converter<Object> c = (Converter<Object>) resolveConverter(acc.converters()[0]);
            if (c != null) return c.encode(v);
        }
        // 递归: Protocol 子对象
        if (v instanceof Protocol p) return p.encode();
        // 递归: Iterable<Protocol>
        if (v instanceof Iterable<?> col) {
            boolean detect = false;
            List<Object> arr = null;
            int leadingNulls = 0;
            for (Object item : col) {
                if (!detect) {
                    if (item == null) {
                        leadingNulls++;
                        continue;
                    }
                    detect = true;
                    if (!(item instanceof Protocol)) return v;
                    arr = new ArrayList<>();
                    // 前导 null 同样占据集合位置；漏掉它会让后续协议元素整体左移，破坏索引语义。
                    for (int i = 0; i < leadingNulls; i++) {
                        arr.add(null);
                    }
                }
                if (item == null) {
                    arr.add(null);
                } else if (item instanceof Protocol p) {
                    arr.add(p.encode());
                } else {
                    arr.add(item);   // 类型不一致退化
                }
            }
            return arr != null ? arr : v;
        }
        // 递归: Protocol[]
        if (v instanceof Object[] oa) {
            int firstIdx = -1;
            for (int j = 0; j < oa.length; j++) {
                if (oa[j] != null) {
                    firstIdx = j;
                    break;
                }
            }
            if (firstIdx < 0 || !(oa[firstIdx] instanceof Protocol)) return v;
            Object[] arr = new Object[oa.length];
            for (int j = 0; j < oa.length; j++) {
                if (oa[j] == null) {
                    arr[j] = null;
                } else if (oa[j] instanceof Protocol p) {
                    arr[j] = p.encode();
                } else {
                    arr[j] = oa[j];
                }
            }
            return arr;
        }
        return v;
    }

    /* ============================== 解码值处理 (三种模式共用) ============================== */

    /**
     * 业务作用: 把线路值还原成字段实际类型, 是跨语言/跨中间件传输的类型收敛点.
     * 参数说明: v 线路上的原始值, 允许 null; acc 该字段的访问器.
     *
     * @param v 字段值
     * @param acc 字段访问器
     * 返回: 可直接写入字段的值. 线路经 JSON/Redis 往返后数字常退化成 String, 这里统一按
     * 字段声明类型强转回来, 否则 VarHandle 写入会抛 ClassCastException.
     */
    @SuppressWarnings("unchecked")
    private static Object decodeVal(Object v, Accessor acc) {
        if (v == null) return null;

        if (acc.converters().length > 0) {
            Converter<?> c = resolveConverter(acc.converters()[0]);
            if (c != null) return c.decode(v);
        }

        Class<?> type = acc.type();
        Class<?> et = acc.elementType();

        switch (v) {
            case Object[] arr when Protocol.class.isAssignableFrom(type) -> {
                return Protocol.of((Class<? extends Protocol>) type, arr);
            }
            case Iterable<?> iter when et != null && Protocol.class.isAssignableFrom(et) && Iterable.class.isAssignableFrom(type) -> {
                Class<? extends Protocol> elementClass = (Class<? extends Protocol>) et;
                int expectedSize = v instanceof Collection<?> col ? col.size() : 0;
                Collection<Object> result = createCollection(type, expectedSize);
                for (Object item : iter) {
                    if (item == null) {
                        result.add(null);
                    } else if (item instanceof Object[] inner) {
                        result.add(Protocol.of(elementClass, inner));
                    } else {
                        result.add(item);
                    }
                }
                return result;
            }
            case Object[] vArr when et != null && Protocol.class.isAssignableFrom(et) && type.isArray() -> {
                Class<? extends Protocol> elementClass = (Class<? extends Protocol>) et;
                Object[] result = (Object[]) Array.newInstance(elementClass, vArr.length);
                for (int i = 0; i < vArr.length; i++) {
                    Object item = vArr[i];
                    if (item == null) {
                        result[i] = null;
                    } else if (item instanceof Object[] inner) {
                        result[i] = Protocol.of(elementClass, inner);
                    } else {
                        result[i] = item;
                    }
                }
                return result;
            }
            default -> {
            }
        }

        return coerce(v, type);
    }

    /**
     * 业务作用: 为协议集合字段创建与声明类型兼容的结果容器，避免把 ArrayList 写入 Set、Queue
     * 或具体集合字段时在 VarHandle 边界失败。
     * 参数说明: type 字段声明类型; expectedSize 预计元素数量，仅用于预分配。
     *
     * @param type 目标类型
     * @param expectedSize 预估元素个数
     * 返回: 可赋值给 type 的可变集合；无法安全构造兼容容器时抛 IllegalArgumentException。
     */
    @SuppressWarnings("unchecked")
    private static Collection<Object> createCollection(Class<?> type, int expectedSize) {
        if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
            Object instance = ReflectUtils.newInstanceOrNull(type);
            if (instance instanceof Collection<?> collection) {
                return (Collection<Object>) collection;
            }
            throw new IllegalArgumentException("Protocol 集合字段类型必须可实例化并实现 Collection: "
                    + type.getName());
        }
        if (Set.class.isAssignableFrom(type)) {
            int capacity = Math.max(16, (int) (expectedSize / 0.75f) + 1);
            return new LinkedHashSet<>(capacity);
        }
        if (Queue.class.isAssignableFrom(type)) {
            // LinkedList 允许保留 null 元素，ArrayDeque 会拒绝并改变线路数据语义。
            return new LinkedList<>();
        }
        if (type.isAssignableFrom(ArrayList.class)) {
            return new ArrayList<>(expectedSize);
        }
        throw new IllegalArgumentException("Protocol 不支持的 Iterable 字段类型: " + type.getName());
    }

    /**
     * 业务作用: 把线路值按字段声明类型做数值/布尔/字符/枚举/字符串收敛, 兜住 JSON、Redis、Kafka
     * 往返造成的类型漂移 (整型被解析成 Long、小数被解析成 Double、数字/字符/枚举被序列化成 String 等).
     * 参数说明: v 非 null 的线路值; type 字段声明类型.
     *
     * @param v 字段值
     * @param type 目标类型
     * 返回: 转换后的值; type 不在已知收敛范围内时原值返回, 交由 VarHandle 直接写入.
     * 转换失败一律抛异常而不是塞默认值 (非法数字 NumberFormatException、未知枚举名或枚举
     * 序号 IllegalArgumentException) —— 宁可在解码点暴露, 也不让一笔错数据流进业务.
     */
    private static Object coerce(Object v, Class<?> type) {
        if (type == byte.class || type == Byte.class) {
            if (v instanceof Number n) {
                long value = exactLong(n, type);
                if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
                    throw numericOverflow(type, n);
                }
                return (byte) value;
            }
            if (v instanceof String s) return Byte.parseByte(s);
        } else if (type == short.class || type == Short.class) {
            if (v instanceof Number n) {
                long value = exactLong(n, type);
                if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
                    throw numericOverflow(type, n);
                }
                return (short) value;
            }
            if (v instanceof String s) return Short.parseShort(s);
        } else if (type == int.class || type == Integer.class) {
            if (v instanceof Number n) {
                long value = exactLong(n, type);
                if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                    throw numericOverflow(type, n);
                }
                return (int) value;
            }
            if (v instanceof String s) return Integer.parseInt(s);
        } else if (type == long.class || type == Long.class) {
            if (v instanceof Number n) return exactLong(n, type);
            if (v instanceof String s) return Long.parseLong(s);
        } else if (type == float.class || type == Float.class) {
            if (v instanceof Number n) return n.floatValue();
            if (v instanceof String s) return Float.parseFloat(s);
        } else if (type == double.class || type == Double.class) {
            if (v instanceof Number n) return n.doubleValue();
            if (v instanceof String s) return Double.parseDouble(s);
        } else if (type == BigDecimal.class) {
            // 浮点与字符串一律走 toString 构造: BigDecimal.valueOf(double) 会带入二进制浮点误差,
            // 精度误差可能在累计计算中被放大，因此保留十进制文本表达的真实值。
            if (v instanceof Float || v instanceof Double || v instanceof String) {
                return new BigDecimal(v.toString());
            }
            if (v instanceof BigInteger n) return new BigDecimal(n);
            if (v instanceof Number n && !(v instanceof BigDecimal)) return new BigDecimal(n.toString());
        } else if (type == BigInteger.class) {
            if (v instanceof BigDecimal n) return n.toBigIntegerExact();
            if (v instanceof Float || v instanceof Double) {
                return new BigDecimal(v.toString()).toBigIntegerExact();
            }
            if (v instanceof Number n && !(v instanceof BigInteger)) return BigInteger.valueOf(n.longValue());
            if (v instanceof String s) return new BigInteger(s);
        } else if (type == boolean.class || type == Boolean.class) {
            if (v instanceof String s) {
                if ("true".equalsIgnoreCase(s)) return true;
                if ("false".equalsIgnoreCase(s)) return false;
                throw new IllegalArgumentException("Protocol boolean 字段只接受 true/false, 收到 \"" + s + "\"");
            }
        } else if (type == char.class || type == Character.class) {
            // 只接受恰好一个字符的串。取 charAt(0) 会把 "ABC" 静默截断成 'A'，
            // 这类错误可能绕过当前字段校验，直到下游核对业务简码时才暴露。
            if (v instanceof String s) {
                if (s.length() != 1) {
                    throw new IllegalArgumentException("Protocol char 字段要求长度为 1 的字符串, 收到 \""
                            + s + "\" (长度 " + s.length() + ")");
                }
                return s.charAt(0);
            }
            // 数字按 UTF-16 码元还原, 越界拒绝而不是回绕: (char) 强转会把 65536 悄悄变成 0
            if (v instanceof Number n) {
                long cp = exactLong(n, type);
                if (cp < Character.MIN_VALUE || cp > Character.MAX_VALUE) {
                    throw new IllegalArgumentException("Protocol char 字段数值越界: " + cp
                            + " 不在 [0, " + (int) Character.MAX_VALUE + "]");
                }
                return (char) cp;
            }
        } else if (type.isEnum()) {
            // 按常量名还原, 不按 ordinal: ordinal 依赖枚举声明顺序, 一次插入/重排就会让历史消息
            // 静默解析成另一个枚举值会改变业务语义，因此必须在协议边界拒绝。
            if (v instanceof String s) return enumOf(type, s);
            if (v instanceof Number) {
                throw new IllegalArgumentException("Protocol 枚举字段不接受序号: " + type.getName()
                        + " 收到 " + v + ", 请用常量名传输 (ordinal 随声明顺序漂移会静默错值)");
            }
        } else if (type == String.class) {
            // 只对标量做 toString. 数组/集合 toString 出来是 "[Ljava.lang.Object;@1a2b" 这种垃圾值,
            // 落入标识或数值字段后难以追溯；原样返回让 VarHandle 抛 ClassCastException 更易定位。
            if (v instanceof Number || v instanceof Boolean || v instanceof Character || v instanceof Enum<?>) {
                return v.toString();
            }
        }
        return v;
    }

    /**
     * 业务作用: 将任意 Number 精确收敛为 long，作为整数协议字段转换的统一门禁，阻止小数截断、
     * 非有限浮点数和超出 long 范围的值静默进入业务对象。
     * 参数说明: number 待转换数字; targetType 最终字段类型，用于形成可定位的异常信息。
     *
     * @param number 待转换的数值
     * @param targetType 目标数值类型
     * 返回: 与输入数值完全等价的 long；存在小数、非有限值或越界时抛 IllegalArgumentException。
     */
    private static long exactLong(Number number, Class<?> targetType) {
        try {
            if (number instanceof BigInteger integer) return integer.longValueExact();
            if (number instanceof BigDecimal decimal) return decimal.longValueExact();
            if (number instanceof Byte || number instanceof Short
                    || number instanceof Integer || number instanceof Long) {
                return number.longValue();
            }
            return new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("Protocol 数值无法精确转换为 " + targetType.getTypeName()
                    + ": " + number, e);
        }
    }

    /**
     * 业务作用: 统一生成窄整数类型的越界异常，确保非法线路值在解码边界被拒绝而不是发生回绕。
     * 参数说明: targetType 目标字段类型; number 原始线路数字。
     *
     * @param targetType 目标数值类型
     * @param number 待转换的数值
     * 返回: 包含目标类型和非法值的 IllegalArgumentException，由调用方直接抛出。
     */
    private static IllegalArgumentException numericOverflow(Class<?> targetType, Number number) {
        return new IllegalArgumentException("Protocol 数值超出 " + targetType.getTypeName()
                + " 范围: " + number);
    }

    /**
     * 业务作用: 按常量名把线路字符串还原成枚举值, 供 {@link #coerce} 处理经 JSON/Redis 往返后
     * 退化成字符串的枚举字段.
     * 参数说明: type 目标枚举类型; name 常量名.
     *
     * @param type 目标类型
     * @param name 枚举常量名
     * 返回: 对应的枚举常量; 名称不存在时抛 IllegalArgumentException, 不回退默认值 ——
     * 未知枚举值多半意味着收发两端版本不一致, 静默取默认值会把它变成一笔错数据.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumOf(Class<?> type, String name) {
        return Enum.valueOf((Class<? extends Enum>) type, name);
    }

    /* ============================== 转换器解析 ============================== */

    /**
     * 业务作用: 拿到字段声明的转换器实例, 优先复用容器托管的单例以便转换器能注入业务依赖.
     * 参数说明: type 转换器类型.
     *
     * @param type 目标类型
     * 返回: 转换器实例; 容器未托管时退化为反射新建, 两者都失败返回 null, 调用方据此走内置编解码.
     */
    private static Converter<?> resolveConverter(Class<? extends Converter<?>> type) {
        Converter<?> c = null;
        try {
            c = ContextUtils.getBeanOrNull(type);
        } catch (Exception ignored) {
        }
        if (c == null) c = ReflectUtils.newInstanceOrNull(type);
        return c;
    }

    /* ============================== 自定义类型转换 ============================== */

    /**
     * 字段级自定义编解码. 框架无法从声明推断线路形态时 (泛型擦除、第三方类型、需要压缩表示)
     * 由业务实现本接口接管, 通过 {@link Protocols#converter()} 绑定到字段.
     */
    interface Converter<T> {

        /**
         * 业务作用: 把字段值转成线路形态.
         * 参数说明: t 字段原值, 可能为 null.
         *
         * @param t 待转换的值
         * 返回: 线路值; 默认实现原样返回, 表示该方向不需要转换.
         */
        default Object encode(T t) {
            return t;
        }

        /**
         * 业务作用: 把线路值还原成字段值.
         * 参数说明: o 线路值.
         *
         * @param o 待转换的值
         * 返回: 字段可接收的值; 实现方需自行处理 null 与非预期类型.
         */
        T decode(Object o);
    }

}
