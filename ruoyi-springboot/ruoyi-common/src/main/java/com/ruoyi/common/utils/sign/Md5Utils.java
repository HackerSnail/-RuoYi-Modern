package com.ruoyi.common.utils.sign;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ruoyi.common.exception.ServiceException;

/**
 * MD5 摘要工具（非加密用途）
 * <p>
 * 适用：文件校验、缓存 key、数据指纹等一致性比对。
 * <b>严禁用于密码存储或任何安全/签名场景</b>——MD5 计算极快、无盐、已被证明存在碰撞漏洞。
 * 密码请走 SecurityUtils#encryptPassword（BCrypt）；API 签名请走 SignUtils（HMAC-SHA256）。
 *
 * @author HackerSnail <2306002088@qq.com>
 */
public final class Md5Utils {
    private static final Logger log = LoggerFactory.getLogger(Md5Utils.class);

    private Md5Utils() {
        // 工具类，禁止实例化
    }

    private static byte[] md5(String s) {
        try {
            MessageDigest algorithm = MessageDigest.getInstance("MD5");
            algorithm.update(s.getBytes(StandardCharsets.UTF_8));
            return algorithm.digest();
        } catch (NoSuchAlgorithmException e) {
            // MD5 是每个 JDK 都保证提供的算法，理论上不会走到这里；一旦失败显式抛出，绝不静默返回 null
            log.error("MD5 摘要计算失败", e);
            throw new ServiceException("MD5 摘要计算失败");
        }
    }

    /**
     * 计算字符串的 MD5 十六进制摘要（32 位小写）。
     * <p>
     * 入参非空契约：s 为 null 时在此 fail-fast 抛 {@link NullPointerException}，
     * 不静默返回、也不留给下游 getBytes 产生意外 NPE。
     *
     * @param s 待摘要的字符串，不得为 null
     * @return 小写十六进制表示的 MD5 摘要
     */
    public static String hash(String s) {
        Objects.requireNonNull(s, "MD5 摘要入参不得为 null");
        // md5() 恒返回非 null digest（失败即抛）；HexFormat 输出逐字节 2 位小写 hex，
        // 与原手写 toHex 完全一致；失败路径已在 md5() 里显式抛出，这里不回退返回原文 s
        return HexFormat.of().formatHex(md5(s));
    }
}
