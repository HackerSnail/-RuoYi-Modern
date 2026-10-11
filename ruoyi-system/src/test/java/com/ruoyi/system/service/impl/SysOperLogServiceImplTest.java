package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.SysOperLog;
import com.ruoyi.system.mapper.SysOperLogMapper;

/**
 * {@link SysOperLogServiceImpl} 单元测试：覆盖本轮三条主线——
 * insertOperlog 失败降级吞异常不外抛(#4)、deleteOperLogByIds 空守卫+批量上限(#2)、
 * cleanOperLogBefore 入参校验+分批循环到 0+总量上限提前结束(#3)。
 * 纯 mock mapper，无需 Spring/Redis；分批用例含真实 Thread.sleep(常量 100ms)，累计 <400ms。
 *
 * @author ruoyi
 */
class SysOperLogServiceImplTest {

    private SysOperLogMapper operLogMapper;

    private SysOperLogServiceImpl service;

    @BeforeEach
    void setUp() {
        operLogMapper = mock(SysOperLogMapper.class);
        service = new SysOperLogServiceImpl(operLogMapper);
    }

    @Test
    @DisplayName("insertOperlog：mapper 抛异常被 catch 吞掉、不外抛（否则会静默进被丢弃的 Future）")
    void insertOperlogSwallowsFailure() {
        doThrow(new RuntimeException("db down")).when(operLogMapper).insertOperlog(any());
        assertDoesNotThrow(() -> service.insertOperlog(new SysOperLog()));
    }

    @Test
    @DisplayName("insertOperlog：正常路径委托 mapper 插入")
    void insertOperlogDelegatesOnSuccess() {
        SysOperLog log = new SysOperLog();
        service.insertOperlog(log);
        verify(operLogMapper).insertOperlog(log);
    }

    @Test
    @DisplayName("deleteOperLogByIds：null 入参被拒，不触库")
    void deleteRejectsNull() {
        assertThrows(ServiceException.class, () -> service.deleteOperLogByIds(null));
        verify(operLogMapper, never()).deleteOperLogByIds(any());
    }

    @Test
    @DisplayName("deleteOperLogByIds：空数组被拒，避免 foreach IN ()")
    void deleteRejectsEmpty() {
        assertThrows(ServiceException.class, () -> service.deleteOperLogByIds(new Long[0]));
        verify(operLogMapper, never()).deleteOperLogByIds(any());
    }

    @Test
    @DisplayName("deleteOperLogByIds：超过单次上限 500 条被拒")
    void deleteRejectsOverBatchLimit() {
        Long[] ids = new Long[501];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = (long) i;
        }
        assertThrows(ServiceException.class, () -> service.deleteOperLogByIds(ids));
        verify(operLogMapper, never()).deleteOperLogByIds(any());
    }

    @Test
    @DisplayName("deleteOperLogByIds：上限内正常删除")
    void deleteProceedsWithinLimit() {
        Long[] ids = new Long[] { 1L, 2L, 3L };
        when(operLogMapper.deleteOperLogByIds(ids)).thenReturn(3);
        assertEquals(3, service.deleteOperLogByIds(ids));
    }

    @Test
    @DisplayName("cleanOperLogBefore：retentionDays<=0 抛异常，不触库")
    void cleanRejectsNonPositiveDays() {
        assertThrows(ServiceException.class, () -> service.cleanOperLogBefore(0));
        assertThrows(ServiceException.class, () -> service.cleanOperLogBefore(-1));
        verify(operLogMapper, never()).deleteOperLogBefore(any(), anyInt());
    }

    @Test
    @DisplayName("cleanOperLogBefore：分批循环到某批返回 0 为止并累加")
    void cleanLoopsUntilZero() {
        when(operLogMapper.deleteOperLogBefore(any(Date.class), anyInt()))
                .thenReturn(5000).thenReturn(3000).thenReturn(0);
        int total = service.cleanOperLogBefore(90);
        assertEquals(8000, total);
        verify(operLogMapper, times(3)).deleteOperLogBefore(any(Date.class), anyInt());
    }

    @Test
    @DisplayName("cleanOperLogBefore：达单轮总量上限(100万)即提前结束，不再无限循环")
    void cleanStopsAtMaxTotal() {
        // 每批返回 60 万：第一次 60万(<100万)继续，第二次累计 120万(>=100万) break
        when(operLogMapper.deleteOperLogBefore(any(Date.class), anyInt())).thenReturn(600000);
        int total = service.cleanOperLogBefore(1);
        assertEquals(1200000, total);
        verify(operLogMapper, times(2)).deleteOperLogBefore(any(Date.class), anyInt());
    }
}
