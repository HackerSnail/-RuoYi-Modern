package com.ruoyi.common.utils.sign;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * @author ruoyi
 */
public class Md5Utils {
    private static final Logger log = LoggerFactory.getLogger(Md5Utils.class);

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

    private static final String toHex(byte hash[]) {
        if (hash == null) {
            return null;
        }
        StringBuilder buf = new StringBuilder(hash.length * 2);
        int i;

        for (i = 0; i < hash.length; i++) {
            if ((hash[i] & 0xff) < 0x10) {
                buf.append("0");
            }
            buf.append(Long.toString(hash[i] & 0xff, 16));
        }
        return buf.toString();
    }

    public static String hash(String s) {
        // toHex 产出的本就是纯 ASCII 十六进制串，无需再做 String->byte[]->String 的空转；
        // 失败路径已在 md5() 里显式抛出，这里不再回退返回原文 s
        return toHex(md5(s));
    }
}
