package com.ruoyi.common.utils.sign;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.ruoyi.common.exception.ServiceException;

/**
 * 签名/摘要工具（SHA-256 与 HMAC-SHA256）
 * <p>
 * 适用：API 请求签名（{@link #hmacSha256(String, String)}
 * 生成、{@link #verifyHmacSha256(String, String, String)} 校验）、
 * 内容指纹（{@link #sha256(String)}）。
 * <b>不用于密码存储</b>——密码请走 SecurityUtils#encryptPassword（BCrypt，自带盐与自适应代价）。
 * HMAC 与 SHA-256 均为 JDK 保证存在的算法；计算失败显式抛出 {@link ServiceException}，绝不静默返回可疑值。
 *
 * @author ruoyi
 */
public class SignUtils {
    private static final String SHA_256 = "SHA-256";
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /**
     * 计算 SHA-256 摘要（十六进制小写）。用于内容指纹/一致性比对，非密钥场景。
     *
     * @param data 原文
     * @return 64 位十六进制摘要
     */
    public static String sha256(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return toHex(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new ServiceException("SHA-256 摘要计算失败");
        }
    }

    /**
     * 计算 HMAC-SHA256 签名（十六进制小写），用于 API 请求签名。
     *
     * @param data 待签名内容
     * @param key  密钥（须安全保管，不得下发前端）
     * @return 64 位十六进制签名
     */
    public static String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return toHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new ServiceException("HMAC-SHA256 签名计算失败");
        }
    }

    /**
     * 校验 HMAC-SHA256 签名。使用 {@link MessageDigest#isEqual} 做恒定时间比较，
     * 避免通过响应耗时侧信道被逐字节猜测签名。
     *
     * @param data        待签名内容
     * @param key         密钥
     * @param expectedHex 客户端上送的签名
     * @return 是否匹配
     */
    public static boolean verifyHmacSha256(String data, String key, String expectedHex) {
        if (expectedHex == null) {
            return false;
        }
        String actualHex = hmacSha256(data, key);
        return MessageDigest.isEqual(actualHex.getBytes(StandardCharsets.UTF_8),
                expectedHex.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 字节数组转小写十六进制字符串（取每字节高低四位）。
     */
    private static String toHex(byte[] bytes) {
        StringBuilder buf = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xff;
            buf.append(HEX_DIGITS[v >>> 4]);
            buf.append(HEX_DIGITS[v & 0x0f]);
        }
        return buf.toString();
    }
}
