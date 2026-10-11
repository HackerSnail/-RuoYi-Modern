package com.ruoyi.framework.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;

import io.jsonwebtoken.Claims;

/**
 * TokenService 令牌行为测试。
 *
 * <p>
 * 锁定本次 jjwt 0.9.1 → 0.13.0 迁移改造的核心面：{@code createToken(Map)} 的签名生成、
 * {@code parseToken(String)} 的解析校验、以及 HS512 对密钥强度的强制要求。通过反射直接驱动
 * 这两个私有方法，验证“签发—解析”往返语义等价、篡改令牌被拒绝、密钥构建符合算法约束，
 * 不依赖 Redis / Servlet / Spring 容器。
 * </p>
 */
public class TokenServiceTest {
    /** 满足 HS512（≥512bit / 64 字节）要求的测试密钥 */
    private static final String VALID_SECRET = "k9Zx2Qf7Lm4Tn8Rs1Vw6Yb3Jd5Hc0Pe2Ag7Kf4Lz9Xq1Wm3Nr6Ts8Vb0Yd2Hc5Jk7Pl9Rz";

    private TokenService tokenService;

    private RedisCache redisCache;

    @BeforeEach
    void setUp() {
        redisCache = mock(RedisCache.class);
        tokenService = new TokenService(redisCache, VALID_SECRET);
        ReflectionTestUtils.setField(tokenService, "header", "Authorization");
        ReflectionTestUtils.setField(tokenService, "expireTime", 30);
    }

    private Map<String, Object> sampleClaims() {
        Map<String, Object> claims = new HashMap<>();
        claims.put(Constants.LOGIN_USER_KEY, "uuid-123-456");
        claims.put(Constants.JWT_USERNAME, "admin");
        return claims;
    }

    private String issueToken(Map<String, Object> claims) {
        return ReflectionTestUtils.invokeMethod(tokenService, "createToken", claims);
    }

    @Test
    @DisplayName("签发-解析往返保留自定义声明与主题")
    void jwtRoundTripPreservesClaims() {
        Map<String, Object> claims = sampleClaims();
        String token = issueToken(claims);

        assertNotNull(token);
        // 标准 JWS 由 header.payload.signature 三段组成
        assertEquals(3, token.split("\\.").length, "令牌应为合法 JWS 三段结构");

        Claims parsed = ReflectionTestUtils.invokeMethod(tokenService, "parseToken", token);
        assertEquals("uuid-123-456", parsed.get(Constants.LOGIN_USER_KEY));
        assertEquals("admin", parsed.get(Constants.JWT_USERNAME));
        // JWT_USERNAME 即 "sub"，应与 getSubject 一致
        assertEquals("admin", parsed.getSubject());
    }

    @Test
    @DisplayName("getUsernameFromToken 取回主题中的用户名")
    void getUsernameFromTokenReturnsSubject() {
        String token = issueToken(sampleClaims());
        assertEquals("admin", tokenService.getUsernameFromToken(token));
    }

    @Test
    @DisplayName("签名被篡改的令牌解析时抛异常")
    void tamperedTokenIsRejected() {
        String token = issueToken(sampleClaims());
        char last = token.charAt(token.length() - 1);
        char flipped = last == 'a' ? 'b' : 'a';
        String tampered = token.substring(0, token.length() - 1) + flipped;

        assertThrows(Exception.class,
                () -> ReflectionTestUtils.invokeMethod(tokenService, "parseToken", tampered));
    }

    @Test
    @DisplayName("HMAC 签名密钥为 final、构造期生成且算法/强度正确")
    void secretKeyIsFinalAndStable() {
        SecretKey first = (SecretKey) ReflectionTestUtils.getField(tokenService, "secretKey");
        SecretKey second = (SecretKey) ReflectionTestUtils.getField(tokenService, "secretKey");

        assertNotNull(first);
        assertSame(first, second, "final 字段应始终为同一密钥实例");
        assertEquals("HmacSHA512", first.getAlgorithm());
        assertTrue(first.getEncoded().length >= 64, "HS512 要求密钥不少于 64 字节");
    }

    @Test
    @DisplayName("密钥过短：构造期即 fail-fast（HS512 强度约束）")
    void shortSecretFailsFastAtConstruction() {
        assertThrows(Exception.class, () -> new TokenService(mock(RedisCache.class), "too-short"));
    }

    @Test
    @DisplayName("会话标识：同一 uuid 稳定、恰 32 位小写 hex、不暴露 uuid 片段")
    void sessionHandleIsDeterministicAndOpaque() {
        String uuid = "2f1c8a9e-7b3d-4e5f-9a0b-1c2d3e4f5a6b";

        String handle = tokenService.toSessionHandle(uuid);

        assertNotNull(handle);
        assertTrue(handle.matches("[0-9a-f]{32}"), "handle 应为 32 位小写 hex（不含 uuid 的任何分隔符/原串）");
        assertEquals(handle, tokenService.toSessionHandle(uuid), "同一会话反复展示应得到同一标识");
        assertFalse(handle.equals(uuid), "handle 不得等于会话 uuid");
        // 不同会话不得共用一个标识
        assertNotEquals(handle, tokenService.toSessionHandle("2f1c8a9e-7b3d-4e5f-9a0b-1c2d3e4f5a6c"));
    }

    @Test
    @DisplayName("会话标识由密钥派生：换密钥后同一 uuid 得到不同标识（非普通摘要）")
    void sessionHandleIsKeyedNotPlainDigest() {
        String uuid = "2f1c8a9e-7b3d-4e5f-9a0b-1c2d3e4f5a6b";
        String otherSecret = "Zt5Yq8Wd1Ns4Rk7Vb0Tp3Hj6Lc9Fg2Km8Xe5Za1Qw7Er4Ty0Ui3Os6Pd9Sa2Fg5Hc8Jk1Rz";

        String handle = tokenService.toSessionHandle(uuid);
        String handleOtherKey = new TokenService(mock(RedisCache.class), otherSecret).toSessionHandle(uuid);

        assertNotEquals(handle, handleOtherKey, "handle 必须以服务端密钥为参，不能是可离线算出的普通摘要");
    }

    @Test
    @DisplayName("会话标识：残缺会话（uuid 为空）返回 null，不抛不占位")
    void sessionHandleToleratesEmptyUuid() {
        assertNull(tokenService.toSessionHandle(null));
        assertNull(tokenService.toSessionHandle(""));
    }

    @Test
    @DisplayName("按标识强退：命中只删对应会话键")
    void delLoginUserByHandleDeletesOnlyMatchedKey() {
        String uuidA = "aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb";
        String uuidB = "cccccccc-4444-5555-6666-dddddddddddd";
        when(redisCache.keys(CacheConstants.LOGIN_TOKEN_KEY + "*"))
                .thenReturn(Set.of(CacheConstants.LOGIN_TOKEN_KEY + uuidA, CacheConstants.LOGIN_TOKEN_KEY + uuidB));
        String handleB = tokenService.toSessionHandle(uuidB);

        assertEquals(1, tokenService.delLoginUserByHandle(handleB));

        verify(redisCache).deleteObject(CacheConstants.LOGIN_TOKEN_KEY + uuidB);
        verify(redisCache, never()).deleteObject(CacheConstants.LOGIN_TOKEN_KEY + uuidA);
    }

    @Test
    @DisplayName("按标识强退：未命中/无会话时返回 0 且不删任何键")
    void delLoginUserByHandleMissesNothing() {
        when(redisCache.keys(CacheConstants.LOGIN_TOKEN_KEY + "*")).thenReturn(
                Set.of(CacheConstants.LOGIN_TOKEN_KEY + "aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb"));

        // 截断旧 hex 得到的合法格式但非本会话的标识（过不了正则、只能靠比对失败）
        assertEquals(0, tokenService.delLoginUserByHandle("00000000000000000000000000000000"));
        verify(redisCache, never()).deleteObject(anyString());

        when(redisCache.keys(CacheConstants.LOGIN_TOKEN_KEY + "*")).thenReturn(Set.of());
        assertEquals(0, tokenService.delLoginUserByHandle("11111111111111111111111111111111"));
    }

    @Test
    @DisplayName("按标识强退：畸形入参先拒，不进入全量扫描")
    void delLoginUserByHandleRejectsMalformedInput() {
        assertThrows(ServiceException.class, () -> tokenService.delLoginUserByHandle(null));
        assertThrows(ServiceException.class, () -> tokenService.delLoginUserByHandle(""));
        // 旧版前端可能回放真实会话 uuid：长度/字符集不符 32 位 hex，同样被拒而非越权删除
        assertThrows(ServiceException.class,
                () -> tokenService.delLoginUserByHandle("2f1c8a9e-7b3d-4e5f-9a0b-1c2d3e4f5a6b"));
        assertThrows(ServiceException.class, () -> tokenService.delLoginUserByHandle("not-a-handle-at-all"));
        verify(redisCache, never()).keys(anyString());
    }
}
