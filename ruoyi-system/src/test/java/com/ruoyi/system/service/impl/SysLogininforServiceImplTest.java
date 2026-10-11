package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.SysLogininforMapper;

/**
 * {@link SysLogininforServiceImpl} 单元测试：覆盖清空行数估算(方案C)、批量删除返值校验(建2)、
 * 按天归档入参校验三条本次落地主线。纯 mock mapper，无需 Spring/Redis。
 *
 * @author ruoyi
 */
class SysLogininforServiceImplTest {

    private SysLogininforMapper logininforMapper;
    private SysLogininforServiceImpl service;

    @BeforeEach
    void setUp() {
        logininforMapper = mock(SysLogininforMapper.class);
        service = new SysLogininforServiceImpl(logininforMapper);
    }

    // ---------- cleanLogininfor：truncate 前行数估算，明确为近似值 ----------

    @Test
    void cleanReturnsEstimatedRowsAndStillTruncates() {
        when(logininforMapper.estimateLogininforRows()).thenReturn(1234);

        int result = service.cleanLogininfor();

        assertEquals(1234, result);
        verify(logininforMapper).estimateLogininforRows();
        verify(logininforMapper).cleanLogininfor();
    }

    @Test
    void cleanTreatsNullEstimateAsZero() {
        when(logininforMapper.estimateLogininforRows()).thenReturn(null);

        assertEquals(0, service.cleanLogininfor());
        verify(logininforMapper).cleanLogininfor();
    }

    // ---------- deleteLogininforByIds：空集合守卫 + rows==0 抛异常 ----------

    @Test
    void deleteRejectsEmptyArrayBeforeHittingDb() {
        assertThrows(ServiceException.class, () -> service.deleteLogininforByIds(new Long[0]));
        assertThrows(ServiceException.class, () -> service.deleteLogininforByIds(null));
        verify(logininforMapper, never()).deleteLogininforByIds(anyLongArr());
    }

    @Test
    void deleteThrowsWhenNothingAffected() {
        when(logininforMapper.deleteLogininforByIds(new Long[] {1L, 2L})).thenReturn(0);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.deleteLogininforByIds(new Long[] {1L, 2L}));
        assertTrue(ex.getMessage().contains("已被清空"));
    }

    @Test
    void deleteReturnsAffectedRowsWhenSuccess() {
        when(logininforMapper.deleteLogininforByIds(new Long[] {1L})).thenReturn(1);

        assertEquals(1, service.deleteLogininforByIds(new Long[] {1L}));
    }

    // ---------- cleanLogininforBefore：按天归档入参校验 ----------

    @Test
    void cleanBeforeRejectsNonPositiveDays() {
        assertThrows(ServiceException.class, () -> service.cleanLogininforBefore(0));
        assertThrows(ServiceException.class, () -> service.cleanLogininforBefore(-1));
        verify(logininforMapper, never()).deleteLogininforBefore(anyInt());
    }

    @Test
    void cleanBeforeDelegatesAndReturnsRows() {
        when(logininforMapper.deleteLogininforBefore(180)).thenReturn(77);

        assertEquals(77, service.cleanLogininforBefore(180));
        verify(logininforMapper).deleteLogininforBefore(180);
    }

    private static Long[] anyLongArr() {
        return org.mockito.ArgumentMatchers.any(Long[].class);
    }
}
