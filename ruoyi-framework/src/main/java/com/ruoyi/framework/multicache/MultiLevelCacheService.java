package com.ruoyi.framework.multicache;

import java.time.Duration;
import java.util.function.Supplier;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import com.ruoyi.common.core.redis.RedisJson;

/**
 * 二级缓存服务：L1 本地 Caffeine（进程内、纳秒级、无网络开销）+ L2 分布式 Redis（跨进程共享）。
 *
 * <p>
 * 读路径：L1 命中直接返回 → 未命中查 L2，命中则回填 L1 → 仍未命中回源 DB，同时写 L2 与 L1。
 * 写/失效路径：同时处理两级缓存，并通过 Redis 发布订阅广播 key 失效，使<b>其它节点</b>丢弃其本地 L1，
 * 解决"每台机器各有独立 L1、改一处不影响他处"的一致性难题；本节点 L1 直接同步失效。
 * </p>
 *
 * @author ruoyi
 */
@Service
public class MultiLevelCacheService {
    /** L2 Redis key 统一前缀，与业务其它缓存隔离 */
    private static final String L2_PREFIX = "cache:mlc:";

    /** L1 失效广播频道 */
    public static final String INVALIDATE_CHANNEL = "cache:mlc:invalidate";

    /** L1 最大条目数 */
    private static final int L1_MAX_SIZE = 10000;

    /** L1 写入后存活时间（兜底 TTL，限制跨节点陈旧窗口上界） */
    private static final Duration L1_TTL = Duration.ofMinutes(5);

    /** L1 进程内缓存 */
    private final Cache<String, Object> l1Cache = Caffeine.newBuilder()
            .maximumSize(L1_MAX_SIZE)
            .expireAfterWrite(L1_TTL)
            .recordStats()
            .build();

    private final RedisTemplate<String, Object> redisTemplate;

    private final StringRedisTemplate stringRedisTemplate;

    public MultiLevelCacheService(RedisTemplate<String, Object> redisTemplate,
            StringRedisTemplate stringRedisTemplate) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 读多：L1 → L2 → DB 逐级查找并回填。
     *
     * @param key      逻辑缓存键
     * @param l2Ttl    L2 存活时间
     * @param clazz    目标类型（L2 反序列化为通用结构后据此还原）
     * @param dbLoader 回源加载器
     * @return 值（DB 也不存在时返回 null，不缓存）
     */
    public <T> T get(String key, Duration l2Ttl, Class<T> clazz, Supplier<T> dbLoader) {
        Object value = l1Cache.getIfPresent(key);
        if (value != null) {
            return RedisJson.convert(value, clazz);
        }
        value = redisTemplate.opsForValue().get(L2_PREFIX + key);
        if (value != null) {
            T typed = RedisJson.convert(value, clazz);
            l1Cache.put(key, typed);
            return typed;
        }
        T loaded = dbLoader.get();
        if (loaded != null) {
            redisTemplate.opsForValue().set(L2_PREFIX + key, loaded, l2Ttl);
            l1Cache.put(key, loaded);
        }
        return loaded;
    }

    /**
     * 写：同时更新 L1、L2，并广播失效让其它节点刷新其 L1。
     */
    public <T> void put(String key, T value, Duration l2Ttl) {
        redisTemplate.opsForValue().set(L2_PREFIX + key, value, l2Ttl);
        l1Cache.put(key, value);
        stringRedisTemplate.convertAndSend(INVALIDATE_CHANNEL, key);
    }

    /**
     * 失效：删除 L2、清除本节点 L1，并广播失效。
     * L2 删除走 UNLINK 而非 DEL，避免集合型大 value 同释内存时拖住 Redis 主线程。
     */
    public void evict(String key) {
        redisTemplate.unlink(L2_PREFIX + key);
        l1Cache.invalidate(key);
        stringRedisTemplate.convertAndSend(INVALIDATE_CHANNEL, key);
    }

    /**
     * 收到失效广播（可能来自其它节点）：仅清除本节点 L1，L2 由写/失效方负责。
     * 频道消息体为被失效的逻辑 key。
     */
    public void onInvalidateMessage(String key) {
        l1Cache.invalidate(key);
    }

    /** L1 命中率统计，供监控展示 */
    public com.github.benmanes.caffeine.cache.stats.CacheStats l1Stats() {
        return l1Cache.stats();
    }
}
