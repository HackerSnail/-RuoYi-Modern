package com.ruoyi.web.controller.demo;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.annotation.RateLimiter;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.redis.RedisBloomFilter;
import com.ruoyi.framework.cache.CacheDemoService;
import com.ruoyi.framework.cache.MultiLevelCacheService;

/**
 * 缓存基建演示接口：把 #1 三防、#2 分布式锁、#4 二级缓存、限流之外的可观测部分
 * 通过简单 HTTP 端点暴露，便于冒烟验证与学习。标注 {@link Anonymous} 免鉴权，仅限演示环境。
 *
 * @author ruoyi
 */
@Anonymous
@RestController
@RequestMapping("/demo/cache")
public class CacheDemoController {
    /** 演示用布隆过滤器：预期 1 万元素、1% 误判率 */
    private static final long BLOOM_EXPECT = 10000L;

    private static final String BLOOM_KEY = "demo:bloom";

    private final CacheDemoService cacheDemoService;

    private final MultiLevelCacheService multiLevelCacheService;

    private final RedisBloomFilter redisBloomFilter;

    public CacheDemoController(CacheDemoService cacheDemoService, MultiLevelCacheService multiLevelCacheService,
            RedisBloomFilter redisBloomFilter) {
        this.cacheDemoService = cacheDemoService;
        this.multiLevelCacheService = multiLevelCacheService;
        this.redisBloomFilter = redisBloomFilter;
    }

    /** 三防演示：连续对同一 key 调用，观察 safeDbHits 是否只 +1（穿透/击穿被拦截） */
    @GetMapping("/safe")
    public AjaxResult safe(@RequestParam String key, @RequestParam(defaultValue = "true") boolean exists) {
        String value = cacheDemoService.getSafe(key, exists);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", key);
        data.put("value", value);
        data.put("dbHits", cacheDemoService.safeDbHits());
        return AjaxResult.success(data);
    }

    /** 二级缓存演示：观察 mlcDbHits 与 Caffeine L1 命中统计 */
    @GetMapping("/mlc")
    public AjaxResult mlc(@RequestParam String key, @RequestParam(defaultValue = "true") boolean exists) {
        String value = cacheDemoService.getMultiLevel(key, exists);
        CacheStats stats = multiLevelCacheService.l1Stats();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", key);
        data.put("value", value);
        data.put("dbHits", cacheDemoService.mlcDbHits());
        data.put("l1HitRate", String.format("%.4f", stats.hitRate()));
        data.put("l1HitCount", stats.hitCount());
        data.put("l1MissCount", stats.missCount());
        return AjaxResult.success(data);
    }

    /** 分布式锁演示：同一 taskId 并发调用，仅一个成功，其余返回"获取资源锁失败" */
    @GetMapping("/lock")
    public AjaxResult lock(@RequestParam String taskId) {
        return AjaxResult.success(cacheDemoService.doWithLock(taskId));
    }

    /** 布隆演示：op=put 写入，op=check 判定可能存在/一定不存在 */
    @GetMapping("/bloom")
    public AjaxResult bloom(@RequestParam String value, @RequestParam(defaultValue = "check") String op) {
        int hashCount = RedisBloomFilter.optimalHashCount(BLOOM_EXPECT, RedisBloomFilter.optimalBitSize(BLOOM_EXPECT, 0.01));
        long bitSize = RedisBloomFilter.optimalBitSize(BLOOM_EXPECT, 0.01);
        if ("put".equalsIgnoreCase(op)) {
            redisBloomFilter.put(BLOOM_KEY, bitSize, hashCount, value);
            return AjaxResult.success("已写入布隆过滤器：" + value);
        }
        boolean mightContain = redisBloomFilter.mightContain(BLOOM_KEY, bitSize, hashCount, value);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("value", value);
        data.put("mightContain", mightContain);
        return AjaxResult.success(data);
    }

    /** 重置回源计数器，便于重复演示 */
    @GetMapping("/reset")
    public AjaxResult reset() {
        cacheDemoService.resetCounters();
        return AjaxResult.success("计数器已重置");
    }

    /**
     * 限流演示：滑动窗口 10s 内最多 3 次，超出后 RateLimiterAspect 拒绝。连续快速请求可观察前 3 次成功、后续报“访问过于频繁”。
     */
    @RateLimiter(time = 10, count = 3)
    @GetMapping("/limit")
    public AjaxResult limit() {
        return AjaxResult.success("ok");
    }
}
