package com.ruoyi.web.controller.system;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.SysConfig;
import com.ruoyi.system.service.ISysConfigService;

/**
 * SysConfigController.edit 的"内置参数(Y)仅超管可改"守卫测试。
 *
 * <p>
 * 覆盖本轮新增访问层策略：以库中原始 configType 判定（非请求体，防篡改），非超管改内置参数被拒、
 * 且不落库；超管改内置、任何人改非内置均放行。@Validated/@Pattern 属 Spring MVC 层，单元直调不触发，
 * 故本测只验守卫逻辑；@Pattern 的格式约束由集成/手工覆盖。
 * </p>
 */
public class SysConfigControllerTest {

    private static SysConfig req(Long id) {
        SysConfig c = new SysConfig();
        c.setConfigId(id);
        c.setConfigName("n");
        return c;
    }

    private static SysConfig persisted(String configType) {
        SysConfig c = new SysConfig();
        c.setConfigType(configType);
        return c;
    }

    @Test
    @DisplayName("edit：非超管改内置参数(Y)被拒，且不调用 updateConfig")
    void editRejectsBuiltInForNonAdmin() {
        ISysConfigService svc = mock(ISysConfigService.class);
        when(svc.checkConfigKeyUnique(any())).thenReturn(true);
        when(svc.selectConfigById(eq(1L))).thenReturn(persisted("Y"));
        SysConfigController controller = new SysConfigController(svc);

        AjaxResult r;
        try (MockedStatic<SecurityUtils> su = mockStatic(SecurityUtils.class)) {
            su.when(SecurityUtils::isAdmin).thenReturn(false);
            r = controller.edit(req(1L));
        }

        assertFalse(r.isSuccess());
        verify(svc, never()).updateConfig(any());
    }

    @Test
    @DisplayName("edit：超管改内置参数(Y)放行")
    void editAllowsBuiltInForAdmin() {
        ISysConfigService svc = mock(ISysConfigService.class);
        when(svc.checkConfigKeyUnique(any())).thenReturn(true);
        when(svc.selectConfigById(eq(2L))).thenReturn(persisted("Y"));
        when(svc.updateConfig(any())).thenReturn(1);
        SysConfigController controller = new SysConfigController(svc);

        AjaxResult r;
        // 守卫放行后会执行 setUpdateBy(getUsername()) →
        // BaseController.getLoginUser().getUsername()，
        // 故须桩 getLoginUser()（mockStatic 下默认返回 null 会 NPE），而非从未被调用的
        // SecurityUtils.getUsername()。
        LoginUser loginUser = mock(LoginUser.class);
        when(loginUser.getUsername()).thenReturn("admin");
        try (MockedStatic<SecurityUtils> su = mockStatic(SecurityUtils.class)) {
            su.when(SecurityUtils::getLoginUser).thenReturn(loginUser);
            su.when(SecurityUtils::isAdmin).thenReturn(true);
            r = controller.edit(req(2L));
        }

        assertTrue(r.isSuccess());
        verify(svc).updateConfig(any());
    }

    @Test
    @DisplayName("edit：非超管改非内置参数(N)放行")
    void editAllowsNonBuiltInForNonAdmin() {
        ISysConfigService svc = mock(ISysConfigService.class);
        when(svc.checkConfigKeyUnique(any())).thenReturn(true);
        when(svc.selectConfigById(eq(3L))).thenReturn(persisted("N"));
        when(svc.updateConfig(any())).thenReturn(1);
        SysConfigController controller = new SysConfigController(svc);

        LoginUser loginUser = mock(LoginUser.class);
        when(loginUser.getUsername()).thenReturn("user01");
        AjaxResult r;
        try (MockedStatic<SecurityUtils> su = mockStatic(SecurityUtils.class)) {
            su.when(SecurityUtils::getLoginUser).thenReturn(loginUser);
            su.when(SecurityUtils::isAdmin).thenReturn(false);
            r = controller.edit(req(3L));
        }

        assertTrue(r.isSuccess());
        verify(svc).updateConfig(any());
    }

    @Test
    @DisplayName("edit：键名重复先被拒（早于内置判定，不落库）")
    void editRejectsDuplicateKey() {
        ISysConfigService svc = mock(ISysConfigService.class);
        when(svc.checkConfigKeyUnique(any())).thenReturn(false);
        SysConfigController controller = new SysConfigController(svc);

        AjaxResult r = controller.edit(req(4L));

        assertFalse(r.isSuccess());
        verify(svc, never()).updateConfig(any());
    }
}
