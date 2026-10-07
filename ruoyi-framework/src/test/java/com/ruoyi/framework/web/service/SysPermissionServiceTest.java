package com.ruoyi.framework.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.system.service.ISysMenuService;
import com.ruoyi.system.service.ISysRoleService;

/**
 * SysPermissionService 权限聚合测试。
 *
 * <p>
 * 锁定角色/菜单数据权限的分配规则：超级管理员直接短路为全权限标记；普通用户按角色
 * 聚合——仅统计状态正常且非管理员的角色，逐角色查菜单权限，无角色时回落到按用户查询。
 * 依赖的 role/menu service 全部 mock。
 * </p>
 */
public class SysPermissionServiceTest {
    private SysPermissionService permissionService;

    private ISysRoleService roleService;

    private ISysMenuService menuService;

    @BeforeEach
    void setUp() {
        roleService = mock(ISysRoleService.class);
        menuService = mock(ISysMenuService.class);
        permissionService = new SysPermissionService(roleService, menuService);
    }

    private SysUser user(Long userId) {
        SysUser user = new SysUser();
        user.setUserId(userId);
        return user;
    }

    private SysRole role(Long roleId, String status) {
        SysRole role = new SysRole();
        role.setRoleId(roleId);
        role.setStatus(status);
        return role;
    }

    @Test
    @DisplayName("角色权限：超级管理员短路返回 admin，不查库")
    void rolePermissionForAdmin() {
        Set<String> roles = permissionService.getRolePermission(user(1L));

        assertTrue(roles.contains(Constants.SUPER_ADMIN));
        verify(roleService, never()).selectRolePermissionByUserId(anyLong());
    }

    @Test
    @DisplayName("角色权限：普通用户委托 roleService 查询")
    void rolePermissionForNormalUser() {
        when(roleService.selectRolePermissionByUserId(2L)).thenReturn(new HashSet<>(Arrays.asList("common")));

        Set<String> roles = permissionService.getRolePermission(user(2L));

        assertTrue(roles.contains("common"));
        verify(roleService).selectRolePermissionByUserId(2L);
    }

    @Test
    @DisplayName("菜单权限：超级管理员短路返回全权限标记，不查库")
    void menuPermissionForAdmin() {
        Set<String> perms = permissionService.getMenuPermission(user(1L));

        assertTrue(perms.contains(Constants.ALL_PERMISSION));
        verify(menuService, never()).selectMenuPermsByRoleId(anyLong());
        verify(menuService, never()).selectMenuPermsByUserId(anyLong());
    }

    @Test
    @DisplayName("菜单权限：多角色仅聚合状态正常且非管理员的角色")
    void menuPermissionAggregatesOnlyNormalNonAdminRoles() {
        SysUser user = user(2L);
        user.setRoles(Arrays.asList(
                role(2L, UserConstants.ROLE_NORMAL), // 正常，应统计
                role(3L, "1"), // 停用，跳过
                role(1L, UserConstants.ROLE_NORMAL))); // 管理员角色，跳过
        when(menuService.selectMenuPermsByRoleId(2L)).thenReturn(new HashSet<>(Arrays.asList("sys:user:list")));

        Set<String> perms = permissionService.getMenuPermission(user);

        assertTrue(perms.contains("sys:user:list"));
        verify(menuService).selectMenuPermsByRoleId(2L);
        verify(menuService, never()).selectMenuPermsByRoleId(3L);
        verify(menuService, never()).selectMenuPermsByRoleId(1L);
        verify(menuService, never()).selectMenuPermsByUserId(anyLong());
    }

    @Test
    @DisplayName("菜单权限：无角色时回落到按用户查询")
    void menuPermissionFallsBackToUserIdWhenNoRoles() {
        SysUser user = user(2L);
        when(menuService.selectMenuPermsByUserId(2L)).thenReturn(new HashSet<>(Arrays.asList("a:b")));

        Set<String> perms = permissionService.getMenuPermission(user);

        assertEquals("a:b", perms.toArray(new String[0])[0]);
        assertFalse(perms.contains(Constants.ALL_PERMISSION));
        verify(menuService).selectMenuPermsByUserId(2L);
    }
}
