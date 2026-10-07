package com.ruoyi.framework.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.security.core.userdetails.UserDetails;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.enums.UserStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.MessageUtils;
import com.ruoyi.system.service.ISysUserService;

/**
 * UserDetailsServiceImpl 用户装载测试。
 *
 * <p>覆盖 {@code loadUserByUsername} 的四条分支：用户不存在 / 已删除 / 已停用 分别抛出
 * {@code ServiceException}；正常用户则校验密码并构建携带权限的 {@code LoginUser}。i18n
 * （{@code MessageUtils}）用静态打桩隔离，其余依赖全部 mock，不触碰容器与数据库。</p>
 */
public class UserDetailsServiceImplTest
{
    private UserDetailsServiceImpl userDetailsService;

    private ISysUserService userService;

    private SysPasswordService passwordService;

    private SysPermissionService permissionService;

    @BeforeEach
    void setUp()
    {
        userService = mock(ISysUserService.class);
        passwordService = mock(SysPasswordService.class);
        permissionService = mock(SysPermissionService.class);

        userDetailsService = new UserDetailsServiceImpl(userService, passwordService, permissionService);
    }

    private SysUser activeUser()
    {
        SysUser user = new SysUser();
        user.setUserId(2L);
        user.setDeptId(5L);
        user.setUserName("zhangsan");
        user.setDelFlag(UserStatus.OK.getCode());
        user.setStatus(UserStatus.OK.getCode());
        return user;
    }

    @Test
    @DisplayName("用户不存在：抛 ServiceException")
    void loadUserThrowsWhenMissing()
    {
        when(userService.selectUserByUserName("ghost")).thenReturn(null);

        try (MockedStatic<MessageUtils> messageUtils = mockStatic(MessageUtils.class, inv -> "msg"))
        {
            assertThrows(ServiceException.class, () -> userDetailsService.loadUserByUsername("ghost"));
            verify(passwordService, never()).validate(any());
        }
    }

    @Test
    @DisplayName("用户已删除：抛 ServiceException")
    void loadUserThrowsWhenDeleted()
    {
        SysUser user = activeUser();
        user.setDelFlag(UserStatus.DELETED.getCode());
        when(userService.selectUserByUserName("zhangsan")).thenReturn(user);

        try (MockedStatic<MessageUtils> messageUtils = mockStatic(MessageUtils.class, inv -> "msg"))
        {
            assertThrows(ServiceException.class, () -> userDetailsService.loadUserByUsername("zhangsan"));
        }
    }

    @Test
    @DisplayName("用户已停用：抛 ServiceException")
    void loadUserThrowsWhenDisabled()
    {
        SysUser user = activeUser();
        user.setStatus(UserStatus.DISABLE.getCode());
        when(userService.selectUserByUserName("zhangsan")).thenReturn(user);

        try (MockedStatic<MessageUtils> messageUtils = mockStatic(MessageUtils.class, inv -> "msg"))
        {
            assertThrows(ServiceException.class, () -> userDetailsService.loadUserByUsername("zhangsan"));
        }
    }

    @Test
    @DisplayName("正常用户：校验密码并构建携带权限的 LoginUser")
    void loadUserSuccessBuildsLoginUser()
    {
        SysUser user = activeUser();
        when(userService.selectUserByUserName("zhangsan")).thenReturn(user);
        Set<String> perms = new HashSet<>();
        perms.add("sys:user:list");
        when(permissionService.getMenuPermission(user)).thenReturn(perms);

        UserDetails userDetails = userDetailsService.loadUserByUsername("zhangsan");

        verify(passwordService).validate(user);
        assertInstanceOf(LoginUser.class, userDetails);
        LoginUser loginUser = (LoginUser) userDetails;
        assertEquals(2L, loginUser.getUserId());
        assertEquals(5L, loginUser.getDeptId());
        assertEquals("zhangsan", loginUser.getUsername());
        assertEquals(perms, loginUser.getPermissions());
    }
}
