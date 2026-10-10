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
import com.ruoyi.quartz.domain.SysJob;
import com.ruoyi.quartz.service.ISysJobService;

/**
 * SysJobController changeStatus 判空 + remove 限长守卫测试。
 *
 * <p>
 * 覆盖本轮修复点：① changeStatus 对 selectJobById 结果不判空 → 传错/不存在的 jobId 时
 * {@code newJob.setStatus(...)} 直接 NPE 500；② remove 与调度日志同一限长口径，空选与
 * 超 500 上限均拒绝且不调 Service。验证两种情况均返回 error、不再解引用 null/不触发批量删除。
 * </p>
 */
public class SysJobControllerTest {

    @Test
    @DisplayName("changeStatus：任务不存在返回 error，而非 NPE")
    void changeStatusMissingJob() throws Exception {
        ISysJobService svc = mock(ISysJobService.class);
        SysJob req = new SysJob();
        req.setJobId(999L);
        req.setStatus("0");
        when(svc.selectJobById(999L)).thenReturn(null);

        SysJobController controller = new SysJobController(svc);
        AjaxResult r = controller.changeStatus(req);

        assertFalse(r.isSuccess());
        verify(svc, never()).changeStatus(any());
    }

    @Test
    @DisplayName("changeStatus：状态为空返回 error，不改库")
    void changeStatusBlankStatus() throws Exception {
        ISysJobService svc = mock(ISysJobService.class);
        SysJob existing = new SysJob();
        existing.setJobId(1L);
        SysJob req = new SysJob();
        req.setJobId(1L);
        req.setStatus("");
        when(svc.selectJobById(1L)).thenReturn(existing);

        SysJobController controller = new SysJobController(svc);
        AjaxResult r = controller.changeStatus(req);

        assertFalse(r.isSuccess());
        verify(svc, never()).changeStatus(any());
    }

    @Test
    @DisplayName("remove：空选拒绝，不调 Service")
    void removeEmptyRejected() throws Exception {
        ISysJobService svc = mock(ISysJobService.class);
        SysJobController controller = new SysJobController(svc);

        AjaxResult r = controller.remove(new Long[0]);
        assertFalse(r.isSuccess());
        verify(svc, never()).deleteJobByIds(any());
    }

    @Test
    @DisplayName("remove：超 500 上限拒绝，不调 Service")
    void removeOverLimitRejected() throws Exception {
        ISysJobService svc = mock(ISysJobService.class);
        SysJobController controller = new SysJobController(svc);

        Long[] ids = new Long[501];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = (long) i;
        }
        AjaxResult r = controller.remove(ids);
        assertFalse(r.isSuccess());
        verify(svc, never()).deleteJobByIds(any());
    }

    @Test
    @DisplayName("remove：限长内正常删除")
    void removeWithinLimit() throws Exception {
        ISysJobService svc = mock(ISysJobService.class);
        SysJobController controller = new SysJobController(svc);

        AjaxResult r = controller.remove(new Long[] { 1L, 2L });
        assertTrue(r.isSuccess());
        verify(svc).deleteJobByIds(any());
    }
}
