package com.ruoyi.framework.web.service;

import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.exception.user.UserPasswordNotMatchException;
import com.ruoyi.common.exception.user.UserPasswordRetryLimitExceedException;
import com.ruoyi.common.utils.SecurityUtils;

/**
 * 登录密码方法
 * 
 * @author ruoyi
 */
@Component
public class SysPasswordService {
    private final RedisCache redisCache;

    private final int maxRetryCount;

    private final int lockTime;

    public SysPasswordService(RedisCache redisCache,
            @Value("${user.password.maxRetryCount}") int maxRetryCount,
            @Value("${user.password.lockTime}") int lockTime) {
        this.redisCache = redisCache;
        this.maxRetryCount = maxRetryCount;
        this.lockTime = lockTime;
    }

    /**
     * 登录账户密码错误次数缓存键名
     * 
     * @param username 用户名
     * @return 缓存键key
     */
    private String getCacheKey(String username) {
        return CacheConstants.PWD_ERR_CNT_KEY + username;
    }

    /**
     * 密码校验：先查 Redis 中的失败次数是否达上限（锁定），
     * 再用 BCrypt 比对；失败则递增计数并回写（带 lockTime 过期），成功则清除计数。
     * <p>
     * 显式接收 username/rawPassword，不再依赖 {@code AuthenticationContextHolder} ThreadLocal，
     * 便于单测和无上下文环境调用。调用方（{@code UserDetailsServiceImpl}）负责从认证上下文取值并传入。
     *
     * @param username    登录用户名
     * @param rawPassword 用户输入的明文密码
     * @param user        用户实体（提供库中密文）
     */
    public void validate(String username, String rawPassword, SysUser user) {
        Integer retryCount = redisCache.getCacheObject(getCacheKey(username));

        if (retryCount == null) {
            retryCount = 0;
        }

        if (retryCount >= maxRetryCount) {
            throw new UserPasswordRetryLimitExceedException(maxRetryCount, lockTime);
        }

        if (!matches(user, rawPassword)) {
            retryCount = retryCount + 1;
            redisCache.setCacheObject(getCacheKey(username), retryCount, lockTime, TimeUnit.MINUTES);
            throw new UserPasswordNotMatchException();
        } else {
            clearLoginRecordCache(username);
        }
    }

    /**
     * 使用 BCrypt 将明文密码与用户库中密文比对
     */
    public boolean matches(SysUser user, String rawPassword) {
        return SecurityUtils.matchesPassword(rawPassword, user.getPassword());
    }

    /**
     * 屏幕解锁密码校验：复用登录的失败计数与锁定阈值（同一 pwd_err_cnt 键），
     * 防止持有有效 Token 的攻击者对解锁接口无限暴破。
     * <p>
     * 与 {@link #validate(SysUser)} 的区别：validate 服务于登录流程，抛框架自定义的
     * UserPassword*Exception；而本方法供已登录态的解锁调用，统一抛 ServiceException，
     * 由 GlobalExceptionHandler 原样返回 message，保证前端能拿到"密码错误/已锁定"等具体提示。
     * 失败计数达上限、或比对失败都抛异常，成功则清零计数——因此解锁失败也会锁定登录，
     * 属刻意设计：登录与解锁共享同一份密码尝试预算，跨接口暴破同样被限制在 maxRetryCount 次/lockTime 分钟。
     *
     * @param username    当前登录用户名
     * @param rawPassword 待校验的明文密码
     * @param user        用户实体（提供库中密文）
     */
    public void validateUnlock(String username, String rawPassword, SysUser user) {
        Integer retryCount = redisCache.getCacheObject(getCacheKey(username));
        if (retryCount == null) {
            retryCount = 0;
        }
        if (retryCount >= maxRetryCount) {
            throw new ServiceException("密码错误次数过多，账号已锁定，请 " + lockTime + " 分钟后再试");
        }
        if (!matches(user, rawPassword)) {
            retryCount = retryCount + 1;
            redisCache.setCacheObject(getCacheKey(username), retryCount, lockTime, TimeUnit.MINUTES);
            throw new ServiceException("密码错误，请重新输入");
        }
        clearLoginRecordCache(username);
    }

    /**
     * 登录成功后清除该用户的密码错误计数缓存。
     * deleteObject 走 UNLINK，key 不存在时安全返回 false，无需前置 hasKey 浪费 RTT。
     */
    public void clearLoginRecordCache(String loginName) {
        redisCache.deleteObject(getCacheKey(loginName));
    }
}
