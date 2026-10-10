package com.ruoyi.framework.web.service;

import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.RegisterBody;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.user.CaptchaException;
import com.ruoyi.common.exception.user.CaptchaExpireException;
import java.time.LocalDateTime;
import com.ruoyi.common.utils.MessageUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.ServletUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.framework.manager.AsyncManager;
import com.ruoyi.framework.manager.factory.AsyncFactory;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.ISysUserService;

/**
 * 注册校验方法
 * 
 * @author ruoyi
 */
@Component
public class SysRegisterService {
    private final ISysUserService userService;

    private final ISysConfigService configService;

    private final RedisCache redisCache;

    private final AsyncManager asyncManager;

    public SysRegisterService(ISysUserService userService, ISysConfigService configService, RedisCache redisCache,
            AsyncManager asyncManager) {
        this.userService = userService;
        this.configService = configService;
        this.redisCache = redisCache;
        this.asyncManager = asyncManager;
    }

    /**
     * 注册
     */
    public String register(RegisterBody registerBody) {
        String msg = "", username = registerBody.getUsername(), password = registerBody.getPassword();
        // 请求线程统一抓取 IP/UA，供验证码校验与注册结果审计多处复用；
        // 作为实参 eager 求值后交给异步任务，异步线程只拿到已捕获的值，不会访问已销毁的 Request 上下文
        final String ip = IpUtils.getIpAddr();
        final String userAgent = ServletUtils.getRequest().getHeader("User-Agent");
        SysUser sysUser = new SysUser();
        sysUser.setUserName(username);

        // 验证码开关
        boolean captchaEnabled = configService.selectCaptchaEnabled();
        if (captchaEnabled) {
            validateCaptcha(username, registerBody.getCode(), registerBody.getUuid(), ip, userAgent);
        }

        if (StringUtils.isEmpty(username)) {
            msg = "用户名不能为空";
        } else if (StringUtils.isEmpty(password)) {
            msg = "用户密码不能为空";
        } else if (username.length() < UserConstants.USERNAME_MIN_LENGTH
                || username.length() > UserConstants.USERNAME_MAX_LENGTH) {
            msg = "账户长度必须在2到20个字符之间";
        } else if (password.length() < UserConstants.PASSWORD_MIN_LENGTH
                || password.length() > UserConstants.PASSWORD_MAX_LENGTH) {
            msg = "密码长度必须在5到20个字符之间";
        } else if (!userService.checkUserNameUnique(sysUser)) {
            msg = "保存用户'" + username + "'失败，注册账号已存在";
        } else {
            sysUser.setNickName(username);
            sysUser.setPwdUpdateDate(LocalDateTime.now());
            sysUser.setPassword(SecurityUtils.encryptPassword(password));
            boolean regFlag = false;
            try {
                regFlag = userService.registerUser(sysUser);
            } catch (RuntimeException e) {
                // 唯一索引并发冲突等 DB 异常会抛出（而非返回 false）：记审计后原样抛出，
                // 交由全局异常处理返回 500，避免注册滥用/竞态在审计链路留白
                asyncManager.execute(AsyncFactory.recordLogininfor(username, Constants.REGISTER,
                        "注册异常：" + e.getMessage(), ip, userAgent));
                throw e;
            }
            if (!regFlag) {
                msg = "注册失败,请联系系统管理人员";
            }
        }
        // 统一记录注册结果审计：成功记成功文案，任一校验/入库失败记失败原因（与登录链路的 LOGIN_FAIL 审计对称）
        asyncManager.execute(AsyncFactory.recordLogininfor(username, Constants.REGISTER,
                StringUtils.isEmpty(msg) ? MessageUtils.message("user.register.success") : msg, ip, userAgent));
        return msg;
    }

    /**
     * 校验验证码
     * 
     * @param username 用户名
     * @param code 验证码
     * @param uuid 唯一标识
     * @param ip 请求 IP（供失败审计）
     * @param userAgent 浏览器 UA（供失败审计）
     */
    public void validateCaptcha(String username, String code, String uuid, String ip, String userAgent) {
        // uuid 为空时 early-reject：空 key 永不命中，且会退化成所有请求共用 captcha_code: 的安全隐患
        if (StringUtils.isEmpty(uuid)) {
            asyncManager.execute(AsyncFactory.recordLogininfor(username, Constants.REGISTER,
                    MessageUtils.message("user.jcaptcha.expire"), ip, userAgent));
            throw new CaptchaExpireException();
        }
        String verifyKey = CacheConstants.CAPTCHA_CODE_KEY + uuid;
        String captcha = redisCache.getCacheObject(verifyKey);
        if (captcha == null) {
            asyncManager.execute(AsyncFactory.recordLogininfor(username, Constants.REGISTER,
                    MessageUtils.message("user.jcaptcha.expire"), ip, userAgent));
            throw new CaptchaExpireException();
        }
        // 先确认存在再删除（省一次对不存在 key 的无效 RTT）；无论比对成败都消费验证码——保留错误答案也作废的反暴力语义
        redisCache.deleteObject(verifyKey);
        if (!code.equalsIgnoreCase(captcha)) {
            asyncManager.execute(AsyncFactory.recordLogininfor(username, Constants.REGISTER,
                    MessageUtils.message("user.jcaptcha.error"), ip, userAgent));
            throw new CaptchaException();
        }
    }
}
