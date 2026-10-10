package com.ruoyi.quartz.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.quartz.domain.SysJobLog;
import com.ruoyi.quartz.service.ISysJobLogService;

/**
 * SysJobLogController 查询判空 + 批量删除委托测试。
 *
 * <p>
 * 覆盖：① getInfo 查回 null 返回 error 而非 success(null)；② remove 现为薄委托——空选/限长校验已
 * 下沉 {@code SysJobLogServiceImpl.deleteJobLogByIds}，Controller 只调用 Service 并透传其 ServiceException。
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
    @DisplayName("remove：委托 Service 删除并返回 success")
    void removeDelegatesToService() {
        ISysJobLogService svc = mock(ISysJobLogService.class);
        when(svc.deleteJobLogByIds(any())).thenReturn(2);
        SysJobLogController controller = new SysJobLogController(svc);

        AjaxResult r = controller.remove(new Long[] { 1L, 2L });
        assertTrue(r.isSuccess());
        verify(svc).deleteJobLogByIds(any());
    }

    @Test
    @DisplayName("remove：Service 抛出的校验异常原样透传（Controller 不吞异常）")
    void removePropagatesServiceException() {
        ISysJobLogService svc = mock(ISysJobLogService.class);
        doThrow(new ServiceException("请选择要删除的调度日志")).when(svc).deleteJobLogByIds(any());
        SysJobLogController controller = new SysJobLogController(svc);

        assertThrows(ServiceException.class, () -> controller.remove(new Long[0]));
    }
}
