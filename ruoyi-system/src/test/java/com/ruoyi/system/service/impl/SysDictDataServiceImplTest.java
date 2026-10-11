package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.core.domain.entity.SysDictType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DictUtils;
import com.ruoyi.system.mapper.SysDictDataMapper;
import com.ruoyi.system.service.ISysDictTypeService;

/**
 * SysDictDataServiceImpl 单元测试：覆盖判空防 NPE、唯一性纵深、类型存在性、两趟删除原子性与缓存刷新语义。
 * 纯 Mockito，DictUtils 静态方法以 mockStatic 拦截，避免依赖 Spring/Redis。
 */
class SysDictDataServiceImplTest {

    private SysDictDataMapper dictDataMapper;
    private ISysDictTypeService dictTypeService;
    private SysDictDataServiceImpl service;
    private MockedStatic<DictUtils> dictUtils;

    @BeforeEach
    void setUp() {
        dictDataMapper = mock(SysDictDataMapper.class);
        dictTypeService = mock(ISysDictTypeService.class);
        service = new SysDictDataServiceImpl(dictDataMapper, dictTypeService);
        dictUtils = mockStatic(DictUtils.class);
    }

    @AfterEach
    void tearDown() {
        dictUtils.close();
    }

    private static SysDictData data(Long code, String type, String value, String label) {
        SysDictData d = new SysDictData();
        d.setDictCode(code);
        d.setDictType(type);
        d.setDictValue(value);
        d.setDictLabel(label);
        return d;
    }

    private static SysDictType typeOf(String type) {
        SysDictType t = new SysDictType();
        t.setDictType(type);
        return t;
    }

    // ---------- insert ----------

    @Test
    void insertRejectsMissingDictType() {
        when(dictTypeService.selectDictTypeByType("ghost")).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.insertDictData(data(null, "ghost", "0", "男")));
        assertTrue(ex.getMessage().contains("字典类型不存在"));
        verify(dictDataMapper, never()).insertDictData(any());
    }

    @Test
    void insertRejectsDuplicateValue() {
        when(dictTypeService.selectDictTypeByType("sys_user_sex")).thenReturn(typeOf("sys_user_sex"));
        // 已存在一条不同 dictCode 的同 type+value
        when(dictDataMapper.checkDictValueUnique("sys_user_sex", "0")).thenReturn(data(99L, "sys_user_sex", "0", "男"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.insertDictData(data(null, "sys_user_sex", "0", "男")));
        assertTrue(ex.getMessage().contains("键值已存在"));
        verify(dictDataMapper, never()).insertDictData(any());
    }

    @Test
    void insertRefreshesCacheWithFullList() {
        when(dictTypeService.selectDictTypeByType("sys_user_sex")).thenReturn(typeOf("sys_user_sex"));
        when(dictDataMapper.checkDictValueUnique("sys_user_sex", "2")).thenReturn(null);
        when(dictDataMapper.insertDictData(any())).thenReturn(1);
        List<SysDictData> full = new ArrayList<>();
        full.add(data(1L, "sys_user_sex", "0", "男"));
        when(dictDataMapper.selectDictDataByType("sys_user_sex")).thenReturn(full);

        service.insertDictData(data(null, "sys_user_sex", "2", "未知"));

        dictUtils.verify(() -> DictUtils.setDictCache("sys_user_sex", full), times(1));
        dictUtils.verify(() -> DictUtils.removeDictCache(anyString()), never());
    }

    // ---------- update ----------

    @Test
    void updateRejectsMissingDictType() {
        when(dictTypeService.selectDictTypeByType("ghost")).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.updateDictData(data(5L, "ghost", "0", "男")));
        assertTrue(ex.getMessage().contains("字典类型不存在"));
        verify(dictDataMapper, never()).updateDictData(any());
    }

    @Test
    void updateRejectsDuplicateValueOfOtherRecord() {
        when(dictTypeService.selectDictTypeByType("sys_user_sex")).thenReturn(typeOf("sys_user_sex"));
        when(dictDataMapper.checkDictValueUnique("sys_user_sex", "1")).thenReturn(data(9L, "sys_user_sex", "1", "女"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.updateDictData(data(5L, "sys_user_sex", "1", "女")));
        assertTrue(ex.getMessage().contains("键值已存在"));
        verify(dictDataMapper, never()).updateDictData(any());
    }

    @Test
    void updateAllowsSelfAndRefreshesCache() {
        when(dictTypeService.selectDictTypeByType("sys_user_sex")).thenReturn(typeOf("sys_user_sex"));
        // 命中的就是自己（dictCode 相同）→ 视为唯一，放行
        when(dictDataMapper.checkDictValueUnique("sys_user_sex", "0")).thenReturn(data(5L, "sys_user_sex", "0", "男"));
        when(dictDataMapper.updateDictData(any())).thenReturn(1);
        List<SysDictData> full = Collections.singletonList(data(5L, "sys_user_sex", "0", "男"));
        when(dictDataMapper.selectDictDataByType("sys_user_sex")).thenReturn(full);

        service.updateDictData(data(5L, "sys_user_sex", "0", "男"));

        verify(dictDataMapper, times(1)).updateDictData(any());
        dictUtils.verify(() -> DictUtils.setDictCache("sys_user_sex", full), times(1));
    }

    // ---------- delete ----------

    @Test
    void deleteEmptyArrayIsNoop() {
        service.deleteDictDataByIds(new Long[0]);
        verify(dictDataMapper, never()).selectDictDataById(any());
        verify(dictDataMapper, never()).deleteDictDataById(any());
    }

    @Test
    void deleteThrowsOnMissingRecordAndDoesNotDeleteAny() {
        when(dictDataMapper.selectDictDataById(1L)).thenReturn(data(1L, "sys_user_sex", "0", "男"));
        when(dictDataMapper.selectDictDataById(2L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.deleteDictDataByIds(new Long[] { 1L, 2L }));
        assertTrue(ex.getMessage().contains("不存在或已被删除"));
        // 预校验失败应整批回滚：一条都不删
        verify(dictDataMapper, never()).deleteDictDataById(any());
    }

    @Test
    void deleteRefreshesCacheOncePerDistinctType() {
        when(dictDataMapper.selectDictDataById(1L)).thenReturn(data(1L, "sys_user_sex", "0", "男"));
        when(dictDataMapper.selectDictDataById(2L)).thenReturn(data(2L, "sys_user_sex", "1", "女"));
        when(dictDataMapper.selectDictDataByType("sys_user_sex"))
                .thenReturn(Collections.singletonList(data(3L, "sys_user_sex", "2", "未知")));

        service.deleteDictDataByIds(new Long[] { 1L, 2L });

        verify(dictDataMapper, times(1)).deleteDictDataById(1L);
        verify(dictDataMapper, times(1)).deleteDictDataById(2L);
        // 同一类型两条 → 只刷一次
        dictUtils.verify(() -> DictUtils.setDictCache(eq("sys_user_sex"), any()), times(1));
    }

    @Test
    void deleteRemovesCacheWhenTypeEmptied() {
        when(dictDataMapper.selectDictDataById(1L)).thenReturn(data(1L, "sys_job_group", "DEFAULT", "默认"));
        when(dictDataMapper.selectDictDataByType("sys_job_group")).thenReturn(new ArrayList<>());

        service.deleteDictDataByIds(new Long[] { 1L });

        dictUtils.verify(() -> DictUtils.removeDictCache("sys_job_group"), times(1));
        dictUtils.verify(() -> DictUtils.setDictCache(anyString(), any()), never());
    }
}
