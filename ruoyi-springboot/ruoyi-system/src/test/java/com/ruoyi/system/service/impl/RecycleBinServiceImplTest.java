package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.enums.RecycleBinType;
import com.ruoyi.common.enums.UserStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.system.domain.dto.RecycleBinOpResult;
import com.ruoyi.system.mapper.SysDeptMapper;
import com.ruoyi.system.mapper.SysRoleMapper;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.ISysUserService;

/**
 * RecycleBinServiceImpl 恢复/彻底删除的逐项语义测试（纯 mock，不打库）。
 *
 * <p>
 * 锁定回收站服务层契约：恢复的"只还壳 + 严格校验"（记录缺失/身份冲突/悬空部门/数据越权各自 FAILED 且不写库）、
 * 彻底删除的整请求二次确认（缺 confirm 直接抛而不逐项）、自删锁死、部门销毁的活跃下级/在职用户阻断，
 * 以及批处理"部分成功"计数——一坏项不影响其余。数据权限采软删口径，故须 mock {@code SpringUtils.getAopProxy}
 * 回传本实例以驱动带 @DataScope 的列表可见性判定；自删判定 mock {@code SecurityUtils} 静态。
 * </p>
 */
public class RecycleBinServiceImplTest {
    private SysUserMapper userMapper;

    private SysRoleMapper roleMapper;

    private SysDeptMapper deptMapper;

    private ISysUserService userService;

    private ISysRoleService roleService;

    private RecycleBinServiceImpl service;

    private MockedStatic<SpringUtils> springUtils;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        roleMapper = mock(SysRoleMapper.class);
        deptMapper = mock(SysDeptMapper.class);
        userService = mock(ISysUserService.class);
        roleService = mock(ISysRoleService.class);
        service = new RecycleBinServiceImpl(userMapper, roleMapper, deptMapper, userService, roleService);
        // 软删口径：getAopProxy(this) 回传本实例，使 selectDeletedXxxList 落到被 mock 的 mapper 判可见性
        springUtils = mockStatic(SpringUtils.class);
        springUtils.when(() -> SpringUtils.getAopProxy(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        springUtils.close();
    }

    // ===== 恢复：用户 =====

    @Test
    @DisplayName("恢复用户成功：只还壳，逐项 SUCCESS 且计数正确")
    void restoreUserSuccess() {
        SysUser trash = deletedUser(1L, "alice");
        when(userMapper.selectDeletedUserById(1L)).thenReturn(trash);
        when(userMapper.selectDeletedUserList(any())).thenReturn(Collections.singletonList(trash));
        when(userMapper.checkUserNameUnique("alice")).thenReturn(null);
        when(userMapper.restoreUserById(1L)).thenReturn(1);

        RecycleBinOpResult result = service.restore(RecycleBinType.USER, new Long[] { 1L });

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getSuccessCount());
        assertEquals(0, result.getFailCount());
        assertEquals(RecycleBinOpResult.Status.SUCCESS, result.getItems().get(0).getStatus());
        assertEquals("alice", result.getItems().get(0).getName());
        verify(userMapper).restoreUserById(1L);
    }

    @Test
    @DisplayName("恢复用户：记录不在回收站 → FAILED，不写库")
    void restoreUserNotInTrash() {
        when(userMapper.selectDeletedUserById(2L)).thenReturn(null);

        RecycleBinOpResult result = service.restore(RecycleBinType.USER, new Long[] { 2L });

        assertEquals(1, result.getFailCount());
        assertTrue(result.getItems().get(0).getReason().contains("回收站"));
        verify(userMapper, never()).restoreUserById(any());
    }

    @Test
    @DisplayName("恢复用户：登录名撞活跃记录 → 身份冲突 FAILED")
    void restoreUserNameConflict() {
        SysUser trash = deletedUser(1L, "alice");
        when(userMapper.selectDeletedUserById(1L)).thenReturn(trash);
        when(userMapper.selectDeletedUserList(any())).thenReturn(Collections.singletonList(trash));
        when(userMapper.checkUserNameUnique("alice")).thenReturn(new SysUser());

        RecycleBinOpResult result = service.restore(RecycleBinType.USER, new Long[] { 1L });

        assertEquals(1, result.getFailCount());
        assertTrue(result.getItems().get(0).getReason().contains("同名活跃用户"));
        verify(userMapper, never()).restoreUserById(any());
    }

    @Test
    @DisplayName("恢复用户：所属部门仍在回收站 → 悬空引用 FAILED")
    void restoreUserDeptDangling() {
        SysUser trash = deletedUser(1L, "alice");
        trash.setDeptId(100L);
        SysDept deletedDept = new SysDept();
        deletedDept.setDelFlag(UserStatus.DELETED.getCode());
        when(userMapper.selectDeletedUserById(1L)).thenReturn(trash);
        when(userMapper.selectDeletedUserList(any())).thenReturn(Collections.singletonList(trash));
        when(userMapper.checkUserNameUnique("alice")).thenReturn(null);
        when(deptMapper.selectDeptById(100L)).thenReturn(deletedDept);

        RecycleBinOpResult result = service.restore(RecycleBinType.USER, new Long[] { 1L });

        assertEquals(1, result.getFailCount());
        assertTrue(result.getItems().get(0).getReason().contains("请先恢复所属部门"));
        verify(userMapper, never()).restoreUserById(any());
    }

    @Test
    @DisplayName("恢复用户：目标超出数据范围（软删列表查空）→ 越权 FAILED")
    void restoreUserOutOfScope() {
        SysUser trash = deletedUser(1L, "alice");
        when(userMapper.selectDeletedUserById(1L)).thenReturn(trash);
        when(userMapper.selectDeletedUserList(any())).thenReturn(Collections.emptyList());

        RecycleBinOpResult result = service.restore(RecycleBinType.USER, new Long[] { 1L });

        assertEquals(1, result.getFailCount());
        assertTrue(result.getItems().get(0).getReason().contains("数据范围"));
        verify(userMapper, never()).restoreUserById(any());
    }

    // ===== 恢复：角色 =====

    @Test
    @DisplayName("恢复角色成功：标识/名称无冲突则还壳")
    void restoreRoleSuccess() {
        SysRole trash = new SysRole();
        trash.setRoleId(10L);
        trash.setRoleKey("editor");
        trash.setRoleName("编辑");
        when(roleMapper.selectDeletedRoleById(10L)).thenReturn(trash);
        when(roleMapper.selectDeletedRoleList(any())).thenReturn(Collections.singletonList(trash));
        when(roleMapper.checkRoleKeyUnique("editor")).thenReturn(null);
        when(roleMapper.checkRoleNameUnique("编辑")).thenReturn(null);
        when(roleMapper.restoreRoleById(10L)).thenReturn(1);

        RecycleBinOpResult result = service.restore(RecycleBinType.ROLE, new Long[] { 10L });

        assertEquals(1, result.getSuccessCount());
        assertEquals("编辑", result.getItems().get(0).getName());
        verify(roleMapper).restoreRoleById(10L);
    }

    // ===== 彻底删除 =====

    @Test
    @DisplayName("彻底删除：缺 confirm → 整请求前置拒绝，不进入逐项")
    void purgeRequiresConfirm() {
        assertThrows(ServiceException.class,
                () -> service.purge(RecycleBinType.USER, new Long[] { 1L }, false));
        verify(userMapper, never()).deleteUserByIdPhysically(any());
    }

    @Test
    @DisplayName("彻底删除用户：目标是当前登录用户 → 自删锁死 FAILED")
    void purgeUserSelfLocked() {
        SysUser trash = deletedUser(5L, "self");
        when(userMapper.selectDeletedUserById(5L)).thenReturn(trash);
        when(userMapper.selectDeletedUserList(any())).thenReturn(Collections.singletonList(trash));

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(5L);

            RecycleBinOpResult result = service.purge(RecycleBinType.USER, new Long[] { 5L }, true);

            assertEquals(1, result.getFailCount());
            assertTrue(result.getItems().get(0).getReason().contains("不能彻底删除当前登录用户"));
            verify(userMapper, never()).deleteUserByIdPhysically(any());
        }
    }

    @Test
    @DisplayName("彻底删除用户：非自身且可见 → 物理删 SUCCESS")
    void purgeUserSuccess() {
        SysUser trash = deletedUser(6L, "bob");
        when(userMapper.selectDeletedUserById(6L)).thenReturn(trash);
        when(userMapper.selectDeletedUserList(any())).thenReturn(Collections.singletonList(trash));
        when(userMapper.deleteUserByIdPhysically(6L)).thenReturn(1);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(1L);

            RecycleBinOpResult result = service.purge(RecycleBinType.USER, new Long[] { 6L }, true);

            assertEquals(1, result.getSuccessCount());
            assertEquals("bob", result.getItems().get(0).getName());
            verify(userMapper).deleteUserByIdPhysically(6L);
        }
    }

    @Test
    @DisplayName("彻底删除部门：下挂在职用户 → 阻断销毁 FAILED")
    void purgeDeptBlockedByActiveUser() {
        SysDept trash = new SysDept();
        trash.setDeptId(20L);
        trash.setDeptName("研发部");
        when(deptMapper.selectDeletedDeptById(20L)).thenReturn(trash);
        when(deptMapper.selectDeletedDeptList(any())).thenReturn(Collections.singletonList(trash));
        when(deptMapper.hasChildByDeptId(20L)).thenReturn(0);
        when(deptMapper.checkDeptExistUser(20L)).thenReturn(1);

        RecycleBinOpResult result = service.purge(RecycleBinType.DEPT, new Long[] { 20L }, true);

        assertEquals(1, result.getFailCount());
        assertTrue(result.getItems().get(0).getReason().contains("在职用户"));
        verify(deptMapper, never()).deleteDeptByIdPhysically(any());
    }

    @Test
    @DisplayName("彻底删除部门：回收站中仍有下级部门 → 阻断销毁 FAILED（防子级悬空）")
    void purgeDeptBlockedByRecycledChildDept() {
        SysDept trash = new SysDept();
        trash.setDeptId(21L);
        trash.setDeptName("旧研发部");
        when(deptMapper.selectDeletedDeptById(21L)).thenReturn(trash);
        when(deptMapper.selectDeletedDeptList(any())).thenReturn(Collections.singletonList(trash));
        when(deptMapper.hasChildByDeptId(21L)).thenReturn(0);
        when(deptMapper.hasRecycledChildByDeptId(21L)).thenReturn(1);

        RecycleBinOpResult result = service.purge(RecycleBinType.DEPT, new Long[] { 21L }, true);

        assertEquals(1, result.getFailCount());
        assertTrue(result.getItems().get(0).getReason().contains("回收站中存在下级部门"));
        verify(deptMapper, never()).deleteDeptByIdPhysically(any());
    }

    @Test
    @DisplayName("彻底删除部门：回收站中仍有该部门下用户 → 阻断销毁 FAILED（防用户悬空）")
    void purgeDeptBlockedByRecycledUser() {
        SysDept trash = new SysDept();
        trash.setDeptId(22L);
        trash.setDeptName("旧测试部");
        when(deptMapper.selectDeletedDeptById(22L)).thenReturn(trash);
        when(deptMapper.selectDeletedDeptList(any())).thenReturn(Collections.singletonList(trash));
        when(deptMapper.hasChildByDeptId(22L)).thenReturn(0);
        when(deptMapper.hasRecycledChildByDeptId(22L)).thenReturn(0);
        when(deptMapper.checkDeptExistUser(22L)).thenReturn(0);
        when(deptMapper.countRecycledUserByDeptId(22L)).thenReturn(1);

        RecycleBinOpResult result = service.purge(RecycleBinType.DEPT, new Long[] { 22L }, true);

        assertEquals(1, result.getFailCount());
        assertTrue(result.getItems().get(0).getReason().contains("该部门下的用户"));
        verify(deptMapper, never()).deleteDeptByIdPhysically(any());
    }

    // ===== 异常脱敏：非预期异常不外泄原始 message =====

    @Test
    @DisplayName("恢复遇非预期异常（如 SQL/NPE）→ 回传通用文案、不含底层原文")
    void restoreUnexpectedExceptionSanitized() {
        when(userMapper.selectDeletedUserById(1L))
                .thenThrow(new RuntimeException("Table 'ry-vue.sys_user' doesn't exist; bad SQL grammar"));

        RecycleBinOpResult result = service.restore(RecycleBinType.USER, new Long[] { 1L });

        assertEquals(1, result.getFailCount());
        assertEquals("操作失败，请联系管理员", result.getItems().get(0).getReason());
        assertTrue(!result.getItems().get(0).getReason().contains("sys_user"), "不应泄露表名/SQL");
        verify(userMapper, never()).restoreUserById(any());
    }

    @Test
    @DisplayName("恢复遇业务护栏（ServiceException）→ 原文案面向操作者安全回传")
    void restoreServiceExceptionMessagePreserved() {
        SysUser trash = deletedUser(1L, "alice");
        when(userMapper.selectDeletedUserById(1L)).thenReturn(trash);
        when(userMapper.selectDeletedUserList(any())).thenReturn(Collections.emptyList());

        RecycleBinOpResult result = service.restore(RecycleBinType.USER, new Long[] { 1L });

        assertEquals(1, result.getFailCount());
        assertTrue(result.getItems().get(0).getReason().contains("数据范围"), "越权护栏文案应原样回传");
        verify(userMapper, never()).restoreUserById(any());
    }

    // ===== 批处理：部分成功 =====

    @Test
    @DisplayName("批量恢复：一好一坏 → 好项写库、坏项记失败，计数互不影响")
    void restoreBatchPartialSuccess() {
        SysUser ok = deletedUser(1L, "alice");
        when(userMapper.selectDeletedUserById(1L)).thenReturn(ok);
        when(userMapper.selectDeletedUserById(2L)).thenReturn(null);
        when(userMapper.selectDeletedUserList(any())).thenReturn(Collections.singletonList(ok));
        when(userMapper.checkUserNameUnique("alice")).thenReturn(null);
        when(userMapper.restoreUserById(1L)).thenReturn(1);

        RecycleBinOpResult result = service.restore(RecycleBinType.USER, new Long[] { 1L, 2L });

        assertEquals(2, result.getTotal());
        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
        verify(userMapper).restoreUserById(1L);
        verify(userMapper, never()).restoreUserById(2L);
    }

    private SysUser deletedUser(Long id, String name) {
        SysUser user = new SysUser();
        user.setUserId(id);
        user.setUserName(name);
        user.setDelFlag(UserStatus.DELETED.getCode());
        return user;
    }
}
