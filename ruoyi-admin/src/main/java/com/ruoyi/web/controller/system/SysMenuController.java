package com.ruoyi.web.controller.system;

import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysMenu;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.service.ISysMenuService;

/**
 * 菜单信息
 * 
 * @author ruoyi
 */
@RestController
@RequestMapping("/system/menu")
public class SysMenuController extends BaseController {
    private final ISysMenuService menuService;

    public SysMenuController(ISysMenuService menuService) {
        this.menuService = menuService;
    }

    /**
     * 获取菜单列表
     */
    @PreAuthorize("@ss.hasPermi('system:menu:list')")
    @GetMapping("/list")
    public AjaxResult list(SysMenu menu) {
        List<SysMenu> menus = menuService.selectMenuList(menu, getUserId());
        return success(menus);
    }

    /**
     * 根据菜单编号获取详细信息
     */
    @PreAuthorize("@ss.hasPermi('system:menu:query')")
    @GetMapping(value = "/{menuId}")
    public AjaxResult getInfo(@PathVariable Long menuId) {
        return success(menuService.selectMenuById(menuId));
    }

    /**
     * 获取菜单下拉树列表
     */
    @GetMapping("/treeselect")
    public AjaxResult treeselect(SysMenu menu) {
        List<SysMenu> menus = menuService.selectMenuList(menu, getUserId());
        return success(menuService.buildMenuTreeSelect(menus));
    }

    /**
     * 加载对应角色菜单列表树
     * <p> 本接口返回的 checkedKeys 会按传入 roleId 回显该角色已分配的菜单，若不做鉴权，
     * 任何持登录态、只有菜单可见权的人都能遍历 roleId 窥探“某角色被授了哪些菜单”，故复用角色详情页的 system:role:query。
     */
    @PreAuthorize("@ss.hasPermi('system:role:query')")
    @GetMapping(value = "/roleMenuTreeselect/{roleId}")
    public AjaxResult roleMenuTreeselect(@PathVariable("roleId") Long roleId) {
        List<SysMenu> menus = menuService.selectMenuList(getUserId());
        AjaxResult ajax = AjaxResult.success();
        // checkedKeys 为该角色已分配的菜单 ID；menus 为当前用户可见的完整菜单树，供前端回显勾选
        ajax.put("checkedKeys", menuService.selectMenuListByRoleId(roleId));
        ajax.put("menus", menuService.buildMenuTreeSelect(menus));
        return ajax;
    }

    /**
     * 新增菜单
     */
    @PreAuthorize("@ss.hasPermi('system:menu:add')")
    @Log(title = "菜单管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@Validated @RequestBody SysMenu menu) {
        if (!menuService.checkMenuNameUnique(menu)) {
            return error("新增菜单'" + menu.getMenuName() + "'失败，菜单名称已存在");
        } else if (UserConstants.YES_FRAME.equals(menu.getIsFrame()) && !StringUtils.ishttp(menu.getPath())) {
            // 外链菜单（isFrame=是）的路径必须是完整 http(s) 地址，否则前端无法正确跳转
            return error("新增菜单'" + menu.getMenuName() + "'失败，地址必须以http(s)://开头");
        } else if (!menuService.checkRouteConfigUnique(menu)) {
            return error("新增菜单'" + menu.getMenuName() + "'失败，路由名称或地址已存在");
        }
        menu.setCreateBy(getUsername());
        return toAjax(menuService.insertMenu(menu));
    }

    /**
     * 修改菜单
     */
    @PreAuthorize("@ss.hasPermi('system:menu:edit')")
    @Log(title = "菜单管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@Validated @RequestBody SysMenu menu) {
        if (!menuService.checkMenuNameUnique(menu)) {
            return error("修改菜单'" + menu.getMenuName() + "'失败，菜单名称已存在");
        } else if (UserConstants.YES_FRAME.equals(menu.getIsFrame()) && !StringUtils.ishttp(menu.getPath())) {
            return error("修改菜单'" + menu.getMenuName() + "'失败，地址必须以http(s)://开头");
        } else if (menu.getMenuId().equals(menu.getParentId())) {
            return error("修改菜单'" + menu.getMenuName() + "'失败，上级菜单不能选择自己");
        } else if (!menuService.checkRouteConfigUnique(menu)) {
            return error("修改菜单'" + menu.getMenuName() + "'失败，路由名称或地址已存在");
        }
        menu.setUpdateBy(getUsername());
        return toAjax(menuService.updateMenu(menu));
    }

    /**
     * 保存菜单排序
     * <p> 前端拖拽后将菜单 ID 与排序号拼为两个逗号分隔字符串传回。旧实现直接 split 后交给 Service：
     * 键缺失会在本层 NPE（落到兜底返回“系统繁忙”）、空串会带 menuId=null 去批量更新、
     * 两数组长度不等会在 Service 越界。因此在入口做防御校验，不合法直接返回具体文案，不进 Service。
     */
    @PreAuthorize("@ss.hasPermi('system:menu:edit')")
    @Log(title = "保存菜单排序", businessType = BusinessType.UPDATE)
    @PutMapping("/updateSort")
    public AjaxResult updateSort(@RequestBody Map<String, String> params) {
        String menuIdsStr = params.get("menuIds");
        String orderNumsStr = params.get("orderNums");
        if (StringUtils.isEmpty(menuIdsStr) || StringUtils.isEmpty(orderNumsStr)) {
            return error("排序参数不能为空");
        }
        String[] menuIds = menuIdsStr.split(",");
        String[] orderNums = orderNumsStr.split(",");
        if (menuIds.length != orderNums.length) {
            return error("菜单数量与排序号数量不一致");
        }
        // 逐项校验：菜单 ID 必须为正整数，排序号必须为非负整数；Convert 解析失败回退默认值即判定非法，避免抛异常
        for (int i = 0; i < menuIds.length; i++) {
            if (Convert.toLong(menuIds[i], -1L) <= 0) {
                return error("非法的菜单ID：" + menuIds[i]);
            }
            if (Convert.toInt(orderNums[i], -1) < 0) {
                return error("非法的排序号：" + orderNums[i]);
            }
        }
        menuService.updateMenuSort(menuIds, orderNums);
        return success();
    }

    /**
     * 删除菜单
     */
    @PreAuthorize("@ss.hasPermi('system:menu:remove')")
    @Log(title = "菜单管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{menuId}")
    public AjaxResult remove(@PathVariable("menuId") Long menuId) {
        if (menuService.hasChildByMenuId(menuId)) {
            return warn("存在子菜单,不允许删除");
        }
        if (menuService.checkMenuExistRole(menuId)) {
            return warn("菜单已分配,不允许删除");
        }
        return toAjax(menuService.deleteMenuById(menuId));
    }
}
