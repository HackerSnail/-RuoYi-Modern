package com.ruoyi.common.constant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 字典值常量契约测试。
 *
 * <p>这些常量对应数据库中固定的字典取值（状态、可见性、并发等）。后续"消灭魔数"会把散落在
 * 业务代码里的裸字符串（"0"/"1"）统一替换为这里的常量；本测试锁定每个常量的字面量取值，
 * 保证替换前后语义等价，防止某个分支被替换成不一致的值。</p>
 */
public class DictValueConstantsTest
{
    @Test
    @DisplayName("字典数据正常状态值为 0")
    void dictNormalIsZero()
    {
        assertEquals("0", UserConstants.DICT_NORMAL);
    }

    @Test
    @DisplayName("菜单显示/隐藏值分别为 0/1")
    void menuVisibilityValues()
    {
        assertEquals("0", UserConstants.MENU_SHOW);
        assertEquals("1", UserConstants.MENU_HIDE);
    }

    @Test
    @DisplayName("定时任务允许并发值为 0")
    void concurrentAllowIsZero()
    {
        assertEquals("0", ScheduleConstants.CONCURRENT_ALLOW);
    }

    @Test
    @DisplayName("菜单不缓存值为 1")
    void menuCacheNoIsOne()
    {
        assertEquals("1", UserConstants.MENU_CACHE_NO);
    }

    @Test
    @DisplayName("顶级祖级列表值为 0")
    void rootAncestorsIsZero()
    {
        assertEquals("0", UserConstants.ROOT_ANCESTORS);
    }

    @Test
    @DisplayName("生成器列标志“是”值为 1")
    void columnFlagYesIsOne()
    {
        assertEquals("1", GenConstants.COLUMN_FLAG_YES);
    }
}
