package com.nasa.runtime.core.utils;

import com.nasa.runtime.core.exception.GoogleAuthorException;
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
     * 创建密钥
     */
    public static String generateSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return new String(new Base32().encode(bytes));
    }

    /**
     * 生成 Google Authenticator Key Uri
     * Google Authenticator 规定的 Key Uri 格式:
     * {@code otpauth://totp/{issuer}:{account}?secret={secret}&issuer={issuer}}
     * <p>
     * 参数需要进行 url 编码 +号需要替换成%20
     * @param secret 密钥 使用 createSecretKey 方法生成
     * @param account 用户账户 如: example@domain.com
     * @param issuer 服务名称 如: Google,GitHub
     */
    public static String generateUri(String secret, String account, String issuer) {
        issuer = StringUtils.isBlank(issuer) ? "Google" : issuer;
        String iss = URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20");
        String acc = URLEncoder.encode(account, StandardCharsets.UTF_8).replace("+", "%20");
        String sec = URLEncoder.encode(secret, StandardCharsets.UTF_8).replace("+", "%20");
        return StringUtils.format(QR_CODE_URI, iss, acc, sec, iss);
    }

    /**
     * 校验google验证码
     * @param secret 密钥
     * @param code   验证码
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

    private static boolean secureCodeEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
        byte[] actualBytes = actual == null ? new byte[0] : actual.getBytes(StandardCharsets.US_ASCII);
        boolean sameLength = actualBytes.length == expectedBytes.length;
        byte[] comparable = sameLength ? actualBytes : Arrays.copyOf(actualBytes, expectedBytes.length);
        return MessageDigest.isEqual(expectedBytes, comparable) && sameLength;
    }

    /**
     * 计算google验证码
     * @param key 密钥
     * @param t 时间窗口，第几个30s
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
