package com.ruoyi.framework.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.domain.model.RegisterBody;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.user.CaptchaException;
import com.ruoyi.common.exception.user.CaptchaExpireException;
import com.ruoyi.common.utils.MessageUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.ServletUtils;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.framework.manager.AsyncManager;
import com.ruoyi.framework.manager.factory.AsyncFactory;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.ISysUserService;

/**
 * SysRegisterService 注册编排测试。
 *
 * <p>覆盖本轮 review 落地的控制流：验证码校验的 uuid 空 early-reject、null 先于 delete 的顺序、
 * 错误答案消费验证码后抛异常；注册结果统一审计（成功/校验失败/重复名/DB 异常各分支）；
 * registerUser 抛异常时记审计后原样 rethrow。</p>
 *
 * <p>静态协作（AsyncFactory 审计工厂、MessageUtils 国际化、IpUtils 取 IP、ServletUtils 取 Request、
 * SecurityUtils 加密）用 Mockito inline 静态打桩隔离，全程不触碰容器 / Redis / 数据库。</p>
 */
public class SysRegisterServiceTest {
    private SysRegisterService registerService;

    private ISysUserService userService;

    private ISysConfigService configService;

    private RedisCache redisCache;

    private AsyncManager asyncManager;

    @BeforeEach
    void setUp() {
        userService = mock(ISysUserService.class);
        configService = mock(ISysConfigService.class);
        redisCache = mock(RedisCache.class);
        asyncManager = mock(AsyncManager.class);
        registerService = new SysRegisterService(userService, configService, redisCache, asyncManager);
    }

    private RegisterBody body(String username, String password, String code, String uuid) {
        RegisterBody b = new RegisterBody();
        b.setUsername(username);
        b.setPassword(password);
        b.setCode(code);
        b.setUuid(uuid);
        return b;
    }

    // ---------- validateCaptcha：直接调用（5 参签名，ip/ua 显式传入） ----------

    @Test
    @DisplayName("验证码校验：uuid 为空 early-reject，抛 CaptchaExpireException 且完全不查 Redis")
    void validateCaptchaRejectsEmptyUuid() {
        try (MockedStatic<AsyncFactory> asyncFactory = mockStatic(AsyncFactory.class);
             MockedStatic<MessageUtils> messageUtils = mockStatic(MessageUtils.class, inv -> "expire-msg")) {
            assertThrows(CaptchaExpireException.class,
                    () -> registerService.validateCaptcha("u", "1234", "", "127.0.0.1", "junit-ua"));
            // early-reject：在查 Redis 之前就抛出，绝不触碰缓存
            verify(redisCache, never()).getCacheObject(anyString());
            verify(redisCache, never()).deleteObject(anyString());
            asyncFactory.verify(() -> AsyncFactory.recordLogininfor("u", Constants.REGISTER, "expire-msg", "127.0.0.1", "junit-ua"));
        }
    }

    @Test
    @DisplayName("验证码校验：缓存缺失(过期)抛 CaptchaExpireException，且 null-check 先于 delete(不浪费无效 RTT)")
    void validateCaptchaExpiresBeforeDelete() {
        when(redisCache.getCacheObject(CacheConstants.CAPTCHA_CODE_KEY + "uuid")).thenReturn(null);
        try (MockedStatic<AsyncFactory> asyncFactory = mockStatic(AsyncFactory.class);
             MockedStatic<MessageUtils> messageUtils = mockStatic(MessageUtils.class, inv -> "expire-msg")) {
            assertThrows(CaptchaExpireException.class,
                    () -> registerService.validateCaptcha("u", "1234", "uuid", "127.0.0.1", "junit-ua"));
            // 顺序修复：key 不存在时不应再执行 delete
            verify(redisCache, never()).deleteObject(anyString());
        }
    }

    @Test
    @DisplayName("验证码校验：答案错误先删除消费验证码，再抛 CaptchaException")
    void validateCaptchaWrongCodeConsumesAndThrows() {
        when(redisCache.getCacheObject(CacheConstants.CAPTCHA_CODE_KEY + "uuid")).thenReturn("abcd");
        try (MockedStatic<AsyncFactory> asyncFactory = mockStatic(AsyncFactory.class);
             MockedStatic<MessageUtils> messageUtils = mockStatic(MessageUtils.class, inv -> "error-msg")) {
            assertThrows(CaptchaException.class,
                    () -> registerService.validateCaptcha("u", "zzzz", "uuid", "127.0.0.1", "junit-ua"));
            // 反暴力语义：错误答案也消费验证码（删除保留）
            verify(redisCache).deleteObject(CacheConstants.CAPTCHA_CODE_KEY + "uuid");
            asyncFactory.verify(() -> AsyncFactory.recordLogininfor("u", Constants.REGISTER, "error-msg", "127.0.0.1", "junit-ua"));
        }
    }

    // ---------- register：驱动全流程（需静态 mock IpUtils/ServletUtils/SecurityUtils） ----------

    private void stubRequestContext(MockedStatic<IpUtils> ipUtils, MockedStatic<ServletUtils> servletUtils) {
        ipUtils.when(IpUtils::getIpAddr).thenReturn("127.0.0.1");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("User-Agent")).thenReturn("junit-ua");
        servletUtils.when(ServletUtils::getRequest).thenReturn(request);
    }

    @Test
    @DisplayName("注册成功：验证码关闭，建号一次并记成功审计")
    void registerSuccessRecordsSuccessAudit() {
        when(configService.selectCaptchaEnabled()).thenReturn(false);
        when(userService.checkUserNameUnique(any())).thenReturn(true);
        when(userService.registerUser(any())).thenReturn(true);

        try (MockedStatic<AsyncFactory> asyncFactory = mockStatic(AsyncFactory.class);
             MockedStatic<MessageUtils> messageUtils = mockStatic(MessageUtils.class, inv -> "success-msg");
             MockedStatic<IpUtils> ipUtils = mockStatic(IpUtils.class);
             MockedStatic<ServletUtils> servletUtils = mockStatic(ServletUtils.class);
             MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            stubRequestContext(ipUtils, servletUtils);
            securityUtils.when(() -> SecurityUtils.encryptPassword(anyString())).thenReturn("hashed");

            String msg = registerService.register(body("newuser", "probe123", null, null));

            assertEquals("", msg);
            verify(userService).registerUser(any());
            asyncFactory.verify(() -> AsyncFactory.recordLogininfor("newuser", Constants.REGISTER, "success-msg", "127.0.0.1", "junit-ua"));
        }
    }

    @Test
    @DisplayName("注册重复名：不建号，记失败审计(账号已存在)")
    void registerDuplicateNameSkipsInsert() {
        when(configService.selectCaptchaEnabled()).thenReturn(false);
        when(userService.checkUserNameUnique(any())).thenReturn(false);

        try (MockedStatic<AsyncFactory> asyncFactory = mockStatic(AsyncFactory.class);
             MockedStatic<MessageUtils> messageUtils = mockStatic(MessageUtils.class, inv -> "msg");
             MockedStatic<IpUtils> ipUtils = mockStatic(IpUtils.class);
             MockedStatic<ServletUtils> servletUtils = mockStatic(ServletUtils.class)) {
            stubRequestContext(ipUtils, servletUtils);

            String msg = registerService.register(body("dupuser", "probe123", null, null));

            assertEquals("保存用户'dupuser'失败，注册账号已存在", msg);
            verify(userService, never()).registerUser(any());
            asyncFactory.verify(() -> AsyncFactory.recordLogininfor("dupuser", Constants.REGISTER,
                    "保存用户'dupuser'失败，注册账号已存在", "127.0.0.1", "junit-ua"));
        }
    }

    @Test
    @DisplayName("注册 DB 异常：记注册异常审计后原样 rethrow")
    void registerDbExceptionAuditsAndRethrows() {
        when(configService.selectCaptchaEnabled()).thenReturn(false);
        when(userService.checkUserNameUnique(any())).thenReturn(true);
        when(userService.registerUser(any())).thenThrow(new RuntimeException("db boom"));

        try (MockedStatic<AsyncFactory> asyncFactory = mockStatic(AsyncFactory.class);
             MockedStatic<MessageUtils> messageUtils = mockStatic(MessageUtils.class, inv -> "msg");
             MockedStatic<IpUtils> ipUtils = mockStatic(IpUtils.class);
             MockedStatic<ServletUtils> servletUtils = mockStatic(ServletUtils.class);
             MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            stubRequestContext(ipUtils, servletUtils);
            securityUtils.when(() -> SecurityUtils.encryptPassword(anyString())).thenReturn("hashed");

            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> registerService.register(body("boomuser", "probe123", null, null)));
            assertEquals("db boom", thrown.getMessage());
            // catch 分支记一次"注册异常"审计后 rethrow，末尾统一审计不再执行（异常已逃逸）
            asyncFactory.verify(() -> AsyncFactory.recordLogininfor("boomuser", Constants.REGISTER,
                    "注册异常：db boom", "127.0.0.1", "junit-ua"));
            asyncFactory.verify(() -> AsyncFactory.recordLogininfor(any(), any(), any(), any(), any()), times(1));
        }
    }

    @Test
    @DisplayName("注册空用户名：不建号，记失败审计(用户名不能为空)")
    void registerEmptyUsernameRecordsFailureAudit() {
        when(configService.selectCaptchaEnabled()).thenReturn(false);

        try (MockedStatic<AsyncFactory> asyncFactory = mockStatic(AsyncFactory.class);
             MockedStatic<MessageUtils> messageUtils = mockStatic(MessageUtils.class, inv -> "msg");
             MockedStatic<IpUtils> ipUtils = mockStatic(IpUtils.class);
             MockedStatic<ServletUtils> servletUtils = mockStatic(ServletUtils.class)) {
            stubRequestContext(ipUtils, servletUtils);

            String msg = registerService.register(body("", "probe123", null, null));

            assertEquals("用户名不能为空", msg);
            verify(userService, never()).registerUser(any());
            asyncFactory.verify(() -> AsyncFactory.recordLogininfor("", Constants.REGISTER,
                    "用户名不能为空", "127.0.0.1", "junit-ua"));
        }
    }
}
