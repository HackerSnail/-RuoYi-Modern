package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ruoyi.common.core.domain.entity.SysMenu;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.SysMenuMapper;
import com.ruoyi.system.mapper.SysRoleMapper;
import com.ruoyi.system.mapper.SysRoleMenuMapper;

/**
 * SysMenuServiceImpl 单元测试。
 *
 * <p>
 * 覆盖两类：(1) 既有 updateMenuSort 批量排序契约；(2) 本轮 C 档落地的写入口收口校验——
 * 唯一性、perms 通配后门、上级存在性/按钮顶级/子孙环、component/path 加固，以及读侧成环不崩溃的回归。
 * 纯 mock mapper，无需 Spring/Redis。
 * </p>
 */
public class SysMenuServiceImplTest {
    private SysMenuServiceImpl menuService;

    private SysMenuMapper menuMapper;

    private SysRoleMapper roleMapper;

    @BeforeEach
    void setUp() {
        menuMapper = mock(SysMenuMapper.class);
        roleMapper = mock(SysRoleMapper.class);
        menuService = new SysMenuServiceImpl(menuMapper, roleMapper, mock(SysRoleMenuMapper.class));
    }

    // ---------- updateMenuSort（既有） ----------

    @Test
    @DisplayName("排序：单次批量调用且 ID/序号按序正确映射")
    @SuppressWarnings("unchecked")
    void updateMenuSortIssuesSingleBatchCall() {
        menuService.updateMenuSort(new String[] { "1", "2", "3" }, new String[] { "10", "20", "30" });

        ArgumentCaptor<List<SysMenu>> captor = ArgumentCaptor.forClass(List.class);
        verify(menuMapper, times(1)).updateMenuSortBatch(captor.capture());
        verify(menuMapper, never()).updateMenuSort(any());

        List<SysMenu> list = captor.getValue();
        assertEquals(3, list.size());
        assertEquals(Long.valueOf(1L), list.get(0).getMenuId());
        assertEquals(Integer.valueOf(10), list.get(0).getOrderNum());
    }

    @Test
    @DisplayName("排序：空输入不触发批量调用")
    void updateMenuSortSkipsBatchWhenEmpty() {
        menuService.updateMenuSort(new String[] {}, new String[] {});
        verify(menuMapper, never()).updateMenuSortBatch(any());
    }

    @Test
    @DisplayName("排序：底层异常被包装为 ServiceException")
    void updateMenuSortWrapsFailureAsServiceException() {
        doThrow(new RuntimeException("db down")).when(menuMapper).updateMenuSortBatch(any());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> menuService.updateMenuSort(new String[] { "1" }, new String[] { "10" }));
        assertEquals("保存排序异常，请联系管理员", ex.getMessage());
    }

    // ---------- selectMenuListByRoleId：回查判空 ----------

    @Test
    @DisplayName("按角色查菜单：角色不存在(回查 null)抛异常而非 NPE")
    void selectMenuListByRoleIdThrowsWhenRoleMissing() {
        when(roleMapper.selectRoleById(999L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> menuService.selectMenuListByRoleId(999L));
        assertTrue(ex.getMessage().contains("角色不存在"));
    }

    // ---------- perms 通配后门 ----------

    @Test
    @DisplayName("新增：拒绝含通配符的 perms（堵 *:*:* 万能权限后门）")
    void insertRejectsWildcardPerms() {
        SysMenu menu = validMenu("C");
        menu.setPerms("*:*:*");
        ServiceException ex = assertThrows(ServiceException.class, () -> menuService.insertMenu(menu));
        assertTrue(ex.getMessage().contains("通配符"));
        verify(menuMapper, never()).insertMenu(any());
    }

    // ---------- 上级存在性 / 按钮顶级 ----------

    @Test
    @DisplayName("新增：按钮(F)不得作为顶级菜单")
    void insertRejectsButtonAsRoot() {
        SysMenu menu = validMenu("F");
        menu.setParentId(0L);
        ServiceException ex = assertThrows(ServiceException.class, () -> menuService.insertMenu(menu));
        assertTrue(ex.getMessage().contains("按钮不能设为顶级"));
    }

    @Test
    @DisplayName("新增：上级菜单不存在则拒绝")
    void insertRejectsMissingParent() {
        SysMenu menu = validMenu("C");
        menu.setParentId(5L);
        when(menuMapper.selectMenuById(5L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> menuService.insertMenu(menu));
        assertTrue(ex.getMessage().contains("上级菜单不存在"));
    }

    // ---------- component / path 加固 ----------

    @Test
    @DisplayName("新增：component 含 .. 穿越被拒绝")
    void insertRejectsUnsafeComponent() {
        SysMenu menu = validMenu("C");
        menu.setComponent("../secret/evil");
        ServiceException ex = assertThrows(ServiceException.class, () -> menuService.insertMenu(menu));
        assertTrue(ex.getMessage().contains("组件路径不合法"));
    }

    @Test
    @DisplayName("新增：合法菜单通过校验并落库")
    void insertAllowsNormalMenuAndPersists() {
        SysMenu menu = validMenu("C");
        menu.setComponent("system/user/index");
        menu.setPath("user");

        when(menuMapper.insertMenu(menu)).thenReturn(1);
        assertEquals(1, menuService.insertMenu(menu));
        verify(menuMapper).insertMenu(menu);
    }

    // ---------- 子孙环：拒绝 + 合法迁移放行 ----------

    @Test
    @DisplayName("修改：上级指向自身后代(成环)被拒")
    void updateRejectsParentToOwnDescendant() {
        SysMenu menu = validMenu("M");
        menu.setMenuId(10L);
        menu.setParentId(11L);
        // 11 的父亲是 10 → 把 10 挂到 11 下即成环
        SysMenu parent11 = new SysMenu();
        parent11.setMenuId(11L);
        parent11.setParentId(10L);
        when(menuMapper.selectMenuById(11L)).thenReturn(parent11);

        ServiceException ex = assertThrows(ServiceException.class, () -> menuService.updateMenu(menu));
        assertTrue(ex.getMessage().contains("下级菜单"));
        verify(menuMapper, never()).updateMenu(any());
    }

    @Test
    @DisplayName("修改：合法迁移到新父节点通过并落库")
    void updateAllowsValidMove() {
        SysMenu menu = validMenu("M");
        menu.setMenuId(10L);
        menu.setParentId(20L);
        SysMenu parent20 = new SysMenu();
        parent20.setMenuId(20L);
        parent20.setParentId(0L);
        when(menuMapper.selectMenuById(20L)).thenReturn(parent20);
        when(menuMapper.updateMenu(menu)).thenReturn(1);

        assertEquals(1, menuService.updateMenu(menu));
        verify(menuMapper).updateMenu(menu);
    }

    // ---------- 读侧成环不崩溃回归（纠正"DoS"定性） ----------

    @Test
    @DisplayName("回归：父指向后代的成环菜单集，建树不会无限递归/崩溃，环内节点从根不可达被排除")
    void cyclicMenuTreeDoesNotCrash() {
        SysMenu m1 = node(1L, 2L, "M"); // 环：1 的父亲是 2
        SysMenu m2 = node(2L, 1L, "M"); // 环：2 的父亲是 1
        SysMenu m3 = node(3L, 0L, "M"); // 正常根级
        List<SysMenu> list = new ArrayList<>();
        list.add(m1);
        list.add(m2);
        list.add(m3);

        List<SysMenu> tree = assertDoesNotThrow(() -> menuService.buildMenuTree(list));
        // 环内 1、2 因父链不含根、从根不可达，不会进入自根向下的递归 → 不炸栈；仅 m3 作为顶级返回
        assertEquals(1, tree.size());
        assertEquals(Long.valueOf(3L), tree.get(0).getMenuId());
        assertFalse(containsId(tree, 1L));

        List<SysMenu> roots = assertDoesNotThrow(() -> menuService.getChildPerms(list, 0L));
        assertEquals(1, roots.size());
    }

    // ---------- helpers ----------

    private SysMenu validMenu(String menuType) {
        SysMenu menu = new SysMenu();
        menu.setMenuName("测试菜单");
        menu.setParentId(0L);
        menu.setOrderNum(1);
        menu.setMenuType(menuType);
        menu.setPath("test");
        menu.setIsFrame("1");
        return menu;
    }

    private SysMenu node(Long id, Long parentId, String menuType) {
        SysMenu m = new SysMenu();
        m.setMenuId(id);
        m.setParentId(parentId);
        m.setMenuType(menuType);
        m.setMenuName("n" + id);
        m.setPath("p" + id);
        m.setIsFrame("1");
        return m;
    }

    private boolean containsId(List<SysMenu> list, Long id) {
        for (SysMenu m : list) {
            if (id.equals(m.getMenuId())) {
                return true;
            }
        }
        return false;
    }
}
