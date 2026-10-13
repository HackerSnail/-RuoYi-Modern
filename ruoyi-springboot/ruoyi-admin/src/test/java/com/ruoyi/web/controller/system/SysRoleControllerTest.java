package com.ruoyi.web.controller.system;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.domain.SysUserRole;
import com.ruoyi.system.service.ISysDeptService;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.ISysUserService;

/**
 * SysRoleController 各提权/降权写路径的"在线会话失效"接线测试。
 *
 * <p>
 * 本轮把红线#4（角色变更后清理权限缓存）补全：角色停用/数据范围变更按角色强制持有者重登
 * （delLoginUsersByRoleId），取消/批量授权用户按用户强制重登（delLoginUsers）。本测只验这些失效
 * 调用在写成功时被触发、写失败(0 行)时不触发；Service 全 mock，不触真实库。changeStatus 会调
 * getUsername，需桩 SecurityUtils.getLoginUser()。
 * </p>
 */
public class SysRoleControllerTest {

    private final ISysRoleService roleService = mock(ISysRoleService.class);
    private final TokenService tokenService = mock(TokenService.class);
    private final SysPermissionService permissionService = mock(SysPermissionService.class);
    private final ISysUserService userService = mock(ISysUserService.class);
    private final ISysDeptService deptService = mock(ISysDeptService.class);
    private final SysRoleController controller = new SysRoleController(roleService, tokenService, permissionService,
            userService, deptService);

    @Test
    @DisplayName("changeStatus：更新成功按角色强制持有者重登")
    void changeStatusEvictsRoleHoldersOnSuccess() {
        SysRole role = new SysRole();
        role.setRoleId(2L);
        role.setStatus("1");
        when(roleService.updateRoleStatus(any())).thenReturn(1);

        LoginUser loginUser = mock(LoginUser.class);
        when(loginUser.getUsername()).thenReturn("admin");
        AjaxResult r;
        try (MockedStatic<SecurityUtils> su = mockStatic(SecurityUtils.class)) {
            su.when(SecurityUtils::getLoginUser).thenReturn(loginUser);
            r = controller.changeStatus(role);
        }

        assertTrue(r.isSuccess());
        verify(tokenService).delLoginUsersByRoleId(2L);
    }

    @Test
    @DisplayName("changeStatus：更新 0 行不下线")
    void changeStatusNoEvictWhenUnaffected() {
        SysRole role = new SysRole();
        role.setRoleId(2L);
        role.setStatus("1");
        when(roleService.updateRoleStatus(any())).thenReturn(0);

        LoginUser loginUser = mock(LoginUser.class);
        when(loginUser.getUsername()).thenReturn("admin");
        try (MockedStatic<SecurityUtils> su = mockStatic(SecurityUtils.class)) {
            su.when(SecurityUtils::getLoginUser).thenReturn(loginUser);
            controller.changeStatus(role);
        }

        verify(tokenService, never()).delLoginUsersByRoleId(anyLong());
    }

    @Test
    @DisplayName("dataScope：数据范围保存成功按角色强制持有者重登")
    void dataScopeEvictsRoleHoldersOnSuccess() {
        SysRole role = new SysRole();
        role.setRoleId(3L);
        when(roleService.authDataScope(any())).thenReturn(1);

        AjaxResult r = controller.dataScope(role);

        assertTrue(r.isSuccess());
        verify(tokenService).delLoginUsersByRoleId(3L);
    }

    @Test
    @DisplayName("cancelAuthUser：取消授权后强制被取消用户重登")
    void cancelAuthUserEvictsTargetUser() {
        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(7L);
        userRole.setRoleId(2L);
        when(roleService.deleteAuthUser(any())).thenReturn(1);

        AjaxResult r = controller.cancelAuthUser(userRole);

        assertTrue(r.isSuccess());
        verify(tokenService).delLoginUsers(7L);
    }

    @Test
    @DisplayName("selectAuthUserAll：批量授权后逐个用户强制重登")
    void selectAuthUserAllEvictsEachUser() {
        Long[] userIds = new Long[] { 10L, 11L };
        when(roleService.insertAuthUsers(anyLong(), any())).thenReturn(2);

        AjaxResult r = controller.selectAuthUserAll(2L, userIds);

        assertTrue(r.isSuccess());
        verify(tokenService).delLoginUsers(10L);
        verify(tokenService).delLoginUsers(11L);
    }

    @Test
    @DisplayName("cancelAuthUserAll：批量取消后逐个用户强制重登")
    void cancelAuthUserAllEvictsEachUser() {
        Long[] userIds = new Long[] { 20L, 21L };
        when(roleService.deleteAuthUsers(anyLong(), any())).thenReturn(2);

        AjaxResult r = controller.cancelAuthUserAll(2L, userIds);

        assertTrue(r.isSuccess());
        verify(tokenService).delLoginUsers(20L);
        verify(tokenService).delLoginUsers(21L);
    }
}
