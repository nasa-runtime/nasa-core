package io.github.nasaruntime.core.base;

import io.github.nasaruntime.core.utils.StringUtils;

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

    /**
     * 业务作用：构造实例。字段取默认值。
     *
     * 参数说明: 无。
     * 返回: 构造完成后可直接使用的实例。
     */
    private static int[] initDecode() {
        int[] d = new int[128];
        Arrays.fill(d, -1);
        for (int i = 0; i < ENCODE.length; i++) {
            d[ENCODE[i]] = i;
        }
        return d;
    }

    /**
     * 业务作用：给出该状态占用的字符位序号，是编解码与持久化的唯一依据。序号一经发布不可调整，否则历史数据的状态位会整体错位。
     *
     * 参数说明: 无。
     * 返回: 位序号，从 0 起。
     */
    int ordinal();

    /**
     * 业务作用：给出该状态在数据库中所属的列名。
     *
     * 参数说明: 无。
     * 返回: 列名。
     */
    default String column() {
        return "state";
    }

    // ===== 注册 =====

    /**
     * 业务作用：注册枚举类到字符串中的指定位置 (项目启动时调用, 全局一次).
     * 注册后该枚举类与字符串中的某一位绑定, 该位的字符对应枚举的 ordinal.
     *
     * @param enumClass 枚举类 (枚举值个数不超过 62)
     * @param position 字符串中的位置 (0-indexed)
     * 返回: 无返回值。
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
     * 业务作用：从状态字符串中读取指定枚举类对应位置的枚举值.
     * 字符串为 null、长度不足或字符无法解析时返回枚举的第一个值 (ordinal 0).
     *
     * @param state 状态字符串
     * @param enumClass 枚举类 (必须已 registry)
     * 返回: 该位置对应的枚举值
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
     * 业务作用：将枚举值写入状态字符串的对应位置, 返回新字符串.
     * 字符串为 null 或长度不足时自动用 '0' 补位.
     *
     * @param state 原状态字符串
     * @param value 要写入的枚举值
     * 返回: 更新后的状态字符串
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
     * 业务作用：判断状态串中该位是否为指定状态值。
     *
     * @param state 状态串
     * @param value 位集合当前值
     * 返回: 命中返回 true。
     */
    static <E extends Enum<E>> boolean is(String state, E value) {
        return value == get(state, (Class<E>) value.getDeclaringClass());
    }

    // ===== SQL 片段生成 (default 方法, 语义: "这个枚举值的 SQL 条件/赋值") =====

    /**
     * 业务作用：拼出按该状态过滤的 SQL 条件片段，用子串定位在数据库侧完成筛选。
     *
     * 参数说明: 无。
     * 返回: SQL 条件片段。
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
     * 业务作用：拼出设置该状态位的 SQL 赋值片段，只改动对应位而不重写整串，避免并发覆盖。
     *
     * 参数说明: 无。
     * 返回: SQL 赋值片段。
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
     * 业务作用：拼出对该状态位做数值增减的 SQL 片段，用于把计数直接压在状态串里。
     *
     * @param delta 增量
     * 返回: SQL 赋值片段。
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
     * 业务作用：给出单个状态串能容纳的状态位上限。
     *
     * 参数说明: 无。
     * 返回: 状态位数量上限。
     */
    static int maxStates() {
        return ENCODE.length;
    }

}
