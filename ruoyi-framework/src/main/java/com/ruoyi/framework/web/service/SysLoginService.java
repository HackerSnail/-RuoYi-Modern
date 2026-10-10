package com.ruoyi.framework.web.service;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.exception.user.BlackListException;
import com.ruoyi.common.exception.user.CaptchaException;
import com.ruoyi.common.exception.user.CaptchaExpireException;
import com.ruoyi.common.exception.user.UserPasswordNotMatchException;
import java.time.LocalDateTime;
import com.ruoyi.common.utils.MessageUtils;
import com.ruoyi.common.utils.ServletUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.framework.manager.AsyncManager;
import com.ruoyi.framework.manager.factory.AsyncFactory;
import com.ruoyi.framework.security.context.AuthenticationContextHolder;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.ISysUserService;

/**
 * 登录校验方法
 * 
 * @author ruoyi
 */
@Component
public class SysLoginService {
    private static final Logger log = LoggerFactory.getLogger(SysLoginService.class);

    private final TokenService tokenService;

    private final AuthenticationManager authenticationManager;

    private final RedisCache redisCache;

    private final ISysUserService userService;

    private final ISysConfigService configService;

    private final AsyncManager asyncManager;

    public SysLoginService(TokenService tokenService, AuthenticationManager authenticationManager,
            RedisCache redisCache, ISysUserService userService, ISysConfigService configService,
            AsyncManager asyncManager) {
        this.tokenService = tokenService;
        this.authenticationManager = authenticationManager;
        this.redisCache = redisCache;
        this.userService = userService;
        this.configService = configService;
        this.asyncManager = asyncManager;
    }

    /**
     * 登录验证
     * 
     * @param username 用户名
     * @param password 密码
     * @param code 验证码
     * @param uuid 唯一标识
     * @return 结果
     */
    public String login(String username, String password, String code, String uuid) {
        // 在当前（请求）线程提前抓取 IP/UA，传入异步任务，避免任务在线程池执行时再访问已销毁的 Request 上下文
        final String ip = IpUtils.getIpAddr();
        final String userAgent = ServletUtils.getRequest().getHeader("User-Agent");
        // 验证码校验
        validateCaptcha(username, code, uuid, ip, userAgent);
        // 登录前置校验
        loginPreCheck(username, password, ip, userAgent);
        // 用户验证
        Authentication authentication = null;
        try {
            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(username, password);
            AuthenticationContextHolder.setContext(authenticationToken);
            // 该方法会去调用UserDetailsServiceImpl.loadUserByUsername
            authentication = authenticationManager.authenticate(authenticationToken);
        } catch (AuthenticationException e) {
            log.warn("登录认证失败: username={}, type={}", username, e.getClass().getSimpleName(), e);
            asyncManager.execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("user.password.not.match"), ip, userAgent));
            throw new UserPasswordNotMatchException();
        } finally {
            AuthenticationContextHolder.clearContext();
        }
        asyncManager.execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_SUCCESS, MessageUtils.message("user.login.success"), ip, userAgent));
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        recordLoginInfo(loginUser.getUserId(), ip);
        // 生成token
        return tokenService.createToken(loginUser);
    }

    /**
     * 校验验证码
     * 
     * @param username 用户名
     * @param code 验证码
     * @param uuid 唯一标识
     * @return 结果
     */
    void validateCaptcha(String username, String code, String uuid, String ip, String userAgent) {
        boolean captchaEnabled = configService.selectCaptchaEnabled();
        if (captchaEnabled) {
            // uuid 为空时 early-reject（空 key 永不命中，直接走失败分支避免共享退化）
            if (StringUtils.isEmpty(uuid)) {
                asyncManager.execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.expire"), ip, userAgent));
                throw new CaptchaExpireException();
            }
            String verifyKey = CacheConstants.CAPTCHA_CODE_KEY + uuid;
            String captcha = redisCache.getCacheObject(verifyKey);
            if (captcha == null) {
                asyncManager.execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.expire"), ip, userAgent));
                throw new CaptchaExpireException();
            }
            redisCache.deleteObject(verifyKey);
            if (!code.equalsIgnoreCase(captcha)) {
                asyncManager.execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.error"), ip, userAgent));
                throw new CaptchaException();
            }
        }
    }

    /**
     * 登录前置校验
     * @param username 用户名
     * @param password 用户密码
     */
    void loginPreCheck(String username, String password, String ip, String userAgent) {
        // 用户名或密码为空 错误
        if (StringUtils.isEmpty(username) || StringUtils.isEmpty(password)) {
            asyncManager.execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("not.null"), ip, userAgent));
            throw new ServiceException(MessageUtils.message("not.null"));
        }
        // 密码如果不在指定范围内 错误
        if (password.length() < UserConstants.PASSWORD_MIN_LENGTH
                || password.length() > UserConstants.PASSWORD_MAX_LENGTH) {
            asyncManager.execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("user.password.not.match"), ip, userAgent));
            throw new UserPasswordNotMatchException();
        }
        // 用户名不在指定范围内 错误
        if (username.length() < UserConstants.USERNAME_MIN_LENGTH
                || username.length() > UserConstants.USERNAME_MAX_LENGTH) {
            asyncManager.execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("user.password.not.match"), ip, userAgent));
            throw new UserPasswordNotMatchException();
        }
        // IP黑名单校验
        String blackStr = configService.selectConfigByKey("sys.login.blackIPList");
        if (IpUtils.isMatchedIp(blackStr, ip)) {
            asyncManager.execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("login.blocked"), ip, userAgent));
            throw new BlackListException();
        }
    }

    /**
     * 记录登录信息
     *
     * @param userId 用户ID
     */
    void recordLoginInfo(Long userId, String ip) {
        userService.updateLoginInfo(userId, ip, LocalDateTime.now());
    }
}
