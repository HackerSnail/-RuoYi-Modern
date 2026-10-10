package com.ruoyi.framework.aspectj;

import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;
import com.ruoyi.common.annotation.DistributedLock;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;

/**
 * 分布式锁切面：环绕 {@link DistributedLock} 方法，基于 Redisson 可重入锁实现互斥。
 *
 * <p>
 * leaseTime &lt; 0 走 {@code tryLock(waitTime, -1, unit)} 由看门狗自动续期（Redisson 对
 * leaseTime&lt;=0 启用看门狗，与不传 leaseTime 的二参重载等价）；否则走
 * {@code tryLock(waitTime, leaseTime, unit)} 固定持有。仅由当前线程持锁时才解锁，
 * 避免误释放他人锁；获取失败抛出业务异常提示重试。
 * </p>
 *
 * @author ruoyi
 */
@Aspect
@Component
public class DistributedLockAspect {
    private static final Logger log = LoggerFactory.getLogger(DistributedLockAspect.class);

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
                log.warn("获取分布式锁失败, key={}, waitTime={} {}", lockKey, distributedLock.waitTime(), distributedLock.timeUnit());
                throw new ServiceException("请求过于频繁，获取资源锁失败，请稍后重试");
            }
            log.debug("获取分布式锁成功, key={}", lockKey);
            long start = System.nanoTime();
            try {
                return joinPoint.proceed();
            } finally {
                log.debug("分布式锁临界区执行耗时 {} ms, key={}", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), lockKey);
            }
        } finally {
            if (acquired) {
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (Exception e) {
                    // 解锁异常(多为 Redis 连接抖动)不能覆盖业务异常，降级为 warn 记录
                    log.warn("释放分布式锁异常, key={}", lockKey, e);
                }
            }
        }
    }

    /**
     * 按是否配置 leaseTime 选择加锁方式：-1 交由看门狗续期，否则固定租期。
     */
    private boolean doTryLock(RLock lock, DistributedLock annotation) throws InterruptedException {
        TimeUnit unit = annotation.timeUnit();
        if (annotation.leaseTime() < 0) {
            // 显式传 -1：Redisson 对 leaseTime<=0 走看门狗自动续期（与不传 leaseTime 的二参重载等价）
            return lock.tryLock(annotation.waitTime(), -1, unit);
        }
        return lock.tryLock(annotation.waitTime(), annotation.leaseTime(), unit);
    }

    /**
     * 组装锁名：prefix + ":" + key。key 含 '#' 视为 SpEL，用方法形参名绑定实参求值；否则按字面量。
     */
    private String buildLockKey(ProceedingJoinPoint joinPoint, DistributedLock annotation) {
        String rawKey = annotation.key();
        String prefix = annotation.prefix();
        if (StringUtils.isBlank(prefix)) {
            throw new ServiceException("分布式锁 prefix 不能为空，key=" + rawKey);
        }
        String resolvedKey;
        if (rawKey.contains("#")) {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            String[] paramNames = signature.getParameterNames();
            Object[] args = joinPoint.getArgs();
            if (paramNames == null || paramNames.length != args.length) {
                // 参数名不可用/长度不匹配时不能静默跳过，否则 #xxx 解析为 null 会把不同业务塌成同一把锁
                throw new ServiceException("分布式锁 SpEL 解析失败：方法参数名不可用，请确认已开启 -parameters 编译选项或改用字面量 key");
            }
            // 受限上下文：仅只读数据绑定 + 实例方法，禁用类型引用/构造器/Bean 引用，收敛 SpEL 注入面
            SimpleEvaluationContext context = SimpleEvaluationContext
                    .forReadOnlyDataBinding()
                    .withInstanceMethods()
                    .build();
            for (int i = 0; i < paramNames.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
            ExpressionParser parser = new SpelExpressionParser();
            resolvedKey = parser.parseExpression(rawKey).getValue(context, String.class);
        } else {
            resolvedKey = rawKey;
        }
        if (StringUtils.isBlank(resolvedKey)) {
            throw new ServiceException("分布式锁 key 解析结果为空: " + rawKey);
        }
        return prefix + ":" + resolvedKey;
    }
}
