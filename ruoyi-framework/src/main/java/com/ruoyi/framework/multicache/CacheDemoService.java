package com.ruoyi.framework.multicache;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;
import com.ruoyi.common.annotation.DistributedLock;
import com.ruoyi.common.core.redis.SafeCacheService;

/**
 * 缓存基建演示服务：把 #1 三防、#2 分布式锁、#4 二级缓存的入口收拢于此，
 * 并通过回源计数器（{@link #safeDbHits}/{@link #mlcDbHits}）让"防护是否生效"可被外部观测——
 * 例如对同一不存在的 key 连续请求，若 safeDbHits 只增加 1，即证明空值缓存拦截了穿透。
 *
 * @author ruoyi
 */
@Service
public class CacheDemoService {
    private final SafeCacheService safeCacheService;

    private final MultiLevelCacheService multiLevelCacheService;

    /** SafeCacheService 回源 DB 次数计数器 */
    private final AtomicInteger safeDbHits = new AtomicInteger();

    /** MultiLevelCacheService 回源 DB 次数计数器 */
    private final AtomicInteger mlcDbHits = new AtomicInteger();

    public CacheDemoService(SafeCacheService safeCacheService, MultiLevelCacheService multiLevelCacheService) {
        this.safeCacheService = safeCacheService;
        this.multiLevelCacheService = multiLevelCacheService;
    }

    /**
     * 三防演示：命中不存在返回 null（写空值哨兵），命中存在返回模拟值；每次真正回源 safeDbHits++。
     */
    public String getSafe(String key, boolean existsInDb) {
        return safeCacheService.get("demo:safe:" + key, Duration.ofSeconds(60), 0.2, String.class, () -> {
            safeDbHits.incrementAndGet();
            sleep(30);
            return existsInDb ? ("value-of-" + key) : null;
        });
    }

    /**
     * 二级缓存演示：L1→L2→DB，真正回源时 mlcDbHits++。
     */
    public String getMultiLevel(String key, boolean existsInDb) {
        return multiLevelCacheService.get("demo:" + key, Duration.ofSeconds(120), String.class, () -> {
            mlcDbHits.incrementAndGet();
            sleep(30);
            return existsInDb ? ("mlc-value-of-" + key) : null;
        });
    }

    /**
     * 分布式锁演示：以 taskId 为锁 key（SpEL），waitTime=0 表示拿不到锁立即失败，
     * 用于并发调用同一 taskId 时观察互斥（仅一个成功，其余抛"获取资源锁失败"）。
     */
    @DistributedLock(key = "#taskId", prefix = "demo-lock", waitTime = 0, leaseTime = 10)
    public String doWithLock(String taskId) {
        sleep(500);
        return "locked:" + taskId;
    }

    public int safeDbHits() {
        return safeDbHits.get();
    }

    public int mlcDbHits() {
        return mlcDbHits.get();
    }

    public void resetCounters() {
        safeDbHits.set(0);
        mlcDbHits.set(0);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
