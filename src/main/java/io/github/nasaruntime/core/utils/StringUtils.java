package io.github.nasaruntime.core.utils;

import io.github.nasaruntime.core.copier.string.concat.ACopier;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Nasa
 * 字符串工具
 */
@SuppressWarnings("unused")
@Slf4j
public abstract class StringUtils {

    private static final SecureRandom Secure_random = new SecureRandom();

    private static final char[] hexArray = "0123456789ABCDEF".toCharArray();

    /* 数组字符数组，每次项目启动都不一样 */
    public static final char[] Digital_arr = unOrderly('0', '1', '2', '3', '4', '5', '6', '7', '8', '9');
    /* 数组大小字母字符数组，每次项目启动都不一样 */
    public static final char[] Digital_and_Char_arr = unOrderly('0', '1', '2', '3', '4', '5', '6', '7', '8', '9'
            ,'a','b','c','d','e','f','g','h','i','j','k','l','m','n','o','p','q','r','s','t','u','v','w','x','y','z'
            ,'A','B','C','D','E','F','G','H','I','J','K','L','M','N','O','P','Q','R','S','T','U','V','W','X','Y','Z'
    );

    public static final String EMPTY = "";
    /* format方法替换字符串中的字符串组合 */
    public static final String rep = "{}";
    public static final String rep_patton = "\\{}";
    /* 右斜杠 */
    public static final String Mark_right_slash = "/";
    /* 路径分隔符，linux和win通用 */
    public static final String Mark_separator = Mark_right_slash;
    /* 符号 . */
    public static final String Mark_spot = ".";
    public static final String Mark_qst = "?";
    public static final String Mark_at = "&";
    public static final String Mark_eq = "=";
    public static final String Mark_comma = ",";
    public static final String Mark_semicolon = ";";
    public static final String Mark_subtract = "-";
    public static final String Mark_underscore = "_";
    public static final String Mark_add = "+";
    public static final String Mark_colon = ":";
    public static final String Mark_whitespace = " ";


    /**
     * 业务作用：用 Fisher-Yates 算法原地打乱字符数组。该算法保证每种排列等概率出现。
     *
     * @param source 见上述说明
     * 返回: 打乱后的字符数组，与入参为同一实例。
     */
    public static char[] unOrderly(char... source) {
        char[] cs = source.clone();
        for (int i = cs.length - 1; i > 0; i--) {
            int j = Numeric.nextInt(i);
            char tmp = cs[i];
            cs[i] = cs[j];
            cs[j] = tmp;
        }
        return cs;
    }


    /**
     * 业务作用：把字符串中匹配正则的部分全部替换为目标串。
     *
     * @param source 见上述说明
     * @param regex 见上述说明
     * @param target 见上述说明
     * 返回: 替换后的字符串。
     */
    public static String replace(String source, String regex, String target) {
        return source.replaceAll(regex, target);
    }


    /**
     * 业务作用：忽略大小写地替换子串。
     *
     * @param source 见上述说明
     * @param regex 见上述说明
     * @param target 见上述说明
     * 返回: 替换后的字符串。
     */
    public static String replaceIgnoreCase(String source, String regex, String target) {
        return source.replaceAll("(?i)" + regex, target);
    }


    /**
     * 业务作用：判断字符串为空
     * StringUtils.isBlank(null)      = true
     * StringUtils.isBlank("")        = true
     * StringUtils.isBlank(" ")       = true
     * StringUtils.isBlank("bob")     = false
     * StringUtils.isBlank("  bob  ") = false
     *
     * @param cs 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isBlank(final CharSequence cs) {
        int strLen = length(cs);
        if (strLen == 0) {
            return true;
        }
        for (int i = 0; i < strLen; i++) {
            if (!Character.isWhitespace(cs.charAt(i))) {
                return false;
            }
        }
        return true;
    }


    /**
     * 业务作用：判断字符串不为空
     *
     * @param cs 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isNotBlank(final CharSequence cs) {
        return !isBlank(cs);
    }


    /**
     * 业务作用：判断字符串为空
     * StringUtils.isEmpty(null)      = true
     * StringUtils.isEmpty("")        = true
     * StringUtils.isEmpty(" ")       = false
     * StringUtils.isEmpty("bob")     = false
     * StringUtils.isEmpty("  bob  ") = false
     *
     * @param cs 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isEmpty(final CharSequence cs) {
        return length(cs) == 0;
    }


    /**
     * 业务作用：判断字符串不为空
     *
     * @param cs 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isNotEmpty(final CharSequence cs) {
        return !isEmpty(cs);
    }


    /**
     * 业务作用：检查给定的字符串是否包含实际文本。
     * 更具体地说，如果 String 不为 null，其长度大于 0，并且至少包含一个非空格字符，则此方法返回 true。
     * StringUtils.hasText(null) = false
     * StringUtils.hasText("") = false
     * StringUtils.hasText(" ") = false
     * StringUtils.hasText("12345") = true
     * StringUtils.hasText(" 12345 ") = true
     *
     * @param cs 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean hasText(final CharSequence cs) {
        return isNotBlank(cs);
    }


    /**
     * 业务作用：取字符串长度并把 null 归一为 0，省去调用方判空。
     *
     * @param cs 字符序列
     * 返回: 字符数；入参为 null 时返回 0。
     */
    private static int length(final CharSequence cs) {
        return Objects.isNull(cs) ? 0 : cs.length();
    }


    /**
     * 业务作用：字符串为 null、空串或全空白时返回默认值。
     *
     * @param val 见上述说明
     * @param ds 见上述说明
     * 返回: 原字符串或默认值。
     */
    public static <T extends CharSequence> T defaultIfBlank(T val, T ds) {
        return isBlank(val) ? ds : val;
    }


    /**
     * 业务作用：字符串为 null 或空串时返回默认值；与 defaultIfBlank 的区别是全空白串被视为有内容。
     *
     * @param val 见上述说明
     * @param ds 见上述说明
     * 返回: 原字符串或默认值。
     */
    public static <T extends CharSequence> T defaultIfEmpty(final T val, final T ds) {
        return isEmpty(val) ? ds : val;
    }


    /**
     * 业务作用：删除字符串中所有空白字符，而不只是首尾。
     *
     * @param val 见上述说明
     * 返回: 不含空白字符的字符串。
     */
    public static String deleteWhitespace(String val) {
        if (isEmpty(val)) {
            return val;
        }
        final int sz = val.length();
        final char[] chs = new char[sz];
        int count = 0;
        for (int i = 0; i < sz; i++) {
            if (!Character.isWhitespace(val.charAt(i))) {
                chs[count++] = val.charAt(i);
            }
        }
        if (count == sz) {
            return val;
        }
        if (count == 0) {
            return EMPTY;
        }
        return new String(chs, 0, count);
    }


    /**
     * 业务作用：检查给定的 CharSequence 是否包含任何空白字符。
     *
     * @param cs 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean containsWhitespace(CharSequence cs) {
        return contains(cs, Mark_whitespace);
    }


    /**
     * 业务作用：检查指定的 source 是否包含 target
     *
     * @param source 源字符串
     * @param target 目标字符串
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean contains(CharSequence source, CharSequence target) {
        int sl = length(source);
        int tl = length(target);
        if (sl == 0 || tl == 0 || sl < tl) {
            return false;
        }

        int n, j;
        char first = target.charAt(0);
        for (int i = 0; i < sl; i++) {
            if (source.charAt(i) != first) {
                continue;
            }

            if (tl == 1) {
                return true;
            }

            for (j = 1; j < tl; j++) {
                n = i + j;
                if (n >= sl || source.charAt(n) != target.charAt(j)) {
                    break;
                }
                if (j == tl -1) {
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * 业务作用：检查指定的 source 是否包含 target，忽略大小写
     * sc | 32		大写转小写
     * {@code sc & -33}	    小写转大写
     * sc ^ 32		大写转小写，小写转大写
     *
     * @param source 源字符串
     * @param target 目标字符串
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean containsIgnoreCase(CharSequence source, CharSequence target) {
        int sl = length(source);
        int tl = length(target);
        if (sl == 0 || tl == 0 || sl < tl) {
            return false;
        }

        int n, j;
        char first = target.charAt(0);
        for (int i = 0; i < sl; i++) {
            char sc = source.charAt(i);
            if (!charEqualsIgnoreCase(sc, first)) {
                continue;
            }

            if (tl == 1) {
                return true;
            }

            for (j = 1; j < tl; j++) {
                n = i + j;
                if (n >= sl) {
                    break;
                }
                if (!charEqualsIgnoreCase(source.charAt(n), target.charAt(j))) {
                    break;
                }
                if (j == tl - 1) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 业务作用：字符比较忽略大小写 (仅对 ASCII 字母做 ^32 转换, 非字母直接比较)
     *
     * @param a 见上述说明
     * @param b 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    private static boolean charEqualsIgnoreCase(char a, char b) {
        if (a == b) return true;
        // 仅当两者都是字母时才做大小写转换
        if ((a >= 'A' && a <= 'Z') || (a >= 'a' && a <= 'z')) {
            return (a ^ 32) == b;
        }
        return false;
    }


    /**
     * 业务作用：统计子串在字符串中出现的次数，按不重叠方式计数。
     *
     * @param source 见上述说明
     * @param target 见上述说明
     * 返回: 出现次数；未出现时返回 0。
     */
    public static int count(CharSequence source, CharSequence target) {
        return count(source, target, false);
    }


    /**
     * 业务作用：统计子串在字符串中出现的次数，按不重叠方式计数。
     *
     * @param source 见上述说明
     * @param target 见上述说明
     * @param overlapping 见上述说明
     * 返回: 出现次数；未出现时返回 0。
     */
    public static int count(CharSequence source, CharSequence target, boolean overlapping) {
        int count = 0;
        if (isEmpty(source) || isEmpty(target) || source.length() < target.length()) {
            return count;
        }
        // 每一个target首字母出现的地方开始，所有target字符都依次出现，则为true
        boolean same = true;
        int n, j;
        char first = target.charAt(0);
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) != first) {
                continue;
            }

            for (j = 1; j < target.length(); j++) {
                n = i + j;
                if (n >= source.length() || source.charAt(n) != target.charAt(j)) {
                    same = false;
                    break;
                }
            }
            if (same) {
                // 出现次数加1
                count++;
                if (!overlapping) {
                    // 不计算重叠
                    i += target.length() - 1;
                }
            }
            same = true;
        }
        return count;
    }


    /**
     * 业务作用：使用String.charAt()方法，复制字符到指定字符数组
     *
     * @param source 源字符串
     * @param cs 字符数组
     * @param destPos 数组中开始位置
     * 返回: 无返回值。
     */
    public static void copyToCharArray(CharSequence source, char[] cs, int destPos) {
        copyToCharArray(source, 0, source.length(), cs, destPos);
    }


    /**
     * 业务作用：使用String.charAt()方法，复制字符到指定字符数组
     *
     * @param source 源字符串
     * @param start 源字符串开始下标
     * @param end 源字符串结束下标
     * @param cs 字符数组
     * @param destPos 数组中开始位置
     * 返回: 无返回值。
     */
    public static void copyToCharArray(CharSequence source, int start, int end, char[] cs, int destPos) {
        for (int i = start; i < end; i++) {
            cs[destPos + i - start] = source.charAt(i);
        }
    }


    /**
     * 业务作用：零中间字符串拼接：先算总长再一次性写入字符数组，比字符串相加快得多，且比 StringBuilder 更省去手工管理。
     *
     * @param sources 见上述说明
     * 返回: 拼接后的字符串。
     */
    public static String concat(Object... sources) {
        int length = 0;
        for (Object source : sources) {
            if (Objects.isNull(source)) {
                continue;
            }
            length += ACopier.getCopier(source.getClass()).length(source);
        }
        char[] cs = new char[length];
        int destPos = 0;
        for (Object source : sources) {
            if (Objects.isNull(source)) {
                continue;
            }
            destPos = ACopier.getCopier(source.getClass()).copyToCharArray(source, cs, destPos);
        }
        return new String(cs);
    }

    /**
     * 业务作用：把任意对象渲染成字符串并统一处理 null。
     *
     * @param source 见上述说明
     * 返回: 对象的字符串表示；入参为 null 时返回空串。
     */
    public static String toString(Object source) {
        try {
            return ObjMprUtils.toString(source);
        } catch (Throwable e) {
            log.debug("Object mapper toString failed, fallback to Object.toString", e);
            return source == null ? null : source.toString();
        }
    }


    /**
     * 业务作用：按 {} 占位符格式化字符串，占位符按序被参数替换。
     *
     * @param val 见上述说明
     * @param params 见上述说明
     * 返回: 格式化后的字符串。
     */
    public static String format(String val, Object... params) {
        if (ColUtils.isEmpty(params) || isBlank(val) || !val.contains(rep)) {
            return val;
        }
        String[] arr = val.split(rep_patton, count(val, rep) + 1);
        Object[] sources  = new Object[(arr.length << 1) - 1];
        for (int i = 0, j = 0; i < arr.length; i++) {
            sources[j++] = arr[i];
            if (i == arr.length - 1) {
                break;
            }
            if (i < params.length) {
                sources[j++] = params[i];
            } else {
                sources[j++] = rep;
            }
        }
        return concat(sources);
    }


    /**
     * 业务作用：生成指定长度的随机数字串，用于验证码等场景。
     *
     * @param length 长度
     * 返回: 随机数字字符串。
     */
    public static String randomDigital(int length) {
        return randomDigital(Digital_arr, length);
    }


    /**
     * 业务作用：生成指定长度的随机数字串，用于验证码等场景。
     *
     * @param digitalArr 见上述说明
     * @param length 长度
     * 返回: 随机数字字符串。
     */
    public static String randomDigital(char[] digitalArr, int length) {
        char[] cs = new char[length];
        for (int i = 0; i < length; i++) {
            cs[i] = digitalArr[Numeric.nextInt(digitalArr.length - 1)];
        }
        return new String(cs);
    }


    /**
     * 业务作用：生成指定长度的随机字符串。
     *
     * @param length 长度
     * 返回: 随机字符串。
     */
    public static String random(int length) {
        return random(Digital_and_Char_arr, length);
    }


    /**
     * 业务作用：生成指定长度的随机字符串。
     *
     * @param arr 见上述说明
     * @param length 长度
     * 返回: 随机字符串。
     */
    public static String random(char[] arr, int length) {
        char[] cs = new char[length];
        for (int i = 0; i < length; i++) {
            cs[i] = arr[Secure_random.nextInt(arr.length)];
        }
        return new String(cs);
    }


    /**
     * 业务作用：生成不含连字符的 32 位 UUID，供数据库主键与幂等键使用。
     *
     * 参数说明: 无。
     * 返回: 32 位十六进制字符串。
     */
    public static String uuid() {
        return UUID.randomUUID().toString().replace(Mark_subtract, EMPTY);
    }


    /**
     * 业务作用：去掉字符串首尾空格并容忍 null。
     *
     * @param source 见上述说明
     * 返回: 去掉首尾空格的字符串；入参为 null 时返回 null。
     */
    public static String trim(String source) {
        return trimWhitespace(source);
    }


    /**
     * 业务作用：去掉字符串首尾的全部空白字符（含制表符、换行等），范围比 trim 更广。
     *
     * @param source 见上述说明
     * 返回: 去掉首尾空白的字符串。
     */
    public static String trimWhitespace(String source) {
        return Objects.isNull(source) ? null : source.trim();
    }


    /**
     * 业务作用：字符串source是否以target开头，忽略大小写
     * sc | 32		大写转小写
     * {@code sc & -33}	    小写转大写
     * sc ^ 32		大写转小写，小写转大写
     *
     * @param source 源字符串
     * @param target 目标字符串
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean startsWithIgnoreCase(CharSequence source, CharSequence target) {
        int sl = length(source);
        int tl = length(target);
        if (sl == 0 || tl == 0 || sl < tl) {
            return false;
        }
        for (int i = 0; i < tl; i++) {
            if (!charEqualsIgnoreCase(source.charAt(i), target.charAt(i))) {
                return false;
            }
        }
        return true;
    }


    /**
     * 业务作用：字符串source是否以target结尾，忽略大小写
     * sc | 32		大写转小写
     * {@code sc & -33}	    小写转大写
     * sc ^ 32		大写转小写，小写转大写
     *
     * @param source 源字符串
     * @param target 目标字符串
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean endsWithIgnoreCase(CharSequence source, CharSequence target) {
        int sl = length(source);
        int tl = length(target);
        if (sl == 0 || tl == 0 || sl < tl) {
            return false;
        }
        for (int i = 1; i <= tl; i++) {
            if (!charEqualsIgnoreCase(source.charAt(sl - i), target.charAt(tl - i))) {
                return false;
            }
        }
        return true;
    }


    /**
     * 业务作用：驼峰转下划线，例如 userName 转成 user_name，用于对接下划线命名的数据库列。
     *
     * @param source 见上述说明
     * 返回: 下划线形式的字符串。
     */
    public static String camelToUnderscore(String source) {
        if (isBlank(source)) {
            return source;
        }
        StringBuilder sb = new StringBuilder(source.length() + 4);
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                // 大写字母
                if (i > 0) sb.append('_');
                sb.append((char) (c | 32));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 业务作用：下划线转驼峰的旧方法名，保留以兼容既有调用。
     *
     * @param source 见上述说明
     * 返回: 驼峰形式的字符串。
     */
    public static String underscoreToCamelCase(String source) {
        return camelToUnderscore(source);
    }

    /**
     * 业务作用：下划线转驼峰，例如 user_name 转成 userName。
     *
     * @param source 见上述说明
     * 返回: 驼峰形式的字符串。
     */
    public static String underscoreToCamel(String source) {
        if (isBlank(source)) {
            return source;
        }
        StringBuilder sb = new StringBuilder(source.length());
        boolean upperNext = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '_') {
                upperNext = true;
            } else if (upperNext) {
                sb.append((c >= 'a' && c <= 'z') ? (char) (c & ~32) : c);
                upperNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }


    /**
     * 业务作用：字符串是否相等
     *
     * @param source1 见上述说明
     * @param source2 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean eq(CharSequence source1, CharSequence source2) {
        if (Objects.isNull(source1) || Objects.isNull(source2)) {
            return false;
        }
        return source1.equals(source2);
    }


    /**
     * 业务作用：字符串是否不相等
     *
     * @param source1 见上述说明
     * @param source2 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean ne(CharSequence source1, CharSequence source2) {
        return !eq(source1, source2);
    }

    /**
     * 业务作用：Base64 编码。
     *
     * @param source 见上述说明
     * 返回: 编码后的字符串。
     */
    public static String base64Encode(String source) {
        return Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 业务作用：Base64 编码并返回字节数组，省去再转字符串的开销。
     *
     * @param source 见上述说明
     * 返回: 编码后的字节数组。
     */
    public static byte[] base64EncodeBytes(String source) {
        return Base64.getEncoder().encode(source.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 业务作用：Base64 解码。
     *
     * @param source 见上述说明
     * 返回: 解码后的字符串；输入非法时抛出异常。
     */
    public static String base64Decode(String source) {
        return new String(base64DecodeBytes(source), StandardCharsets.UTF_8);
    }

    /**
     * 业务作用：Base64 解码为字节数组。
     *
     * @param source 见上述说明
     * 返回: 解码后的字节数组；输入非法时抛出异常。
     */
    public static byte[] base64DecodeBytes(String source) {
        return Base64.getDecoder().decode(source.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 业务作用：把字节数据编码成十六进制字符串。
     *
     * @param source 见上述说明
     * 返回: 十六进制字符串。
     */
    public static String toHex(String source) {
        return toHex(source.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 业务作用：把字节数据编码成十六进制字符串。
     *
     * @param bytes 字节数组
     * 返回: 十六进制字符串。
     */
    public static String toHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    /**
     * 业务作用：把十六进制字符串解码回原始文本。
     *
     * @param hexStr 见上述说明
     * 返回: 解码后的文本；输入非法时抛出异常。
     */
    public static String fromHex(String hexStr) {
        return new String(hexToBytes(hexStr), StandardCharsets.UTF_8);
    }

    /**
     * 业务作用：把十六进制字符串解码成字节数组。
     *
     * @param hexStr 见上述说明
     * 返回: 解码后的字节数组；输入长度为奇数或含非法字符时抛出异常。
     */
    public static byte[] hexToBytes(String hexStr) {
        int len = hexStr.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hexStr.charAt(i), 16) << 4) + Character.digit(hexStr.charAt(i + 1), 16));
        }
        return data;
    }

}
