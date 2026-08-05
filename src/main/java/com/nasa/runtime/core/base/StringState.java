package com.nasa.runtime.core.base;

import com.nasa.runtime.core.utils.StringUtils;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 字符串状态: 一个字符串中每个字符位代表一个场景的状态.
 *
 * 每个字符可表示 62 种状态 (0-9 a-z A-Z), 远超 {@link BinaryEnable} 的 2 种.
 * 项目启动时通过 {@link #registry(Class, int)} 将枚举类绑定到字符串的指定位,
 * 运行时通过静态方法读写, 通过 default 方法生成 SQL 片段.
 *
 * <h2>使用示例</h2>
 * {@snippet :
 * // 1. 定义枚举，实现接口并覆盖 column() 指定 DB 字段名
 * enum TaskState implements StringState<TaskState> {
 *     CREATED, RUNNING, CLOSED;
 *     @Override public String column() { return "state"; }
 * }
 *
 * // 2. 启动时注册: 将 TaskState 绑定到字符串第 0 位
 * StringState.registry(TaskState.class, 0);
 *
 * // 3. 读取: 从字符串第 0 位读出 TaskState 枚举值
 * TaskState state = StringState.get("1a", TaskState.class);
 *
 * // 4. 写入: 将 RUNNING 写入第 0 位，返回新字符串
 * String encoded = StringState.set("00", TaskState.RUNNING);
 *
 * // 5. 判等: 判断第 0 位是否为 RUNNING
 * boolean running = StringState.is("10", TaskState.RUNNING);
 *
 * // 6. SQL WHERE 条件
 * TaskState.RUNNING.where()
 * // → "SUBSTRING(state, 1, 1) = '1'"
 *
 * // 7. SQL SET 片段
 * TaskState.RUNNING.update()
 * // → "state = INSERT(RPAD(IFNULL(state,''), 1, '0'), 1, 1, '1')"
 * }
 *
 * <h2>编码与兼容约束</h2>
 * 字符编码: '0'-'9' → ordinal 0-9, 'a'-'z' → 10-35, 'A'-'Z' → 36-61
 * <p>
 * 注意: 枚举值的定义顺序决定 ordinal，一旦数据写入数据库后不可在中间插入新值，只能在末尾追加。
 * 否则已有数据的 ordinal 映射会错位。
 */
@SuppressWarnings("unused")
public interface StringState<E extends Enum<E>> {

    char[] ENCODE = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    int[] DECODE = initDecode();
    Map<Class<?>, Integer> POS = new ConcurrentHashMap<>();

    private static int[] initDecode() {
        int[] d = new int[128];
        Arrays.fill(d, -1);
        for (int i = 0; i < ENCODE.length; i++) {
            d[ENCODE[i]] = i;
        }
        return d;
    }

    /**
     * 枚举序号 (由 Enum 提供, 映射到字符编码)
     */
    int ordinal();

    /**
     * 数据库字段名, 用于 SQL 片段生成. 枚举类可覆盖.
     */
    default String column() {
        return "state";
    }

    // ===== 注册 =====

    /**
     * 注册枚举类到字符串中的指定位置 (项目启动时调用, 全局一次).
     * 注册后该枚举类与字符串中的某一位绑定, 该位的字符对应枚举的 ordinal.
     *
     * @param enumClass 枚举类 (枚举值个数不超过 62)
     * @param position  字符串中的位置 (0-indexed)
     */
    static void registry(Class<? extends Enum<?>> enumClass, int position) {
        if (position < 0) {
            throw new IllegalArgumentException("position must >= 0");
        }
        if (enumClass.getEnumConstants().length > ENCODE.length) {
            throw new IllegalArgumentException(
                    enumClass.getSimpleName() + " has " + enumClass.getEnumConstants().length
                            + " constants, max " + ENCODE.length);
        }
        // 防止同一枚举类重复注册到不同位置（已有数据会错位）
        Integer existing = POS.putIfAbsent(enumClass, position);
        if (existing != null && existing != position) {
            throw new IllegalStateException(
                    enumClass.getSimpleName() + " already registered at position " + existing
                            + ", cannot re-register at " + position);
        }
        // 防止不同枚举类注册到同一位置（读写互相覆盖）
        for (Map.Entry<Class<?>, Integer> entry : POS.entrySet()) {
            if (entry.getValue() == position && entry.getKey() != enumClass) {
                throw new IllegalStateException(
                        "position " + position + " already bound to " + entry.getKey().getSimpleName()
                                + ", cannot bind " + enumClass.getSimpleName());
            }
        }
    }

    // ===== 读写 (static 方法，避免 TaskState.RUNNING.get() 返回 CREATED 的语义混乱) =====

    /**
     * 从状态字符串中读取指定枚举类对应位置的枚举值.
     * 字符串为 null、长度不足或字符无法解析时返回枚举的第一个值 (ordinal 0).
     *
     * @param state     状态字符串
     * @param enumClass 枚举类 (必须已 registry)
     * @return 该位置对应的枚举值
     */
    static <E extends Enum<E>> E get(String state, Class<E> enumClass) {
        Integer pos = POS.get(enumClass);
        if (pos == null) {
            throw new IllegalStateException(enumClass.getSimpleName() + " not registered");
        }
        E[] constants = enumClass.getEnumConstants();
        if (state == null || state.length() <= pos) {
            return constants[0];
        }
        char c = state.charAt(pos);
        int ord = c < 128 ? DECODE[c] : -1;
        if (ord < 0 || ord >= constants.length) {
            return constants[0];
        }
        return constants[ord];
    }

    /**
     * 将枚举值写入状态字符串的对应位置, 返回新字符串.
     * 字符串为 null 或长度不足时自动用 '0' 补位.
     *
     * @param state 原状态字符串
     * @param value 要写入的枚举值
     * @return 更新后的状态字符串
     */
    static <E extends Enum<E>> String set(String state, E value) {
        Class<?> clazz = value.getDeclaringClass();
        Integer pos = POS.get(clazz);
        if (pos == null) {
            throw new IllegalStateException(clazz.getSimpleName() + " not registered");
        }
        int len = Math.max(state == null ? 0 : state.length(), pos + 1);
        char[] chars = new char[len];
        Arrays.fill(chars, ENCODE[0]);
        if (state != null) {
            state.getChars(0, state.length(), chars, 0);
        }
        chars[pos] = ENCODE[value.ordinal()];
        return new String(chars);
    }

    /**
     * 判断状态字符串中指定枚举类的位置是否等于给定枚举值.
     *
     * @param state 状态字符串
     * @param value 要比较的枚举值
     */
    static <E extends Enum<E>> boolean is(String state, E value) {
        return value == get(state, (Class<E>) value.getDeclaringClass());
    }

    // ===== SQL 片段生成 (default 方法, 语义: "这个枚举值的 SQL 条件/赋值") =====

    /**
     * 生成 SQL WHERE 条件片段.
     * <p>
     * 输出示例: {@code SUBSTRING(state, 1, 1) = '1'}
     * <p>
     * 用法: {@code wrapper.last(TaskState.RUNNING.where())}，也可嵌入 XML 动态条件。
     */
    default String where() {
        Class<?> clazz = ((Enum<?>) this).getDeclaringClass();
        Integer pos = POS.get(clazz);
        if (pos == null) {
            throw new IllegalStateException(clazz.getSimpleName() + " not registered");
        }
        int sqlPos = pos + 1;
        return StringUtils.concat("SUBSTRING(RPAD(IFNULL(", this.column(), ",''), ", sqlPos, ", '0'), ", sqlPos, ", 1) = '", ENCODE[this.ordinal()], "'");
    }

    /**
     * 生成 SQL SET 赋值片段.
     * <p>
     * 输出示例: {@code state = INSERT(RPAD(IFNULL(state,''), 1, '0'), 1, 1, '1')}
     * <p>
     * IFNULL 兜底 NULL, RPAD 补位确保长度足够, INSERT 替换指定位字符.
     * <p>
     * 用法: {@code wrapper.setSql(TaskState.RUNNING.update())}
     */
    default String update() {
        Class<?> clazz = ((Enum<?>) this).getDeclaringClass();
        Integer pos = POS.get(clazz);
        if (pos == null) {
            throw new IllegalStateException(clazz.getSimpleName() + " not registered");
        }
        int sqlPos = pos + 1;
        String col = this.column();
        return StringUtils.concat(col, " = INSERT(RPAD(IFNULL(", col, ",''), ",
                sqlPos, ", '0'), ", sqlPos, ", 1, '", ENCODE[this.ordinal()], "')");
    }

    /** ENCODE 字符串常量, 供 SQL increment 使用 */
    String ENCODE_STR = new String(ENCODE);

    /**
     * 生成 SQL SET 原子增量片段: 对指定位置的状态值加 delta.
     * <p>
     * 用于状态流转: PENDING(0) +1 → PARTIAL(1) +1 → COMPLETED(2)
     * <p>
     * 原理: 在 ENCODE 表中查当前字符的位置, 加 delta, 取新字符写回.
     * DB 行锁保证原子性, 多节点并发安全.
     * <p>
     * 输出示例 (delta=1): {@code state = INSERT(state, 1, 1, SUBSTRING('0123...Z', LOCATE(SUBSTRING(state, 1, 1), '0123...Z') + 1, 1))}
     * <p>
     * 用法: {@code wrapper.setSql(OrderStatus.PENDING.increment(1))}
     *
     * @param delta 状态增量 (如 PENDING→PARTIAL 传 1, PENDING→COMPLETED 传 2)
     */
    default String increment(int delta) {
        Class<?> clazz = ((Enum<?>) this).getDeclaringClass();
        Integer pos = POS.get(clazz);
        if (pos == null) {
            throw new IllegalStateException(clazz.getSimpleName() + " not registered");
        }
        int sqlPos = pos + 1;
        String col = this.column();
        // SUBSTRING(ENCODE, LOCATE(当前字符, ENCODE) + delta, 1) → 新字符
        return StringUtils.concat(col, " = INSERT(", col, ", ", sqlPos, ", 1, SUBSTRING('",
                ENCODE_STR, "', LOCATE(SUBSTRING(", col, ", ", sqlPos, ", 1), '",
                ENCODE_STR, "') + ", delta, ", 1))");
    }

    /**
     * 每个字符位可表示的最大状态数 (62)
     */
    static int maxStates() {
        return ENCODE.length;
    }

}
