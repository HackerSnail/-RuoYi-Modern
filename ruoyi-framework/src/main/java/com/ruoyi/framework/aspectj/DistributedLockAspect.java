package com.ruoyi.framework.aspectj;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import com.ruoyi.common.annotation.DistributedLock;
import com.ruoyi.common.exception.ServiceException;

/**
 * 分布式锁切面：环绕 {@link DistributedLock} 方法，基于 Redisson 可重入锁实现互斥。
 *
 * <p>leaseTime &lt; 0 走 {@code tryLock(waitTime, unit)} 由看门狗自动续期；否则走
 * {@code tryLock(waitTime, leaseTime, unit)} 固定持有。仅由当前线程持锁时才解锁，
 * 避免误释放他人锁；获取失败抛出业务异常提示重试。</p>
 *
 * @author ruoyi
 */
@Aspect
@Component
public class DistributedLockAspect {
    private final RedissonClient redissonClient;

    public DistributedLockAspect(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        String lockKey = buildLockKey(joinPoint, distributedLock);
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            acquired = doTryLock(lock, distributedLock);
            if (!acquired) {
                throw new ServiceException("请求过于频繁，获取资源锁失败，请稍后重试");
            }
            return joinPoint.proceed();
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 按是否配置 leaseTime 选择加锁方式：-1 交由看门狗续期，否则固定租期。
     */
    private boolean doTryLock(RLock lock, DistributedLock annotation) throws InterruptedException {
        TimeUnit unit = annotation.timeUnit();
        if (annotation.leaseTime() < 0) {
            return lock.tryLock(annotation.waitTime(), unit);
        }
        return lock.tryLock(annotation.waitTime(), annotation.leaseTime(), unit);
    }

    /**
     * 组装锁名：prefix + ":" + key。key 含 '#' 视为 SpEL，用方法形参名绑定实参求值；否则按字面量。
     */
    private String buildLockKey(ProceedingJoinPoint joinPoint, DistributedLock annotation) {
        String rawKey = annotation.key();
        String resolvedKey;
        if (rawKey.contains("#")) {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            String[] paramNames = signature.getParameterNames();
            Object[] args = joinPoint.getArgs();
            StandardEvaluationContext context = new StandardEvaluationContext();
            if (paramNames != null) {
                for (int i = 0; i < paramNames.length && i < args.length; i++) {
                    context.setVariable(paramNames[i], args[i]);
                }
            }
            ExpressionParser parser = new SpelExpressionParser();
            resolvedKey = parser.parseExpression(rawKey).getValue(context, String.class);
        } else {
            resolvedKey = rawKey;
        }
        return annotation.prefix() + ":" + resolvedKey;
    }
}
