package com.ruoyi.framework.multicache;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import com.ruoyi.common.annotation.DistributedLock;
import com.ruoyi.common.core.redis.SafeCacheService;

/**
 * 缓存基建演示服务：把 #1 三防、#2 分布式锁、#4 二级缓存的入口收拢于此，
 * 并通过回源计数器（{@link #safeDbHits}/{@link #mlcDbHits}）让"防护是否生效"可被外部观测——
 * 例如对同一不存在的 key 连续请求，若 safeDbHits 只增加 1，即证明空值缓存拦截了穿透。
 *
 * <p>
 * 本服务（及其 {@code CacheDemoController}）由配置项 {@code demo.cache.enabled=true} 显式开启，
 * 缺省不注册，避免锁/演示用的匿名端点与每实例计数器进入生产；计数器为每 JVM 实例独立，仅供本地演示。
 * </p>
 *
 * @author ruoyi
 */
@Service
@ConditionalOnProperty(value = "demo.cache.enabled", havingValue = "true")
public class CacheDemoService {
    /** 模拟 DB 回源耗时（毫秒），仅演示用，抽出便于统一调整 */
    private static final long SIMULATED_DB_LATENCY_MS = 30L;

    /** 模拟持锁业务耗时（毫秒），仅演示用 */
    private static final long LOCK_SIMULATION_MS = 500L;

    private final SafeCacheService safeCacheService;

    private final MultiLevelCacheService multiLevelCacheService;

    /** SafeCacheService 回源 DB 次数计数器：每 JVM 实例独立，仅供本地演示/冒烟观测，非生产指标 */
    private final AtomicInteger safeDbHits = new AtomicInteger();

    /** MultiLevelCacheService 回源 DB 次数计数器：每 JVM 实例独立，仅供本地演示/冒烟观测，非生产指标 */
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
            sleep(SIMULATED_DB_LATENCY_MS);
            return existsInDb ? ("value-of-" + key) : null;
        });
    }

    /**
     * 二级缓存演示：L1→L2→DB，真正回源时 mlcDbHits++。
     */
    public String getMultiLevel(String key, boolean existsInDb) {
        return multiLevelCacheService.get("demo:" + key, Duration.ofSeconds(120), String.class, () -> {
            mlcDbHits.incrementAndGet();
            sleep(SIMULATED_DB_LATENCY_MS);
            return existsInDb ? ("mlc-value-of-" + key) : null;
        });
    }

    /**
     * 分布式锁演示：以 taskId 为锁 key（SpEL）。waitTime/leaseTime 单位均为「秒」
     * （{@code @DistributedLock.timeUnit} 默认 SECONDS）。waitTime=3 为「阻塞式互斥」——
     * 首个线程持锁约 {@link #LOCK_SIMULATION_MS}ms 后释放，后到线程在 3 秒内排队串行获取并依次成功；
     * 若等待超过 waitTime 仍未拿到锁则抛"获取资源锁失败"。
     * 注意：分布式锁只保证持锁期间的互斥，并不等于幂等——锁释放后的重复请求仍会再次执行业务。
     */
    @DistributedLock(key = "#taskId", prefix = "demo-lock", waitTime = 3, leaseTime = 10)
    public String doWithLock(String taskId) {
        sleep(LOCK_SIMULATION_MS);
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
