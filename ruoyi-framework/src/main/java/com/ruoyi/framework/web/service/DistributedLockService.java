package com.ruoyi.framework.web.service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;

/**
 * 编程式分布式锁服务：注解 {@code @DistributedLock} 之外的手动入口，便于在无法用注解标注、
 * 或需要在同一方法内对不同 key 分段加锁的场景使用。leaseTime&lt;0 时由 Redisson 看门狗自动续期。
 *
 * @author ruoyi
 */
@Service
public class DistributedLockService {
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
            acquired = leaseTime < 0 ? lock.tryLock(waitTime, unit) : lock.tryLock(waitTime, leaseTime, unit);
            if (!acquired) {
                throw new ServiceException("获取资源锁失败，请稍后重试：" + key);
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("获取分布式锁被中断：" + key);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
