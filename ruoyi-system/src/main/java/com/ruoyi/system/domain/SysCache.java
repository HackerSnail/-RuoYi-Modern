package com.ruoyi.system.domain;

import java.util.Objects;

import com.ruoyi.common.utils.StringUtils;

/**
 * 缓存信息
 * <p>
 * 不可变对象：字段全部 final、无 setter、无默认构造，构造完成后状态即固定。
 * 这样 {@code CacheController} 里共享的静态列表即使被外部拿到元素引用，
 * 也无法再把某个条目改坏（unmodifiableList 只能挡结构性修改，挡不住元素内部被改）。
 *
 * @author ruoyi
 */
public final class SysCache {
    /** 缓存名称 */
    private final String cacheName;

    /** 缓存键名 */
    private final String cacheKey;

    /** 缓存内容 */
    private final String cacheValue;

    /** 备注 */
    private final String remark;

    public SysCache(String cacheName, String remark) {
        this(cacheName, "", "", remark);
    }

    /**
     * 以完整 redis key 构建展示行：cacheName 去掉冒号作为分组名，
     * cacheKey 再去掉该前缀只保留区分部分，便于监控页面按名称分组展示
     */
    public SysCache(String cacheName, String cacheKey, String cacheValue) {
        this(StringUtils.replace(cacheName, ":", ""), StringUtils.replace(cacheKey, cacheName, ""), cacheValue, "");
    }

    private SysCache(String cacheName, String cacheKey, String cacheValue, String remark) {
        this.cacheName = cacheName;
        this.cacheKey = cacheKey;
        this.cacheValue = cacheValue;
        this.remark = remark;
    }

    public String getCacheName() {
        return cacheName;
    }

    public String getCacheKey() {
        return cacheKey;
    }

    public String getCacheValue() {
        return cacheValue;
    }

    public String getRemark() {
        return remark;
    }

    /**
     * 值语义：身份由 cacheName + cacheKey 决定（cacheValue/remark 不参与），
     * 使该不可变对象在 Set/Map 或 contains 去重时按值而非按引用比较。null 安全。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SysCache)) {
            return false;
        }
        SysCache that = (SysCache) o;
        return Objects.equals(cacheName, that.cacheName) && Objects.equals(cacheKey, that.cacheKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cacheName, cacheKey);
    }
}
