package com.ruoyi.web.controller.system;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.service.ISysNoticeReadService;
import com.ruoyi.system.service.ISysNoticeService;

/**
 * SysNoticeController 的 markRead / markReadAll 入口 fail-fast 守卫测试（无 Spring）。
 *
 * <p>
 * 覆盖本轮新增：客户端可控的 noticeId（null/非正数）在入口即拒，且守卫早于 getUserId() 与落库，
 * 故拒绝路径无需 SecurityUtils 上下文即可直测；service 的写方法绝不因非法入参被调用。
 * userId 始终由服务端 getUserId() 取当前登录用户（拒绝路径不触达，放行路径由集成/手工覆盖）。
 * </p>
 */
public class SysNoticeControllerTest {

    private static SysNoticeController controller(ISysNoticeReadService readSvc) {
        return new SysNoticeController(mock(ISysNoticeService.class), readSvc);
    }

    @Test
    @DisplayName("markRead：noticeId 为 null 被拒，不调用 service.markRead")
    void markReadRejectsNullId() {
        ISysNoticeReadService readSvc = mock(ISysNoticeReadService.class);
        AjaxResult r = controller(readSvc).markRead(null);
        assertFalse(r.isSuccess());
        verify(readSvc, never()).markRead(anyLong(), any());
    }

    @Test
    @DisplayName("markRead：noticeId<=0 被拒，不触库")
    void markReadRejectsNonPositiveId() {
        ISysNoticeReadService readSvc = mock(ISysNoticeReadService.class);
        SysNoticeController c = controller(readSvc);
        assertFalse(c.markRead(0L).isSuccess());
        assertFalse(c.markRead(-3L).isSuccess());
        verify(readSvc, never()).markRead(anyLong(), any());
    }

    @Test
    @DisplayName("markReadAll：含非法元素(非数字→null)整批被拒，不调用 markReadBatch")
    void markReadAllRejectsInvalidElement() {
        ISysNoticeReadService readSvc = mock(ISysNoticeReadService.class);
        // Convert.toLongArray("1,abc") → [1, null]，null 元素命中守卫
        AjaxResult r = controller(readSvc).markReadAll("1,abc");
        assertFalse(r.isSuccess());
        verify(readSvc, never()).markReadBatch(any(), any());
    }

    @Test
    @DisplayName("markReadAll：空选直接成功且无副作用（不触 service）")
    void markReadAllEmptyIsNoop() {
        ISysNoticeReadService readSvc = mock(ISysNoticeReadService.class);
        AjaxResult r = controller(readSvc).markReadAll("");
        assertTrue(r.isSuccess());
        verify(readSvc, never()).markReadBatch(any(), any());
    }
}
