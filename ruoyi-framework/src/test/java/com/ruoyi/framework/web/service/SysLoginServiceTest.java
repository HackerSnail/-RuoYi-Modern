package com.ruoyi.framework.web.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.concurrent.ScheduledExecutorService;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.user.BlackListException;
import com.ruoyi.common.exception.user.CaptchaException;
import com.ruoyi.common.exception.user.CaptchaExpireException;
import com.ruoyi.common.exception.user.UserNotExistsException;
import com.ruoyi.common.exception.user.UserPasswordNotMatchException;
import com.ruoyi.common.utils.MessageUtils;
import com.ruoyi.common.utils.ServletUtils;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.framework.manager.factory.AsyncFactory;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.ISysUserService;

/**
 * SysLoginService 登录编排测试。
 *
 * <p>覆盖登录主流程的控制流：验证码开关、前置校验、认证委托与令牌签发。异步日志工厂
 * （{@code AsyncFactory}）、国际化（{@code MessageUtils}）、取 IP（{@code IpUtils}）等静态
 * 协作用 Mockito inline 静态打桩隔离。</p>
 *
 * <p>{@code AsyncManager} 的单例在类加载时会调用 {@code SpringUtils.getBean} 拉取调度线程池，
 * 无法被 mockStatic（类初始化即失败），因此改为在 {@code @BeforeAll} 里向 {@code SpringUtils}
 * 注入一个返回 mock 线程池的 beanFactory，让真实的 {@code AsyncManager} 在无害的 mock 执行器上运行，
 * 从而在不触碰 Spring 容器 / Redis / 数据库的前提下驱动登录全链路。</p>
 */
public class SysLoginServiceTest {
    private SysLoginService loginService;

    private TokenService tokenService;

    private AuthenticationManager authenticationManager;

    private RedisCache redisCache;

    private ISysUserService userService;

    private ISysConfigService configService;

    /**
     * 向 SpringUtils 注入 mock beanFactory，使 AsyncManager 的静态单例可正常初始化。
     */
    @BeforeAll
    static void primeSpringUtils() {
        ConfigurableListableBeanFactory beanFactory = mock(ConfigurableListableBeanFactory.class);
        when(beanFactory.getBean("scheduledExecutorService")).thenReturn(mock(ScheduledExecutorService.class));
        ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", beanFactory);
    }

    @BeforeEach
    void setUp() {
        tokenService = mock(TokenService.class);
        authenticationManager = mock(AuthenticationManager.class);
        redisCache = mock(RedisCache.class);
        userService = mock(ISysUserService.class);
        configService = mock(ISysConfigService.class);

        loginService = new SysLoginService(tokenService, authenticationManager, redisCache, userService, configService);
    }

    private LoginUser adminLoginUser() {
        SysUser user = new SysUser();
        user.setUserName("admin");
        return new LoginUser(1L, 100L, user, new HashSet<>());
    }

    @Test
    @DisplayName("认证成功：委托 TokenService 签发并返回令牌")
    void loginSuccessDelegatesToTokenService() {
        when(configService.selectCaptchaEnabled()).thenReturn(false);

        try (MockedStatic<AsyncFactory> asyncFactory = mockStatic(AsyncFactory.class);
             MockedStatic<MessageUtils> messageUtils = mockStatic(MessageUtils.class, inv -> "msg");
             MockedStatic<IpUtils> ipUtils = mockStatic(IpUtils.class);
             MockedStatic<ServletUtils> servletUtils = mockStatic(ServletUtils.class)) {
            ipUtils.when(IpUtils::getIpAddr).thenReturn("127.0.0.1");
            ipUtils.when(() -> IpUtils.isMatchedIp(any(), any())).thenReturn(false);
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader("User-Agent")).thenReturn("junit-ua");
            servletUtils.when(ServletUtils::getRequest).thenReturn(request);

            LoginUser loginUser = adminLoginUser();
            Authentication authentication = mock(Authentication.class);
            when(authentication.getPrincipal()).thenReturn(loginUser);
            when(authenticationManager.authenticate(any())).thenReturn(authentication);
            when(tokenService.createToken(loginUser)).thenReturn("jwt-token");

            String result = loginService.login("admin", "123456", null, null);

            assertEquals("jwt-token", result);
            verify(tokenService).createToken(loginUser);
            verify(userService).updateLoginInfo(any(), any(), any());
        }
    }

    @Test
    @DisplayName("认证失败（坏凭据）：抛出 UserPasswordNotMatchException")
    void loginBadCredentialsThrows() {
        when(configService.selectCaptchaEnabled()).thenReturn(false);

        try (MockedStatic<AsyncFactory> asyncFactory = mockStatic(AsyncFactory.class);
             MockedStatic<MessageUtils> messageUtils = mockStatic(MessageUtils.class, inv -> "msg");
             MockedStatic<IpUtils> ipUtils = mockStatic(IpUtils.class);
             MockedStatic<ServletUtils> servletUtils = mockStatic(ServletUtils.class)) {
            ipUtils.when(IpUtils::getIpAddr).thenReturn("127.0.0.1");
            ipUtils.when(() -> IpUtils.isMatchedIp(any(), any())).thenReturn(false);
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader("User-Agent")).thenReturn("junit-ua");
            servletUtils.when(ServletUtils::getRequest).thenReturn(request);
            when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

            assertThrows(UserPasswordNotMatchException.class,
                    () -> loginService.login("admin", "123456", null, null));
            verify(tokenService, never()).createToken(any());
        }
    }

    @Test
    @DisplayName("前置校验：空密码直接拒绝")
    void loginPreCheckRejectsBlankPassword() {
        try (MockedStatic<AsyncFactory> asyncFactory = mockStatic(AsyncFactory.class);
             MockedStatic<MessageUtils> messageUtils = mockStatic(MessageUtils.class, inv -> "msg");
             MockedStatic<IpUtils> ipUtils = mockStatic(IpUtils.class);
             MockedStatic<ServletUtils> servletUtils = mockStatic(ServletUtils.class)) {
            ipUtils.when(IpUtils::getIpAddr).thenReturn("127.0.0.1");
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader("User-Agent")).thenReturn("junit-ua");
            servletUtils.when(ServletUtils::getRequest).thenReturn(request);
            assertThrows(UserNotExistsException.class,
                    () -> loginService.loginPreCheck("admin", ""));
        }
    }

    @Test
    @DisplayName("前置校验：IP 命中黑名单抛 BlackListException")
    void loginPreCheckRejectsBlacklistedIp() {
        when(configService.selectConfigByKey("sys.login.blackIPList")).thenReturn("1.2.3.4");

        try (MockedStatic<AsyncFactory> asyncFactory = mockStatic(AsyncFactory.class);
             MockedStatic<MessageUtils> messageUtils = mockStatic(MessageUtils.class, inv -> "msg");
             MockedStatic<IpUtils> ipUtils = mockStatic(IpUtils.class);
             MockedStatic<ServletUtils> servletUtils = mockStatic(ServletUtils.class)) {
            ipUtils.when(IpUtils::getIpAddr).thenReturn("1.2.3.4");
            ipUtils.when(() -> IpUtils.isMatchedIp(any(), any())).thenReturn(true);
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader("User-Agent")).thenReturn("junit-ua");
            servletUtils.when(ServletUtils::getRequest).thenReturn(request);

            assertThrows(BlackListException.class,
                    () -> loginService.loginPreCheck("admin", "123456"));
        }
    }

    @Test
    @DisplayName("recordLoginInfo：委托 userService 更新登录 IP 与时间")
    void recordLoginInfoDelegatesToUserService() {
        try (MockedStatic<IpUtils> ipUtils = mockStatic(IpUtils.class)) {
            ipUtils.when(IpUtils::getIpAddr).thenReturn("10.0.0.1");

            loginService.recordLoginInfo(2L);

            verify(userService).updateLoginInfo(eq(2L), eq("10.0.0.1"), any(LocalDateTime.class));
        }
    }

    @Test
    @DisplayName("验证码关闭时跳过校验，不查 Redis")
    void validateCaptchaSkippedWhenDisabled() {
        when(configService.selectCaptchaEnabled()).thenReturn(false);

        assertDoesNotThrow(() -> loginService.validateCaptcha("admin", "1234", "uuid"));
        verify(redisCache, never()).getCacheObject(anyString());
    }

    @Test
    @DisplayName("验证码错误：抛 CaptchaException 并删除缓存")
    void validateCaptchaRejectsWrongCode() {
        when(configService.selectCaptchaEnabled()).thenReturn(true);
        when(redisCache.getCacheObject(anyString())).thenReturn("abcd");

        try (MockedStatic<AsyncFactory> asyncFactory = mockStatic(AsyncFactory.class);
             MockedStatic<MessageUtils> messageUtils = mockStatic(MessageUtils.class, inv -> "msg");
             MockedStatic<IpUtils> ipUtils = mockStatic(IpUtils.class);
             MockedStatic<ServletUtils> servletUtils = mockStatic(ServletUtils.class)) {
            ipUtils.when(IpUtils::getIpAddr).thenReturn("127.0.0.1");
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader("User-Agent")).thenReturn("junit-ua");
            servletUtils.when(ServletUtils::getRequest).thenReturn(request);
            assertThrows(CaptchaException.class,
                    () -> loginService.validateCaptcha("admin", "zzzz", "uuid"));
            verify(redisCache).deleteObject(CacheConstants.CAPTCHA_CODE_KEY + "uuid");
        }
    }

    @Test
    @DisplayName("验证码缺失（过期）：抛 CaptchaExpireException")
    void validateCaptchaExpiresWhenMissing() {
        when(configService.selectCaptchaEnabled()).thenReturn(true);
        when(redisCache.getCacheObject(anyString())).thenReturn(null);

        try (MockedStatic<AsyncFactory> asyncFactory = mockStatic(AsyncFactory.class);
             MockedStatic<MessageUtils> messageUtils = mockStatic(MessageUtils.class, inv -> "msg");
             MockedStatic<IpUtils> ipUtils = mockStatic(IpUtils.class);
             MockedStatic<ServletUtils> servletUtils = mockStatic(ServletUtils.class)) {
            ipUtils.when(IpUtils::getIpAddr).thenReturn("127.0.0.1");
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader("User-Agent")).thenReturn("junit-ua");
            servletUtils.when(ServletUtils::getRequest).thenReturn(request);
            assertThrows(CaptchaExpireException.class,
                    () -> loginService.validateCaptcha("admin", "1234", "uuid"));
        }
    }
}
