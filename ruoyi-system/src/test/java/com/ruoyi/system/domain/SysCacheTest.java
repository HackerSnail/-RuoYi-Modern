package com.ruoyi.system.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SysCache 值语义（equals/hashCode）测试。
 *
 * <p>
 * 身份由 cacheName + cacheKey 决定，cacheValue/remark 不参与；构造器会剥掉 cacheName 的冒号、
 * 并把 cacheKey 的 "cacheName:" 前缀去掉，故相等性基于归一化后的字段。
 * </p>
 */
public class SysCacheTest {

    @Test
    @DisplayName("equals：cacheName+cacheKey 相同则相等，cacheValue 不同不影响；hashCode 一致")
    void equalByValueIgnoringCacheValue() {
        SysCache a = new SysCache("sys_config:", "sys_config:1", "v1");
        SysCache b = new SysCache("sys_config:", "sys_config:1", "v2");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("equals：cacheKey 不同则不等")
    void notEqualByDifferentKey() {
        SysCache a = new SysCache("sys_config:", "sys_config:1", "v");
        SysCache b = new SysCache("sys_config:", "sys_config:2", "v");
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("Set 按值去重（equals/hashCode 生效，非引用比较）")
    void setDeduplicatesByValue() {
        Set<SysCache> set = new HashSet<>();
        set.add(new SysCache("sys_dict:", "sys_dict:1", "a"));
        set.add(new SysCache("sys_dict:", "sys_dict:1", "b"));
        assertEquals(1, set.size());
    }

    @Test
    @DisplayName("equals：null 字段安全（两参构造器 cacheKey 为空串，不同 remark 仍按 name+key 相等）")
    void twoArgEntriesCompareByNameAndEmptyKey() {
        SysCache a = new SysCache("login_tokens:", "用户信息");
        SysCache b = new SysCache("login_tokens:", "登录信息");
        // 2 参构造器 cacheKey 恒为 ""，remark 不参与身份 → 相等
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
