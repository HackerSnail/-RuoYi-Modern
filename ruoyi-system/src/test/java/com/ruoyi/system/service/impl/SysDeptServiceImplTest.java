package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.SysDeptMapper;

/**
 * SysDeptServiceImpl.updateDeptSort 批量排序测试。
 *
 * <p>本次将“保存部门排序”从逐行 {@code updateDeptSort}（N 次数据库往返）改为单次
 * {@code updateDeptSortBatch}。用 mock mapper 锁定服务契约：ID/序号按序映射进同一条批量列表、
 * 只发起一次批量调用、空输入不触发、底层异常包装为 {@code ServiceException}。
 * （CASE 批量 SQL 的执行正确性另以真实 MySQL 事务验证。）</p>
 */
public class SysDeptServiceImplTest
{
    private SysDeptServiceImpl deptService;

    private SysDeptMapper deptMapper;

    @BeforeEach
    void setUp()
    {
        deptService = new SysDeptServiceImpl();
        deptMapper = mock(SysDeptMapper.class);
        ReflectionTestUtils.setField(deptService, "deptMapper", deptMapper);
    }

    @Test
    @DisplayName("排序：单次批量调用且 ID/序号按序正确映射")
    @SuppressWarnings("unchecked")
    void updateDeptSortIssuesSingleBatchCall()
    {
        deptService.updateDeptSort(new String[] { "100", "200", "300" }, new String[] { "1", "2", "3" });

        ArgumentCaptor<List<SysDept>> captor = ArgumentCaptor.forClass(List.class);
        verify(deptMapper, times(1)).updateDeptSortBatch(captor.capture());
        verify(deptMapper, never()).updateDeptSort(any());

        List<SysDept> list = captor.getValue();
        assertEquals(3, list.size());
        assertEquals(Long.valueOf(100L), list.get(0).getDeptId());
        assertEquals(Integer.valueOf(1), list.get(0).getOrderNum());
        assertEquals(Long.valueOf(200L), list.get(1).getDeptId());
        assertEquals(Integer.valueOf(2), list.get(1).getOrderNum());
        assertEquals(Long.valueOf(300L), list.get(2).getDeptId());
        assertEquals(Integer.valueOf(3), list.get(2).getOrderNum());
    }

    @Test
    @DisplayName("排序：空输入不触发批量调用")
    void updateDeptSortSkipsBatchWhenEmpty()
    {
        deptService.updateDeptSort(new String[] {}, new String[] {});

        verify(deptMapper, never()).updateDeptSortBatch(any());
    }

    @Test
    @DisplayName("排序：底层异常被包装为 ServiceException")
    void updateDeptSortWrapsFailureAsServiceException()
    {
        doThrow(new RuntimeException("db down")).when(deptMapper).updateDeptSortBatch(any());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> deptService.updateDeptSort(new String[] { "1" }, new String[] { "1" }));
        assertEquals("保存排序异常，请联系管理员", ex.getMessage());
    }
}
