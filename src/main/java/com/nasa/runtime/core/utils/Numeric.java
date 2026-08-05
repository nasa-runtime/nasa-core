package com.nasa.runtime.core.utils;

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
     * 获取数字的字符串长度，负数比正数多1位
     *
     * @param num 数字
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
     * 获取整数的字符串长度，负数比正数多1位
     * <p>
     * O(1) 算法: {@link Long#numberOfLeadingZeros} (硬件 NLZ 指令) 估算 log2,
     * 配合 log10 ≈ log2 * 0.301 (用 1233/4096 整数近似) 求位数, 再用 MULTI_TABLE 校准 1 位.
     * 该路径不创建 Iterator/Entry 等临时对象，保持零分配.
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
     * 将数字number复制到字符数组中，从字符数组的下标start开始复制，完成后返回number的长度
     *
     * @param number 整数
     * @param chars  字符数组
     * @param start  字符数组开始下标
     * @return number的长度
     */
    public static int copyToCharArray(long number, char[] chars, int start) {
        return copyToCharArray(number, stringSizeLong(number), chars, start);
    }


    /**
     * 将数字number复制到字符数组中，从字符数组的下标start开始复制，完成后返回number的长度
     *
     * @param number 整数
     * @param length 整数长度
     * @param chars  字符数组
     * @param start  字符数组开始下标
     * @return number的长度
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
     * 数字是否是偶数
     */
    public static boolean isEven(long num) {
        return (num & 1L) == 0;
    }

    public static boolean isEven(int num) {
        return (num & 1) == 0;
    }

    public static boolean isEven(byte num) {
        return (num & 1) == 0;
    }

    public static boolean isEven(short num) {
        return (num & 1) == 0;
    }

    /**
     * 数字是否是奇数
     */
    public static boolean isOdd(long num) {
        return (num & 1L) != 0;
    }

    public static boolean isOdd(int num) {
        return (num & 1) != 0;
    }

    public static boolean isOdd(byte num) {
        return (num & 1) != 0;
    }

    public static boolean isOdd(short num) {
        return (num & 1) != 0;
    }


    /**
     * 等于
     */
    public static <T extends Comparable<T>> boolean eq(T n1, T n2) {
        return Compares.eq(n1, n2);
    }


    /**
     * 不等于
     */
    public static <T extends Comparable<T>> boolean ne(T n1, T n2) {
        return Compares.ne(n1, n2);
    }


    /**
     * 大于
     */
    public static <T extends Comparable<T>> boolean gt(T n1, T n2) {
        return Compares.gt(n1, n2);
    }


    /**
     * 大于等于
     */
    public static <T extends Comparable<T>> boolean ge(T n1, T n2) {
        return Compares.ge(n1, n2);
    }


    /**
     * 小于
     */
    public static <T extends Comparable<T>> boolean lt(T n1, T n2) {
        return Compares.lt(n1, n2);
    }


    /**
     * 小于等于
     */
    public static <T extends Comparable<T>> boolean le(T n1, T n2) {
        return Compares.le(n1, n2);
    }

    /**
     * 获取指定精度的最小值
     * 1 -> 0.1
     * 2 -> 0.01
     * 5 -> 0.00001
     *
     * @param scale 精度
     */
    public static BigDecimal scaleMin(int scale) {
        // valueOf(unscaledValue=1, scale) 直接构造 1×10^-scale, 一次分配, 比 ONE.divide(...) 省 Math.pow + valueOf + divide 链
        return BigDecimal.valueOf(1L, scale);
    }

    /**
     * 在最小精度位上，获取步长的随机值. 范围 [min, (step-1)×min].
     * <p>
     * 用 {@link ThreadLocalRandom} 替代 {@link Math#random()} (后者全局锁), 直接 long 算+一次 BigDecimal.valueOf 构造,
     * 省 Math.random + 多次 BigDecimal multiply/setScale 链.
     *
     * @param step  步长
     * @param scale 精度
     */
    public static BigDecimal randomStep(BigDecimal step, int scale) {
        return randomStep(step.longValue(), scale);
    }

    /**
     * 在最小精度位上，获取步长的随机值
     *
     * @param step  步长
     * @param scale 精度
     */
    public static BigDecimal randomStep(int step, int scale) {
        return randomStep((long) step, scale);
    }

    /**
     * 在最小精度位上，获取步长的随机值
     *
     * @param step  步长
     * @param scale 精度
     */
    public static BigDecimal randomStep(long step, int scale) {
        if (step <= 0) return scaleMin(scale);
        long rand = ThreadLocalRandom.current().nextLong(step);
        // rand=0 时仍返回 min，保持方法约定的不返回 0 语义。
        return rand == 0 ? scaleMin(scale) : BigDecimal.valueOf(rand, scale);
    }

    /**
     * 获取[min, max]之间随机整数
     */
    public static int nextInt(int min, int max) {
        if (min == max) return min;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /**
     * 获取[0, max]之间随机整数
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
     * scale 校验: 范围 [0, MAX_FIXED_SCALE].
     */
    private static void checkScale(int scale) {
        if (scale < 0 || scale > MAX_FIXED_SCALE) {
            throw new IllegalArgumentException(
                    "fixed-long scale must be in [0, " + MAX_FIXED_SCALE + "], got " + scale
                            + ". For higher precision use BigDecimal API.");
        }
    }

    /**
     * scale 校验 (双参 align*): fromScale ∈ [0, MAX], toScale ∈ [0, fromScale].
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
     * 字符串数字 → 定点 long. 走 {@link Double#parseDouble}, 入口 {@link Math#round} 吸收 double ε.
     * <p>
     * 例: toFixed("123.456789", 8) = 12345678900
     * <p>
     * 精度边界: double 53 位尾数 ≈ 16 位十进制有效数字. {@code val × 10^scale} 后绝对值小于 {@code 10^16}
     * 时能够保持预期精度。超出范围请使用 BigDecimal 处理后再转 long.
     * <p>
     * 零分配: parseDouble、乘法和 round 都使用 primitive，不创建 BigDecimal.
     */
    public static long toFixed(String val, int scale) {
        checkScale(scale);
        return Math.round(Double.parseDouble(val) * MULTI_TABLE[scale]);
    }

    public static long toFixed(String val) {
        return toFixed(val, DEFAULT_FIXED_SCALE);
    }

    /**
     * double → 定点 long.
     */
    public static long toFixed(double val, int scale) {
        checkScale(scale);
        return Math.round(val * MULTI_TABLE[scale]);
    }

    public static long toFixed(double val) {
        return toFixed(val, DEFAULT_FIXED_SCALE);
    }

    // ---- I/O 出口: 定点 long → String / BigDecimal ----

    /**
     * 定点 long → 易读字符串, 自动去尾 0. 纯 long 算术 + StringBuilder, 不走 BigDecimal, 零分配 BigDecimal.
     * <p>
     * 例:
     * <pre>
     * toPlainString(123456789, 8) = "1.23456789"
     * toPlainString(120000000, 8) = "1.2"          // 去尾 0
     * toPlainString(100000000, 8) = "1"            // 整数不加小数点
     * toPlainString(0, 8)         = "0"
     * toPlainString(-100, 8)      = "-0.000001"
     * toPlainString(50, 8)        = "0.0000005"
     * </pre>
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

    public static String toPlainString(long fixed) {
        return toPlainString(fixed, DEFAULT_FIXED_SCALE);
    }

    /**
     * double → 字符串, 自动去尾 0. 内部 round 到定点 long 后转字符串, 中转防漂移.
     * <p>
     * 跟 {@link #toPlainString(long, int)} 通过参数类型重载, 同名同概念.
     * <p>
     * 例: toPlainString(0.30000000000000004, 8) = "0.3" (避免 double 表示噪音)
     */
    public static String toPlainString(double val, int scale) {
        return toPlainString(toFixed(val, scale), scale);
    }

    public static String toPlainString(double val) {
        return toPlainString(val, DEFAULT_FIXED_SCALE);
    }

    /**
     * 定点 long → 字符串, 保留所有尾 0 (固定 scale 位小数). 纯 long 算术, 不走 BigDecimal.
     * <p>
     * 例:
     * <pre>
     * toPlainStringRaw(123456789, 8) = "1.23456789"
     * toPlainStringRaw(120000000, 8) = "1.20000000"
     * toPlainStringRaw(100000000, 8) = "1.00000000"
     * toPlainStringRaw(0, 8)         = "0.00000000"
     * </pre>
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
     * 定点 long → 字符串, 定点精度和显示精度分离.
     * <p>
     * fixedScale: 定点除数 (除以 10^fixedScale)
     * displayScale: 显示小数位数 (截断/补 0 到 displayScale 位, 四舍五入)
     * <pre>
     * toPlainStringRaw(8075697000000L, 8, 2) = "80756.97"
     * toPlainStringRaw(100000000L, 8, 4)      = "1.0000"
     * toPlainStringRaw(123456789L, 8, 4)       = "1.2346"
     * toPlainStringRaw(2501L, 4, 4)            = "0.2501"
     * </pre>
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
     * double → 字符串, 保留所有尾 0 (固定 scale 位小数). 内部 round 到定点 long, 中转防漂移.
     * <p>
     * 例: toPlainStringRaw(1.2, 8) = "1.20000000"
     */
    public static String toPlainStringRaw(double val, int scale) {
        return toPlainStringRaw(toFixed(val, scale), scale);
    }

    /**
     * double → 字符串, 定点精度和显示精度分离, 保留尾 0.
     * <p>
     * 例: toPlainStringRaw(80756.97, 8, 2) = "80756.97"
     */
    public static String toPlainStringRaw(double val, int fixedScale, int displayScale) {
        return toPlainStringRaw(toFixed(val, fixedScale), fixedScale, displayScale);
    }

    /**
     * 定点 long → 字符串, 定点精度和显示精度分离, 去尾 0.
     * <pre>
     * toPlainString(8075697000000L, 8, 2) = "80756.97"
     * toPlainString(100000000L, 8, 4)      = "1"
     * toPlainString(120000000L, 8, 4)      = "1.2"
     * toPlainString(123456789L, 8, 4)      = "1.2346"
     * </pre>
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
     * double → 字符串, 定点精度和显示精度分离, 去尾 0.
     * <p>
     * 例: toPlainString(80756.97, 8, 2) = "80756.97"
     */
    public static String toPlainString(double val, int fixedScale, int displayScale) {
        return toPlainString(toFixed(val, fixedScale), fixedScale, displayScale);
    }

    /**
     * 定点 long → {@link BigDecimal}. 真需要 BigDecimal 时才用 (例如对接外部要求 BigDecimal 入参的 API).
     * 大多数展示场景直接用 {@link #toPlainString} 拼字符串更轻.
     */
    public static BigDecimal toBigDecimal(long fixed, int scale) {
        checkScale(scale);
        return BigDecimal.valueOf(fixed).divide(MULTI_BD_TABLE[scale], scale, RoundingMode.HALF_UP);
    }

    public static BigDecimal toBigDecimal(long fixed) {
        return toBigDecimal(fixed, DEFAULT_FIXED_SCALE);
    }

    // ---- 算术: 全 long primitive，零分配 ----

    /**
     * 余数 → long 舍入. HALF_UP 快路径 (远离零方向, 跟 BigDecimal HALF_UP 严格一致, 修正 Math.round 对 .5 负数偏 +∞ 的微小不一致).
     * frac 是浮点余数项；调用方前置范围校验保证其可安全转换为 long.
     */
    private static long roundHalfUp(double frac) {
        return frac >= 0 ? (long) Math.floor(frac + 0.5d) : -(long) Math.floor(-frac + 0.5d);
    }

    /**
     * 余数 → long 舍入, 支持 {@link RoundingMode} 全集. 对照 BigDecimal 同名 mode 语义.
     * <p>
     * 实现思路: 拆 frac 为 sign + abs, abs 拆 floor + diff, 按 mode 决定 absResult 取 floor 还是 floor+1, 还原符号.
     * UNNECESSARY 模式遇到非整数 frac 抛 {@link ArithmeticException}.
     * <p>
     * <b>intPart 参数</b>: 调用方算出来的整数部分 (multiply/divide 的 a/m*b 或 a/b*m), 仅 HALF_EVEN tie
     * 需要它判定 (intPart + 符号·absResult) 的奇偶, 其他 mode 忽略.
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
     * 两个定点 long 相乘, 结果仍是定点 long, 默认 HALF_UP 舍入.
     * <p>
     * 算法: 整数部分 a/M * b 用 long 防溢出; 余数部分 (a%M)/M * b 用 double 临时算
     * ({@code M ≤ 10^8 < 2^27}，余数小于 M，double 53 位尾数精度足够)，不创建 BigDecimal，零 GC.
     * <p>
     * 必须传定点 long: a 和 b 都必须是 ×10^scale 后的值. 普通整数应直接使用
     * {@code *} 运算符，例如 {@code amount * 5}，不要写成 {@code multiply(amount, 5×10^8)}.
     * <p>
     * 例: multiply(60_0000_0000_0000L, 1000_0000L, 8) = 6_0000_0000_0000L (即 6000 × 0.1 = 600)
     *
     * @param scale 定点精度 (与 a, b 一致)
     */
    public static long multiply(long a, long b, int scale) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        return a / m * b + roundHalfUp((double) (a % m) / m * b);
    }

    public static long multiply(long a, long b) {
        return multiply(a, b, DEFAULT_FIXED_SCALE);
    }

    /**
     * 两个定点 long 相乘, 指定 {@link RoundingMode}. 跟 BigDecimal 同名 mode 语义对齐.
     * <p>
     * 支持 HALF_UP（默认四舍五入）、DOWN（截断）、UP（远离零）、FLOOR 和 CEILING。
     */
    public static long multiply(long a, long b, int scale, RoundingMode mode) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        long intPart = a / m * b;
        return intPart + applyRounding((double) (a % m) / m * b, mode, intPart);
    }

    /**
     * 两个定点 long 相除, 结果仍是定点 long, 默认 HALF_UP 舍入.
     * 算法同 {@link #multiply}: 整数 + 余数分离防溢出, 余数走 double 临时算.
     * <p>
     * 例: divide(6_0000_0000_0000L, 1000_0000L, 8) = 60_0000_0000_0000L (即 6000 / 0.1 = 60000)
     */
    public static long divide(long a, long b, int scale) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        return a / b * m + roundHalfUp((double) (a % b) / b * m);
    }

    public static long divide(long a, long b) {
        return divide(a, b, DEFAULT_FIXED_SCALE);
    }

    /**
     * 两个定点 long 相除, 指定 {@link RoundingMode}. 跟 BigDecimal 同名 mode 语义对齐.
     */
    public static long divide(long a, long b, int scale, RoundingMode mode) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        long intPart = a / b * m;
        return intPart + applyRounding((double) (a % b) / b * m, mode, intPart);
    }

    // ---- 精度对齐: 按业务要求的最小单位或步长对齐 ----

    /**
     * 定点 long 对齐到指定精度, 默认 HALF_UP (四舍五入). 保持原 fromScale 体系表示.
     * <p>
     * 例: align(20021365, 8, 4) → 20020000 (即 0.20021365 HALF_UP 到 4 位 = 0.2002, 仍 ×10^8)
     */
    public static long align(long fixed, int fromScale, int toScale) {
        return align(fixed, fromScale, toScale, RoundingMode.HALF_UP);
    }

    /**
     * 定点 long 对齐到指定精度, 指定 {@link RoundingMode}. 跟 BigDecimal 同名 mode 语义对齐.
     * <p>
     * 注意 {@link #alignUp} (CEILING-toward-+∞) 和 {@link #alignDown} (DOWN-toward-zero) 是常用别名,
     * 跟此方法的 RoundingMode.CEILING / RoundingMode.DOWN 等价, 保留是为了调用点直观.
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
     * 定点 long 向上对齐到指定精度 (CEILING, 朝 +∞)，等价 {@code align(fixed, fromScale, toScale, RoundingMode.CEILING)}.
     * <p>
     * 例: alignUp(20021365, 8, 4) → 20030000 (即 0.20021365 → 0.2003, 仍 ×10^8 表示)
     *
     * @param fixed     定点 long (×10^fromScale)
     * @param fromScale 当前定点精度
     * @param toScale   目标对齐精度, 必须 ≤ fromScale
     */
    public static long alignUp(long fixed, int fromScale, int toScale) {
        return align(fixed, fromScale, toScale, RoundingMode.CEILING);
    }

    /**
     * 定点 long 向下对齐到指定精度 (DOWN, 朝零)，等价 {@code align(fixed, fromScale, toScale, RoundingMode.DOWN)}.
     * <p>
     * 例: alignDown(20021365, 8, 4) → 20020000 (即 0.20021365 → 0.2002, 仍 ×10^8)
     */
    public static long alignDown(long fixed, int fromScale, int toScale) {
        return align(fixed, fromScale, toScale, RoundingMode.DOWN);
    }

    /**
     * 检查定点 long 是否对齐到指定精度.
     * <p>
     * 例: isAligned(20020000, 8, 4) = true; isAligned(20021365, 8, 4) = false
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
     * double 加法, scale 位精度. 内部走 long 防漂移.
     */
    public static double add(double a, double b, int scale) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        return (double) (Math.round(a * m) + Math.round(b * m)) / m;
    }

    public static double add(double a, double b) {
        return add(a, b, DEFAULT_FIXED_SCALE);
    }

    /**
     * double 减法, scale 位精度. 内部走 long 防漂移.
     */
    public static double subtract(double a, double b, int scale) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        return (double) (Math.round(a * m) - Math.round(b * m)) / m;
    }

    public static double subtract(double a, double b) {
        return subtract(a, b, DEFAULT_FIXED_SCALE);
    }

    /**
     * double 乘法, scale 位精度. 内部走 long 防漂移.
     * <p>
     * 算法: a, b 各自 round 到定点 long → 调 {@link #multiply(long, long, int)} → /m 还原 double.
     */
    public static double multiply(double a, double b, int scale) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        long af = Math.round(a * m);
        long bf = Math.round(b * m);
        return (double) multiply(af, bf, scale) / m;
    }

    public static double multiply(double a, double b) {
        return multiply(a, b, DEFAULT_FIXED_SCALE);
    }

    /**
     * double 除法, scale 位精度. 内部走 long 防漂移.
     * <p>
     * b=0 抛 {@link ArithmeticException} (long 除零行为, 与 BigDecimal 一致).
     */
    public static double divide(double a, double b, int scale) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        long af = Math.round(a * m);
        long bf = Math.round(b * m);
        return (double) divide(af, bf, scale) / m;
    }

    public static double divide(double a, double b) {
        return divide(a, b, DEFAULT_FIXED_SCALE);
    }

    /**
     * double 截取到 scale 位精度, HALF_UP 舍入. 例: align(0.123456789, 4) = 0.1235
     * <p>
     * 与 long 体系 {@link #alignUp(long, int, int)} 通过参数 arity 重载区分 (long 版 3 参 fromScale+toScale).
     */
    public static double align(double val, int scale) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        return (double) Math.round(val * m) / m;
    }

    /**
     * double 向上截取到 scale 位精度. 例: alignUp(0.12341, 4) = 0.1235
     */
    public static double alignUp(double val, int scale) {
        checkScale(scale);
        long m = MULTI_TABLE[scale];
        return Math.ceil(val * m) / m;
    }

    /**
     * double 向下截取到 scale 位精度. 例: alignDown(0.12349, 4) = 0.1234
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
     * BigDecimal 加法.
     */
    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return a.add(b);
    }

    /**
     * BigDecimal 减法.
     */
    public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        return a.subtract(b);
    }

    /**
     * BigDecimal 乘法.
     */
    public static BigDecimal multiply(BigDecimal a, BigDecimal b) {
        return a.multiply(b);
    }

    /**
     * BigDecimal 乘法, 结果对齐到指定 scale (HALF_UP).
     */
    public static BigDecimal multiply(BigDecimal a, BigDecimal b, int scale) {
        return a.multiply(b).setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * BigDecimal 除法, 必须指定 scale (避免无限循环小数 ArithmeticException). 默认 HALF_UP.
     */
    public static BigDecimal divide(BigDecimal a, BigDecimal b, int scale) {
        return a.divide(b, scale, RoundingMode.HALF_UP);
    }

    /**
     * BigDecimal 除法, 指定 scale 与舍入模式.
     */
    public static BigDecimal divide(BigDecimal a, BigDecimal b, int scale, RoundingMode mode) {
        return a.divide(b, scale, mode);
    }

    /**
     * BigDecimal 截取到 scale 位精度, HALF_UP. 例: align(1.23456, 2) = 1.23
     */
    public static BigDecimal align(BigDecimal val, int scale) {
        return val.setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * BigDecimal 向上取整到 scale 位精度 (向 +∞). 例: alignUp(1.231, 2) = 1.24
     */
    public static BigDecimal alignUp(BigDecimal val, int scale) {
        return val.setScale(scale, RoundingMode.CEILING);
    }

    /**
     * BigDecimal 向下取整到 scale 位精度 (向 -∞). 例: alignDown(1.239, 2) = 1.23
     */
    public static BigDecimal alignDown(BigDecimal val, int scale) {
        return val.setScale(scale, RoundingMode.FLOOR);
    }

    /**
     * BigDecimal 求和, 空入参返回 {@link BigDecimal#ZERO}.
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
     * BigDecimal 求和, null / 空集合返回 {@link BigDecimal#ZERO}.
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
     * BigDecimal 平均值, 结果对齐到指定 scale (HALF_UP). null / 空集合返回 {@link BigDecimal#ZERO}.
     * 跳过 null 元素, 分母只算非 null 项; 全 null 也返回 ZERO.
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
    public static BigDecimal addDecimal(String a, String b) {
        return add(new BigDecimal(a), new BigDecimal(b));
    }

    public static BigDecimal addDecimal(double a, double b) {
        return add(BigDecimal.valueOf(a), BigDecimal.valueOf(b));
    }

    public static BigDecimal subtractDecimal(String a, String b) {
        return subtract(new BigDecimal(a), new BigDecimal(b));
    }

    public static BigDecimal subtractDecimal(double a, double b) {
        return subtract(BigDecimal.valueOf(a), BigDecimal.valueOf(b));
    }

    // ---- 乘 ----

    public static BigDecimal multiplyDecimal(String a, String b) {
        return multiply(new BigDecimal(a), new BigDecimal(b));
    }

    public static BigDecimal multiplyDecimal(double a, double b) {
        return multiply(BigDecimal.valueOf(a), BigDecimal.valueOf(b));
    }

    public static BigDecimal multiplyDecimal(String a, String b, int scale) {
        return multiply(new BigDecimal(a), new BigDecimal(b), scale);
    }

    public static BigDecimal multiplyDecimal(double a, double b, int scale) {
        return multiply(BigDecimal.valueOf(a), BigDecimal.valueOf(b), scale);
    }

    // ---- 除 ----

    public static BigDecimal divideDecimal(String a, String b, int scale) {
        return divide(new BigDecimal(a), new BigDecimal(b), scale);
    }

    public static BigDecimal divideDecimal(double a, double b, int scale) {
        return divide(BigDecimal.valueOf(a), BigDecimal.valueOf(b), scale);
    }

    public static BigDecimal divideDecimal(String a, String b, int scale, RoundingMode mode) {
        return divide(new BigDecimal(a), new BigDecimal(b), scale, mode);
    }

    public static BigDecimal divideDecimal(double a, double b, int scale, RoundingMode mode) {
        return divide(BigDecimal.valueOf(a), BigDecimal.valueOf(b), scale, mode);
    }

    // ---- 对齐 ----

    public static BigDecimal alignDecimal(String val, int scale) {
        return align(new BigDecimal(val), scale);
    }

    public static BigDecimal alignDecimal(double val, int scale) {
        return align(BigDecimal.valueOf(val), scale);
    }

    public static BigDecimal alignUpDecimal(String val, int scale) {
        return alignUp(new BigDecimal(val), scale);
    }

    public static BigDecimal alignUpDecimal(double val, int scale) {
        return alignUp(BigDecimal.valueOf(val), scale);
    }

    public static BigDecimal alignDownDecimal(String val, int scale) {
        return alignDown(new BigDecimal(val), scale);
    }

    public static BigDecimal alignDownDecimal(double val, int scale) {
        return alignDown(BigDecimal.valueOf(val), scale);
    }

    // ---- 求和 / 平均 ----

    /**
     * String varargs 求和, null / 空数组返回 {@link BigDecimal#ZERO}, 跳过 null 元素.
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
     * double varargs 求和, null / 空数组返回 {@link BigDecimal#ZERO}. double 走 valueOf 转换避免 IEEE 噪音.
     */
    public static BigDecimal sumDecimal(double... vals) {
        if (vals == null || vals.length == 0) return BigDecimal.ZERO;
        BigDecimal r = BigDecimal.ZERO;
        for (double v : vals) r = r.add(BigDecimal.valueOf(v));
        return r;
    }

    /**
     * String 数组平均值, 结果对齐到 scale (HALF_UP). null / 空数组返回 {@link BigDecimal#ZERO}.
     * 跳过 null 元素, 分母只算非 null 项; 全 null 也返回 ZERO.
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
     * double 数组平均值, 结果对齐到 scale (HALF_UP). null / 空数组返回 {@link BigDecimal#ZERO}.
     */
    public static BigDecimal avgDecimal(double[] vals, int scale) {
        if (vals == null || vals.length == 0) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (double v : vals) sum = sum.add(BigDecimal.valueOf(v));
        return sum.divide(BigDecimal.valueOf(vals.length), scale, RoundingMode.HALF_UP);
    }

}
