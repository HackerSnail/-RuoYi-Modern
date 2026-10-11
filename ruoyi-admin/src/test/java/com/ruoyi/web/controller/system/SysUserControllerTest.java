package com.ruoyi.web.controller.system;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.service.ISysDeptService;
import com.ruoyi.system.service.ISysPostService;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.ISysUserService;

/**
 * SysUserController.insertAuthRole 的"角色成员关系变更后强制该用户重新登录"接线测试。
 *
 * <p>
 * 用户↔角色的增删改变化的是缓存 LoginUser 的 roles 快照，软刷新权限集无法反映，故本轮在授权
 * 成功后调用 tokenService.delLoginUsers(userId) 令收回/新增角色即刻生效。本测只验该失效调用被
 * 正确触发、且数据域守卫抛异常时不触发；insertAuthRole 不读当前登录用户，故无需 mockStatic。
 * </p>
 */
public class SysUserControllerTest {

    private static SysUserController newController(ISysUserService userService, ISysRoleService roleService,
            TokenService tokenService) {
        return new SysUserController(userService, roleService, mock(ISysDeptService.class),
                mock(ISysPostService.class), tokenService);
    }

    @Test
    @DisplayName("insertAuthRole：授权成功后强制目标用户下线重新登录")
    void insertAuthRoleForcesTargetReLogin() {
        ISysUserService userService = mock(ISysUserService.class);
        ISysRoleService roleService = mock(ISysRoleService.class);
        TokenService tokenService = mock(TokenService.class);
        SysUserController controller = newController(userService, roleService, tokenService);

        Long userId = 5L;
        Long[] roleIds = new Long[] { 2L, 3L };
        AjaxResult r = controller.insertAuthRole(userId, roleIds);

        assertTrue(r.isSuccess());
        verify(userService).insertUserAuth(eq(userId), eq(roleIds));
        verify(tokenService).delLoginUsers(userId);
    }

    @Test
    @DisplayName("insertAuthRole：目标用户越出数据域被守卫拦截时，不写库也不下线")
    void insertAuthRoleGuardFailureDoesNotInvalidate() {
        ISysUserService userService = mock(ISysUserService.class);
        ISysRoleService roleService = mock(ISysRoleService.class);
        TokenService tokenService = mock(TokenService.class);
        doThrow(new ServiceException("没有权限访问用户数据")).when(userService).checkUserDataScope(9L);
        SysUserController controller = newController(userService, roleService, tokenService);

        assertThrows(ServiceException.class, () -> controller.insertAuthRole(9L, new Long[] { 2L }));

        verify(userService, never()).insertUserAuth(any(), any());
        verify(tokenService, never()).delLoginUsers(any());
    }
}
