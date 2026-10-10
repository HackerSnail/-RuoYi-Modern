package com.ruoyi.quartz.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.quartz.mapper.SysJobLogMapper;

/**
 * SysJobLogServiceImpl.deleteJobLogByIds 批量删除限长测试。
 *
 * <p>
 * 锁定本轮从 Controller 下沉到 Service 的收口：空选与超 500 上限均抛 ServiceException（而非静默
 * 返回 0 触发 {@code IN ()} 语法错误或 toAjax(0) 误报失败），且不触碰 mapper；限长内正常透传删除结果。
 * </p>
 */
public class SysJobLogServiceImplTest {

    @Test
    @DisplayName("deleteJobLogByIds：空选抛 ServiceException，不触碰 mapper")
    void emptyRejected() {
        SysJobLogMapper mapper = mock(SysJobLogMapper.class);
        SysJobLogServiceImpl service = new SysJobLogServiceImpl(mapper);

        assertThrows(ServiceException.class, () -> service.deleteJobLogByIds(new Long[0]));
        verify(mapper, never()).deleteJobLogByIds(any());
    }

    @Test
    @DisplayName("deleteJobLogByIds：超 500 上限抛 ServiceException，不触碰 mapper")
    void overLimitRejected() {
        SysJobLogMapper mapper = mock(SysJobLogMapper.class);
        SysJobLogServiceImpl service = new SysJobLogServiceImpl(mapper);

        Long[] ids = new Long[501];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = (long) i;
        }
        assertThrows(ServiceException.class, () -> service.deleteJobLogByIds(ids));
        verify(mapper, never()).deleteJobLogByIds(any());
    }

    @Test
    @DisplayName("deleteJobLogByIds：限长内透传 mapper 删除结果")
    void withinLimitDelegates() {
        SysJobLogMapper mapper = mock(SysJobLogMapper.class);
        when(mapper.deleteJobLogByIds(any())).thenReturn(2);
        SysJobLogServiceImpl service = new SysJobLogServiceImpl(mapper);

        assertEquals(2, service.deleteJobLogByIds(new Long[] { 1L, 2L }));
    }
}
