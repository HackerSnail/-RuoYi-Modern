package com.ruoyi.framework.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.redis.RedisCache;

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

    @BeforeEach
    void setUp() {
        tokenService = new TokenService(mock(RedisCache.class));
        ReflectionTestUtils.setField(tokenService, "header", "Authorization");
        ReflectionTestUtils.setField(tokenService, "secret", VALID_SECRET);
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
    @DisplayName("HS512 密钥被缓存复用且算法正确")
    void secretKeyIsCachedAndStable() {
        SecretKey first = ReflectionTestUtils.invokeMethod(tokenService, "getSecretKey");
        SecretKey second = ReflectionTestUtils.invokeMethod(tokenService, "getSecretKey");

        assertNotNull(first);
        assertSame(first, second, "双检锁应保证同一密钥实例被复用");
        assertEquals("HmacSHA512", first.getAlgorithm());
        assertTrue(first.getEncoded().length >= 64, "HS512 要求密钥不少于 64 字节");
    }

    @Test
    @DisplayName("密钥过短无法构建 HMAC 签名密钥（强度约束）")
    void shortSecretIsRejected() {
        ReflectionTestUtils.setField(tokenService, "secret", "too-short");
        assertThrows(Exception.class, () -> issueToken(sampleClaims()));
    }
}
