package com.ruoyi.quartz.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.quartz.domain.SysJob;
import com.ruoyi.quartz.service.ISysJobService;

/**
 * SysJobController changeStatus 判空 + remove 委托测试。
 *
 * <p>
 * 覆盖：① changeStatus 对 selectJobById 结果不判空 → 传错/不存在的 jobId 时
 * {@code newJob.setStatus(...)} 直接 NPE 500；② remove 现为薄委托——空选/限长校验已下沉
 * {@code SysJobServiceImpl.deleteJobByIds}，Controller 只调用 Service 并透传其 ServiceException
 * （由全局异常处理转友好 error），不再自行预检。
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
    @DisplayName("remove：委托 Service 批量删除并返回 success")
    void removeDelegatesToService() throws Exception {
        ISysJobService svc = mock(ISysJobService.class);
        when(svc.deleteJobByIds(any())).thenReturn(2);
        SysJobController controller = new SysJobController(svc);

        AjaxResult r = controller.remove(new Long[] { 1L, 2L });
        assertTrue(r.isSuccess());
        verify(svc).deleteJobByIds(any());
    }

    @Test
    @DisplayName("remove：Service 抛出的校验异常原样透传（Controller 不吞异常）")
    void removePropagatesServiceException() throws Exception {
        ISysJobService svc = mock(ISysJobService.class);
        doThrow(new ServiceException("请选择要删除的任务")).when(svc).deleteJobByIds(any());
        SysJobController controller = new SysJobController(svc);

        assertThrows(ServiceException.class, () -> controller.remove(new Long[0]));
    }
}
