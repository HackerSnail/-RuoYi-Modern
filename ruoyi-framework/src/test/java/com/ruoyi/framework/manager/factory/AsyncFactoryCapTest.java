package com.ruoyi.framework.manager.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AsyncFactory.cap 纯函数单测（无 Spring）：登录日志入库前的 trim + 按列宽截断。
 *
 * <p>
 * cap 是本轮为 SysLogininfor 写路径加的纵深防御助手——不依赖 Request 上下文/DB，可直接调用。
 * 记录既有 SysLoginServiceTest/SysRegisterServiceTest 均 mockStatic(AsyncFactory)
 * 校验静态调用入参，
 * 不执行返回的 Runnable.run()，故本助手与其互不影响。
 * </p>
 */
public class AsyncFactoryCapTest {

    @Test
    @DisplayName("null 原样返回 null（对应列可空、default ''）")
    void nullReturnsNull() {
        assertNull(AsyncFactory.cap(null, 50));
    }

    @Test
    @DisplayName("去首尾空白；未超列宽保持原文")
    void trimsButKeepsWhenWithinLimit() {
        assertEquals("admin", AsyncFactory.cap("  admin  ", 50));
    }

    @Test
    @DisplayName("超长截断到列宽")
    void truncatesWhenOverLimit() {
        String longMsg = "x".repeat(300);
        String capped = AsyncFactory.cap(longMsg, 255);
        assertEquals(255, capped.length());
        assertEquals("x".repeat(255), capped);
    }

    @Test
    @DisplayName("先 trim 再截断：前导空白不占用列宽额度")
    void trimsBeforeTruncating() {
        // 5 个前导空格 + 60 个字符，列宽 50：trim 后取前 50 个字符（而非含空格的截断）
        String raw = "     " + "y".repeat(60);
        String capped = AsyncFactory.cap(raw, 50);
        assertEquals("y".repeat(50), capped);
    }

    @Test
    @DisplayName("空串与全空白分别返回空串")
    void emptyAndBlank() {
        assertEquals("", AsyncFactory.cap("", 50));
        assertEquals("", AsyncFactory.cap("    ", 50));
    }
}
