package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ruoyi.common.core.domain.entity.SysDictType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DictUtils;
import com.ruoyi.system.mapper.SysDictDataMapper;
import com.ruoyi.system.mapper.SysDictTypeMapper;

/**
 * SysDictTypeServiceImpl.deleteDictTypeByIds 批量删除契约测试。
 *
 * <p>本次将“逐行 select+count+delete”（3N 次数据库往返）改为：一次批量查类型、一次批量查已分配类型、
 * 一次批量删除，并加事务。语义改进点：只要所选类型中有任一已分配，则整体不删并抛错（原子），
 * 而不再出现“删到一半才报错”的部分删除。测试用 mock mapper 锁定该契约；{@code DictUtils} 走 Redis
 * 的静态缓存清理用 {@code mockStatic} 隔离。</p>
 */
public class SysDictTypeServiceImplTest
{
    private SysDictTypeServiceImpl dictTypeService;

    private SysDictTypeMapper dictTypeMapper;

    private SysDictDataMapper dictDataMapper;

    @BeforeEach
    void setUp()
    {
        dictTypeMapper = mock(SysDictTypeMapper.class);
        dictDataMapper = mock(SysDictDataMapper.class);
        dictTypeService = new SysDictTypeServiceImpl(dictTypeMapper, dictDataMapper);
    }

    private static SysDictType type(Long id, String dictType, String dictName)
    {
        SysDictType t = new SysDictType();
        t.setDictId(id);
        t.setDictType(dictType);
        t.setDictName(dictName);
        return t;
    }

    @Test
    @DisplayName("删除：全部未分配时单次批量删除并按类型清理缓存")
    void deleteAllUnassignedIssuesSingleBatchDelete()
    {
        Long[] ids = new Long[] { 1L, 2L };
        when(dictTypeMapper.selectDictTypeByIds(ids))
                .thenReturn(new ArrayList<>(Arrays.asList(type(1L, "sys_a", "类型A"), type(2L, "sys_b", "类型B"))));
        when(dictDataMapper.selectAssignedDictTypes(any())).thenReturn(new ArrayList<>());

        try (MockedStatic<DictUtils> dictUtils = mockStatic(DictUtils.class))
        {
            dictTypeService.deleteDictTypeByIds(ids);

            verify(dictTypeMapper, times(1)).deleteDictTypeByIds(ids);
            verify(dictTypeMapper, never()).deleteDictTypeById(any());
            dictUtils.verify(() -> DictUtils.removeDictCache("sys_a"));
            dictUtils.verify(() -> DictUtils.removeDictCache("sys_b"));
        }
    }

    @Test
    @DisplayName("删除：有任一已分配则整体不删并抛 ServiceException（原子语义）")
    void deleteRejectsWhenAnyAssignedAndDeletesNothing()
    {
        Long[] ids = new Long[] { 1L, 2L };
        when(dictTypeMapper.selectDictTypeByIds(ids))
                .thenReturn(new ArrayList<>(Arrays.asList(type(1L, "sys_a", "类型A"), type(2L, "sys_b", "类型B"))));
        List<String> assigned = new ArrayList<>(Arrays.asList("sys_b"));
        when(dictDataMapper.selectAssignedDictTypes(any())).thenReturn(assigned);

        try (MockedStatic<DictUtils> dictUtils = mockStatic(DictUtils.class))
        {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> dictTypeService.deleteDictTypeByIds(ids));
            assertEquals("类型B已分配,不能删除", ex.getMessage());

            verify(dictTypeMapper, never()).deleteDictTypeByIds(any());
            verify(dictTypeMapper, never()).deleteDictTypeById(any());
        }
    }

    @Test
    @DisplayName("删除：空输入直接返回，不触碰任何 mapper")
    void deleteEmptyInputIsNoOp()
    {
        dictTypeService.deleteDictTypeByIds(new Long[] {});

        verify(dictTypeMapper, never()).selectDictTypeByIds(any());
        verify(dictTypeMapper, never()).deleteDictTypeByIds(any());
    }
}
