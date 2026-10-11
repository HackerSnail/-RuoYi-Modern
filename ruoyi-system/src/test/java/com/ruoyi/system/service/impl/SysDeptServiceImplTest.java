package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.system.mapper.SysDeptMapper;
import com.ruoyi.system.mapper.SysRoleMapper;

/**
 * SysDeptServiceImpl 服务层契约测试（纯 mock，不打库）。
 *
 * <p>
 * 锁定本轮 C 档落地：数据权限收口到 Service（selectDeptById/insertDept/updateDept/deleteDeptById 各自
 * checkDeptDataScope，不再仅依赖 Controller“记得调用”）、insertDept 父节点判空防 NPE、updateDept 环检测 +
 * 新父判空拒绝（不再静默写脏 ancestors）+ 移除无条件“复活祖级”副作用、selectDeptListByRoleId 角色判空、
 * updateDeptSort 长度校验 + 逐部门数据权限（权限异常不被兜成通用文案）。
 * </p>
 *
 * <p>
 * 默认 mock {@code SecurityUtils.isAdmin()} 返 true 令 checkDeptDataScope 短路，聚焦业务护栏；越权类用例在
 * 方法内改判为 false 并 mock {@code SpringUtils.getAopProxy} 回传本实例，驱动 @DataScope 的可见性判定。
 * </p>
 */
public class SysDeptServiceImplTest {
    private SysDeptMapper deptMapper;

    private SysRoleMapper roleMapper;

    private SysDeptServiceImpl deptService;

    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUp() {
        deptMapper = mock(SysDeptMapper.class);
        roleMapper = mock(SysRoleMapper.class);
        deptService = new SysDeptServiceImpl(deptMapper, roleMapper);
        // 默认超管：checkDeptDataScope 短路，让用例聚焦各业务护栏本身
        securityUtils = mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::isAdmin).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    private static SysDept dept(Long id, Long parentId, String ancestors, String status) {
        SysDept d = new SysDept();
        d.setDeptId(id);
        d.setParentId(parentId);
        d.setAncestors(ancestors);
        d.setStatus(status);
        return d;
    }

    /** 非超管 + getAopProxy 回传本实例 + selectDeptList 返空 → checkDeptDataScope 视为越权拒绝。 */
    private void asOutOfScopeNonAdmin() {
        securityUtils.when(SecurityUtils::isAdmin).thenReturn(false);
        mockSpringUtilsThis();
        when(deptMapper.selectDeptList(any())).thenReturn(new ArrayList<>());
    }

    private MockedStatic<SpringUtils> mockSpringUtilsThis() {
        MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class);
        spring.when(() -> SpringUtils.getAopProxy(any())).thenAnswer(inv -> inv.getArgument(0));
        return spring;
    }

    // ---------- 既有：updateDeptSort 批量单次调用 ----------

    @Test
    @DisplayName("排序：单次批量调用且 ID/序号按序正确映射")
    @SuppressWarnings("unchecked")
    void updateDeptSortIssuesSingleBatchCall() {
        deptService.updateDeptSort(new String[] { "100", "200", "300" }, new String[] { "1", "2", "3" });

        ArgumentCaptor<List<SysDept>> captor = ArgumentCaptor.forClass(List.class);
        verify(deptMapper, times(1)).updateDeptSortBatch(captor.capture());
        verify(deptMapper, never()).updateDeptSort(any());

        List<SysDept> list = captor.getValue();
        assertEquals(3, list.size());
        assertEquals(Long.valueOf(100L), list.get(0).getDeptId());
        assertEquals(Integer.valueOf(1), list.get(0).getOrderNum());
    }

    @Test
    @DisplayName("排序：空输入不触发批量调用")
    void updateDeptSortSkipsBatchWhenEmpty() {
        deptService.updateDeptSort(new String[] {}, new String[] {});

        verify(deptMapper, never()).updateDeptSortBatch(any());
    }

    @Test
    @DisplayName("排序：底层异常被包装为 ServiceException")
    void updateDeptSortWrapsFailureAsServiceException() {
        doThrow(new RuntimeException("db down")).when(deptMapper).updateDeptSortBatch(any());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> deptService.updateDeptSort(new String[] { "1" }, new String[] { "1" }));
        assertEquals("保存排序异常，请联系管理员", ex.getMessage());
    }

    // ---------- #7 排序长度校验 + 数据权限 ----------

    @Test
    @DisplayName("排序：deptIds 与 orderNums 长度不一致直接拒绝")
    void updateDeptSortRejectsLengthMismatch() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> deptService.updateDeptSort(new String[] { "1", "2" }, new String[] { "1" }));
        assertTrue(ex.getMessage().contains("数量不一致"));
        verify(deptMapper, never()).updateDeptSortBatch(any());
    }

    @Test
    @DisplayName("排序：非超管越权部门被拒，且不被兜成通用'保存排序异常'")
    void updateDeptSortRejectsOutOfScopeNotSwallowed() {
        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class)) {
            spring.when(() -> SpringUtils.getAopProxy(any())).thenAnswer(inv -> inv.getArgument(0));
            securityUtils.when(SecurityUtils::isAdmin).thenReturn(false);
            when(deptMapper.selectDeptList(any())).thenReturn(new ArrayList<>());

            ServiceException ex = assertThrows(ServiceException.class,
                    () -> deptService.updateDeptSort(new String[] { "500" }, new String[] { "1" }));
            assertTrue(ex.getMessage().contains("没有权限访问部门"));
            assertTrue(!ex.getMessage().contains("保存排序异常"));
            verify(deptMapper, never()).updateDeptSortBatch(any());
        }
    }

    // ---------- #2 insertDept 父判空 ----------

    @Test
    @DisplayName("insertDept：父节点不存在抛业务异常而非 NPE")
    void insertDeptRejectsMissingParent() {
        when(deptMapper.selectDeptById(2L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> deptService.insertDept(dept(null, 2L, null, "0")));
        assertTrue(ex.getMessage().contains("上级部门不存在"));
        verify(deptMapper, never()).insertDept(any());
    }

    @Test
    @DisplayName("insertDept：父节点被停用拒绝新增")
    void insertDeptRejectsDisabledParent() {
        when(deptMapper.selectDeptById(2L)).thenReturn(dept(2L, 1L, "0,100", "1"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> deptService.insertDept(dept(null, 2L, null, "0")));
        assertTrue(ex.getMessage().contains("部门停用"));
        verify(deptMapper, never()).insertDept(any());
    }

    // ---------- #1 selectDeptById 数据权限收口 ----------

    @Test
    @DisplayName("selectDeptById：非超管越权先被拒，不到 mapper")
    void selectDeptByIdRejectsOutOfScope() {
        asOutOfScopeNonAdmin();

        ServiceException ex = assertThrows(ServiceException.class, () -> deptService.selectDeptById(999L));
        assertTrue(ex.getMessage().contains("没有权限访问部门"));
        verify(deptMapper, never()).selectDeptById(anyLong());
    }

    // ---------- #3 updateDept 环检测/脏 ancestors/新父判空 ----------

    @Test
    @DisplayName("updateDept：自身不存在抛业务异常")
    void updateDeptRejectsMissingSelf() {
        when(deptMapper.selectDeptById(404L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> deptService.updateDept(dept(404L, 100L, null, "0")));
        assertTrue(ex.getMessage().contains("部门不存在"));
        verify(deptMapper, never()).updateDept(any());
    }

    @Test
    @DisplayName("updateDept：新父不存在拒绝，不静默写脏 ancestors")
    void updateDeptRejectsMissingNewParent() {
        when(deptMapper.selectDeptById(200L)).thenReturn(dept(200L, 100L, "0,100", "0"));
        when(deptMapper.selectDeptById(999L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> deptService.updateDept(dept(200L, 999L, "0,100", "0")));
        assertTrue(ex.getMessage().contains("上级部门不存在"));
        verify(deptMapper, never()).updateDept(any());
    }

    @Test
    @DisplayName("updateDept：上级设为自己拒绝")
    void updateDeptRejectsSelfParent() {
        when(deptMapper.selectDeptById(200L)).thenReturn(dept(200L, 100L, "0,100", "0"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> deptService.updateDept(dept(200L, 200L, "0,100", "0")));
        assertTrue(ex.getMessage().contains("上级部门不能是自己"));
        verify(deptMapper, never()).updateDept(any());
    }

    @Test
    @DisplayName("updateDept：上级设为自己的子孙拒绝（环检测）")
    void updateDeptRejectsDescendantParent() {
        when(deptMapper.selectDeptById(200L)).thenReturn(dept(200L, 100L, "0,100", "0"));
        // 新父 300 的 ancestors 含 200 → 300 是 200 的子孙
        when(deptMapper.selectDeptById(300L)).thenReturn(dept(300L, 250L, "0,100,200,250", "0"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> deptService.updateDept(dept(200L, 300L, "0,100", "0")));
        assertTrue(ex.getMessage().contains("上级部门不能是自己的下级部门"));
        verify(deptMapper, never()).updateDept(any());
    }

    @Test
    @DisplayName("#4 updateDept 不再无条件复活祖级：mapper updateDeptStatusNormal 已移除，正常保存仅改自身")
    void updateDeptDoesNotReviveAncestors() {
        when(deptMapper.selectDeptById(200L)).thenReturn(dept(200L, 100L, "0,100", "0"));
        when(deptMapper.selectDeptById(100L)).thenReturn(dept(100L, 0L, "0", "1")); // 新父停用中
        when(deptMapper.selectChildrenDeptById(200L)).thenReturn(new ArrayList<>());
        when(deptMapper.updateDept(any())).thenReturn(1);

        // 即便目标状态为正常，也不应触碰任何祖级启用逻辑（原 updateDeptStatusNormal 已从接口删除）
        int r = deptService.updateDept(dept(200L, 100L, "0", "0"));

        assertEquals(1, r);
        verify(deptMapper).updateDept(any());
    }

    // ---------- #5 selectDeptListByRoleId 角色判空 ----------

    @Test
    @DisplayName("selectDeptListByRoleId：角色不存在抛业务异常而非 NPE")
    void selectDeptListByRoleIdRejectsMissingRole() {
        when(roleMapper.selectRoleById(7L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> deptService.selectDeptListByRoleId(7L));
        assertTrue(ex.getMessage().contains("角色不存在"));
        verify(deptMapper, never()).selectDeptListByRoleId(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    // ---------- #6 deleteDeptById 删前护栏收口 ----------

    @Test
    @DisplayName("deleteDeptById：存在下级部门拒绝删除")
    void deleteDeptByIdRejectsWhenHasChild() {
        when(deptMapper.hasChildByDeptId(100L)).thenReturn(1);

        ServiceException ex = assertThrows(ServiceException.class, () -> deptService.deleteDeptById(100L));
        assertTrue(ex.getMessage().contains("存在下级部门"));
        verify(deptMapper, never()).deleteDeptById(anyLong());
    }

    @Test
    @DisplayName("deleteDeptById：部门下存在用户拒绝删除")
    void deleteDeptByIdRejectsWhenHasUser() {
        when(deptMapper.hasChildByDeptId(100L)).thenReturn(0);
        when(deptMapper.checkDeptExistUser(100L)).thenReturn(1);

        ServiceException ex = assertThrows(ServiceException.class, () -> deptService.deleteDeptById(100L));
        assertTrue(ex.getMessage().contains("部门存在用户"));
        verify(deptMapper, never()).deleteDeptById(anyLong());
    }
}
