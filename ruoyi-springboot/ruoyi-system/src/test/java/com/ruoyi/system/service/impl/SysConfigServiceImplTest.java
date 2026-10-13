package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.SysConfig;
import com.ruoyi.system.mapper.SysConfigMapper;

/**
 * SysConfigServiceImpl 缓存/唯一性/NPE/批量原子性 契约测试（纯 mock，不打库）。
 *
 * <p>
 * 锁定本轮 C 档落地：selectConfigByKey 的空值哨兵穿透防护（回源确认不存在则回填短 TTL 哨兵、
 * 命中哨兵直接返回空且不再打 DB）、insert/update 在 Service 层的唯一性纵深防御、updateConfig 回查
 * 判空防 NPE、deleteConfigByIds 先整批预校验再删（内置项/缺失 id 一次性拒绝、不产生部分删除）、
 * 公开读取异常文案剥离用户可控 configKey。
 * </p>
 */
public class SysConfigServiceImplTest {

    private SysConfigMapper configMapper;

    private RedisCache redisCache;

    private SysConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        configMapper = mock(SysConfigMapper.class);
        redisCache = mock(RedisCache.class);
        service = new SysConfigServiceImpl(configMapper, redisCache);
    }

    private static SysConfig cfg(Long id, String key, String value, String type) {
        SysConfig c = new SysConfig();
        c.setConfigId(id);
        c.setConfigKey(key);
        c.setConfigValue(value);
        c.setConfigType(type);
        return c;
    }

    private static String cacheKey(String configKey) {
        return CacheConstants.SYS_CONFIG_KEY + configKey;
    }

    // ---------- #3 缓存穿透：空值哨兵 ----------

    @Test
    @DisplayName("selectConfigByKey 回源命中：写真实值缓存并返回")
    void selectConfigByKeyFoundCachesRealValue() {
        when(redisCache.getCacheObject(anyString())).thenReturn(null);
        when(configMapper.selectConfig(any())).thenReturn(cfg(1L, "k", "v", "N"));

        String result = service.selectConfigByKey("k");

        assertEquals("v", result);
        verify(redisCache).setCacheObject(cacheKey("k"), "v");
    }

    @Test
    @DisplayName("selectConfigByKey 回源不存在：回填短 TTL 空值哨兵")
    void selectConfigByKeyAbsentCachesNullSentinel() {
        when(redisCache.getCacheObject(anyString())).thenReturn(null);
        when(configMapper.selectConfig(any())).thenReturn(null);

        String result = service.selectConfigByKey("missing");

        assertEquals("", result);
        verify(redisCache).setCacheObject(eq(cacheKey("missing")), eq("__CONFIG_NULL__"), eq(60),
                eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("selectConfigByKey 命中空值哨兵：直接返回空且不再打 DB")
    void selectConfigByKeySentinelHitSkipsDb() {
        when(redisCache.getCacheObject(cacheKey("gone"))).thenReturn("__CONFIG_NULL__");

        String result = service.selectConfigByKey("gone");

        assertEquals("", result);
        verify(configMapper, never()).selectConfig(any());
    }

    // ---------- #1 唯一性 Service 层纵深防御 ----------

    @Test
    @DisplayName("insertConfig 键名重复：抛业务异常且不写库")
    void insertConfigRejectsDuplicateKey() {
        // 新增：configId 为 null 视作 -1，库中已存在同键的其它记录 → 非唯一
        when(configMapper.checkConfigKeyUnique("dup")).thenReturn(cfg(9L, "dup", "v", "N"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.insertConfig(cfg(null, "dup", "v", "N")));
        assertTrue(ex.getMessage().contains("参数键名已存在"));
        verify(configMapper, never()).insertConfig(any());
    }

    @Test
    @DisplayName("updateConfig 键名撞他人：抛业务异常且不写库")
    void updateConfigRejectsDuplicateKey() {
        when(configMapper.selectConfigById(1L)).thenReturn(cfg(1L, "mine", "v", "N"));
        // 目标键已被 id=2 占用（!= 本记录 id=1）→ 非唯一
        when(configMapper.checkConfigKeyUnique("taken")).thenReturn(cfg(2L, "taken", "v", "N"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.updateConfig(cfg(1L, "taken", "v", "N")));
        assertTrue(ex.getMessage().contains("参数键名已存在"));
        verify(configMapper, never()).updateConfig(any());
    }

    // ---------- #2 updateConfig 回查判空防 NPE ----------

    @Test
    @DisplayName("updateConfig configId 不存在：抛业务异常而非 NPE")
    void updateConfigThrowsWhenIdMissing() {
        when(configMapper.selectConfigById(404L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.updateConfig(cfg(404L, "k", "v", "N")));
        assertTrue(ex.getMessage().contains("参数不存在"));
        verify(configMapper, never()).updateConfig(any());
    }

    @Test
    @DisplayName("updateConfig 键名变更：清旧键缓存并写新键缓存")
    void updateConfigDeletesOldKeyCacheWhenKeyChanged() {
        when(configMapper.selectConfigById(1L)).thenReturn(cfg(1L, "old", "v", "N"));
        when(configMapper.checkConfigKeyUnique("new")).thenReturn(null);
        when(configMapper.updateConfig(any())).thenReturn(1);

        int row = service.updateConfig(cfg(1L, "new", "v2", "N"));

        assertEquals(1, row);
        verify(redisCache).deleteObject(cacheKey("old"));
        verify(redisCache).setCacheObject(cacheKey("new"), "v2");
    }

    // ---------- #5 deleteConfigByIds 先整批预校验再删 ----------

    @Test
    @DisplayName("deleteConfigByIds 夹内置项：整批拒绝，一条都不删")
    void deleteConfigByIdsRejectsWholeBatchOnBuiltIn() {
        // selectConfigById(服务方法)→ configMapper.selectConfig(config)；按 configId 分流
        when(configMapper.selectConfig(any())).thenAnswer(inv -> {
            SysConfig q = inv.getArgument(0);
            return q.getConfigId() == 2L ? cfg(2L, "builtin", "v", "Y") : cfg(1L, "normal", "v", "N");
        });

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.deleteConfigByIds(new Long[] { 1L, 2L }));
        assertTrue(ex.getMessage().contains("内置参数"));
        // 关键：命中内置项前，排在前面的 id=1 也绝不能被删（原子性）
        verify(configMapper, never()).deleteConfigById(any());
        verify(redisCache, never()).deleteObject(anyString());
    }

    @Test
    @DisplayName("deleteConfigByIds 夹不存在的 id：整批拒绝，一条都不删")
    void deleteConfigByIdsThrowsOnMissingId() {
        when(configMapper.selectConfig(any())).thenAnswer(inv -> {
            SysConfig q = inv.getArgument(0);
            return q.getConfigId() == 2L ? null : cfg(1L, "normal", "v", "N");
        });

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.deleteConfigByIds(new Long[] { 1L, 2L }));
        assertTrue(ex.getMessage().contains("参数不存在"));
        verify(configMapper, never()).deleteConfigById(any());
    }

    @Test
    @DisplayName("deleteConfigByIds 全部合法：逐条删除并清缓存")
    void deleteConfigByIdsHappyPath() {
        when(configMapper.selectConfig(any())).thenReturn(cfg(1L, "k1", "v", "N"));

        service.deleteConfigByIds(new Long[] { 1L });

        verify(configMapper).deleteConfigById(1L);
        verify(redisCache).deleteObject(cacheKey("k1"));
    }

    // ---------- #4 公开读取文案剥离 configKey ----------

    @Test
    @DisplayName("selectPublicConfigByKey 非白名单键：拒绝且文案不回显该键")
    void selectPublicConfigByKeyRejectsWithoutEchoingKey() {
        String probe = "sys.secret.probe<script>";

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.selectPublicConfigByKey(probe));

        // 文案不得含用户可控 configKey（防 XSS / 日志注入回传面）
        assertFalse(ex.getMessage().contains(probe));
        assertFalse(ex.getMessage().contains("probe"));
        assertEquals("参数键不允许通过公开接口读取", ex.getMessage());
    }
}
