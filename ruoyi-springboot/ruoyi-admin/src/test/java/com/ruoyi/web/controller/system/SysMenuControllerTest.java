package com.ruoyi.web.controller.system;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysMenu;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.service.ISysMenuService;

/**
 * SysMenuController.edit 的"菜单变更后全量软刷新在线用户权限"接线测试。
 *
 * <p>
 * 菜单权限字符/可见性变化会经 getMenuPermission 逐角色实时查库反映，且不涉及用户角色成员关系，
 * 故本轮在 edit 成功后调用 tokenService.refreshAllOnlineUserPermission（会话保持、不下线）。add
 * 的新菜单
 * 尚未绑定角色、remove 又被 checkMenuExistRole 禁止删除已分配菜单，二者均可证明为 no-op，故不接线。
 * edit 会调 getUsername，需桩 SecurityUtils.getLoginUser()。
 * </p>
 */
public class SysMenuControllerTest {

    private final ISysMenuService menuService = mock(ISysMenuService.class);
    private final TokenService tokenService = mock(TokenService.class);
    private final SysPermissionService permissionService = mock(SysPermissionService.class);
    private final SysMenuController controller = new SysMenuController(menuService, tokenService, permissionService);

    private static SysMenu editableMenu() {
        SysMenu menu = new SysMenu();
        menu.setMenuId(5L);
        menu.setParentId(1L);
        menu.setMenuName("m");
        // isFrame 留空即非"外链"，跳过 path 必须 http 的分支；menuId != parentId 跳过"上级选自己"
        return menu;
    }

    @Test
    @DisplayName("edit：更新成功后全量刷新在线用户权限")
    void editRefreshesAllOnlineUsersOnSuccess() {
        when(menuService.checkMenuNameUnique(any())).thenReturn(true);
        when(menuService.checkRouteConfigUnique(any())).thenReturn(true);
        when(menuService.updateMenu(any())).thenReturn(1);

        LoginUser loginUser = mock(LoginUser.class);
        when(loginUser.getUsername()).thenReturn("admin");
        AjaxResult r;
        try (MockedStatic<SecurityUtils> su = mockStatic(SecurityUtils.class)) {
            su.when(SecurityUtils::getLoginUser).thenReturn(loginUser);
            r = controller.edit(editableMenu());
        }

        assertTrue(r.isSuccess());
        verify(tokenService).refreshAllOnlineUserPermission(permissionService);
    }

    @Test
    @DisplayName("edit：更新 0 行不刷新")
    void editNoRefreshWhenUnaffected() {
        when(menuService.checkMenuNameUnique(any())).thenReturn(true);
        when(menuService.checkRouteConfigUnique(any())).thenReturn(true);
        when(menuService.updateMenu(any())).thenReturn(0);

        LoginUser loginUser = mock(LoginUser.class);
        when(loginUser.getUsername()).thenReturn("admin");
        try (MockedStatic<SecurityUtils> su = mockStatic(SecurityUtils.class)) {
            su.when(SecurityUtils::getLoginUser).thenReturn(loginUser);
            controller.edit(editableMenu());
        }

        verify(tokenService, never()).refreshAllOnlineUserPermission(any());
    }
}
