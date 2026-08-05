package com.nasa.runtime.core.utils;

import com.nasa.runtime.core.copier.string.concat.ACopier;
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
     * 将字符数组顺序打乱 (Fisher-Yates shuffle)，乱序返回
     * 每次项目启动都不一样
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
     * 将字符串中，符合正则表达式的字符串全部替换为目标字符串
     * @param source 源
     * @param regex 正则表达式
     * @param target 目标字符串
     */
    public static String replace(String source, String regex, String target) {
        return source.replaceAll(regex, target);
    }


    /**
     * 替换字符串，忽略大小写
     * @param source 源
     * @param regex 正则表达式
     * @param target 目标字符串
     */
    public static String replaceIgnoreCase(String source, String regex, String target) {
        return source.replaceAll("(?i)" + regex, target);
    }


    /**
     * 判断字符串为空
     * StringUtils.isBlank(null)      = true
     * StringUtils.isBlank("")        = true
     * StringUtils.isBlank(" ")       = true
     * StringUtils.isBlank("bob")     = false
     * StringUtils.isBlank("  bob  ") = false
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
     * 判断字符串不为空
     */
    public static boolean isNotBlank(final CharSequence cs) {
        return !isBlank(cs);
    }


    /**
     * 判断字符串为空
     * StringUtils.isEmpty(null)      = true
     * StringUtils.isEmpty("")        = true
     * StringUtils.isEmpty(" ")       = false
     * StringUtils.isEmpty("bob")     = false
     * StringUtils.isEmpty("  bob  ") = false
     */
    public static boolean isEmpty(final CharSequence cs) {
        return length(cs) == 0;
    }


    /**
     * 判断字符串不为空
     */
    public static boolean isNotEmpty(final CharSequence cs) {
        return !isEmpty(cs);
    }


    /**
     * 检查给定的字符串是否包含实际文本。
     * 更具体地说，如果 String 不为 null，其长度大于 0，并且至少包含一个非空格字符，则此方法返回 true。
     * StringUtils.hasText(null) = false
     * StringUtils.hasText("") = false
     * StringUtils.hasText(" ") = false
     * StringUtils.hasText("12345") = true
     * StringUtils.hasText(" 12345 ") = true
     */
    public static boolean hasText(final CharSequence cs) {
        return isNotBlank(cs);
    }


    private static int length(final CharSequence cs) {
        return Objects.isNull(cs) ? 0 : cs.length();
    }


    /**
     * 字符串为空则返回默认值
     * 不为空返回本身
     */
    public static <T extends CharSequence> T defaultIfBlank(T val, T ds) {
        return isBlank(val) ? ds : val;
    }


    /**
     * 字符串为空则返回默认值
     * 不为空返回本身
     */
    public static <T extends CharSequence> T defaultIfEmpty(final T val, final T ds) {
        return isEmpty(val) ? ds : val;
    }


    /**
     * 去掉字符串空格
     * StringUtils.deleteWhitespace(null)         = null
     * StringUtils.deleteWhitespace("")           = ""
     * StringUtils.deleteWhitespace("abc")        = "abc"
     * StringUtils.deleteWhitespace("   ab  c  ") = "abc"
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
     * 检查给定的 CharSequence 是否包含任何空白字符。
     */
    public static boolean containsWhitespace(CharSequence cs) {
        return contains(cs, Mark_whitespace);
    }


    /**
     * 检查指定的 source 是否包含 target
     * @param source 源字符串
     * @param target 目标字符串
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
     * 检查指定的 source 是否包含 target，忽略大小写
     * sc | 32		大写转小写
     * {@code sc & -33}	    小写转大写
     * sc ^ 32		大写转小写，小写转大写
     * @param source 源字符串
     * @param target 目标字符串
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
     * 字符比较忽略大小写 (仅对 ASCII 字母做 ^32 转换, 非字母直接比较)
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
     * 统计字符串中，指定字符串出现多少次
     * 不含重叠字符串
     * 如：bcbcbcb，当target=bcb时，仅计算2次，中间重叠的不计算
     * @param source 源字符串
     * @param target 目标字符串
     */
    public static int count(CharSequence source, CharSequence target) {
        return count(source, target, false);
    }


    /**
     * 统计字符串中，指定字符串出现多少次
     * @param source 源字符串
     * @param target 目标字符串
     * @param overlapping 是否计算重叠的字符串，true计算重叠，false不计算重叠
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
     * 使用String.charAt()方法，复制字符到指定字符数组
     * @param source 源字符串
     * @param cs 字符数组
     * @param destPos 数组中开始位置
     */
    public static void copyToCharArray(CharSequence source, char[] cs, int destPos) {
        copyToCharArray(source, 0, source.length(), cs, destPos);
    }


    /**
     * 使用String.charAt()方法，复制字符到指定字符数组
     * @param source 源字符串
     * @param start 源字符串开始下标
     * @param end 源字符串结束下标
     * @param cs 字符数组
     * @param destPos 数组中开始位置
     */
    public static void copyToCharArray(CharSequence source, int start, int end, char[] cs, int destPos) {
        for (int i = start; i < end; i++) {
            cs[destPos + i - start] = source.charAt(i);
        }
    }


    /**
     * 字符串拼接，效率比字符串相加要高得多得多，仅次于StringBuilder，但比StringBuilder更灵活好用
     * concat为了提高灵活性增加了许多指令的执行，但是在复杂对象序列化后再拼接中，
     * concat比StringBuilder效率要高出1倍多
     * 10万个数字拼接，concat比StringBuilder慢10几毫秒
     * 10万个KeyValue对象拼接，concat在80-100ms，StringBuilder在210-220ms
     * 支持：
     * 基本类型、包装类、基本类型数组、包装类数组、集合、其它对象
     * 当元素为数组或集合时，concat会解析里面的每一个元素，然后拼接
     * 当没有元素的解析器Copier时，会执行默认的ObjectCopier
     *
     * @param sources 拼接源数组
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
     * 将对象实例转字符串
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
     * 字符串格式化输出
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
     * 生成随机数字字符串
     * @param length 字符串长度
     */
    public static String randomDigital(int length) {
        return randomDigital(Digital_arr, length);
    }


    /**
     * 生成随机数字字符串
     * @param digitalArr 字符数组源
     * @param length 字符串长度
     */
    public static String randomDigital(char[] digitalArr, int length) {
        char[] cs = new char[length];
        for (int i = 0; i < length; i++) {
            cs[i] = digitalArr[Numeric.nextInt(digitalArr.length - 1)];
        }
        return new String(cs);
    }


    /**
     * 生成随机字符串
     * @param length 字符串长度
     */
    public static String random(int length) {
        return random(Digital_and_Char_arr, length);
    }


    /**
     * 生成随机字符串
     * @param arr 字符数组源
     * @param length 字符串长度
     */
    public static String random(char[] arr, int length) {
        char[] cs = new char[length];
        for (int i = 0; i < length; i++) {
            cs[i] = arr[Secure_random.nextInt(arr.length)];
        }
        return new String(cs);
    }


    /**
     * 生成一个UUID，32位，不含 - .
     */
    public static String uuid() {
        return UUID.randomUUID().toString().replace(Mark_subtract, EMPTY);
    }


    /**
     * 去掉字符串头尾空格
     * @param source 字符串
     */
    public static String trim(String source) {
        return trimWhitespace(source);
    }


    /**
     * 去掉字符串头尾空格
     * @param source 字符串
     */
    public static String trimWhitespace(String source) {
        return Objects.isNull(source) ? null : source.trim();
    }


    /**
     * 字符串source是否以target开头，忽略大小写
     * sc | 32		大写转小写
     * {@code sc & -33}	    小写转大写
     * sc ^ 32		大写转小写，小写转大写
     * @param source 源字符串
     * @param target 目标字符串
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
     * 字符串source是否以target结尾，忽略大小写
     * sc | 32		大写转小写
     * {@code sc & -33}	    小写转大写
     * sc ^ 32		大写转小写，小写转大写
     * @param source 源字符串
     * @param target 目标字符串
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
     * 驼峰转下划线: userName → user_name
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
     * 驼峰转下划线 (兼容旧方法名)
     */
    public static String underscoreToCamelCase(String source) {
        return camelToUnderscore(source);
    }

    /**
     * 下划线转驼峰: user_name → userName
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
     * 字符串是否相等
     */
    public static boolean eq(CharSequence source1, CharSequence source2) {
        if (Objects.isNull(source1) || Objects.isNull(source2)) {
            return false;
        }
        return source1.equals(source2);
    }


    /**
     * 字符串是否不相等
     */
    public static boolean ne(CharSequence source1, CharSequence source2) {
        return !eq(source1, source2);
    }

    /**
     * Base64编码
     */
    public static String base64Encode(String source) {
        return Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Base64编码
     */
    public static byte[] base64EncodeBytes(String source) {
        return Base64.getEncoder().encode(source.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Base64编码
     */
    public static String base64Decode(String source) {
        return new String(base64DecodeBytes(source), StandardCharsets.UTF_8);
    }

    /**
     * Base64编码
     */
    public static byte[] base64DecodeBytes(String source) {
        return Base64.getDecoder().decode(source.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Hex 16进制编码
     */
    public static String toHex(String source) {
        return toHex(source.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Bytes to HEX
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
     * Hex 16进制解码
     */
    public static String fromHex(String hexStr) {
        return new String(hexToBytes(hexStr), StandardCharsets.UTF_8);
    }

    /**
     * Convert HEX to Bytes
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
