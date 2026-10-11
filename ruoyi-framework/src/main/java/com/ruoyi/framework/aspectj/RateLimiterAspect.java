package com.ruoyi.framework.aspectj;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import com.ruoyi.common.annotation.RateLimiter;
import com.ruoyi.common.enums.LimitType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.ip.IpUtils;

/**
 * 限流处理
 *
 * @author HackerSnail <2306002088@qq.com>
 */
@Aspect
@Component
public class RateLimiterAspect {
    private static final Logger log = LoggerFactory.getLogger(RateLimiterAspect.class);

    private final RedisTemplate<String, Object> redisTemplate;

    private final RedisScript<Long> limitScript;

    public RateLimiterAspect(RedisTemplate<String, Object> redisTemplate, RedisScript<Long> limitScript) {
        this.redisTemplate = redisTemplate;
        this.limitScript = limitScript;
    }

    /**
     * 前置限流：执行 Lua 脚本原子计数，超过阈值（或脚本执行异常返回 null）则拒绝请求
     */
    @Before("@annotation(rateLimiter)")
    public void doBefore(JoinPoint point, RateLimiter rateLimiter) throws Throwable {
        int time = rateLimiter.time();
        int count = rateLimiter.count();

        String combineKey = getCombineKey(rateLimiter, point);
        List<String> keys = Collections.singletonList(combineKey);
        try {
            Long number = redisTemplate.execute(limitScript, keys, count, time);
            // 脚本返回滑动窗口内已用量(含本次放行)，达/超阈值时返回 current+1(即 >count)，故用 >count 判拒绝、
            // <=count 放行；勿改成 >=——那会把第 count 次合法请求误拒。number 为 null 表示脚本执行异常，按拒绝处理。
            if (StringUtils.isNull(number) || number.intValue() > count) {
                throw new ServiceException("访问过于频繁，请稍候再试");
            }
            log.debug("限制请求'{}',当前请求'{}',缓存key'{}'", count, number.intValue(), combineKey);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            // 保留真实原因作 cause 并落 error 日志，避免 Redis 抖动/脚本错误/序列化失败被无声吞掉
            log.error("限流脚本执行异常, key:{}", combineKey, e);
            throw new RuntimeException("服务器限流异常，请稍候再试", e);
        }
    }

    /**
     * 拼接限流 key：基础 key + （LimitType.IP 时附加客户端 IP）+ 目标类名+方法名，实现按接口/按 IP 粒度隔离
     */
    public String getCombineKey(RateLimiter rateLimiter, JoinPoint point) {
        StringBuilder stringBuffer = new StringBuilder(rateLimiter.key());
        if (rateLimiter.limitType() == LimitType.IP) {
            stringBuffer.append(IpUtils.getIpAddr()).append("-");
        }
        MethodSignature signature = (MethodSignature) point.getSignature();
        Method method = signature.getMethod();
        // 取实际目标类而非 method.getDeclaringClass()（后者在接口/继承场景可能是父类或接口），与 DataSourceAspect 一致
        Class<?> targetClass = point.getTarget().getClass();
        stringBuffer.append(targetClass.getName()).append("-").append(method.getName());
        return stringBuffer.toString();
    }
}
