package com.ruoyi.common.utils.sign;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
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
public final class SignUtils {
    private static final String SHA_256 = "SHA-256";
    private static final String HMAC_SHA256 = "HmacSHA256";

    private SignUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 计算 SHA-256 摘要（十六进制小写）。用于内容指纹/一致性比对，非密钥场景。
     *
     * @param data 原文
     * @return 64 位十六进制摘要
     */
    public static String sha256(String data) {
        Objects.requireNonNull(data, "SHA-256 摘要入参不得为 null");
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return HexFormat.of().formatHex(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
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
        // 空/ null 密钥须前置拦截：本 JDK 的 SecretKeySpec 对空 key 抛 IllegalArgumentException("Empty key")，
        // key==null 则在 getBytes 处 NPE——两者都不在本方法 catch 集合内会裸抛逃逸，这里统一收敛为
        // 明确的 ServiceException（并非空 key 能算出有效签名，JDK 本就拒绝空 key）。
        if (key == null || key.isEmpty()) {
            throw new ServiceException("HMAC 密钥不能为空");
        }
        Objects.requireNonNull(data, "HMAC 摘要入参内容不得为 null");
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
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
        // 统一校验路径：无论上送签名是否合法都先算一次 HMAC 再比较，避免"跳过计算"的分支差异
        //（MessageDigest.isEqual 的恒定时间比较保护的是 actual vs expected 这条逐字节路径）。
        String actualHex = hmacSha256(data, key);
        if (expectedHex == null || expectedHex.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(actualHex.getBytes(StandardCharsets.UTF_8),
                expectedHex.getBytes(StandardCharsets.UTF_8));
    }
}
