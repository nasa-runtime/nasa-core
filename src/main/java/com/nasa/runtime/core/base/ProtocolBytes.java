package com.nasa.runtime.core.base;

import com.nasa.runtime.core.annotation.Protocols;
import com.nasa.runtime.core.enums.Reflect;
import com.nasa.runtime.core.utils.ObjMprUtils;
import com.nasa.runtime.core.utils.ReflectUtils;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 业务作用: 把标有 {@link Protocols} 的业务字段编码为独立 {@code byte[]}，供需要紧凑载荷或
 * 明确线路格式的消息通道使用；它与返回 {@code Object[]} 的 {@link Protocol} 平行存在。
 * <p>
 * 实现通过 VarHandle 元数据缓存和 ThreadLocal 中间缓冲降低重复反射与临时缓冲分配。
 * 返回的 {@code byte[]} 始终由调用方独占，不承诺整个编码过程零分配或端到端零拷贝。
 *
 * <h2>编码模式</h2>
 * <ul>
 *   <li>{@link Mode#JSON_BYTES} 是默认模式，使用 Jackson JSON UTF-8，便于跨语言检查。</li>
 *   <li>{@link Mode#VARINT_TLV} 使用 protobuf 风格的 tag、wire type、varint 和 zigzag 基础编码；
 *       集合扩展布局属于 nasa-core 协议，不能直接替代由 {@code .proto} 生成的消息。</li>
 *   <li>{@link Mode#BITPACK_TLV} 使用 bitmap 加紧凑字段值，最多支持 64 个协议字段并锁定 schema。</li>
 *   <li>{@link Mode#FAST_FIXED} 使用固定布局，只支持非空的 Java 基础类型字段并锁定 schema。</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * {@snippet :
 * public class FastMessage implements ProtocolBytes {
 *     @Protocols(10) private long timestamp;
 *     @Protocols(20) private long sequence;
 *     @Protocols(30) private String name;
 *     @Override public Mode mode() { return Mode.VARINT_TLV; }
 * }
 *
 * byte[] bytes = msg.encodeBytes();
 * FastMessage dst = ProtocolBytes.of(FastMessage.class, bytes);
 *}
 * <p>
 * 所有模式只处理 {@link Protocols} 字段；未标注字段时输出对应模式的空布局（JSON 为
 * {@code {}}，BITPACK_TLV 仍包含零值 bitmap）。集合/数组元素的长度为 0 同时表示
 * {@code null}、空字符串和空字节数组，接收端统一还原为 {@code null}。除 JSON 模式外，
 * Map、BigDecimal、LocalDateTime 和 UUID 等类型不受支持。
 */
public interface ProtocolBytes {

    /* ============================== 元数据缓存 ============================== */

    /**
     * 每个 Class 只构建一次字段元数据，四种模式共同复用。
     */
    ConcurrentHashMap<Class<?>, Meta> META = new ConcurrentHashMap<>();

    /**
     * 使用与 Protobuf 相同的 wire type 数值，占编码后 tag 的低 3 位。
     */
    enum WireType {
        VARINT(0),                 // int/long/short/byte/char/boolean/Enum (zigzag for signed)
        FIXED64(1),                // double
        LENGTH_DELIMITED(2),       // String/byte[]/嵌套 ProtocolBytes/List/Array
        FIXED32(5);                // float

        public final int code;

        WireType(int c) {
            this.code = c;
        }

        public static WireType ofCode(int c) {
            return switch (c) {
                case 0 -> VARINT;
                case 1 -> FIXED64;
                case 2 -> LENGTH_DELIMITED;
                case 5 -> FIXED32;
                default -> throw new IllegalArgumentException("unknown wire type code: " + c);
            };
        }
    }

    /**
     * 字段访问器: tag (= @Protocols.value) + 字段名 (JSON_BYTES 用) + VarHandle + 类型 +
     * 元素类型 (容器场景) + wire type + 固定长度.
     * <p>
     * fixedSize: FAST_FIXED 模式用. -1 表示变长字段不能用 FIXED 模式.
     */
    record Accessor(int tag, String fieldName, VarHandle vh, Class<?> type, Class<?> elementType,
                    WireType wireType, int fixedSize) {
    }

    /**
     * 类级元数据.
     * <ul>
     *   <li>accessors — 按 tag 升序排列的字段访问器</li>
     *   <li>byTag — tag → Accessor 反查表 (VARINT_TLV decode 用)</li>
     *   <li>byName — fieldName → Accessor 反查表 (JSON_BYTES decode 用)</li>
     *   <li>scratch — ThreadLocal byte[] 暂存 (encode 复用, 避免每条消息分配)</li>
     *   <li>inEncode — 同 Class 嵌套递归检测 flag</li>
     *   <li>totalFixedSize — FAST_FIXED 总字节数; -1 表示有变长字段不能用 FIXED 模式</li>
     * </ul>
     */
    record Meta(Accessor[] accessors, Map<Integer, Accessor> byTag, Map<String, Accessor> byName,
                ThreadLocal<byte[]> scratch, ThreadLocal<Boolean> inEncode,
                int totalFixedSize) {
    }

    /* ============================== 序列化模式 ============================== */

    /**
     * 4 种序列化模式. 各 lambda 维护自己的 encode/decode 逻辑, ProtocolBytes 主体只委托.
     */
    enum Mode {

        /**
         * Jackson JSON UTF-8 字节, 仅序列化 {@link Protocols} 注解字段.
         * <p>
         * 通过 {@code toJsonMap(instance, allocs)} 构造 {@link RecycleLinkedMap} (按 @Protocols.value 排序), 再 Jackson 序列化.
         * 序列化期间所有由本框架分配的 {@link RecycleLinkedMap}/{@link RecycleLinkedList} 都登记到 allocs 跟踪表;
         * 序列化完成后逐个 recycle, 稳态零结构容器分配。
         * <p>
         * <b>关键:</b> 只回收登记过的实例 (本框架分配的), 用户字段中如果出现池化对象 (如 Map 字段值是 RecycleLinkedMap)
         * 不会被误回收 — 因为它从未进 allocs 表。
         * <p>
         * decode 时 Jackson 反序列化为 Map, 按 fieldName 反查 Accessor 设字段值, 未识别字段跳过 (跨版本兼容).
         * <p>
         * 跨语言客户端可直接用任意 JSON 解析器, 输出格式跟字段名一致 (人类可读).
         */
        JSON_BYTES(
                instance -> {
                    // 跟踪表: 本次 encode 内本框架分配的所有可回收容器, 末尾统一 recycle
                    RecycleLinkedList<ObjectPool.Recycler<?>> allocs = RecycleLinkedList.of();
                    try {
                        Map<String, Object> map = toJsonMap(instance, allocs);
                        try {
                            return ObjMprUtils.toString(map).getBytes(StandardCharsets.UTF_8);
                        } finally {
                            for (ObjectPool.Recycler<?> r : allocs) r.recycle();
                        }
                    } finally {
                        allocs.recycle();
                    }
                },
                (instance, data) -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = ObjMprUtils.OBJECT_MAPPER.readValue(data, LinkedHashMap.class);
                        fromJsonMap(instance, map);
                    } catch (Exception e) {
                        throw new RuntimeException("JSON_BYTES decode failed: " + instance.getClass().getName(), e);
                    }
                }
        ),

        /**
         * Protobuf 风格线路结构：{@code [varint tag<<3|wireType] [value]}。
         */
        VARINT_TLV(
                ProtocolBytes::encodeVarintTlv,
                ProtocolBytes::decodeVarintTlv
        ),

        /**
         * Bitmap header + 紧凑值，适用于稀疏字段并锁定 schema.
         */
        BITPACK_TLV(
                ProtocolBytes::encodeBitpackTlv,
                ProtocolBytes::decodeBitpackTlv
        ),

        /**
         * 固定布局, 无 tag/length/bitmap. 极致紧凑, 锁 schema, 类型/null 限制.
         */
        FAST_FIXED(
                ProtocolBytes::encodeFastFixed,
                ProtocolBytes::decodeFastFixed
        );

        private final Function<ProtocolBytes, byte[]> encoder;
        private final BiConsumer<ProtocolBytes, byte[]> decoder;

        Mode(Function<ProtocolBytes, byte[]> e, BiConsumer<ProtocolBytes, byte[]> d) {
            this.encoder = e;
            this.decoder = d;
        }

        /**
         * 业务作用: 使用本模式把协议实例编码成字节数组。
         * 参数说明: i 待编码的协议实例。
         * 返回: 调用方独占的编码结果；字段或模式不受支持时抛运行时异常。
         */
        public byte[] encode(ProtocolBytes i) {
            return encoder.apply(i);
        }

        /**
         * 业务作用: 使用本模式把线路字节回填到协议实例。
         * 参数说明: i 待填充实例；d 完整线路数据。
         * 返回: 无；副作用是修改 i 的协议字段，畸形数据会被拒绝。
         */
        public void decode(ProtocolBytes i, byte[] d) {
            decoder.accept(i, d);
        }
    }

    /* ============================== 公共 API ============================== */

    /**
     * 业务作用: 声明当前类型使用的字节线路格式，业务类可覆盖以选择更紧凑的模式。
     * 参数说明: 无。
     * 返回: 默认 {@link Mode#JSON_BYTES}；更改返回值会改变线路协议，收发两端必须一致。
     */
    default Mode mode() {
        return Mode.JSON_BYTES;
    }

    /**
     * 业务作用: 按当前模式编码全部 {@link Protocols} 字段。
     * 参数说明: 无。
     * 返回: 调用方独占的编码结果；没有协议字段时按模式返回空对象或空布局。
     */
    default byte[] encodeBytes() {
        return mode().encode(this);
    }

    /**
     * 业务作用: 按当前模式把线路数据回填到本实例。
     * 参数说明: data 与当前模式匹配的完整字节数组。
     * 返回: 本实例以支持链式调用；副作用是修改协议字段，格式错误时抛运行时异常。
     */
    @SuppressWarnings("unchecked")
    default <T extends ProtocolBytes> T decodeBytes(byte[] data) {
        mode().decode(this, data);
        return (T) this;
    }

    /**
     * 业务作用: 创建指定协议类型并一次性完成字节解码。
     * 参数说明: clazz 具有可用无参构造的目标类型；data 与目标模式匹配的完整字节数组。
     * 返回: 已填充的新实例；实例化或解码失败时抛运行时异常。
     */
    static <T extends ProtocolBytes> T of(Class<T> clazz, byte[] data) {
        return ReflectUtils.newInstance(clazz).decodeBytes(data);
    }

    /**
     * 业务作用: 获取当前类型的协议字段元数据，避免每次编解码重复反射。
     * 参数说明: 无。
     * 返回: 当前 Class 的缓存元数据；tag 非法或重复时首次构建直接失败。
     */
    default Meta meta() {
        return META.computeIfAbsent(this.getClass(), ProtocolBytes::buildMeta);
    }

    /* ============================== 元数据构建（每个 Class 仅首次） ============================== */

    /**
     * 业务作用: 扫描协议字段并构建四种模式共用的访问元数据，是字段、tag 与线路位置的唯一映射来源。
     * 参数说明: clazz 待扫描的协议实现类。
     * 返回: 缓存所需元数据；tag 非正数、重复或字段不可访问时直接失败，避免生成歧义线路格式。
     */
    private static Meta buildMeta(Class<?> clazz) {
        List<Field> fields = ReflectUtils.allField(clazz,
                Reflect.IsNotStatic.getApplier(),
                m -> ((AnnotatedElement) m).isAnnotationPresent(Protocols.class));

        fields.sort(Comparator.comparingInt(f -> f.getAnnotation(Protocols.class).value()));

        Accessor[] accs = new Accessor[fields.size()];
        Map<Integer, Accessor> byTag = HashMap.newHashMap(fields.size());
        Map<String, Accessor> byName = HashMap.newHashMap(fields.size());
        int totalFixedSize = 0;
        boolean fixedAllowed = true;
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(clazz, MethodHandles.lookup());
            for (int i = 0; i < fields.size(); i++) {
                Field f = fields.get(i);
                f.setAccessible(true);
                VarHandle vh = lookup.unreflectVarHandle(f);
                Protocols ann = f.getAnnotation(Protocols.class);
                int tag = ann.value();
                if (tag <= 0) {
                    throw new IllegalStateException("ProtocolBytes 字段 tag 必须大于 0: "
                            + clazz.getName() + "." + f.getName() + " tag=" + tag);
                }
                String fieldName = f.getName();
                Class<?> elementType = extractElementType(f);
                WireType wt = resolveWireType(f.getType());
                int fixedSize = resolveFixedSize(f.getType());
                Accessor acc = new Accessor(tag, fieldName, vh, f.getType(), elementType, wt, fixedSize);
                accs[i] = acc;
                if (byTag.put(tag, acc) != null) {
                    throw new IllegalStateException("ProtocolBytes 字段 tag 冲突: " + clazz.getName() + " tag=" + tag);
                }
                byName.put(fieldName, acc);
                if (fixedSize < 0) {
                    fixedAllowed = false;
                } else if (fixedAllowed) {
                    totalFixedSize += fixedSize;
                }
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException("ProtocolBytes 元数据构建失败: " + clazz.getName(), e);
        }

        ThreadLocal<byte[]> scratch = ThreadLocal.withInitial(() -> new byte[1024]);
        ThreadLocal<Boolean> inEncode = ThreadLocal.withInitial(() -> Boolean.FALSE);
        return new Meta(accs, byTag, byName, scratch, inEncode, fixedAllowed ? totalFixedSize : -1);
    }

    /**
     * List<X>/Iterable<X> → X.class; X[] → X.class; 其他 null.
     */
    private static Class<?> extractElementType(Field f) {
        Class<?> type = f.getType();
        if (type.isArray()) return type.getComponentType();
        if (Iterable.class.isAssignableFrom(type)) {
            Type gt = f.getGenericType();
            if (gt instanceof ParameterizedType pt) {
                Type[] args = pt.getActualTypeArguments();
                if (args.length > 0 && args[0] instanceof Class<?> ec) return ec;
            }
        }
        return null;
    }

    /**
     * 字段 JVM 类型 → wire type.
     */
    private static WireType resolveWireType(Class<?> type) {
        if (type == double.class || type == Double.class) return WireType.FIXED64;
        if (type == float.class || type == Float.class) return WireType.FIXED32;
        if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == short.class || type == Short.class
                || type == byte.class || type == Byte.class
                || type == char.class || type == Character.class
                || type == boolean.class || type == Boolean.class
                || type.isEnum()) return WireType.VARINT;
        // String / byte[] / 嵌套 ProtocolBytes / List / Array
        return WireType.LENGTH_DELIMITED;
    }

    /**
     * 字段固定长度 (FAST_FIXED 模式). 变长字段返回 -1.
     */
    private static int resolveFixedSize(Class<?> type) {
        if (type == boolean.class || type == byte.class) return 1;
        if (type == short.class || type == char.class) return 2;
        if (type == int.class || type == float.class) return 4;
        if (type == long.class || type == double.class) return 8;
        // wrapper / String / byte[] / enum / List / 嵌套 → -1 不能 FIXED
        return -1;
    }

    /* ============================== JSON_BYTES helper ============================== */

    /**
     * 业务作用: 把协议实例转换为仅含 {@link Protocols} 字段的 JSON 中间 Map，并递归处理嵌套协议。
     * 参数说明: instance 待转换的协议实例；allocs 本次编码创建的池化容器跟踪表。
     * 返回: 按 {@link Protocols#value()} 顺序构建的 Map；返回值已登记到 allocs，由调用方统一回收。
     * <p>
     * 每分配一个本框架的 RecycleLinkedMap/List 都登记到 allocs, 调用方在 Jackson 序列化结束后逐个 recycle。
     * <p>
     * <b>不要</b>把用户字段值 (例如类型为 Map 的字段, 或 Iterable 内含的池化对象) 加入 allocs —
     * 那是用户持有的数据, 框架无权 recycle。
     */
    private static Map<String, Object> toJsonMap(ProtocolBytes instance, List<ObjectPool.Recycler<?>> allocs) {
        Meta meta = instance.meta();
        Accessor[] accs = meta.accessors();
        RecycleLinkedMap<String, Object> result = RecycleLinkedMap.of();
        allocs.add(result);
        for (Accessor acc : accs) {
            Object v = acc.vh().get(instance);
            if (v == null) continue;
            result.put(acc.fieldName(), toJsonValue(v, allocs));
        }
        return result;
    }

    /**
     * 业务作用: 把单个字段值转换为 Jackson 可序列化的 JSON 中间值，并登记转换时创建的池化容器。
     * 参数说明: v 非空字段值；allocs 本次编码创建的池化容器跟踪表。
     * 返回: 嵌套协议对应的 Map、集合或数组对应的 List，其他类型保持原值。
     */
    private static Object toJsonValue(Object v, List<ObjectPool.Recycler<?>> allocs) {
        // 嵌套 ProtocolBytes → Map (递归 toJsonMap, 自身 result 已登记)
        if (v instanceof ProtocolBytes pb) return toJsonMap(pb, allocs);
        // List<ProtocolBytes> / List<基础类型> → List<Map> 或 List<原值>
        if (v instanceof Iterable<?> col) {
            RecycleLinkedList<Object> list = RecycleLinkedList.of();
            allocs.add(list);
            for (Object item : col) {
                if (item == null) list.add(null);
                else if (item instanceof ProtocolBytes pb) list.add(toJsonMap(pb, allocs));
                else list.add(item);   // 原值: 不进 allocs (可能是用户池化对象)
            }
            return list;
        }
        // 数组 → List
        if (v.getClass().isArray()) {
            int n = Array.getLength(v);
            RecycleLinkedList<Object> list = RecycleLinkedList.of();
            allocs.add(list);
            for (int i = 0; i < n; i++) {
                Object item = Array.get(v, i);
                if (item == null) list.add(null);
                else if (item instanceof ProtocolBytes pb) list.add(toJsonMap(pb, allocs));
                else list.add(item);
            }
            return list;
        }
        // 基础类型 / String / byte[] / Enum / Map → 原值 (Jackson 自动处理, 不登记)
        return v;
    }

    /**
     * 把 LinkedHashMap 应用到 instance 字段, 按 fieldName 反查 Accessor, 跨版本兼容 (未知字段跳过).
     */
    private static void fromJsonMap(ProtocolBytes instance, Map<String, Object> map) {
        Meta meta = instance.meta();
        Map<String, Accessor> byName = meta.byName();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Accessor acc = byName.get(entry.getKey());
            if (acc == null) continue;   // 跨版本: 跳过未知字段
            Object v = fromJsonValue(entry.getValue(), acc);
            if (v != null) acc.vh().set(instance, v);
        }
    }

    /**
     * 业务作用: 把 JSON Map 中的单个字段值恢复为声明类型，包含嵌套协议和集合容器的递归处理。
     * 参数说明: v JSON 解析后的值；acc 目标字段元数据。
     * 返回: 可写入字段的值；无法恢复为兼容类型时抛异常。
     */
    @SuppressWarnings({"unchecked"})
    private static Object fromJsonValue(Object v, Accessor acc) {
        if (v == null) return null;
        Class<?> type = acc.type();
        Class<?> et = acc.elementType();

        // 嵌套 ProtocolBytes
        switch (v) {
            case Map<?, ?> nestedMap when ProtocolBytes.class.isAssignableFrom(type) -> {
                ProtocolBytes nested = (ProtocolBytes) ReflectUtils.newInstance(type);
                fromJsonMap(nested, (Map<String, Object>) nestedMap);
                return nested;
            }

            // List<ProtocolBytes> 或 List<基础类型>
            case List<?> list when Iterable.class.isAssignableFrom(type) -> {
                Collection<Object> result = createCollection(type, list.size());
                for (Object item : list) {
                    if (item == null) result.add(null);
                    else if (et != null && ProtocolBytes.class.isAssignableFrom(et) && item instanceof Map<?, ?> em) {
                        ProtocolBytes nested = (ProtocolBytes) ReflectUtils.newInstance(et);
                        fromJsonMap(nested, (Map<String, Object>) em);
                        result.add(nested);
                    } else {
                        result.add(coerceJsonValue(item, et));
                    }
                }
                return result;
            }

            // 数组
            case List<?> list when type.isArray() -> {
                Object array = Array.newInstance(et, list.size());
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (item == null) {
                        Array.set(array, i, null);
                    } else if (et != null && ProtocolBytes.class.isAssignableFrom(et) && item instanceof Map<?, ?> em) {
                        ProtocolBytes nested = (ProtocolBytes) ReflectUtils.newInstance(et);
                        fromJsonMap(nested, (Map<String, Object>) em);
                        Array.set(array, i, nested);
                    } else {
                        Array.set(array, i, coerceJsonValue(item, et));
                    }
                }
                return array;
            }
            default -> {
            }
        }
        return coerceJsonValue(v, type);
    }

    /**
     * 业务作用: 将 Jackson 的通用 JSON 值严格恢复为字段声明类型，避免窄整数回绕、非法布尔值
     * 退化为 false，并为 JavaTime、UUID 等 JSON 专属类型提供统一转换入口。
     * 参数说明: v Jackson 解析后的非 null 值；type 目标字段或集合元素类型，可为 null。
     * 返回: 与目标类型兼容的值；存在精度损失、范围溢出或格式错误时抛 IllegalArgumentException。
     */
    @SuppressWarnings({"unchecked"})
    private static Object coerceJsonValue(Object v, Class<?> type) {
        if (v == null || type == null) return v;
        if (v instanceof Number n) {
            // 所有整数转换先做精确性与范围复验，防止 JSON 的 Long/BigDecimal 在窄化时回绕或丢小数。
            if (type == byte.class || type == Byte.class) {
                long value = exactJsonLong(n, type);
                if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) throw jsonNumericOverflow(type, n);
                return (byte) value;
            }
            if (type == short.class || type == Short.class) {
                long value = exactJsonLong(n, type);
                if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) throw jsonNumericOverflow(type, n);
                return (short) value;
            }
            if (type == int.class || type == Integer.class) {
                long value = exactJsonLong(n, type);
                if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw jsonNumericOverflow(type, n);
                return (int) value;
            }
            if (type == long.class || type == Long.class) return exactJsonLong(n, type);
            if (type == double.class || type == Double.class) return n.doubleValue();
            if (type == float.class || type == Float.class) return n.floatValue();
            if (type == BigDecimal.class) {
                if (n instanceof BigDecimal decimal) return decimal;
                if (n instanceof BigInteger integer) return new BigDecimal(integer);
                return new BigDecimal(n.toString());
            }
            if (type == BigInteger.class) {
                if (n instanceof BigInteger integer) return integer;
                if (n instanceof BigDecimal decimal) return decimal.toBigIntegerExact();
                return new BigDecimal(n.toString()).toBigIntegerExact();
            }
        }
        if (v instanceof String s) {
            if (type == boolean.class || type == Boolean.class) {
                // Boolean.parseBoolean 会把任何拼写错误都静默当成 false，协议边界必须显式拒绝。
                if ("true".equalsIgnoreCase(s)) return true;
                if ("false".equalsIgnoreCase(s)) return false;
                throw new IllegalArgumentException("ProtocolBytes boolean JSON 值只接受 true/false, 收到 \""
                        + s + "\"");
            }
            if (type.isEnum()) {
                try {
                    return Enum.valueOf((Class<? extends Enum>) type, s);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("ProtocolBytes 未知枚举值: "
                            + type.getName() + "." + s, e);
                }
            }
            if (type == char.class || type == Character.class) {
                if (s.length() != 1) {
                    throw new IllegalArgumentException("ProtocolBytes char JSON 值要求长度为 1, 收到 \""
                            + s + "\"");
                }
                return s.charAt(0);
            }
        }
        if ((type.isPrimitive() && primitiveWrapper(type).isInstance(v)) || type.isInstance(v)) return v;
        try {
            return ObjMprUtils.OBJECT_MAPPER.convertValue(v, type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ProtocolBytes JSON 值无法转换为 " + type.getTypeName()
                    + ": " + v, e);
        }
    }

    /**
     * 业务作用: 将 JSON 数字精确收敛为 long，作为所有整数字段转换的统一数据完整性门禁。
     * 参数说明: number Jackson 解析出的数字；targetType 最终字段类型，用于异常定位。
     * 返回: 与输入完全等价的 long；小数、非有限值或越界时抛 IllegalArgumentException。
     */
    private static long exactJsonLong(Number number, Class<?> targetType) {
        try {
            if (number instanceof BigInteger integer) return integer.longValueExact();
            if (number instanceof BigDecimal decimal) return decimal.longValueExact();
            if (number instanceof Byte || number instanceof Short
                    || number instanceof Integer || number instanceof Long) {
                return number.longValue();
            }
            return new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("ProtocolBytes JSON 数值无法精确转换为 "
                    + targetType.getTypeName() + ": " + number, e);
        }
    }

    /**
     * 业务作用: 统一生成 JSON 窄整数转换的越界异常，避免不同字段类型产生含义不一致的失败信息。
     * 参数说明: targetType 目标字段类型；number 原始 JSON 数字。
     * 返回: 包含目标类型与非法值的 IllegalArgumentException，由调用方直接抛出。
     */
    private static IllegalArgumentException jsonNumericOverflow(Class<?> targetType, Number number) {
        return new IllegalArgumentException("ProtocolBytes JSON 数值超出 " + targetType.getTypeName()
                + " 范围: " + number);
    }

    /**
     * 业务作用: 获取基础类型对应的包装类型，供 JSON 已匹配类型的快速路径判断使用。
     * 参数说明: type 基础类型 Class。
     * 返回: 对应包装类型；传入非基础类型时原样返回。
     */
    private static Class<?> primitiveWrapper(Class<?> type) {
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    /* ============================== Varint / Zigzag ============================== */

    /**
     * Varint 编码 long, 返回写入后位置. buf 长度由调用方确保. 最多 10 byte (long max).
     */
    private static int writeVarint(byte[] buf, int pos, long value) {
        while ((value & ~0x7FL) != 0L) {
            buf[pos++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf[pos++] = (byte) value;
        return pos;
    }

    /**
     * Varint 解码, ByteBuffer position 自动前进.
     */
    private static long readVarint(ByteBuffer buf) {
        long result = 0;
        for (int i = 0; i < 10; i++) {
            if (!buf.hasRemaining()) throw new RuntimeException("truncated varint");
            int b = buf.get() & 0xFF;
            if (i == 9 && (b & 0xFE) != 0) {
                throw new RuntimeException("varint overflow");
            }
            result |= ((long) (b & 0x7F)) << (i * 7);
            if ((b & 0x80) == 0) return result;
        }
        throw new RuntimeException("varint too long");
    }

    /**
     * Varint 编码后字节数.
     */
    private static int varintSize(long value) {
        if (value < 0) return 10;
        int size = 1;
        while ((value & ~0x7FL) != 0L) {
            size++;
            value >>>= 7;
        }
        return size;
    }

    /**
     * Zigzag 编码 (有符号 → 无符号 varint 友好).
     */
    private static long zigzagEncode(long v) {
        return (v << 1) ^ (v >> 63);
    }

    private static long zigzagDecode(long v) {
        return (v >>> 1) ^ -(v & 1L);
    }

    /* ============================== buffer 扩容 ============================== */

    /**
     * 确保 buf 长度 ≥ minLen, 不够则倍增扩容. 返回 (可能新的) buffer.
     */
    private static byte[] ensureCapacity(byte[] buf, int minLen) {
        if (minLen < 0) throw new RuntimeException("buffer length overflow");
        if (buf.length >= minLen) return buf;
        int newLen = buf.length;
        while (newLen < minLen) {
            int next = newLen << 1;
            if (next <= newLen) {
                newLen = minLen;
                break;
            }
            newLen = next;
        }
        return Arrays.copyOf(buf, newLen);
    }

    /* ============================== VARINT_TLV codec ============================== */

    /**
     * 业务作用: 按 tag 与 wire type 编码可跨版本跳过未知字段的 VARINT_TLV 载荷。
     * 参数说明: instance 待编码实例。
     * 返回: 独立字节数组；不支持的字段类型会终止编码。
     */
    private static byte[] encodeVarintTlv(ProtocolBytes instance) {
        Meta meta = instance.meta();
        Accessor[] accs = meta.accessors();
        boolean reentrant = meta.inEncode().get();
        byte[] buf = reentrant ? new byte[1024] : meta.scratch().get();
        if (!reentrant) meta.inEncode().set(Boolean.TRUE);
        int[] ph = {0};   // mutable pos holder
        try {
            for (Accessor acc : accs) {
                Object v = acc.vh().get(instance);
                if (v == null) continue;
                // 写 tag<<3|wireType
                long taggedType = ((long) acc.tag() << 3) | acc.wireType().code;
                buf = ensureCapacity(buf, ph[0] + varintSize(taggedType));
                ph[0] = writeVarint(buf, ph[0], taggedType);
                // 写 value
                buf = writeFieldValue(buf, ph, v, acc);
            }
            return Arrays.copyOf(buf, ph[0]);
        } finally {
            if (!reentrant) {
                Arrays.fill(buf, 0, Math.min(ph[0], buf.length), (byte) 0);
                // 扩容后的缓冲继续归当前类型与线程复用，避免大消息之后反复从 1 KiB 重新扩容。
                meta.scratch().set(buf);
                meta.inEncode().set(Boolean.FALSE);
            }
        }
    }

    /**
     * 业务作用: 解码 VARINT_TLV 载荷，并在遇到未知 tag 时按 wire type 安全跳过该值。
     * 参数说明: instance 待填充实例；data 完整线路字节。
     * 返回: 无；wire type 不匹配或载荷截断时拒绝继续解码。
     */
    private static void decodeVarintTlv(ProtocolBytes instance, byte[] data) {
        Meta meta = instance.meta();
        Map<Integer, Accessor> byTag = meta.byTag();
        ByteBuffer buf = ByteBuffer.wrap(data);
        while (buf.hasRemaining()) {
            long taggedType = readVarint(buf);
            int tag = (int) (taggedType >>> 3);
            int wtCode = (int) (taggedType & 0x7);
            if (tag <= 0) {
                throw new RuntimeException("invalid ProtocolBytes tag: " + tag);
            }
            Accessor acc = byTag.get(tag);
            if (acc == null) {
                // 跨版本兼容: 跳过未知 tag
                skipValue(buf, wtCode);
                continue;
            }
            if (wtCode != acc.wireType().code) {
                throw new RuntimeException("wire type mismatch for tag " + tag
                        + ": expected=" + acc.wireType().code + ", actual=" + wtCode);
            }
            Object v = readFieldValue(buf, acc);
            if (v != null) acc.vh().set(instance, v);
        }
    }

    /* ============================== BITPACK_TLV codec ============================== */

    /**
     * 业务作用: 使用单个 64 位 bitmap 标识非空字段并编码紧凑的 BITPACK_TLV 载荷。
     * 参数说明: instance 待编码实例。
     * 返回: 独立字节数组；协议字段超过 64 个时拒绝编码，防止 bitmap 位碰撞。
     */
    private static byte[] encodeBitpackTlv(ProtocolBytes instance) {
        Meta meta = instance.meta();
        Accessor[] accs = meta.accessors();
        int len = accs.length;
        validateBitpackFieldCount(instance.getClass(), len);
        boolean reentrant = meta.inEncode().get();
        byte[] buf = reentrant ? new byte[1024] : meta.scratch().get();
        if (!reentrant) meta.inEncode().set(Boolean.TRUE);

        // bitmap 字节数自适应: 1/2/4/8B
        int bitmapBytes = (len <= 8) ? 1 : (len <= 16) ? 2 : (len <= 32) ? 4 : 8;
        int[] ph = {bitmapBytes};   // 头部预留 bitmap 空间, body 从 bitmapBytes 开始写
        long bitmap = 0L;
        try {
            buf = ensureCapacity(buf, bitmapBytes);
            for (int i = 0; i < len; i++) {
                Object v = accs[i].vh().get(instance);
                if (v == null) continue;
                bitmap |= (1L << i);
                buf = writeFieldValue(buf, ph, v, accs[i]);
            }
            // 回填 bitmap (little-endian)
            for (int i = 0; i < bitmapBytes; i++) {
                buf[i] = (byte) (bitmap >>> (i * 8));
            }
            return Arrays.copyOf(buf, ph[0]);
        } finally {
            if (!reentrant) {
                Arrays.fill(buf, 0, Math.min(ph[0], buf.length), (byte) 0);
                meta.scratch().set(buf);
                meta.inEncode().set(Boolean.FALSE);
            }
        }
    }

    /**
     * 业务作用: 按固定 schema 解码 BITPACK_TLV，校验未知 bitmap 位和尾随数据以避免错位回填。
     * 参数说明: instance 待填充实例；data 完整线路字节。
     * 返回: 无；字段超过 64 个、数据截断或存在额外数据时抛异常。
     */
    private static void decodeBitpackTlv(ProtocolBytes instance, byte[] data) {
        Meta meta = instance.meta();
        Accessor[] accs = meta.accessors();
        int len = accs.length;
        validateBitpackFieldCount(instance.getClass(), len);
        int bitmapBytes = (len <= 8) ? 1 : (len <= 16) ? 2 : (len <= 32) ? 4 : 8;
        if (data.length < bitmapBytes) {
            throw new RuntimeException("BITPACK_TLV decode: data too short, expected bitmap " + bitmapBytes + " bytes");
        }
        long bitmap = 0L;
        for (int i = 0; i < bitmapBytes; i++) {
            bitmap |= ((long) (data[i] & 0xFF)) << (i * 8);
        }
        long validMask = len == 64 ? -1L : (1L << len) - 1L;
        if ((bitmap & ~validMask) != 0) {
            throw new RuntimeException("BITPACK_TLV decode: bitmap contains unknown fields");
        }
        ByteBuffer buf = ByteBuffer.wrap(data, bitmapBytes, data.length - bitmapBytes);
        for (int i = 0; i < len; i++) {
            if ((bitmap & (1L << i)) == 0) continue;
            Object v = readFieldValue(buf, accs[i]);
            if (v != null) accs[i].vh().set(instance, v);
        }
        if (buf.hasRemaining()) {
            throw new RuntimeException("BITPACK_TLV decode: trailing bytes=" + buf.remaining());
        }
    }

    /**
     * 业务作用: 把 BITPACK 的 64 位 bitmap 上限限制在模式入口，避免无关的 JSON、VARINT_TLV
     * 和 FAST_FIXED 类型被同一份元数据误拒绝。
     * 参数说明: type 协议实现类型；fieldCount 参与协议的字段数量。
     * 返回: 无；超过 64 个字段时抛 IllegalStateException，防止位移回绕造成字段碰撞和错解。
     */
    private static void validateBitpackFieldCount(Class<?> type, int fieldCount) {
        if (fieldCount > Long.SIZE) {
            throw new IllegalStateException("ProtocolBytes BITPACK_TLV 最多支持 64 个字段: "
                    + type.getName() + " 有 " + fieldCount + " 个");
        }
    }

    /* ============================== FAST_FIXED codec ============================== */

    private static byte[] encodeFastFixed(ProtocolBytes instance) {
        Meta meta = instance.meta();
        if (meta.totalFixedSize() < 0) {
            throw new RuntimeException("FAST_FIXED 不允许变长字段 (String/byte[]/嵌套/List/wrapper/Enum), class="
                    + instance.getClass().getName());
        }
        byte[] out = new byte[meta.totalFixedSize()];
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        for (Accessor acc : meta.accessors()) {
            Object v = acc.vh().get(instance);
            // FAST_FIXED 不允许 null (基础类型默认 0/false)
            writeFixedValue(buf, v, acc);
        }
        return out;
    }

    private static void decodeFastFixed(ProtocolBytes instance, byte[] data) {
        Meta meta = instance.meta();
        if (meta.totalFixedSize() < 0) {
            throw new RuntimeException("FAST_FIXED 不允许变长字段, class=" + instance.getClass().getName());
        }
        if (data.length != meta.totalFixedSize()) {
            throw new RuntimeException("FAST_FIXED decode: data length " + data.length
                    + " mismatch expected " + meta.totalFixedSize());
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (Accessor acc : meta.accessors()) {
            Object v = readFixedValue(buf, acc);
            acc.vh().set(instance, v);
        }
    }

    /* ============================== 字段值读写 (VARINT_TLV / BITPACK_TLV 共用) ============================== */

    /**
     * 写字段值到 buf[posHolder[0]]. 返回 (可能扩容后的) buf, 新 pos 通过 posHolder[0] 写出.
     * <p>
     * 用 mutable int[1] posHolder 传 pos 而非返回 — 避开"返回多值需要分配"的问题, 一次 encode 复用同一个 posHolder.
     * 用 byte[] 返回值传扩容后的 buf — 调用方接收 buf 引用更新.
     */
    private static byte[] writeFieldValue(byte[] buf, int[] posHolder, Object v, Accessor acc) {
        Class<?> type = acc.type();
        WireType wt = acc.wireType();
        int pos = posHolder[0];

        if (wt == WireType.VARINT) {
            long lv = toLong(v, type);
            // 有符号类型 (int/long/short/byte) 用 zigzag; boolean/char/Enum 直接 varint
            if (isSignedIntegral(type)) lv = zigzagEncode(lv);
            buf = ensureCapacity(buf, pos + varintSize(lv));
            pos = writeVarint(buf, pos, lv);
        } else if (wt == WireType.FIXED32) {
            buf = ensureCapacity(buf, pos + 4);
            int bits = Float.floatToIntBits((Float) v);
            buf[pos++] = (byte) bits;
            buf[pos++] = (byte) (bits >>> 8);
            buf[pos++] = (byte) (bits >>> 16);
            buf[pos++] = (byte) (bits >>> 24);
        } else if (wt == WireType.FIXED64) {
            buf = ensureCapacity(buf, pos + 8);
            long bits = Double.doubleToLongBits((Double) v);
            for (int i = 0; i < 8; i++) buf[pos++] = (byte) (bits >>> (i * 8));
        } else {
            // LENGTH_DELIMITED: String / byte[] / 嵌套 ProtocolBytes / List / Array
            byte[] payload = encodeLengthDelimited(v, acc);
            int totalLen = varintSize(payload.length) + payload.length;
            buf = ensureCapacity(buf, pos + totalLen);
            pos = writeVarint(buf, pos, payload.length);
            System.arraycopy(payload, 0, buf, pos, payload.length);
            pos += payload.length;
        }

        posHolder[0] = pos;
        return buf;
    }

    /**
     * 读字段值.
     */
    private static Object readFieldValue(ByteBuffer buf, Accessor acc) {
        Class<?> type = acc.type();
        WireType wt = acc.wireType();

        if (wt == WireType.VARINT) {
            long lv = readVarint(buf);
            if (isSignedIntegral(type)) lv = zigzagDecode(lv);
            return fromLong(lv, type);
        }
        if (wt == WireType.FIXED32) {
            int bits = (buf.get() & 0xFF)
                    | ((buf.get() & 0xFF) << 8)
                    | ((buf.get() & 0xFF) << 16)
                    | ((buf.get() & 0xFF) << 24);
            return Float.intBitsToFloat(bits);
        }
        if (wt == WireType.FIXED64) {
            long bits = 0L;
            for (int i = 0; i < 8; i++) bits |= ((long) (buf.get() & 0xFF)) << (i * 8);
            return Double.longBitsToDouble(bits);
        }
        // LENGTH_DELIMITED
        int len = readLength(buf, "field");
        byte[] payload = new byte[len];
        buf.get(payload);
        return decodeLengthDelimited(payload, acc);
    }

    /**
     * 跳过未知 tag 的 value. 用于跨版本兼容 (VARINT_TLV).
     */
    private static void skipValue(ByteBuffer buf, int wtCode) {
        switch (wtCode) {
            case 0 -> readVarint(buf);   // VARINT
            case 1 -> skipBytes(buf, 8, "FIXED64");
            case 5 -> skipBytes(buf, 4, "FIXED32");
            case 2 -> {
                int len = readLength(buf, "unknown field");
                skipBytes(buf, len, "unknown field");
            }
            default -> throw new RuntimeException("unknown wire type code: " + wtCode);
        }
    }

    /* ============================== LENGTH_DELIMITED 编解码 (String/byte[]/嵌套/List/Array) ============================== */

    private static byte[] encodeLengthDelimited(Object v, Accessor acc) {
        Class<?> type = acc.type();
        if (type == String.class) {
            return ((String) v).getBytes(StandardCharsets.UTF_8);
        }
        if (type == byte[].class) {
            return (byte[]) v;
        }
        if (ProtocolBytes.class.isAssignableFrom(type)) {
            return ((ProtocolBytes) v).encodeBytes();
        }
        // List / Iterable
        if (v instanceof Iterable<?> iter) {
            return encodeIterable(iter, acc.elementType());
        }
        // Array
        if (type.isArray()) {
            return encodeArray(v, acc.elementType());
        }
        throw new RuntimeException("ProtocolBytes 不支持的字段类型: " + type.getName());
    }

    /**
     * 业务作用: 按字段声明类型还原 LENGTH_DELIMITED 载荷，分派字符串、字节数组、嵌套协议和集合。
     * 参数说明: payload 已完成长度校验的字段载荷；acc 目标字段元数据。
     * 返回: 可写入目标字段的对象；声明类型不受支持时抛异常。
     */
    @SuppressWarnings({"unchecked"})
    private static Object decodeLengthDelimited(byte[] payload, Accessor acc) {
        Class<?> type = acc.type();
        if (type == String.class) {
            return new String(payload, StandardCharsets.UTF_8);
        }
        if (type == byte[].class) {
            return payload;
        }
        if (ProtocolBytes.class.isAssignableFrom(type)) {
            return ProtocolBytes.of((Class<? extends ProtocolBytes>) type, payload);
        }
        if (Iterable.class.isAssignableFrom(type)) {
            return decodeIterable(payload, acc.elementType(), type);
        }
        if (type.isArray()) {
            return decodeArray(payload, acc.elementType());
        }
        throw new RuntimeException("ProtocolBytes 不支持的字段类型: " + type.getName());
    }

    /**
     * 编码集合: 内部布局 [count varint] [元素1 length varint] [元素1 bytes] [元素2 length varint] [元素2 bytes] ...
     * 中间累积链表用 {@link RecycleLinkedList} 池化, 出口前合并成单 byte[] 后回收链表.
     */
    private static byte[] encodeIterable(Iterable<?> iter, Class<?> elementType) {
        RecycleLinkedList<byte[]> elements = RecycleLinkedList.of();
        try {
            int totalSize = 0;
            for (Object item : iter) {
                byte[] eBytes = encodeElement(item, elementType);
                elements.add(eBytes);
                totalSize += varintSize(eBytes.length) + eBytes.length;
            }
            int countSize = varintSize(elements.size());
            byte[] out = new byte[countSize + totalSize];
            int pos = writeVarint(out, 0, elements.size());
            for (byte[] eBytes : elements) {
                pos = writeVarint(out, pos, eBytes.length);
                System.arraycopy(eBytes, 0, out, pos, eBytes.length);
                pos += eBytes.length;
            }
            return out;
        } finally {
            elements.recycle();
        }
    }

    private static byte[] encodeArray(Object array, Class<?> elementType) {
        int n = Array.getLength(array);
        RecycleLinkedList<byte[]> elements = RecycleLinkedList.of();
        try {
            int totalSize = 0;
            for (int i = 0; i < n; i++) {
                byte[] eBytes = encodeElement(Array.get(array, i), elementType);
                elements.add(eBytes);
                totalSize += varintSize(eBytes.length) + eBytes.length;
            }
            int countSize = varintSize(n);
            byte[] out = new byte[countSize + totalSize];
            int pos = writeVarint(out, 0, n);
            for (byte[] eBytes : elements) {
                pos = writeVarint(out, pos, eBytes.length);
                System.arraycopy(eBytes, 0, out, pos, eBytes.length);
                pos += eBytes.length;
            }
            return out;
        } finally {
            elements.recycle();
        }
    }

    /**
     * 编码单个元素 — 根据 elementType 选编码方式.
     */
    private static byte[] encodeElement(Object item, Class<?> elementType) {
        if (item == null) return new byte[0];   // null 元素用空 byte[] (length=0)
        if (elementType == String.class) {
            return ((String) item).getBytes(StandardCharsets.UTF_8);
        }
        if (elementType == byte[].class) {
            return (byte[]) item;
        }
        if (elementType != null && ProtocolBytes.class.isAssignableFrom(elementType)) {
            return ((ProtocolBytes) item).encodeBytes();
        }
        // 基础类型元素 (Integer/Long/Double/...) — 用 varint/fixed
        WireType wt = resolveWireType(elementType != null ? elementType : item.getClass());
        byte[] tmp = new byte[16];
        int pos = 0;
        if (wt == WireType.VARINT) {
            long lv = toLong(item, elementType != null ? elementType : item.getClass());
            if (isSignedIntegral(elementType != null ? elementType : item.getClass())) lv = zigzagEncode(lv);
            pos = writeVarint(tmp, pos, lv);
        } else if (wt == WireType.FIXED32) {
            int bits = Float.floatToIntBits((Float) item);
            tmp[pos++] = (byte) bits;
            tmp[pos++] = (byte) (bits >>> 8);
            tmp[pos++] = (byte) (bits >>> 16);
            tmp[pos++] = (byte) (bits >>> 24);
        } else if (wt == WireType.FIXED64) {
            long bits = Double.doubleToLongBits((Double) item);
            for (int i = 0; i < 8; i++) tmp[pos++] = (byte) (bits >>> (i * 8));
        } else {
            throw new RuntimeException("不支持的元素类型: " + (elementType != null ? elementType : item.getClass()));
        }
        return Arrays.copyOf(tmp, pos);
    }

    /**
     * 业务作用: 解码集合扩展布局并恢复为字段声明类型可接收的容器。
     * 参数说明: payload 集合载荷；elementType 元素声明类型；declaredType 字段声明集合类型。
     * 返回: 保持元素顺序且可赋值给 declaredType 的集合；载荷畸形或类型不可构造时抛异常。
     */
    private static Collection<Object> decodeIterable(
            byte[] payload, Class<?> elementType, Class<?> declaredType) {
        ByteBuffer buf = ByteBuffer.wrap(payload);
        int n = readCollectionCount(buf);
        Collection<Object> result = createCollection(declaredType, n);
        for (int i = 0; i < n; i++) {
            int len = readLength(buf, "collection element");
            byte[] eBytes = new byte[len];
            buf.get(eBytes);
            result.add(decodeElement(eBytes, elementType));
        }
        if (buf.hasRemaining()) {
            throw new RuntimeException("collection decode: trailing bytes=" + buf.remaining());
        }
        return result;
    }

    /**
     * 业务作用: 创建与协议字段声明类型兼容的集合，避免解码结果固定为 ArrayList 后写入 Set、Queue
     * 或具体集合字段失败。
     * 参数说明: type 字段声明类型；expectedSize 预计元素数，仅用于预分配。
     * 返回: 可赋值给 type 的可变集合；无法安全构造时抛 IllegalArgumentException。
     */
    @SuppressWarnings("unchecked")
    private static Collection<Object> createCollection(Class<?> type, int expectedSize) {
        if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
            Object instance = ReflectUtils.newInstanceOrNull(type);
            if (instance instanceof Collection<?> collection) {
                return (Collection<Object>) collection;
            }
            throw new IllegalArgumentException("ProtocolBytes 集合字段类型必须可实例化并实现 Collection: "
                    + type.getName());
        }
        if (Set.class.isAssignableFrom(type)) {
            int capacity = Math.max(16, (int) (expectedSize / 0.75f) + 1);
            return new LinkedHashSet<>(capacity);
        }
        if (Queue.class.isAssignableFrom(type)) {
            // 集合线路允许 null 元素，因此使用 LinkedList，不能使用拒绝 null 的 ArrayDeque。
            return new LinkedList<>();
        }
        if (type.isAssignableFrom(ArrayList.class)) {
            return new ArrayList<>(expectedSize);
        }
        throw new IllegalArgumentException("ProtocolBytes 不支持的 Iterable 字段类型: " + type.getName());
    }

    private static Object decodeArray(byte[] payload, Class<?> elementType) {
        ByteBuffer buf = ByteBuffer.wrap(payload);
        int n = readCollectionCount(buf);
        Object result = Array.newInstance(elementType, n);
        for (int i = 0; i < n; i++) {
            int len = readLength(buf, "array element");
            byte[] eBytes = new byte[len];
            buf.get(eBytes);
            Array.set(result, i, decodeElement(eBytes, elementType));
        }
        if (buf.hasRemaining()) {
            throw new RuntimeException("array decode: trailing bytes=" + buf.remaining());
        }
        return result;
    }

    private static int readCollectionCount(ByteBuffer buf) {
        long count = readVarint(buf);
        if (count > Integer.MAX_VALUE) {
            throw new RuntimeException("collection count too large: " + count);
        }
        int n = (int) count;
        // 每个元素至少还需要 1 byte 的 length varint，先阻止伪造 count 触发巨量分配。
        if (n > buf.remaining()) {
            throw new RuntimeException("collection count " + n
                    + " exceeds remaining bytes " + buf.remaining());
        }
        return n;
    }

    private static int readLength(ByteBuffer buf, String field) {
        long length = readVarint(buf);
        if (length > Integer.MAX_VALUE || length > buf.remaining()) {
            throw new RuntimeException(field + " length " + length
                    + " exceeds remaining bytes " + buf.remaining());
        }
        return (int) length;
    }

    private static void skipBytes(ByteBuffer buf, int length, String field) {
        if (length < 0 || length > buf.remaining()) {
            throw new RuntimeException(field + " length " + length
                    + " exceeds remaining bytes " + buf.remaining());
        }
        buf.position(buf.position() + length);
    }

    @SuppressWarnings("unchecked")
    private static Object decodeElement(byte[] eBytes, Class<?> elementType) {
        if (eBytes.length == 0) return null;
        if (elementType == null) {
            throw new IllegalArgumentException("ProtocolBytes 集合元素缺少可解析的泛型类型");
        }
        if (elementType == String.class) {
            return new String(eBytes, StandardCharsets.UTF_8);
        }
        if (elementType == byte[].class) {
            return eBytes;
        }
        if (ProtocolBytes.class.isAssignableFrom(elementType)) {
            return ProtocolBytes.of((Class<? extends ProtocolBytes>) elementType, eBytes);
        }
        // 基础类型
        WireType wt = resolveWireType(elementType);
        ByteBuffer buf = ByteBuffer.wrap(eBytes);
        if (wt == WireType.VARINT) {
            long lv = readVarint(buf);
            // 集合元素有独立 length；读取后仍有数据说明生产端格式错位，不能把前缀当成合法值。
            if (buf.hasRemaining()) {
                throw new RuntimeException("varint collection element contains trailing bytes=" + buf.remaining());
            }
            if (isSignedIntegral(elementType)) lv = zigzagDecode(lv);
            return fromLong(lv, elementType);
        }
        if (wt == WireType.FIXED32) {
            if (eBytes.length != Integer.BYTES) {
                throw new RuntimeException("fixed32 collection element length must be 4, actual=" + eBytes.length);
            }
            int bits = (eBytes[0] & 0xFF) | ((eBytes[1] & 0xFF) << 8)
                    | ((eBytes[2] & 0xFF) << 16) | ((eBytes[3] & 0xFF) << 24);
            return Float.intBitsToFloat(bits);
        }
        if (wt == WireType.FIXED64) {
            if (eBytes.length != Long.BYTES) {
                throw new RuntimeException("fixed64 collection element length must be 8, actual=" + eBytes.length);
            }
            long bits = 0L;
            for (int i = 0; i < 8; i++) bits |= ((long) (eBytes[i] & 0xFF)) << (i * 8);
            return Double.longBitsToDouble(bits);
        }
        throw new RuntimeException("不支持的元素类型: " + elementType);
    }

    /* ============================== FAST_FIXED 字段读写 ============================== */

    private static void writeFixedValue(ByteBuffer buf, Object v, Accessor acc) {
        Class<?> type = acc.type();
        if (type == boolean.class) {
            buf.put((byte) (((Boolean) v) ? 1 : 0));
        } else if (type == byte.class) {
            buf.put((Byte) v);
        } else if (type == short.class) {
            buf.putShort((Short) v);
        } else if (type == char.class) {
            buf.putChar((Character) v);
        } else if (type == int.class) {
            buf.putInt((Integer) v);
        } else if (type == long.class) {
            buf.putLong((Long) v);
        } else if (type == float.class) {
            buf.putFloat((Float) v);
        } else if (type == double.class) {
            buf.putDouble((Double) v);
        } else {
            throw new RuntimeException("FAST_FIXED 不支持非基础类型字段: " + type);
        }
    }

    private static Object readFixedValue(ByteBuffer buf, Accessor acc) {
        Class<?> type = acc.type();
        if (type == boolean.class) return buf.get() != 0;
        if (type == byte.class) return buf.get();
        if (type == short.class) return buf.getShort();
        if (type == char.class) return buf.getChar();
        if (type == int.class) return buf.getInt();
        if (type == long.class) return buf.getLong();
        if (type == float.class) return buf.getFloat();
        if (type == double.class) return buf.getDouble();
        throw new RuntimeException("FAST_FIXED 不支持非基础类型字段: " + type);
    }

    /* ============================== 类型转换 helper ============================== */

    /**
     * JVM 对象 → long (VARINT 模式编码用). 不区分 zigzag, 调用方决定.
     */
    @SuppressWarnings({"rawtypes"})
    private static long toLong(Object v, Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return ((Boolean) v) ? 1L : 0L;
        if (type == byte.class || type == Byte.class) return ((Byte) v);
        if (type == short.class || type == Short.class) return ((Short) v);
        if (type == int.class || type == Integer.class) return ((Integer) v);
        if (type == long.class || type == Long.class) return ((Long) v);
        if (type == char.class || type == Character.class) return ((Character) v);
        if (type.isEnum()) return ((Enum) v).ordinal();
        throw new RuntimeException("toLong 不支持类型: " + type);
    }

    /**
     * 业务作用: 把 VARINT 解出的 long 安全收敛为字段声明类型，并拒绝越界数值与非法枚举序号。
     * 参数说明: v 已完成 zigzag 处理的线路值；type 目标字段或元素类型。
     * 返回: 可写入目标位置的基础类型或枚举；范围不合法时抛 IllegalArgumentException。
     */
    private static Object fromLong(long v, Class<?> type) {
        if (type == boolean.class || type == Boolean.class) {
            if (v != 0L && v != 1L) {
                throw new IllegalArgumentException("ProtocolBytes boolean 线路值只能是 0 或 1: " + v);
            }
            return v == 1L;
        }
        if (type == byte.class || type == Byte.class) {
            if (v < Byte.MIN_VALUE || v > Byte.MAX_VALUE) {
                throw new IllegalArgumentException("ProtocolBytes byte 线路值越界: " + v);
            }
            return (byte) v;
        }
        if (type == short.class || type == Short.class) {
            if (v < Short.MIN_VALUE || v > Short.MAX_VALUE) {
                throw new IllegalArgumentException("ProtocolBytes short 线路值越界: " + v);
            }
            return (short) v;
        }
        if (type == int.class || type == Integer.class) return Math.toIntExact(v);
        if (type == long.class || type == Long.class) return v;
        if (type == char.class || type == Character.class) {
            if (v < Character.MIN_VALUE || v > Character.MAX_VALUE) {
                throw new IllegalArgumentException("ProtocolBytes char 线路值越界: " + v);
            }
            return (char) v;
        }
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            if (v < 0 || v >= constants.length) {
                throw new IllegalArgumentException("ProtocolBytes 枚举序号越界: "
                        + type.getName() + " ordinal=" + v);
            }
            return constants[(int) v];
        }
        throw new RuntimeException("fromLong 不支持类型: " + type);
    }

    /**
     * 是否有符号整型 (VARINT 模式需要 zigzag 编码). boolean/char/Enum 不需要 zigzag.
     */
    private static boolean isSignedIntegral(Class<?> type) {
        return type == byte.class || type == Byte.class
                || type == short.class || type == Short.class
                || type == int.class || type == Integer.class
                || type == long.class || type == Long.class;
    }
}
