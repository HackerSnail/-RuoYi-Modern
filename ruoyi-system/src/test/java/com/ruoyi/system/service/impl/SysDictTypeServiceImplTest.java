package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.core.domain.entity.SysDictType;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DictUtils;
import com.ruoyi.system.mapper.SysDictDataMapper;
import com.ruoyi.system.mapper.SysDictTypeMapper;

/**
 * SysDictTypeServiceImpl 单元测试：覆盖改名/新增唯一性 Service 收口、updateDictType 回查判空防 NPE、
 * 以及缓存刷新/清理的 afterCommit 时序（含无活动事务时的内联兜底）。
 * 纯 Mockito；DictUtils 静态以 mockStatic 拦截；事务时序以 mockStatic(TransactionSynchronizationManager) 驱动。
 * init() 的 SET NX 启动锁经 @SpringBootTest 真实启动覆盖（final redisTemplate 字段不便在此 mock）。
 */
class SysDictTypeServiceImplTest {

    private SysDictTypeMapper dictTypeMapper;
    private SysDictDataMapper dictDataMapper;
    private SysDictTypeServiceImpl service;
    private MockedStatic<DictUtils> dictUtils;

    @BeforeEach
    void setUp() {
        dictTypeMapper = mock(SysDictTypeMapper.class);
        dictDataMapper = mock(SysDictDataMapper.class);
        service = new SysDictTypeServiceImpl(dictTypeMapper, dictDataMapper, mock(RedisCache.class));
        dictUtils = mockStatic(DictUtils.class);
    }

    @AfterEach
    void tearDown() {
        dictUtils.close();
    }

    private static SysDictType type(Long id, String name, String dictType) {
        SysDictType t = new SysDictType();
        t.setDictId(id);
        t.setDictName(name);
        t.setDictType(dictType);
        return t;
    }

    // ---------- updateDictType ----------

    @Test
    void updateRejectsDuplicateRenameBeforeTouchingDb() {
        // 目标名 sys_new 被另一个 dictId(9) 占用 → 非唯一 → 立即拒，且不回查/不级联
        when(dictTypeMapper.checkDictTypeUnique("sys_new")).thenReturn(type(9L, "x", "sys_new"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.updateDictType(type(5L, "改名", "sys_new")));
        assertTrue(ex.getMessage().contains("字典类型已存在"));
        verify(dictTypeMapper, never()).selectDictTypeById(any());
        verify(dictDataMapper, never()).updateDictDataType(anyString(), anyString());
    }

    @Test
    void updateThrowsWhenOldDictMissing() {
        when(dictTypeMapper.checkDictTypeUnique("sys_new")).thenReturn(null);
        when(dictTypeMapper.selectDictTypeById(5L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.updateDictType(type(5L, "改名", "sys_new")));
        assertTrue(ex.getMessage().contains("不存在或已被删除"));
        verify(dictDataMapper, never()).updateDictDataType(anyString(), anyString());
        verify(dictTypeMapper, never()).updateDictType(any());
    }

    @Test
    void updateRefreshesCacheInlineWhenNoActiveTx() {
        // 纯单测无 Spring 事务 → isSynchronizationActive()==false → runAfterCommit 走内联兜底
        when(dictTypeMapper.checkDictTypeUnique("sys_new")).thenReturn(null);
        when(dictTypeMapper.selectDictTypeById(5L)).thenReturn(type(5L, "旧", "sys_old"));
        when(dictTypeMapper.updateDictType(any())).thenReturn(1);
        List<SysDictData> full = Collections.singletonList(new SysDictData());
        when(dictDataMapper.selectDictDataByType("sys_new")).thenReturn(full);

        service.updateDictType(type(5L, "改名", "sys_new"));

        // 级联用旧类型名作源
        verify(dictDataMapper, times(1)).updateDictDataType("sys_old", "sys_new");
        dictUtils.verify(() -> DictUtils.setDictCache("sys_new", full), times(1));
    }

    @Test
    void updateDefersCacheUntilCommitWhenTxActive() {
        when(dictTypeMapper.checkDictTypeUnique("sys_new")).thenReturn(null);
        when(dictTypeMapper.selectDictTypeById(5L)).thenReturn(type(5L, "旧", "sys_old"));
        when(dictTypeMapper.updateDictType(any())).thenReturn(1);
        List<SysDictData> full = Collections.singletonList(new SysDictData());
        when(dictDataMapper.selectDictDataByType("sys_new")).thenReturn(full);

        try (MockedStatic<TransactionSynchronizationManager> tx = mockStatic(TransactionSynchronizationManager.class)) {
            tx.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(true);

            service.updateDictType(type(5L, "改名", "sys_new"));

            // 提交前不应写缓存
            dictUtils.verify(() -> DictUtils.setDictCache(anyString(), any()), never());
            ArgumentCaptor<TransactionSynchronization> cap = ArgumentCaptor.forClass(TransactionSynchronization.class);
            tx.verify(() -> TransactionSynchronizationManager.registerSynchronization(cap.capture()));
            // 手动触发提交回调 → 此时才刷新
            cap.getValue().afterCommit();
            dictUtils.verify(() -> DictUtils.setDictCache("sys_new", full), times(1));
        }
    }

    // ---------- insertDictType ----------

    @Test
    void insertRejectsDuplicateType() {
        when(dictTypeMapper.checkDictTypeUnique("sys_new")).thenReturn(type(9L, "x", "sys_new"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.insertDictType(type(null, "新增", "sys_new")));
        assertTrue(ex.getMessage().contains("字典类型已存在"));
        verify(dictTypeMapper, never()).insertDictType(any());
    }

    @Test
    void insertWritesNullPlaceholderWhenUnique() {
        when(dictTypeMapper.checkDictTypeUnique("sys_new")).thenReturn(null);
        when(dictTypeMapper.insertDictType(any())).thenReturn(1);

        service.insertDictType(type(null, "新增", "sys_new"));

        dictUtils.verify(() -> DictUtils.setDictCache("sys_new", null), times(1));
    }

    // ---------- deleteDictTypeByIds ----------

    @Test
    void deleteRejectsAssignedTypeAndSkipsDelete() {
        Long[] ids = new Long[] { 1L };
        when(dictTypeMapper.selectDictTypeByIds(ids)).thenReturn(Collections.singletonList(type(1L, "性别", "sys_x")));
        when(dictDataMapper.selectAssignedDictTypes(any())).thenReturn(Collections.singletonList("sys_x"));
        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteDictTypeByIds(ids));
        assertTrue(ex.getMessage().contains("已分配"));
        verify(dictTypeMapper, never()).deleteDictTypeByIds(any());
    }

    @Test
    void deleteRemovesCacheInlineWhenNoActiveTx() {
        Long[] ids = new Long[] { 1L };
        when(dictTypeMapper.selectDictTypeByIds(ids)).thenReturn(Collections.singletonList(type(1L, "性别", "sys_x")));
        when(dictDataMapper.selectAssignedDictTypes(any())).thenReturn(new ArrayList<>());

        service.deleteDictTypeByIds(ids);

        verify(dictTypeMapper, times(1)).deleteDictTypeByIds(ids);
        dictUtils.verify(() -> DictUtils.removeDictCache("sys_x"), times(1));
    }

    @Test
    void deleteDefersCacheRemovalUntilCommitWhenTxActive() {
        Long[] ids = new Long[] { 1L };
        when(dictTypeMapper.selectDictTypeByIds(ids)).thenReturn(Collections.singletonList(type(1L, "性别", "sys_x")));
        when(dictDataMapper.selectAssignedDictTypes(any())).thenReturn(new ArrayList<>());

        try (MockedStatic<TransactionSynchronizationManager> tx = mockStatic(TransactionSynchronizationManager.class)) {
            tx.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(true);

            service.deleteDictTypeByIds(ids);

            dictUtils.verify(() -> DictUtils.removeDictCache(anyString()), never());
            ArgumentCaptor<TransactionSynchronization> cap = ArgumentCaptor.forClass(TransactionSynchronization.class);
            tx.verify(() -> TransactionSynchronizationManager.registerSynchronization(cap.capture()));
            cap.getValue().afterCommit();
            dictUtils.verify(() -> DictUtils.removeDictCache("sys_x"), times(1));
        }
    }
}
