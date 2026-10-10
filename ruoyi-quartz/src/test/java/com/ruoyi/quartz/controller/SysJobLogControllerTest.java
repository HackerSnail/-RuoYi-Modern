package com.ruoyi.quartz.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.quartz.domain.SysJobLog;
import com.ruoyi.quartz.service.ISysJobLogService;

/**
 * SysJobLogController 查询判空 + 批量删除限长测试。
 *
 * <p>
 * 覆盖本次修复点：① getInfo 查回 null 返回 error 而非 success(null)；② remove 对空选与超上限
 * （&gt;500）拒绝，且未通过校验时不调用 Service。清空/列表等其余分支不在本轮改动范围。
 * </p>
 */
public class SysJobLogControllerTest {

    @Test
    @DisplayName("getInfo：日志不存在返回 error")
    void getInfoMissing() {
        ISysJobLogService svc = mock(ISysJobLogService.class);
        when(svc.selectJobLogById(999L)).thenReturn(null);
        SysJobLogController controller = new SysJobLogController(svc);

        AjaxResult r = controller.getInfo(999L);
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("getInfo：命中返回 success")
    void getInfoFound() {
        ISysJobLogService svc = mock(ISysJobLogService.class);
        SysJobLog log = new SysJobLog();
        log.setJobLogId(1L);
        when(svc.selectJobLogById(1L)).thenReturn(log);
        SysJobLogController controller = new SysJobLogController(svc);

        AjaxResult r = controller.getInfo(1L);
        assertTrue(r.isSuccess());
    }

    @Test
    @DisplayName("remove：空选拒绝，不调 Service")
    void removeEmptyRejected() {
        ISysJobLogService svc = mock(ISysJobLogService.class);
        SysJobLogController controller = new SysJobLogController(svc);

        AjaxResult r = controller.remove(new Long[0]);
        assertFalse(r.isSuccess());
        verify(svc, never()).deleteJobLogByIds(any());
    }

    @Test
    @DisplayName("remove：超 500 上限拒绝，不调 Service")
    void removeOverLimitRejected() {
        ISysJobLogService svc = mock(ISysJobLogService.class);
        SysJobLogController controller = new SysJobLogController(svc);

        Long[] ids = new Long[501];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = (long) i;
        }
        AjaxResult r = controller.remove(ids);
        assertFalse(r.isSuccess());
        verify(svc, never()).deleteJobLogByIds(any());
    }

    @Test
    @DisplayName("remove：限长内正常删除")
    void removeWithinLimit() {
        ISysJobLogService svc = mock(ISysJobLogService.class);
        when(svc.deleteJobLogByIds(any())).thenReturn(2);
        SysJobLogController controller = new SysJobLogController(svc);

        AjaxResult r = controller.remove(new Long[] { 1L, 2L });
        assertTrue(r.isSuccess());
    }
}
