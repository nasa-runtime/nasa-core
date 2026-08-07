package io.github.nasaruntime.core.utils;

import io.github.nasaruntime.core.exception.GoogleAuthorException;
import org.apache.commons.codec.binary.Base32;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Nasa
 * Google验证器
 */
@SuppressWarnings("unused")
public abstract class GoogleAuthorUtils {

    public static String QR_CODE_URI = "otpauth://totp/{}:{}?secret={}&issuer={}";

    /**
     * 时间前后偏移量
     * 如果为0,当前时间为 10:10:15
     * 则表明在 10:10:00-10:10:30 之间生成的code 能校验通过
     * 如果为1,则表明在
     * 10:09:30-10:10:00
     * 10:10:00-10:10:30
     * 10:10:30-10:11:00 之间生成的code 能校验通过
     * 以此类推
     */
    public static int TIME_OFFSET = 0;

    /**
     * 业务作用：生成动态口令的随机密钥，供绑定认证器时展示给用户。
     *
     * 参数说明: 无。
     * 返回: Base32 编码的密钥。
     */
    public static String generateSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return new String(new Base32().encode(bytes));
    }

    /**
     * 业务作用：按 otpauth 规范拼出可被认证器扫码的 URI。
     *
     * @param secret 见上述说明
     * @param account 见上述说明
     * @param issuer 见上述说明
     * 返回: otpauth 形式的 URI。
     */
    public static String generateUri(String secret, String account, String issuer) {
        issuer = StringUtils.isBlank(issuer) ? "Google" : issuer;
        String iss = URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20");
        String acc = URLEncoder.encode(account, StandardCharsets.UTF_8).replace("+", "%20");
        String sec = URLEncoder.encode(secret, StandardCharsets.UTF_8).replace("+", "%20");
        return StringUtils.format(QR_CODE_URI, iss, acc, sec, iss);
    }

    /**
     * 业务作用：校验google验证码
     *
     * @param secret 密钥
     * @param code 验证码
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean validCode(String secret, String code) {
        Base32 codec = new Base32();
        byte[] decodedKey = codec.decode(secret);
        long t = System.currentTimeMillis() / 1000L / 30L;
        String hash;
        try {
            hash = verifyCode(decodedKey, t);
        } catch (Exception e) {
            throw new GoogleAuthorException(e.getMessage(), e);
        }
        if (secureCodeEquals(hash, code)) {
            return true;
        }
        for (int i = -TIME_OFFSET; i <= TIME_OFFSET; i++) {
            if (i == 0) {
                continue;
            }
            try {
                hash = verifyCode(decodedKey, t + i);
            } catch (Exception e) {
                throw new GoogleAuthorException(e.getMessage(), e);
            }
            if (secureCodeEquals(hash, code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 业务作用：以恒定时间比较两个口令。刻意不用普通相等比较：后者会因提前返回而泄漏首个不同字符的位置，可被计时攻击逐位猜解。
     *
     * @param expected 见上述说明
     * @param actual 见上述说明
     * 返回: 两者一致返回 true。
     */
    private static boolean secureCodeEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
        byte[] actualBytes = actual == null ? new byte[0] : actual.getBytes(StandardCharsets.US_ASCII);
        boolean sameLength = actualBytes.length == expectedBytes.length;
        byte[] comparable = sameLength ? actualBytes : Arrays.copyOf(actualBytes, expectedBytes.length);
        return MessageDigest.isEqual(expectedBytes, comparable) && sameLength;
    }

    /**
     * 业务作用：校验用户输入的动态口令，允许一定的时间窗口偏移以容忍时钟漂移。
     *
     * @param key 键
     * @param t 元素
     * 返回: 口令有效返回 true。
     */
    private static String verifyCode(byte[] key, long t) {
        byte[] data = new byte[8];
        long value = t;
        for (int i = 8; i-- > 0; value >>>= 8) {
            data[i] = (byte) value;
        }
        try {
            SecretKeySpec signKey = new SecretKeySpec(key, "HmacSHA1");
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(signKey);
            byte[] hash = mac.doFinal(data);
            int offset = hash[20 - 1] & 0xF;
            // We're using a long because JVM hasn't got unsigned int.
            long truncatedHash = 0;
            for (int i = 0; i < 4; ++i) {
                truncatedHash <<= 8;
                // We are dealing with signed bytes:
                // we just keep the first byte.
                truncatedHash |= (hash[offset + i] & 0xFF);
            }
            truncatedHash &= 0x7FFFFFFF;
            truncatedHash %= 1000000;
            String code = Long.toUnsignedString(truncatedHash);
            int length = code.length();
            // 高位补0
            return length == 6 ? code : "0".repeat(Math.max(0, 6 - length)) + code;
        } catch (Exception e) {
            throw new GoogleAuthorException(e.getMessage(), e);
        }
    }

}
