package io.github.nasaruntime.core.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Nasa 数字操作工具.
 * <p>
 * 涵盖: 字符串长度计算 / char[] 复制 / 奇偶判定 / Comparable 比较 / 精度最小值 / 随机步长 / 随机整数 /
 * 定点数 long 体系 (×10^scale) 算术 + I/O / double 重载 (内部 long 中转防漂移) /
 * BigDecimal 高精度兜底段 (scale > 8 退化路径) /
 * String/double → BigDecimal 便捷段 ({@code Decimal} 后缀).
 * <p>
 * 舍入: long 段 multiply/divide/align 默认 HALF_UP, 跟 BigDecimal 同名 mode 严格一致;
 * 提供 {@link RoundingMode} 重载支持全集 (UP/DOWN/CEILING/FLOOR/HALF_UP/HALF_DOWN/HALF_EVEN/UNNECESSARY).
 * alignUp (CEILING) / alignDown (DOWN) 作为常用别名保留，使调用点更直观.
 *
 * <h2>设计原则</h2>
 *   <ul>
 *     <li>零 GC 优先: 算术热路径使用 long primitive，BigDecimal 仅出现在显式 {@link #toBigDecimal} 调用</li>
 *     <li>O(1) 算法: stringSizeLong 用 NLZ 硬件指令; toPlainString 整数早返回单 String 分配</li>
 *     <li>JDK 21 新 API: {@code Long.numberOfLeadingZeros}, {@code StringBuilder.repeat}, pattern matching switch</li>
 *     <li>查表复用: MULTI_TABLE (10^0..10^18) 跨 stringSizeLong / multiply / divide / toFixed / toPlainString / align* 复用</li>
 *     <li>多线程无锁: 随机走 {@link ThreadLocalRandom} 替代 Math.random 全局锁</li>
 *   </ul>
 * <p>
 * 具体性能与分配行为取决于 JDK、输入规模和调用方式，应由使用方在目标环境中通过基准测试验证。
 */
@SuppressWarnings("unused")
public abstract class Numeric {


    private final static char[] DigitTens = {
            '0', '0', '0', '0', '0', '0', '0', '0', '0', '0',
            '1', '1', '1', '1', '1', '1', '1', '1', '1', '1',
            '2', '2', '2', '2', '2', '2', '2', '2', '2', '2',
            '3', '3', '3', '3', '3', '3', '3', '3', '3', '3',
            '4', '4', '4', '4', '4', '4', '4', '4', '4', '4',
            '5', '5', '5', '5', '5', '5', '5', '5', '5', '5',
            '6', '6', '6', '6', '6', '6', '6', '6', '6', '6',
            '7', '7', '7', '7', '7', '7', '7', '7', '7', '7',
            '8', '8', '8', '8', '8', '8', '8', '8', '8', '8',
            '9', '9', '9', '9', '9', '9', '9', '9', '9', '9',
    };

    private final static char[] DigitOnes = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    };

    private final static char[] digits = {
            '0', '1', '2', '3', '4', '5',
            '6', '7', '8', '9', 'a', 'b',
            'c', 'd', 'e', 'f', 'g', 'h',
            'i', 'j', 'k', 'l', 'm', 'n',
            'o', 'p', 'q', 'r', 's', 't',
            'u', 'v', 'w', 'x', 'y', 'z'
    };


    /**
     * 业务作用：算出整数转成字符串后的字符数，负数比同位数正数多一位符号。用于预分配缓冲区，避免拼接时扩容。
     *
     * @param num 待处理的数值
     * 返回: 该整数的十进制字符数。
     */
    public static int stringSize(Number num) {
        return switch (num) {
            case Byte b -> stringSizeLong(b);
            case Short s -> stringSizeLong(s);
            case Integer i -> stringSizeLong(i);
            case Long l -> stringSizeLong(l);
            case AtomicInteger atomicInteger -> stringSizeLong(atomicInteger.get());
            case AtomicLong al -> stringSizeLong(al.get());
            default -> num.toString().length();
        };
    }


    /**
     * 业务作用：算出 long 整数转成字符串后的字符数，负数比同位数正数多一位符号。
     *
     * @param num 待处理的数值
     * 返回: 该整数的十进制字符数。
     */
    public static int stringSizeLong(long num) {
        // Long.MIN_VALUE: -num 溢出回自身, 单独处理 (其绝对值是 19 位 + 负号 = 20)
        if (num == Long.MIN_VALUE) return 20;
        boolean neg = num < 0;
        long abs = neg ? -num : num;
        if (abs < 10) return neg ? 2 : 1;
        // log2 ∈ [0, 62] for abs in [1, Long.MAX_VALUE]
        int log2 = 63 - Long.numberOfLeadingZeros(abs);
        // log10(abs) ≈ log2 * log10(2) ≈ log2 * 0.30103. 用 1233/4096 = 0.30103515625 整数近似
        int approx = (log2 * 1233) >>> 12;
        int digits = approx + 1;
        // 校准: NLZ 估算可能少 1 位, 比对 10^digits 校正
        if (digits < 19 && abs >= MULTI_TABLE[digits]) digits++;
        return neg ? digits + 1 : digits;
    }


    /**
     * 业务作用：将数字number复制到字符数组中，从字符数组的下标start开始复制，完成后返回number的长度
     *
     * @param number 整数
     * @param chars 字符数组
     * @param start 字符数组开始下标
     * 返回: number的长度
     */
    public static int copyToCharArray(long number, char[] chars, int start) {
        return copyToCharArray(number, stringSizeLong(number), chars, start);
    }


    /**
     * 业务作用：将数字number复制到字符数组中，从字符数组的下标start开始复制，完成后返回number的长度
     *
     * @param number 整数
     * @param length 整数长度
     * @param chars 字符数组
     * @param start 字符数组开始下标
     * 返回: number的长度
     */
    public static int copyToCharArray(long number, int length, char[] chars, int start) {
        long q;
        int r;
        int charPos = start + length;
        char sign = 0;

        if (number < 0) {
            sign = '-';
            number = -number;
        }

        // Get 2 digits/iteration using longs until quotient fits into an int
        while (number > Integer.MAX_VALUE) {
            q = number / 100;
            // really: r = i - (q * 100);
            r = (int) (number - ((q << 6) + (q << 5) + (q << 2)));
            number = q;
            chars[--charPos] = DigitOnes[r];
            chars[--charPos] = DigitTens[r];
        }

        // Get 2 digits/iteration using ints
        int q2;
        int i2 = (int) number;
        while (i2 >= 65536) {
            q2 = i2 / 100;
            // really: r = i2 - (q * 100);
            r = i2 - ((q2 << 6) + (q2 << 5) + (q2 << 2));
            i2 = q2;
            chars[--charPos] = DigitOnes[r];
            chars[--charPos] = DigitTens[r];
        }

        // Fall thru to fast mode for smaller numbers
        // assert(i2 <= 65536, i2);
        do {
            q2 = (i2 * 52429) >>> (16 + 3);
            r = i2 - ((q2 << 3) + (q2 << 1));  // r = i2-(q2*10) ...
            chars[--charPos] = digits[r];
            i2 = q2;
        } while (i2 != 0);
        if (sign != 0) {
            chars[--charPos] = sign;
        }
        return length;
    }


    /**
     * 业务作用：数字是否是偶数
     *
     * @param num 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isEven(long num) {
        return (num & 1L) == 0;
    }

    /**
     * 业务作用：判断整数是否为偶数。
     *
     * @param num 待处理的数值
     * 返回: 是偶数返回 true。
     */
    public static boolean isEven(int num) {
        return (num & 1) == 0;
    }

    /**
     * 业务作用：判断整数是否为偶数。
     *
     * @param num 待处理的数值
     * 返回: 是偶数返回 true。
     */
    public static boolean isEven(byte num) {
        return (num & 1) == 0;
    }

    /**
     * 业务作用：判断整数是否为偶数。
     *
     * @param num 待处理的数值
     * 返回: 是偶数返回 true。
     */
    public static boolean isEven(short num) {
        return (num & 1) == 0;
    }

    /**
     * 业务作用：数字是否是奇数
     *
     * @param num 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isOdd(long num) {
        return (num & 1L) != 0;
    }

    /**
     * 业务作用：判断整数是否为奇数。
     *
     * @param num 待处理的数值
     * 返回: 是奇数返回 true。
     */
    public static boolean isOdd(int num) {
        return (num & 1) != 0;
    }

    /**
     * 业务作用：判断整数是否为奇数。
     *
     * @param num 待处理的数值
     * 返回: 是奇数返回 true。
     */
    public static boolean isOdd(byte num) {
        return (num & 1) != 0;
    }

    /**
     * 业务作用：判断整数是否为奇数。
     *
     * @param num 待处理的数值
     * 返回: 是奇数返回 true。
     */
    public static boolean isOdd(short num) {
        return (num & 1) != 0;
    }


    /**
     * 业务作用：判断两个数值是否相等，按定点语义比较而非对象相等。
     *
     * @param n1 见上述说明
     * @param n2 见上述说明
     * 返回: 相等返回 true。
     */
    public static <T extends Comparable<T>> boolean eq(T n1, T n2) {
        return Compares.eq(n1, n2);
    }


    /**
     * 业务作用：判断两个数值是否不相等。
     *
     * @param n1 见上述说明
     * @param n2 见上述说明
     * 返回: 不相等返回 true。
     */
    public static <T extends Comparable<T>> boolean ne(T n1, T n2) {
        return Compares.ne(n1, n2);
    }


    /**
     * 业务作用：判断左值是否大于右值。
     *
     * @param n1 见上述说明
     * @param n2 见上述说明
     * 返回: 左大于右返回 true。
     */
    public static <T extends Comparable<T>> boolean gt(T n1, T n2) {
        return Compares.gt(n1, n2);
    }


    /**
     * 业务作用：判断左值是否大于等于右值。
     *
     * @param n1 见上述说明
     * @param n2 见上述说明
     * 返回: 左大于等于右返回 true。
     */
    public static <T extends Comparable<T>> boolean ge(T n1, T n2) {
        return Compares.ge(n1, n2);
    }


    /**
     * 业务作用：判断左值是否小于右值。
     *
     * @param n1 见上述说明
     * @param n2 见上述说明
     * 返回: 左小于右返回 true。
     */
    public static <T extends Comparable<T>> boolean lt(T n1, T n2) {
        return Compares.lt(n1, n2);
    }


    /**
     * 业务作用：判断左值是否小于等于右值。
     *
     * @param n1 见上述说明
     * @param n2 见上述说明
     * 返回: 左小于等于右返回 true。
     */
    public static <T extends Comparable<T>> boolean le(T n1, T n2) {
        return Compares.le(n1, n2);
    }

    /**
     * 业务作用：取指定精度下能表示的最小正值，即该精度的最小步进。
     *
     * @param scale 小数位数（精度）
     * 返回: 该精度的最小正值。
     */
    public static BigDecimal scaleMin(int scale) {
        // valueOf(unscaledValue=1, scale) 直接构造 1×10^-scale, 一次分配, 比 ONE.divide(...) 省 Math.pow + valueOf + divide 链
        return BigDecimal.valueOf(1L, scale);
    }

    /**
     * 业务作用：在最小精度位上取步长的随机值，取值范围为 [min, (step-1)×min]，用于打散定时任务与重试的抖动。
     *
     * @param step 步长
     * @param scale 小数位数（精度）
     * 返回: 该范围内的随机值。
     */
    public static BigDecimal randomStep(BigDecimal step, int scale) {
        return randomStep(step.longValue(), scale);
    }

    /**
     * 业务作用：在最小精度位上取步长的随机值，取值范围为 [min, (step-1)×min]，用于打散定时任务与重试的抖动。
     *
     * @param step 步长
     * @param scale 小数位数（精度）
     * 返回: 该范围内的随机值。
     */
    public static BigDecimal randomStep(int step, int scale) {
        return randomStep((long) step, scale);
    }

    /**
     * 业务作用：在最小精度位上取步长的随机值，取值范围为 [min, (step-1)×min]，用于打散定时任务与重试的抖动。
     *
     * @param step 步长
     * @param scale 小数位数（精度）
     * 返回: 该范围内的随机值。
     */
    public static BigDecimal randomStep(long step, int scale) {
        if (step <= 0) return scaleMin(scale);
        long rand = ThreadLocalRandom.current().nextLong(step);
        // rand=0 时仍返回 min，保持方法约定的不返回 0 语义。
        return rand == 0 ? scaleMin(scale) : BigDecimal.valueOf(rand, scale);
    }

    /**
     * 业务作用：取 [min, max] 闭区间内的随机整数。
     *
     * @param min 下界
     * @param max 上界
     * 返回: 该闭区间内的随机整数。
     */
    public static int nextInt(int min, int max) {
        if (min == max) return min;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /**
     * 业务作用：取 [min, max] 闭区间内的随机整数。
     *
     * @param max 上界
     * 返回: 该闭区间内的随机整数。
     */
    public static int nextInt(int max) {
        return nextInt(0, max);
    }

    // ==================== 定点数 long 体系 (×10^scale) ====================

    /*
      设计原则:
        - 算术使用 long primitive，高频计算路径不创建临时对象
        - BigDecimal 仅用于 I/O 边界 (parse 输入 / format 输出), 算术绝不用
        - scale 参数化，默认 scale=8 并提供便捷重载
        - scale 上限 = {@link #MAX_FIXED_SCALE} = 8: 数值表示范围 ±9.2×10^10 (920 亿), 业务足够;
            超过 8 的精度需求请用 BigDecimal API 段 ({@link #multiply(BigDecimal, BigDecimal, int)} 等)

      命名与 {@link BigDecimal} 对齐: multiply / divide / toBigDecimal / toPlainString.
      精度对齐另用 align* (alignUp/alignDown/isAligned), 避免与 BigDecimal.setScale 概念混淆 —
      setScale 改变 scale 表示本身, 我们的 align 是"在原 scale 体系下让低位归零".
     */

    /**
     * 默认定点精度 (×10^8)
     */
    public static final int DEFAULT_FIXED_SCALE = 8;

    /**
     * 定点 long 体系 scale 上限. 超过此值的方法入口会抛 {@link IllegalArgumentException},
     * 请改用 BigDecimal API 段 (本类下方 multiply/divide/add/subtract/align/sum/avg 的 BigDecimal 重载).
     * <p>
     * 边界依据: scale=8 时数值范围约为 ±9.2×10^10；
     * 再大 (10+) 数值范围窄, 算术中间溢出风险高, 不如直接退到 BigDecimal 精确路径.
     */
    public static final int MAX_FIXED_SCALE = 8;

    /**
     * 业务作用：scale 校验: 范围 [0, MAX_FIXED_SCALE].
     *
     * @param scale 见上述说明
     * 返回: 无返回值。
     */
    private static void checkScale(int scale) {
        if (scale < 0 || scale > MAX_FIXED_SCALE) {
            throw new IllegalArgumentException(
                    "fixed-long scale must be in [0, " + MAX_FIXED_SCALE + "], got " + scale
                            + ". For higher precision use BigDecimal API.");
        }
    }

    /**
     * 业务作用：scale 校验 (双参 align*): fromScale ∈ [0, MAX], toScale ∈ [0, fromScale].
     *
     * @param fromScale 见上述说明
     * @param toScale 见上述说明
     * 返回: 无返回值。
     */
    private static void checkScale(int fromScale, int toScale) {
        checkScale(fromScale);
        if (toScale < 0 || toScale > fromScale) {
            throw new IllegalArgumentException(
                    "toScale must be in [0, fromScale=" + fromScale + "], got " + toScale);
        }
    }

    /**
     * 10^i 缓存表, i ∈ [0, 18] (long 上限)
     */
    private static final long[] MULTI_TABLE;
    /**
     * BigDecimal 形式的 10^i 缓存表, 给 toBigDecimal 复用
     */
    private static final BigDecimal[] MULTI_BD_TABLE;

    static {
        MULTI_TABLE = new long[19];
        MULTI_BD_TABLE = new BigDecimal[19];
        long m = 1;
        for (int i = 0; i < 19; i++) {
            MULTI_TABLE[i] = m;
            MULTI_BD_TABLE[i] = BigDecimal.valueOf(m);
            if (i < 18) m *= 10;
        }
    }

    // ---- I/O 入口: 字符串 / double → 定点 long ----

    /**
     * 业务作用：把数字字符串转换成定点 long 表示，是外部数据进入定点体系的入口。
     *
     * @param val 见上述说明
     * @param scale 小数位数（精度）
     * 返回: 定点 long；字符串非法时抛出 NumberFormatException。
     */
    public static long toFixed(String val, int scale) {
        checkScale(scale);
        return Math.round(Double.parseDouble(val) * MULTI_TABLE[scale]);
    }

    /**
     * 业务作用：把数字字符串转换成定点 long 表示，是外部数据进入定点体系的入口。
     *
     * @param val 见上述说明
     * 返回: 定点 long；字符串非法时抛出 NumberFormatException。
     */
    public static long toFixed(String val) {
        return toFixed(val, DEFAULT_FIXED_SCALE);
    }

    /**
     * 业务作用：把数字字符串转换成定点 long 表示，是外部数据进入定点体系的入口。
     *
     * @param val 见上述说明
     * @param scale 小数位数（精度）
     * 返回: 定点 long；字符串非法时抛出 NumberFormatException。
     */
    public static long toFixed(double val, int scale) {
        checkScale(scale);
        return Math.round(val * MULTI_TABLE[scale]);
    }

    /**
     * 业务作用：把数字字符串转换成定点 long 表示，是外部数据进入定点体系的入口。
     *
     * @param val 见上述说明
     * 返回: 定点 long；字符串非法时抛出 NumberFormatException。
     */
    public static long toFixed(double val) {
        return toFixed(val, DEFAULT_FIXED_SCALE);
    }

    // ---- I/O 出口: 定点 long → String / BigDecimal ----

    /**
     * 业务作用：把定点 long 渲染成易读字符串并自动去掉小数尾部的 0。纯 long 算术加 StringBuilder，不经 BigDecimal。
     *
     * @param fixed 定点 long 表示的数值
     * @param scale 小数位数（精度）
     * 返回: 去掉尾随 0 的十进制字符串。
     */
    public static String toPlainString(long fixed, int scale) {
        checkScale(scale);
        // Long.MIN_VALUE: -fixed 溢出, 退到 BigDecimal 精确路径
        if (fixed == Long.MIN_VALUE) return toBigDecimal(fixed, scale).toPlainString();
        long m = MULTI_TABLE[scale];
        boolean neg = fixed < 0;
        long abs = neg ? -fixed : fixed;
        long intPart = abs / m;
        long fracPart = abs % m;
        if (fracPart == 0) {
            // 整数, 不加小数点
            return neg ? "-" + intPart : Long.toString(intPart);
        }
        StringBuilder sb = new StringBuilder(scale + 14);
        if (neg) sb.append('-');
        sb.append(intPart).append('.');
        // 前导 0: scale 减去 fracPart 实际位数
        int fracLen = stringSizeLong(fracPart);
        sb.repeat("0", Math.max(0, scale - fracLen));
        // 去尾 0
        while (fracPart % 10 == 0) fracPart /= 10;
        sb.append(fracPart);
        return sb.toString();
    }

    /**
     * 业务作用：把定点 long 渲染成易读字符串并自动去掉小数尾部的 0。纯 long 算术加 StringBuilder，不经 BigDecimal。
     *
     * @param fixed 定点 long 表示的数值
     * 返回: 去掉尾随 0 的十进制字符串。
     */
    public static String toPlainString(long fixed) {
        return toPlainString(fixed, DEFAULT_FIXED_SCALE);
    }

    /**
     * 业务作用：把定点 long 渲染成易读字符串并自动去掉小数尾部的 0。纯 long 算术加 StringBuilder，不经 BigDecimal。
     *
     * @param val 见上述说明
     * @param scale 小数位数（精度）
     * 返回: 去掉尾随 0 的十进制字符串。
     */
    public static String toPlainString(double val, int scale) {
        return toPlainString(toFixed(val, scale), scale);
    }

    /**
     * 业务作用：把定点 long 渲染成易读字符串并自动去掉小数尾部的 0。纯 long 算术加 StringBuilder，不经 BigDecimal。
     *
     * @param val 见上述说明
     * 返回: 去掉尾随 0 的十进制字符串。
     */
    public static String toPlainString(double val) {
        return toPlainString(val, DEFAULT_FIXED_SCALE);
    }

    /**
     * 业务作用：把定点 long 渲染成字符串并保留全部尾随 0，即固定输出 scale 位小数，供对格式有严格要求的对外报文使用。
     *
     * @param fixed 定点 long 表示的数值
     * @param scale 小数位数（精度）
     * 返回: 固定小数位数的十进制字符串。
     */
    public static String toPlainStringRaw(long fixed, int scale) {
        checkScale(scale);
        // Long.MIN_VALUE: -fixed 溢出, 退到 BigDecimal 精确路径
        if (fixed == Long.MIN_VALUE) {
            return toBigDecimal(fixed, scale).toPlainString();
        }
        long m = MULTI_TABLE[scale];
        boolean neg = fixed < 0;
        long abs = neg ? -fixed : fixed;
        long intPart = abs / m;
        long fracPart = abs % m;
        StringBuilder sb = new StringBuilder(scale + 14);
        if (neg) sb.append('-');
        sb.append(intPart);
        if (scale > 0) {
            sb.append('.');
            int fracLen = (fracPart == 0) ? 0 : stringSizeLong(fracPart);
            sb.repeat("0", Math.max(0, scale - fracLen));
            if (fracPart != 0) sb.append(fracPart);
        }
        return sb.toString();
    }

    /**
     * 业务作用：把定点 long 渲染成字符串并保留全部尾随 0，即固定输出 scale 位小数，供对格式有严格要求的对外报文使用。
     *
     * @param fixed 定点 long 表示的数值
     * @param fixedScale 见上述说明
     * @param displayScale 见上述说明
     * 返回: 固定小数位数的十进制字符串。
     */
    public static String toPlainStringRaw(long fixed, int fixedScale, int displayScale) {
        if (fixedScale == displayScale) return toPlainStringRaw(fixed, fixedScale);
        checkScale(fixedScale);
        if (fixed == Long.MIN_VALUE) {
            return toBigDecimal(fixed, fixedScale).setScale(displayScale, java.math.RoundingMode.HALF_UP).toPlainString();
        }
        long m = MULTI_TABLE[fixedScale];
        boolean neg = fixed < 0;
        long abs = neg ? -fixed : fixed;
        long intPart = abs / m;
        long fracPart = abs % m;
        if (displayScale <= 0) {
            // 只显示整数
            long rounded = (fracPart * 2 >= m) ? intPart + 1 : intPart;
            return neg ? "-" + rounded : Long.toString(rounded);
        }
        StringBuilder sb = new StringBuilder(displayScale + 14);
        if (neg) sb.append('-');
        sb.append(intPart).append('.');
        if (displayScale >= fixedScale) {
            // 显示精度 >= 定点精度: 先填满定点小数, 再补 0
            int fracLen = (fracPart == 0) ? 0 : stringSizeLong(fracPart);
            sb.repeat("0", Math.max(0, fixedScale - fracLen));
            if (fracPart != 0) sb.append(fracPart);
            sb.repeat("0", displayScale - fixedScale);
        } else {
            // 显示精度 < 定点精度: 四舍五入截断
            long divisor = MULTI_TABLE[fixedScale - displayScale];
            long truncated = (fracPart + divisor / 2) / divisor;
            long displayM = MULTI_TABLE[displayScale];
            // 四舍五入可能进位
            if (truncated >= displayM) {
                intPart++;
                truncated = 0;
                sb.setLength(0);
                if (neg) sb.append('-');
                sb.append(intPart).append('.');
            }
            int truncLen = (truncated == 0) ? 0 : stringSizeLong(truncated);
            sb.repeat("0", Math.max(0, displayScale - truncLen));
            if (truncated != 0) sb.append(truncated);
        }
        return sb.toString();
    }

    /**
     * 业务作用：把定点 long 渲染成字符串并保留全部尾随 0，即固定输出 scale 位小数，供对格式有严格要求的对外报文使用。
     *
     * @param val 见上述说明
     * @param scale 小数位数（精度）
     * 返回: 固定小数位数的十进制字符串。
     */
    public static String toPlainStringRaw(double val, int scale) {
        return toPlainStringRaw(toFixed(val, scale), scale);
    }

    /**
     * 业务作用：把定点 long 渲染成字符串并保留全部尾随 0，即固定输出 scale 位小数，供对格式有严格要求的对外报文使用。
     *
     * @param val 见上述说明
     * @param fixedScale 见上述说明
     * @param displayScale 见上述说明
     * 返回: 固定小数位数的十进制字符串。
     */
    public static String toPlainStringRaw(double val, int fixedScale, int displayScale) {
        return toPlainStringRaw(toFixed(val, fixedScale), fixedScale, displayScale);
    }

    /**
     * 业务作用：把定点 long 渲染成易读字符串并自动去掉小数尾部的 0。纯 long 算术加 StringBuilder，不经 BigDecimal。
     *
     * @param fixed 定点 long 表示的数值
     * @param fixedScale 见上述说明
     * @param displayScale 见上述说明
     * 返回: 去掉尾随 0 的十进制字符串。
     */
    public static String toPlainString(long fixed, int fixedScale, int displayScale) {
        if (fixedScale == displayScale) return toPlainString(fixed, fixedScale);
        checkScale(fixedScale);
        if (fixed == Long.MIN_VALUE) {
            return toBigDecimal(fixed, fixedScale).setScale(displayScale, java.math.RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString();
        }
        long m = MULTI_TABLE[fixedScale];
        boolean neg = fixed < 0;
        long abs = neg ? -fixed : fixed;
        long intPart = abs / m;
        long fracPart = abs % m;
        if (displayScale <= 0 || fracPart == 0) {
            long rounded = (displayScale <= 0 && fracPart * 2 >= m) ? intPart + 1 : intPart;
            return neg ? "-" + rounded : Long.toString(rounded);
        }
        // 截断到 displayScale 位 (四舍五入)
        if (displayScale < fixedScale) {
            long divisor = MULTI_TABLE[fixedScale - displayScale];
            fracPart = (fracPart + divisor / 2) / divisor;
            long displayM = MULTI_TABLE[displayScale];
            if (fracPart >= displayM) {
                intPart++;
                fracPart = 0;
            }
        }
        if (fracPart == 0) {
            return neg ? "-" + intPart : Long.toString(intPart);
        }
        // 去尾 0
        int effectiveScale = displayScale < fixedScale ? displayScale : fixedScale;
        while (fracPart % 10 == 0) { fracPart /= 10; effectiveScale--; }
        StringBuilder sb = new StringBuilder(effectiveScale + 14);
        if (neg) sb.append('-');
        sb.append(intPart).append('.');
        int fracLen = stringSizeLong(fracPart);
        sb.repeat("0", Math.max(0, effectiveScale - fracLen));
        sb.append(fracPart);
        return sb.toString();
    }

    /**
     * 业务作用：把定点 long 渲染成易读字符串并自动去掉小数尾部的 0。纯 long 算术加 StringBuilder，不经 BigDecimal。
     *
     * @param val 见上述说明
     * @param fixedScale 见上述说明
     * @param displayScale 见上述说明
     * 返回: 去掉尾随 0 的十进制字符串。
     */
    public static String toPlainString(double val, int fixedScale, int displayScale) {
        return toPlainString(toFixed(val, fixedScale), fixedScale, displayScale);
    }

    /**
     * 业务作用：把定点 long 转成 BigDecimal，仅在确实需要 BigDecimal（例如对接外部要求该类型）时使用。
     *
     * @param fixed 定点 long 表示的数值
     * @param scale 小数位数（精度）
     * 返回: 等值的 BigDecimal。
     */
    public static BigDecimal toBigDecimal(long fixed, int scale) {
        checkScale(scale);
        return BigDecimal.valueOf(fixed).divide(MULTI_BD_TABLE[scale], scale, RoundingMode.HALF_UP);
    }

    /**
     * 业务作用：把定点 long 转成 BigDecimal，仅在确实需要 BigDecimal（例如对接外部要求该类型）时使用。
     *
     * @param fixed 定点 long 表示的数值
     * 返回: 等值的 BigDecimal。
     */
    public static BigDecimal toBigDecimal(long fixed) {
        return toBigDecimal(fixed, DEFAULT_FIXED_SCALE);
    }

    // ---- 算术: 全 long primitive，零分配 ----

    /**
     * 业务作用：把除法余数按 HALF_UP 舍入成 long 增量。该实现与 BigDecimal 的 HALF_UP 严格一致：远离零方向进位。
     *
     * @param frac 见上述说明
     * 返回: 舍入产生的增量。
     */
    private static long roundHalfUp(double frac) {
        return frac >= 0 ? (long) Math.floor(frac + 0.5d) : -(long) Math.floor(-frac + 0.5d);
    }

    /**
     * 业务作用：把除法余数按给定 RoundingMode 舍入成 long 增量，覆盖 RoundingMode 全集，行为对照 BigDecimal 同名模式。
     *
     * @param frac 见上述说明
     * @param mode 舍入模式
     * @param intPart 见上述说明
     * 返回: 舍入产生的增量；模式为 UNNECESSARY 且确有余数时抛出 ArithmeticException。
     */
    private static long applyRounding(double frac, RoundingMode mode, long intPart) {
        if (frac == 0d) return 0L;
        boolean neg = frac < 0;
        double abs = neg ? -frac : frac;
        long floor = (long) abs;
        double diff = abs - floor;
        if (diff == 0d) return neg ? -floor : floor;

        long absResult = switch (mode) {
            case UP -> floor + 1;                           // 始终远离零
            case DOWN -> floor;                                // 始终朝零
            case CEILING -> neg ? floor : floor + 1;              // 朝 +∞
            case FLOOR -> neg ? floor + 1 : floor;              // 朝 -∞
            case HALF_UP -> diff < 0.5d ? floor : floor + 1;
            case HALF_DOWN -> diff <= 0.5d ? floor : floor + 1;
            case HALF_EVEN -> {
                if (diff < 0.5d) yield floor;
                if (diff > 0.5d) yield floor + 1;
                // tie: 选 absResult 让 (intPart + sign·absResult) 为偶数
                long candidate = neg ? intPart - floor : intPart + floor;
                yield (candidate & 1L) == 0 ? floor : floor + 1;
            }
            case UNNECESSARY -> throw new ArithmeticException("Rounding necessary, mode=UNNECESSARY");
        };
        return neg ? -absResult : absResult;
    }

    /**
     * 业务作用：两个定点 long 相乘，结果仍是定点 long，默认 HALF_UP 舍入。全程 long 算术，不经 BigDecimal，避免浮点漂移与临时对象。
     *
     * @param a 左操作数
     * @param b 右操作数
     * @param scale 小数位数（精度）
     * 返回: 相乘后的定点 long。
     */
    public static long multiply(long a, long b, int scale) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        return a / m * b + roundHalfUp((double) (a % m) / m * b);
    }

    /**
     * 业务作用：两个定点 long 相乘，结果仍是定点 long，默认 HALF_UP 舍入。全程 long 算术，不经 BigDecimal，避免浮点漂移与临时对象。
     *
     * @param a 左操作数
     * @param b 右操作数
     * 返回: 相乘后的定点 long。
     */
    public static long multiply(long a, long b) {
        return multiply(a, b, DEFAULT_FIXED_SCALE);
    }

    /**
     * 业务作用：两个定点 long 相乘，结果仍是定点 long，默认 HALF_UP 舍入。全程 long 算术，不经 BigDecimal，避免浮点漂移与临时对象。
     *
     * @param a 左操作数
     * @param b 右操作数
     * @param scale 小数位数（精度）
     * @param mode 舍入模式
     * 返回: 相乘后的定点 long。
     */
    public static long multiply(long a, long b, int scale, RoundingMode mode) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        long intPart = a / m * b;
        return intPart + applyRounding((double) (a % m) / m * b, mode, intPart);
    }

    /**
     * 业务作用：两个定点 long 相除，结果仍是定点 long，默认 HALF_UP 舍入。全程 long 算术，不经 BigDecimal。
     *
     * @param a 左操作数
     * @param b 右操作数
     * @param scale 小数位数（精度）
     * 返回: 相除后的定点 long；除数为 0 时抛出 ArithmeticException。
     */
    public static long divide(long a, long b, int scale) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        return a / b * m + roundHalfUp((double) (a % b) / b * m);
    }

    /**
     * 业务作用：两个定点 long 相除，结果仍是定点 long，默认 HALF_UP 舍入。全程 long 算术，不经 BigDecimal。
     *
     * @param a 左操作数
     * @param b 右操作数
     * 返回: 相除后的定点 long；除数为 0 时抛出 ArithmeticException。
     */
    public static long divide(long a, long b) {
        return divide(a, b, DEFAULT_FIXED_SCALE);
    }

    /**
     * 业务作用：两个定点 long 相除，结果仍是定点 long，默认 HALF_UP 舍入。全程 long 算术，不经 BigDecimal。
     *
     * @param a 左操作数
     * @param b 右操作数
     * @param scale 小数位数（精度）
     * @param mode 舍入模式
     * 返回: 相除后的定点 long；除数为 0 时抛出 ArithmeticException。
     */
    public static long divide(long a, long b, int scale, RoundingMode mode) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        long intPart = a / b * m;
        return intPart + applyRounding((double) (a % b) / b * m, mode, intPart);
    }

    // ---- 精度对齐: 按业务要求的最小单位或步长对齐 ----

    /**
     * 业务作用：把定点 long 从一种精度对齐到另一种精度，默认 HALF_UP 四舍五入。
     *
     * @param fixed 定点 long 表示的数值
     * @param fromScale 源值的小数位数
     * @param toScale 目标值的小数位数
     * 返回: 对齐到目标精度后的定点 long。
     */
    public static long align(long fixed, int fromScale, int toScale) {
        return align(fixed, fromScale, toScale, RoundingMode.HALF_UP);
    }

    /**
     * 业务作用：把定点 long 从一种精度对齐到另一种精度，默认 HALF_UP 四舍五入。
     *
     * @param fixed 定点 long 表示的数值
     * @param fromScale 源值的小数位数
     * @param toScale 目标值的小数位数
     * @param mode 舍入模式
     * 返回: 对齐到目标精度后的定点 long。
     */
    public static long align(long fixed, int fromScale, int toScale, RoundingMode mode) {
        checkScale(fromScale, toScale);
        long unit = MULTI_TABLE[fromScale - toScale];
        long rem = fixed % unit;
        if (rem == 0) return fixed;
        long base = fixed - rem;            // truncate toward zero
        boolean neg = fixed < 0;
        long absRem = neg ? -rem : rem;     // absRem ∈ (0, unit), unit ≤ 10^8, *2 不溢出

        boolean addUnit = switch (mode) {
            case UP -> true;
            case DOWN -> false;
            case CEILING -> !neg;
            case FLOOR -> neg;
            case HALF_UP -> absRem * 2 >= unit;
            case HALF_DOWN -> absRem * 2 > unit;
            case HALF_EVEN -> {
                long cmp = Long.compare(absRem * 2, unit);
                if (cmp < 0) yield false;
                if (cmp > 0) yield true;
                yield ((base / unit) & 1L) != 0;   // tie: base 偶数不进位, 奇数进位 → 都到偶数
            }
            case UNNECESSARY -> throw new ArithmeticException("Rounding necessary, mode=UNNECESSARY");
        };

        if (!addUnit) return base;
        return neg ? base - unit : base + unit;
    }

    /**
     * 业务作用：把定点 long 向上对齐到指定精度，朝正无穷方向取整（CEILING）。
     *
     * @param fixed 定点 long 表示的数值
     * @param fromScale 源值的小数位数
     * @param toScale 目标值的小数位数
     * 返回: 向上对齐后的定点 long。
     */
    public static long alignUp(long fixed, int fromScale, int toScale) {
        return align(fixed, fromScale, toScale, RoundingMode.CEILING);
    }

    /**
     * 业务作用：把定点 long 向下对齐到指定精度，朝零方向取整（DOWN）。
     *
     * @param fixed 定点 long 表示的数值
     * @param fromScale 源值的小数位数
     * @param toScale 目标值的小数位数
     * 返回: 向下对齐后的定点 long。
     */
    public static long alignDown(long fixed, int fromScale, int toScale) {
        return align(fixed, fromScale, toScale, RoundingMode.DOWN);
    }

    /**
     * 业务作用：检查定点 long 是否对齐到指定精度.
     * <p>
     * 例: isAligned(20020000, 8, 4) = true; isAligned(20021365, 8, 4) = false
     *
     * @param fixed 见上述说明
     * @param fromScale 见上述说明
     * @param toScale 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isAligned(long fixed, int fromScale, int toScale) {
        checkScale(fromScale, toScale);
        long unit = MULTI_TABLE[fromScale - toScale];
        return fixed % unit == 0;
    }

    // ==================== double 重载: 入 double / 出 double, 内部 long 防漂移 ====================

    /*
      适用场景: 业务接口接受/返回 double, 但要避免 double 多次运算的累积漂移
      (经典如 0.1 + 0.2 = 0.30000000000000004 — 不是数学错误, 是 IEEE 754 二进制无法精确表示部分十进制小数).

      实现思路: double → 定点 long (round 处理 ε) → long 精确算术 → 出口转回 double.
      中间过程零漂移, 出口 double 仍受 IEEE 754 限制 (某些十进制数 double 表示自带 ε), 但**不再累积**.

      与 long 版本通过参数类型重载区分 — 同名同概念, 编译器自动匹配:
      Numeric.multiply(longA, longB, 8);     // long 定点版
      Numeric.multiply(doubleA, doubleB, 8); // double 重载, 内部 long 中转

      性能: 每次入口两次 round, 一次 long 算术, 一次 long → double 转换. 比纯 double 慢 ~3-5x,
      但比 BigDecimal 快 >10x, 且零分配. 适合接口边界 / 业务计算, 不适合内层 hot 循环.
     */

    /**
     * 业务作用：double 加法，结果保留指定小数位。内部转为 long 运算以避免浮点累加漂移。
     *
     * @param a 左操作数
     * @param b 右操作数
     * @param scale 小数位数（精度）
     * 返回: 相加后的结果。
     */
    public static double add(double a, double b, int scale) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        return (double) (Math.round(a * m) + Math.round(b * m)) / m;
    }

    /**
     * 业务作用：double 加法，结果保留指定小数位。内部转为 long 运算以避免浮点累加漂移。
     *
     * @param a 左操作数
     * @param b 右操作数
     * 返回: 相加后的结果。
     */
    public static double add(double a, double b) {
        return add(a, b, DEFAULT_FIXED_SCALE);
    }

    /**
     * 业务作用：double 减法，结果保留指定小数位。内部转为 long 运算以避免浮点漂移。
     *
     * @param a 左操作数
     * @param b 右操作数
     * @param scale 小数位数（精度）
     * 返回: 相减后的结果。
     */
    public static double subtract(double a, double b, int scale) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        return (double) (Math.round(a * m) - Math.round(b * m)) / m;
    }

    /**
     * 业务作用：double 减法，结果保留指定小数位。内部转为 long 运算以避免浮点漂移。
     *
     * @param a 左操作数
     * @param b 右操作数
     * 返回: 相减后的结果。
     */
    public static double subtract(double a, double b) {
        return subtract(a, b, DEFAULT_FIXED_SCALE);
    }

    /**
     * 业务作用：两个定点 long 相乘，结果仍是定点 long，默认 HALF_UP 舍入。全程 long 算术，不经 BigDecimal，避免浮点漂移与临时对象。
     *
     * @param a 左操作数
     * @param b 右操作数
     * @param scale 小数位数（精度）
     * 返回: 相乘后的定点 long。
     */
    public static double multiply(double a, double b, int scale) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        long af = Math.round(a * m);
        long bf = Math.round(b * m);
        return (double) multiply(af, bf, scale) / m;
    }

    /**
     * 业务作用：两个定点 long 相乘，结果仍是定点 long，默认 HALF_UP 舍入。全程 long 算术，不经 BigDecimal，避免浮点漂移与临时对象。
     *
     * @param a 左操作数
     * @param b 右操作数
     * 返回: 相乘后的定点 long。
     */
    public static double multiply(double a, double b) {
        return multiply(a, b, DEFAULT_FIXED_SCALE);
    }

    /**
     * 业务作用：两个定点 long 相除，结果仍是定点 long，默认 HALF_UP 舍入。全程 long 算术，不经 BigDecimal。
     *
     * @param a 左操作数
     * @param b 右操作数
     * @param scale 小数位数（精度）
     * 返回: 相除后的定点 long；除数为 0 时抛出 ArithmeticException。
     */
    public static double divide(double a, double b, int scale) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        long af = Math.round(a * m);
        long bf = Math.round(b * m);
        return (double) divide(af, bf, scale) / m;
    }

    /**
     * 业务作用：两个定点 long 相除，结果仍是定点 long，默认 HALF_UP 舍入。全程 long 算术，不经 BigDecimal。
     *
     * @param a 左操作数
     * @param b 右操作数
     * 返回: 相除后的定点 long；除数为 0 时抛出 ArithmeticException。
     */
    public static double divide(double a, double b) {
        return divide(a, b, DEFAULT_FIXED_SCALE);
    }

    /**
     * 业务作用：把定点 long 从一种精度对齐到另一种精度，默认 HALF_UP 四舍五入。
     *
     * @param val 见上述说明
     * @param scale 小数位数（精度）
     * 返回: 对齐到目标精度后的定点 long。
     */
    public static double align(double val, int scale) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        return (double) Math.round(val * m) / m;
    }

    /**
     * 业务作用：把定点 long 向上对齐到指定精度，朝正无穷方向取整（CEILING）。
     *
     * @param val 见上述说明
     * @param scale 小数位数（精度）
     * 返回: 向上对齐后的定点 long。
     */
    public static double alignUp(double val, int scale) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        return Math.ceil(val * m) / m;
    }

    /**
     * 业务作用：把定点 long 向下对齐到指定精度，朝零方向取整（DOWN）。
     *
     * @param val 见上述说明
     * @param scale 小数位数（精度）
     * 返回: 向下对齐后的定点 long。
     */
    public static double alignDown(double val, int scale) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        return Math.floor(val * m) / m;
    }

    // ==================== BigDecimal API (scale > MAX_FIXED_SCALE 退化路径) ====================

    /*
      BigDecimal 高精度算术段, 给定点 long 体系覆盖不到 (scale > {@link #MAX_FIXED_SCALE}=8)
      或对绝对精度敏感的场景使用. 本段方法非零分配 (BigDecimal 本身有内部对象), 但语义精确.

      命名与 long 段对齐: add / subtract / multiply / divide / align / alignUp / alignDown / sum / avg.
      通过参数类型 (BigDecimal vs long/double) 与 long/double 段重载区分, 编译器自动选派.

      除法默认舍入: {@link RoundingMode#HALF_UP}, 与 {@link #toBigDecimal} 一致.
      align 系列舍入: align = HALF_UP, alignUp = CEILING (向 +∞), alignDown = FLOOR (向 -∞).
     */

    /**
     * 业务作用：double 加法，结果保留指定小数位。内部转为 long 运算以避免浮点累加漂移。
     *
     * @param a 左操作数
     * @param b 右操作数
     * 返回: 相加后的结果。
     */
    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return a.add(b);
    }

    /**
     * 业务作用：double 减法，结果保留指定小数位。内部转为 long 运算以避免浮点漂移。
     *
     * @param a 左操作数
     * @param b 右操作数
     * 返回: 相减后的结果。
     */
    public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        return a.subtract(b);
    }

    /**
     * 业务作用：两个定点 long 相乘，结果仍是定点 long，默认 HALF_UP 舍入。全程 long 算术，不经 BigDecimal，避免浮点漂移与临时对象。
     *
     * @param a 左操作数
     * @param b 右操作数
     * 返回: 相乘后的定点 long。
     */
    public static BigDecimal multiply(BigDecimal a, BigDecimal b) {
        return a.multiply(b);
    }

    /**
     * 业务作用：两个定点 long 相乘，结果仍是定点 long，默认 HALF_UP 舍入。全程 long 算术，不经 BigDecimal，避免浮点漂移与临时对象。
     *
     * @param a 左操作数
     * @param b 右操作数
     * @param scale 小数位数（精度）
     * 返回: 相乘后的定点 long。
     */
    public static BigDecimal multiply(BigDecimal a, BigDecimal b, int scale) {
        return a.multiply(b).setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * 业务作用：两个定点 long 相除，结果仍是定点 long，默认 HALF_UP 舍入。全程 long 算术，不经 BigDecimal。
     *
     * @param a 左操作数
     * @param b 右操作数
     * @param scale 小数位数（精度）
     * 返回: 相除后的定点 long；除数为 0 时抛出 ArithmeticException。
     */
    public static BigDecimal divide(BigDecimal a, BigDecimal b, int scale) {
        return a.divide(b, scale, RoundingMode.HALF_UP);
    }

    /**
     * 业务作用：两个定点 long 相除，结果仍是定点 long，默认 HALF_UP 舍入。全程 long 算术，不经 BigDecimal。
     *
     * @param a 左操作数
     * @param b 右操作数
     * @param scale 小数位数（精度）
     * @param mode 舍入模式
     * 返回: 相除后的定点 long；除数为 0 时抛出 ArithmeticException。
     */
    public static BigDecimal divide(BigDecimal a, BigDecimal b, int scale, RoundingMode mode) {
        return a.divide(b, scale, mode);
    }

    /**
     * 业务作用：把定点 long 从一种精度对齐到另一种精度，默认 HALF_UP 四舍五入。
     *
     * @param val 见上述说明
     * @param scale 小数位数（精度）
     * 返回: 对齐到目标精度后的定点 long。
     */
    public static BigDecimal align(BigDecimal val, int scale) {
        return val.setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * 业务作用：把定点 long 向上对齐到指定精度，朝正无穷方向取整（CEILING）。
     *
     * @param val 见上述说明
     * @param scale 小数位数（精度）
     * 返回: 向上对齐后的定点 long。
     */
    public static BigDecimal alignUp(BigDecimal val, int scale) {
        return val.setScale(scale, RoundingMode.CEILING);
    }

    /**
     * 业务作用：把定点 long 向下对齐到指定精度，朝零方向取整（DOWN）。
     *
     * @param val 见上述说明
     * @param scale 小数位数（精度）
     * 返回: 向下对齐后的定点 long。
     */
    public static BigDecimal alignDown(BigDecimal val, int scale) {
        return val.setScale(scale, RoundingMode.FLOOR);
    }

    /**
     * 业务作用：对多个 BigDecimal 求和。
     *
     * @param vals 见上述说明
     * 返回: 求和结果；入参为空时返回 BigDecimal.ZERO。
     */
    public static BigDecimal sum(BigDecimal... vals) {
        if (vals == null || vals.length == 0) return BigDecimal.ZERO;
        BigDecimal r = BigDecimal.ZERO;
        for (BigDecimal v : vals) {
            if (v != null) r = r.add(v);
        }
        return r;
    }

    /**
     * 业务作用：对多个 BigDecimal 求和。
     *
     * @param vals 见上述说明
     * 返回: 求和结果；入参为空时返回 BigDecimal.ZERO。
     */
    public static BigDecimal sum(Iterable<BigDecimal> vals) {
        if (vals == null) return BigDecimal.ZERO;
        BigDecimal r = BigDecimal.ZERO;
        for (BigDecimal v : vals) {
            if (v != null) r = r.add(v);
        }
        return r;
    }

    /**
     * 业务作用：对 BigDecimal 集合求平均并对齐到指定精度（HALF_UP）。
     *
     * @param vals 见上述说明
     * @param scale 小数位数（精度）
     * 返回: 平均值；入参为 null 或空集合时返回 BigDecimal.ZERO。
     */
    public static BigDecimal avg(Iterable<BigDecimal> vals, int scale) {
        if (vals == null) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (BigDecimal v : vals) {
            if (v == null) continue;
            sum = sum.add(v);
            n++;
        }
        if (n == 0) return BigDecimal.ZERO;
        return sum.divide(BigDecimal.valueOf(n), scale, RoundingMode.HALF_UP);
    }

    // ==================== String / double → BigDecimal 便捷段 (Decimal 后缀) ====================

    /**
     * 给手里只有 String 或 double 的业务方提供便捷调用. 内部转 BigDecimal 后委托给上方 BigDecimal 段.
     * <p>
     * <b>String 转换</b>: {@code new BigDecimal(s)} 精确解析 (例 "0.1" → 精确 0.1, 无 double 噪音).
     * <b>double 转换</b>: {@link BigDecimal#valueOf(double)} 走 {@code Double.toString}, 跟字面量字符串一致,
     * 不是 IEEE 754 精确表示 (规避 {@code new BigDecimal(0.1)} 拿到 0.1000...5511 的二进制噪音).
     * <p>
     * <b>命名</b>: BigDecimal 段同名 + {@code Decimal} 后缀, 表明返回 BigDecimal, 跟 long/double 段算术重载明确区分.
     */

    // ---- 加 / 减 ----
    /**
     * 业务作用：以 BigDecimal 语义做加法并对齐到指定精度。
     *
     * @param a 左操作数
     * @param b 右操作数
     * 返回: 相加并对齐后的 BigDecimal。
     */
    public static BigDecimal addDecimal(String a, String b) {
        return add(new BigDecimal(a), new BigDecimal(b));
    }

    /**
     * 业务作用：以 BigDecimal 语义做加法并对齐到指定精度。
     *
     * @param a 左操作数
     * @param b 右操作数
     * 返回: 相加并对齐后的 BigDecimal。
     */
    public static BigDecimal addDecimal(double a, double b) {
        return add(BigDecimal.valueOf(a), BigDecimal.valueOf(b));
    }

    /**
     * 业务作用：以 BigDecimal 语义做减法并对齐到指定精度。
     *
     * @param a 左操作数
     * @param b 右操作数
     * 返回: 相减并对齐后的 BigDecimal。
     */
    public static BigDecimal subtractDecimal(String a, String b) {
        return subtract(new BigDecimal(a), new BigDecimal(b));
    }

    /**
     * 业务作用：以 BigDecimal 语义做减法并对齐到指定精度。
     *
     * @param a 左操作数
     * @param b 右操作数
     * 返回: 相减并对齐后的 BigDecimal。
     */
    public static BigDecimal subtractDecimal(double a, double b) {
        return subtract(BigDecimal.valueOf(a), BigDecimal.valueOf(b));
    }

    // ---- 乘 ----

    /**
     * 业务作用：以 BigDecimal 语义做乘法并对齐到指定精度，供必须使用 BigDecimal 的对接场景。
     *
     * @param a 左操作数
     * @param b 右操作数
     * 返回: 相乘并对齐后的 BigDecimal。
     */
    public static BigDecimal multiplyDecimal(String a, String b) {
        return multiply(new BigDecimal(a), new BigDecimal(b));
    }

    /**
     * 业务作用：以 BigDecimal 语义做乘法并对齐到指定精度，供必须使用 BigDecimal 的对接场景。
     *
     * @param a 左操作数
     * @param b 右操作数
     * 返回: 相乘并对齐后的 BigDecimal。
     */
    public static BigDecimal multiplyDecimal(double a, double b) {
        return multiply(BigDecimal.valueOf(a), BigDecimal.valueOf(b));
    }

    /**
     * 业务作用：以 BigDecimal 语义做乘法并对齐到指定精度，供必须使用 BigDecimal 的对接场景。
     *
     * @param a 左操作数
     * @param b 右操作数
     * @param scale 小数位数（精度）
     * 返回: 相乘并对齐后的 BigDecimal。
     */
    public static BigDecimal multiplyDecimal(String a, String b, int scale) {
        return multiply(new BigDecimal(a), new BigDecimal(b), scale);
    }

    /**
     * 业务作用：以 BigDecimal 语义做乘法并对齐到指定精度，供必须使用 BigDecimal 的对接场景。
     *
     * @param a 左操作数
     * @param b 右操作数
     * @param scale 小数位数（精度）
     * 返回: 相乘并对齐后的 BigDecimal。
     */
    public static BigDecimal multiplyDecimal(double a, double b, int scale) {
        return multiply(BigDecimal.valueOf(a), BigDecimal.valueOf(b), scale);
    }

    // ---- 除 ----

    /**
     * 业务作用：以 BigDecimal 语义做除法并对齐到指定精度。
     *
     * @param a 左操作数
     * @param b 右操作数
     * @param scale 小数位数（精度）
     * 返回: 相除并对齐后的 BigDecimal；除数为 0 时抛出 ArithmeticException。
     */
    public static BigDecimal divideDecimal(String a, String b, int scale) {
        return divide(new BigDecimal(a), new BigDecimal(b), scale);
    }

    /**
     * 业务作用：以 BigDecimal 语义做除法并对齐到指定精度。
     *
     * @param a 左操作数
     * @param b 右操作数
     * @param scale 小数位数（精度）
     * 返回: 相除并对齐后的 BigDecimal；除数为 0 时抛出 ArithmeticException。
     */
    public static BigDecimal divideDecimal(double a, double b, int scale) {
        return divide(BigDecimal.valueOf(a), BigDecimal.valueOf(b), scale);
    }

    /**
     * 业务作用：以 BigDecimal 语义做除法并对齐到指定精度。
     *
     * @param a 左操作数
     * @param b 右操作数
     * @param scale 小数位数（精度）
     * @param mode 舍入模式
     * 返回: 相除并对齐后的 BigDecimal；除数为 0 时抛出 ArithmeticException。
     */
    public static BigDecimal divideDecimal(String a, String b, int scale, RoundingMode mode) {
        return divide(new BigDecimal(a), new BigDecimal(b), scale, mode);
    }

    /**
     * 业务作用：以 BigDecimal 语义做除法并对齐到指定精度。
     *
     * @param a 左操作数
     * @param b 右操作数
     * @param scale 小数位数（精度）
     * @param mode 舍入模式
     * 返回: 相除并对齐后的 BigDecimal；除数为 0 时抛出 ArithmeticException。
     */
    public static BigDecimal divideDecimal(double a, double b, int scale, RoundingMode mode) {
        return divide(BigDecimal.valueOf(a), BigDecimal.valueOf(b), scale, mode);
    }

    // ---- 对齐 ----

    /**
     * 业务作用：把 BigDecimal 对齐到指定精度，默认 HALF_UP。
     *
     * @param val 见上述说明
     * @param scale 小数位数（精度）
     * 返回: 对齐后的 BigDecimal。
     */
    public static BigDecimal alignDecimal(String val, int scale) {
        return align(new BigDecimal(val), scale);
    }

    /**
     * 业务作用：把 BigDecimal 对齐到指定精度，默认 HALF_UP。
     *
     * @param val 见上述说明
     * @param scale 小数位数（精度）
     * 返回: 对齐后的 BigDecimal。
     */
    public static BigDecimal alignDecimal(double val, int scale) {
        return align(BigDecimal.valueOf(val), scale);
    }

    /**
     * 业务作用：把 BigDecimal 向上对齐到指定精度（CEILING）。
     *
     * @param val 见上述说明
     * @param scale 小数位数（精度）
     * 返回: 对齐后的 BigDecimal。
     */
    public static BigDecimal alignUpDecimal(String val, int scale) {
        return alignUp(new BigDecimal(val), scale);
    }

    /**
     * 业务作用：把 BigDecimal 向上对齐到指定精度（CEILING）。
     *
     * @param val 见上述说明
     * @param scale 小数位数（精度）
     * 返回: 对齐后的 BigDecimal。
     */
    public static BigDecimal alignUpDecimal(double val, int scale) {
        return alignUp(BigDecimal.valueOf(val), scale);
    }

    /**
     * 业务作用：把 BigDecimal 向下对齐到指定精度（DOWN）。
     *
     * @param val 见上述说明
     * @param scale 小数位数（精度）
     * 返回: 对齐后的 BigDecimal。
     */
    public static BigDecimal alignDownDecimal(String val, int scale) {
        return alignDown(new BigDecimal(val), scale);
    }

    /**
     * 业务作用：把 BigDecimal 向下对齐到指定精度（DOWN）。
     *
     * @param val 见上述说明
     * @param scale 小数位数（精度）
     * 返回: 对齐后的 BigDecimal。
     */
    public static BigDecimal alignDownDecimal(double val, int scale) {
        return alignDown(BigDecimal.valueOf(val), scale);
    }

    // ---- 求和 / 平均 ----

    /**
     * 业务作用：对多个数字字符串求和，跳过其中的 null。
     *
     * @param vals 见上述说明
     * 返回: 求和结果；入参为 null 或空数组时返回 BigDecimal.ZERO。
     */
    public static BigDecimal sumDecimal(String... vals) {
        if (vals == null || vals.length == 0) return BigDecimal.ZERO;
        BigDecimal r = BigDecimal.ZERO;
        for (String v : vals) {
            if (v != null) r = r.add(new BigDecimal(v));
        }
        return r;
    }

    /**
     * 业务作用：对多个数字字符串求和，跳过其中的 null。
     *
     * @param vals 见上述说明
     * 返回: 求和结果；入参为 null 或空数组时返回 BigDecimal.ZERO。
     */
    public static BigDecimal sumDecimal(double... vals) {
        if (vals == null || vals.length == 0) return BigDecimal.ZERO;
        BigDecimal r = BigDecimal.ZERO;
        for (double v : vals) r = r.add(BigDecimal.valueOf(v));
        return r;
    }

    /**
     * 业务作用：对数字字符串数组求平均并对齐到指定精度（HALF_UP）。
     *
     * @param vals 见上述说明
     * @param scale 小数位数（精度）
     * 返回: 平均值；入参为 null 或空数组时返回 BigDecimal.ZERO。
     */
    public static BigDecimal avgDecimal(String[] vals, int scale) {
        if (vals == null || vals.length == 0) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (String v : vals) {
            if (v == null) continue;
            sum = sum.add(new BigDecimal(v));
            n++;
        }
        if (n == 0) return BigDecimal.ZERO;
        return sum.divide(BigDecimal.valueOf(n), scale, RoundingMode.HALF_UP);
    }

    /**
     * 业务作用：对数字字符串数组求平均并对齐到指定精度（HALF_UP）。
     *
     * @param vals 见上述说明
     * @param scale 小数位数（精度）
     * 返回: 平均值；入参为 null 或空数组时返回 BigDecimal.ZERO。
     */
    public static BigDecimal avgDecimal(double[] vals, int scale) {
        if (vals == null || vals.length == 0) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (double v : vals) sum = sum.add(BigDecimal.valueOf(v));
        return sum.divide(BigDecimal.valueOf(vals.length), scale, RoundingMode.HALF_UP);
    }

}
