package com.ruoyi.web.controller.monitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.SysCache;

/**
 * 缓存监控
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
@RestController
@RequestMapping("/monitor/cache")
public class CacheController {
    private static final Logger log = LoggerFactory.getLogger(CacheController.class);

    /**
     * 清空全部缓存的二次确认口令：前端弹窗要求用户输入后随请求回传，后端强校验后才执行。
     * <p>
     * 只靠前端弹窗拦不住脚本误调、请求重试、抓包重放，所以服务端必须再校验一次。
     */
    private static final String CLEAR_ALL_CONFIRM_TOKEN = "DELETE_ALL_CACHE";

    private final RedisTemplate<String, String> redisTemplate;
    /**
     * 工具层的 SCAN 实现：监控页列 键/清理 统一走 RedisCache，不再直提 redisTemplate.keys()
     */
    private final RedisCache redisCache;

    public CacheController(RedisTemplate<String, String> redisTemplate, RedisCache redisCache) {
        this.redisTemplate = redisTemplate;
        this.redisCache = redisCache;
    }

    /**
     * 预置缓存名称与中文说明的映射：key 前缀 -> 业务含义，供前端监控页下拉框展示。
     * 用 unmodifiableList 包一层：字段是 static 的、跨所有请求共享，加锁的代价没必要付，
     * 直接让任何结构性修改（add/remove/set）抛 UnsupportedOperationException，把误操作挡在编译运行期之外。
     */
    private static final List<SysCache> caches = buildCaches();

    private static List<SysCache> buildCaches() {
        List<SysCache> list = new ArrayList<>();
        list.add(new SysCache(CacheConstants.LOGIN_TOKEN_KEY, "用户信息"));
        list.add(new SysCache(CacheConstants.SYS_CONFIG_KEY, "配置信息"));
        list.add(new SysCache(CacheConstants.SYS_DICT_KEY, "数据字典"));
        list.add(new SysCache(CacheConstants.CAPTCHA_CODE_KEY, "验证码"));
        list.add(new SysCache(CacheConstants.REPEAT_SUBMIT_KEY, "防重提交"));
        list.add(new SysCache(CacheConstants.RATE_LIMIT_KEY, "限流处理"));
        list.add(new SysCache(CacheConstants.PWD_ERR_CNT_KEY, "密码错误次数"));
        return Collections.unmodifiableList(list);
    }

    /**
     * 获取 Redis 服务器信息：基本信息 + 库键数量 + 命令统计（拆分为饼图数据）
     * <p>
     * INFO、INFO commandstats、DBSIZE 三条命令合并在一次 execute 内发出：复用同一条连接，
     * 省掉两次连接池借用/归还与往返，监控页定时刷新时开销明显下降。
     */
    @PreAuthorize("@ss.hasPermi('monitor:cache:list')")
    @GetMapping()
    public AjaxResult getInfo() {
        Map<String, Object> result = redisTemplate.execute((RedisCallback<Map<String, Object>>) connection -> {
            Map<String, Object> data = new HashMap<>(3);
            RedisServerCommands serverCommands = connection.serverCommands();

            data.put("info", serverCommands.info());
            data.put("dbSize", serverCommands.dbSize());
            data.put("commandStats",
                    toPieList(serverCommands.info("commandstats")));
            return data;
        });
        return AjaxResult.success(result);
    }

    /**
     * 将 cmdstat_xxx=calls=N,usec=... 形式的命令统计行解析为前端饼图所需的 name/value 列表
     */
    private static List<Map<String, String>> toPieList(Properties commandStats) {
        List<Map<String, String>> pieList = new ArrayList<>();
        if (commandStats == null) {
            return pieList;
        }
        commandStats.stringPropertyNames().forEach(key -> {
            Map<String, String> data = new HashMap<>(2);
            String property = commandStats.getProperty(key);
            data.put("name", key.startsWith("cmdstat_") ? key.substring(8) : key);
            data.put("value", StringUtils.substringBetween(property, "calls=", ",usec"));
            pieList.add(data);
        });
        return pieList;
    }

    @PreAuthorize("@ss.hasPermi('monitor:cache:list')")
    @GetMapping("/getNames")
    public AjaxResult cache() {
        return AjaxResult.success(caches);
    }

    /**
     * 按缓存名前缀列出对应的所有缓存键（已去重排序）。
     * 实现从 KEYS 换为 SCAN，避免大库主线程阻塞。
     */
    @PreAuthorize("@ss.hasPermi('monitor:cache:list')")
    @GetMapping("/getKeys/{cacheName}")
    public AjaxResult getCacheKeys(@PathVariable String cacheName) {
        Set<String> cacheKeys = redisCache.keys(cacheName + "*");
        return AjaxResult.success(new TreeSet<>(cacheKeys));
    }

    @PreAuthorize("@ss.hasPermi('monitor:cache:list')")
    @GetMapping("/getValue/{cacheName}/{cacheKey}")
    public AjaxResult getCacheValue(@PathVariable String cacheName, @PathVariable String cacheKey) {
        String cacheValue = redisTemplate.opsForValue().get(cacheKey);
        SysCache sysCache = new SysCache(cacheName, cacheKey, cacheValue);
        return AjaxResult.success(sysCache);
    }

    /**
     * 清空指定缓存名下的所有键：SCAN 拉键 + 分批 UNLINK，对 Redis 主线程无阻塞。
     */
    @PreAuthorize("@ss.hasPermi('monitor:cache:list')")
    @DeleteMapping("/clearCacheName/{cacheName}")
    public AjaxResult clearCacheName(@PathVariable String cacheName) {
        long removed = redisCache.deleteKeysByPattern(cacheName + "*");
        return AjaxResult.success("已清理 " + removed + " 个键");
    }

    @PreAuthorize("@ss.hasPermi('monitor:cache:list')")
    @DeleteMapping("/clearCacheKey/{cacheKey}")
    public AjaxResult clearCacheKey(@PathVariable String cacheKey) {
        // 统一走 RedisCache.deleteObject → UNLINK，大 key 不阻塞主线程
        redisCache.deleteObject(cacheKey);
        return AjaxResult.success();
    }

    /**
     * 清空所有缓存（高危操作）：SCAN + 分批 UNLINK。
     * <p>
     * 危险点：一次操作清掉整个 db 的键——在线用户全部掉线、字典/配置/限流/防重计数全部失效，
     * 极易引发缓存雪崩把数据库打满，且其它共用该实例的业务也会被误清。因此加了三重拦截：
     * <ol>
     * <li>前端弹窗要求输入确认口令后才发请求（本仓库不含前端，需同步改造监控页）；</li>
     * <li>后端强校验确认口令 {@value #CLEAR_ALL_CONFIRM_TOKEN}，不匹配直接拒绝，
     * 挡住脚本误调、请求重试与抓包重放；</li>
     * <li>限超级管理员执行，普通管理员即使有 monitor:cache:list 权限也调不动。</li>
     * </ol>
     * 若该能力不需要，直接删掉本方法即可彻底下线，按前缀清理的
     * {@code /clearCacheName/{cacheName}} 与单键清理不受影响。
     *
     * @param confirm 前端二次确认口令，必须等于 {@value #CLEAR_ALL_CONFIRM_TOKEN}
     */
    @PreAuthorize("@ss.hasPermi('monitor:cache:list')")
    @DeleteMapping("/clearCacheAll")
    public AjaxResult clearCacheAll(
            @RequestParam(name = "confirm", required = false, defaultValue = "") String confirm) {
        if (!CLEAR_ALL_CONFIRM_TOKEN.equals(confirm)) {
            return AjaxResult.error("二次确认口令错误，已拒绝清空全部缓存");
        }
        if (!SecurityUtils.isAdmin()) {
            return AjaxResult.error("清空全部缓存仅限超级管理员操作");
        }
        long removed = redisCache.deleteKeysByPattern("*");
        log.warn("【高危】用户[{}]清空了全部缓存，共删除 {} 个键", SecurityUtils.getUsername(), removed);
        return AjaxResult.success("已清理 " + removed + " 个键");
    }
}
