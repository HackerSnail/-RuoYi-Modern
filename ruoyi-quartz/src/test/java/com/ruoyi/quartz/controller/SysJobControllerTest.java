package com.ruoyi.quartz.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * SysJobController.changeStatus 判空守卫测试。
 *
 * <p>
 * 覆盖本次修复点：changeStatus 对 selectJobById 结果不判空 → 传错/不存在的 jobId 时
 * {@code newJob.setStatus(...)} 直接 NPE 500。验证任务不存在、状态为空两种情况均返回 error
 * 且不再调用 Service，不再解引用 null。
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
}
