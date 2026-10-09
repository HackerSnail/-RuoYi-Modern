package com.ruoyi.common.core.redis;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 带"三防"的安全缓存模板：在"读缓存→未命中回源 DB→写回缓存"的通用流程上，
 * 系统性防御缓存穿透、缓存击穿、缓存雪崩三类问题。
 *
 * <ul>
 * <li><b>穿透</b>（查不存在的 key 反复打到 DB）：对"确实不存在"的结果写入空值哨兵 {@link #NULL_SENTINEL}
 * 并配较短 TTL，使后续同一 key 命中哨兵直接返回 null；也可配合 {@link RedisBloomFilter} 提前拦截。</li>
 * <li><b>击穿</b>（热点 key 过期瞬间大量请求同时回源）：回源前用 {@code SETNX} 抢"重建锁"，
 * 仅抢到锁的线程查 DB 并回填，其余线程短暂自旋等待读回填结果，等待超时才降级各自回源，避免惊群。</li>
 * <li><b>雪崩</b>（大批 key 同一时刻集中过期）：写入 TTL 时按 {@code jitterRatio} 叠加随机抖动，
 * 把过期时间点打散，避免同一时刻集中失效。</li>
 * </ul>
 *
 * @author ruoyi
 */
@Component
public class SafeCacheService {
    /** 空值哨兵：代表"已确认 DB 中不存在"，与真实值区分，命中时直接返回 null */
    private static final String NULL_SENTINEL = "@@RUOYI_NULL@@";

    /** 重建锁 key 后缀 */
    private static final String LOCK_SUFFIX = ":rebuild-lock";

    /** 空值哨兵的存活秒数（较短，既挡穿透又不至于让"后来才插入"的数据长期读不到） */
    private static final long NULL_TTL_SECONDS = 30L;

    /** 重建锁自动过期，防止持锁线程宕机导致死锁 */
    private static final Duration LOCK_TTL = Duration.ofSeconds(10L);

    /** 未抢到锁时的自旋重试次数 */
    private static final int WAIT_RETRY_TIMES = 20;

    /** 每次自旋等待间隔（毫秒） */
    private static final long WAIT_INTERVAL_MILLIS = 50L;

    private final RedisTemplate<Object, Object> redisTemplate;

    public SafeCacheService(RedisTemplate<Object, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 读取缓存，未命中则按"三防"流程回源并回填。
     *
     * @param key         缓存键
     * @param baseTtl     基础存活时间（会叠加抖动以打散过期点）
     * @param jitterRatio 抖动比例（0~1），实际 TTL = baseTtl * (1 ± jitterRatio 内的随机值)
     * @param clazz       目标类型（缓存反序列化为通用结构后据此还原）
     * @param dbLoader    回源加载器（返回 null 表示 DB 中确实不存在，将缓存空值哨兵）
     * @param <T>         值类型
     * @return 缓存或回源得到的值；DB 不存在时返回 null
     */
    public <T> T get(final String key, final Duration baseTtl, final double jitterRatio, final Class<T> clazz,
            final Supplier<T> dbLoader) {
        Object cached = redisTemplate.opsForValue().get(key);
        if (NULL_SENTINEL.equals(cached)) {
            // 命中空值哨兵：穿透被拦截，直接返回 null，不打 DB
            return null;
        }
        if (cached != null) {
            return RedisJson.convert(cached, clazz);
        }
        // 缓存未命中，进入回源流程
        return rebuildWithMutex(key, baseTtl, jitterRatio, clazz, dbLoader);
    }

    /**
     * 互斥重建：仅一个线程回源并回填，其余线程等待读回填结果。
     */
    private <T> T rebuildWithMutex(final String key, final Duration baseTtl, final double jitterRatio,
            final Class<T> clazz, final Supplier<T> dbLoader) {
        String lockKey = key + LOCK_SUFFIX;
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL);
        if (Boolean.TRUE.equals(locked)) {
            try {
                // 双重检查：拿到锁后可能已被别的线程回填
                Object existing = redisTemplate.opsForValue().get(key);
                if (existing != null && !NULL_SENTINEL.equals(existing)) {
                    return RedisJson.convert(existing, clazz);
                }
                T value = dbLoader.get();
                if (value == null) {
                    // 回源确认不存在：写空值哨兵（短 TTL）拦截后续穿透
                    redisTemplate.opsForValue().set(key, NULL_SENTINEL, Duration.ofSeconds(NULL_TTL_SECONDS));
                } else {
                    redisTemplate.opsForValue().set(key, value, jitteredTtl(baseTtl, jitterRatio));
                }
                return value;
            } finally {
                // 锁本身只是一个很短的字符串，不存在大 key 风险；统一走 UNLINK 与项目其它删除路径保持同行为，
                // 避免日后锁实现变成集合时回到 DEL 同步回收旧路
                redisTemplate.unlink(lockKey);
            }
        }
        // 未抢到锁：短暂自旋等待持锁线程回填，避免同 key 惊群同时打 DB
        for (int i = 0; i < WAIT_RETRY_TIMES; i++) {
            sleep(WAIT_INTERVAL_MILLIS);
            Object cached = redisTemplate.opsForValue().get(key);
            if (NULL_SENTINEL.equals(cached)) {
                return null;
            }
            if (cached != null) {
                return RedisJson.convert(cached, clazz);
            }
        }
        // 等待超时仍无结果（持锁线程可能异常）：降级为各自回源，保证可用性
        return dbLoader.get();
    }

    /**
     * 计算带随机抖动的 TTL：在 [baseTtl*(1-r), baseTtl*(1+r)] 内取随机秒数，打散集中过期点。
     */
    private Duration jitteredTtl(final Duration baseTtl, final double jitterRatio) {
        long baseSeconds = baseTtl.getSeconds();
        if (baseSeconds <= 1 || jitterRatio <= 0) {
            return baseTtl;
        }
        long span = (long) (baseSeconds * Math.min(jitterRatio, 1.0));
        long jittered = baseSeconds + ThreadLocalRandom.current().nextLong(-span, span + 1);
        return Duration.ofSeconds(Math.max(1L, jittered));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
