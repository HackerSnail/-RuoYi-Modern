package com.ruoyi.framework.web.service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;

/**
 * 编程式分布式锁服务：注解 {@code @DistributedLock} 之外的手动入口，便于在无法用注解标注、
 * 或需要在同一方法内对不同 key 分段加锁的场景使用。leaseTime&lt;0 时由 Redisson 看门狗自动续期。
 *
 * @author HackerSnail <2306002088@qq.com>
 */
@Service
public class DistributedLockService {
    private static final Logger log = LoggerFactory.getLogger(DistributedLockService.class);

    private final RedissonClient redissonClient;

    public DistributedLockService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 在分布式锁保护下执行 action，执行完毕自动解锁。
     *
     * @param key       锁 key
     * @param waitTime  获取锁最长等待时间
     * @param leaseTime 锁持有时间；&lt;0 表示看门狗自动续期
     * @param unit      时间单位
     * @param action    临界区业务逻辑
     * @return 业务执行结果
     */
    public <T> T executeWithLock(String key, long waitTime, long leaseTime, TimeUnit unit, Supplier<T> action) {
        RLock lock = redissonClient.getLock(key);
        boolean acquired = false;
        try {
            // leaseTime<0 走不指定租期的二参 tryLock，由 Redisson 看门狗自动续期（等价 tryLock(waitTime,-1,unit)）
            acquired = leaseTime < 0 ? lock.tryLock(waitTime, unit) : lock.tryLock(waitTime, leaseTime, unit);
            if (!acquired) {
                log.warn("获取分布式锁失败, key={}, waitTime={} {}", key, waitTime, unit);
                throw new ServiceException("获取资源锁失败，请稍后重试：" + key);
            }
            log.debug("获取分布式锁成功, key={}, leaseTime={}", key, leaseTime);
            long start = System.nanoTime();
            try {
                return action.get();
            } finally {
                log.debug("分布式锁临界区执行耗时 {} ms, key={}", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), key);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("获取分布式锁被中断：" + key, e);
        } finally {
            if (acquired) {
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (Exception e) {
                    // 解锁异常(多为 Redis 连接抖动)不能覆盖临界区的业务异常，降级为 warn 记录
                    log.warn("释放分布式锁异常, key={}", key, e);
                }
            }
        }
    }
}
