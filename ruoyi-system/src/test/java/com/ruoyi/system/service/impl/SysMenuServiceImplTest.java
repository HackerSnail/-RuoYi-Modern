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

import com.ruoyi.common.core.domain.entity.SysMenu;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.SysMenuMapper;
import com.ruoyi.system.mapper.SysRoleMapper;
import com.ruoyi.system.mapper.SysRoleMenuMapper;

/**
 * SysMenuServiceImpl.updateMenuSort 批量排序测试。
 *
 * <p>本次将“拖拽保存排序”从逐行 {@code updateMenuSort}（N 次数据库往返）改为单次
 * {@code updateMenuSortBatch}。测试用 mock mapper 锁定服务契约：ID/序号按序映射进同一条
 * 批量列表、只发起一次批量调用、空输入不触发、以及底层异常被包装为 {@code ServiceException}。
 * （批量 SQL 语句本身的执行需依赖真实数据库，非本单测覆盖范围。）</p>
 */
public class SysMenuServiceImplTest
{
    private SysMenuServiceImpl menuService;

    private SysMenuMapper menuMapper;

    @BeforeEach
    void setUp()
    {
        menuMapper = mock(SysMenuMapper.class);
        menuService = new SysMenuServiceImpl(menuMapper, mock(SysRoleMapper.class), mock(SysRoleMenuMapper.class));
    }

    @Test
    @DisplayName("排序：单次批量调用且 ID/序号按序正确映射")
    @SuppressWarnings("unchecked")
    void updateMenuSortIssuesSingleBatchCall()
    {
        menuService.updateMenuSort(new String[] { "1", "2", "3" }, new String[] { "10", "20", "30" });

        ArgumentCaptor<List<SysMenu>> captor = ArgumentCaptor.forClass(List.class);
        verify(menuMapper, times(1)).updateMenuSortBatch(captor.capture());
        verify(menuMapper, never()).updateMenuSort(any());

        List<SysMenu> list = captor.getValue();
        assertEquals(3, list.size());
        assertEquals(Long.valueOf(1L), list.get(0).getMenuId());
        assertEquals(Integer.valueOf(10), list.get(0).getOrderNum());
        assertEquals(Long.valueOf(2L), list.get(1).getMenuId());
        assertEquals(Integer.valueOf(20), list.get(1).getOrderNum());
        assertEquals(Long.valueOf(3L), list.get(2).getMenuId());
        assertEquals(Integer.valueOf(30), list.get(2).getOrderNum());
    }

    @Test
    @DisplayName("排序：空输入不触发批量调用")
    void updateMenuSortSkipsBatchWhenEmpty()
    {
        menuService.updateMenuSort(new String[] {}, new String[] {});

        verify(menuMapper, never()).updateMenuSortBatch(any());
    }

    @Test
    @DisplayName("排序：底层异常被包装为 ServiceException")
    void updateMenuSortWrapsFailureAsServiceException()
    {
        doThrow(new RuntimeException("db down")).when(menuMapper).updateMenuSortBatch(any());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> menuService.updateMenuSort(new String[] { "1" }, new String[] { "10" }));
        assertEquals("保存排序异常，请联系管理员", ex.getMessage());
    }
}
