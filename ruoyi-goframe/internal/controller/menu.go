// Package controller —— /system/menu 管理端 HTTP 处理器，逐条对齐 SysMenuController 的入参/出参/校验顺序/文案。
package controller

import (
	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/response"
	"ruoyi-goframe/internal/consts"
	"ruoyi-goframe/internal/middleware"
	"ruoyi-goframe/internal/model"
	"ruoyi-goframe/internal/service"
)

// menuFilter 从查询串构造列表过滤条件（对应 SysMenu 绑定的 menuName/visible/status）。
func menuFilter(r *ghttp.Request) service.MenuListFilter {
	return service.MenuListFilter{
		MenuName: r.Get("menuName").String(),
		Visible:  r.Get("visible").String(),
		Status:   r.Get("status").String(),
	}
}

// MenuList GET /system/menu/list（system:menu:list）→ data:[SysMenu...]（flat，children 恒 []）。
func MenuList(r *ghttp.Request) {
	userId := middleware.CurrentLoginUser(r).UserId
	list := service.SelectMenuList(r.Context(), menuFilter(r), userId)
	if list == nil {
		list = []*model.SysMenu{}
	}
	response.Send(r, response.OkData(list))
}

// MenuGetInfo GET /system/menu/{menuId}（system:menu:query）→ data:{menu}（不存在则 data 省略）。
func MenuGetInfo(r *ghttp.Request) {
	menuId := r.Get("menuId").Int64()
	response.Send(r, response.OkData(service.SelectMenuById(r.Context(), menuId)))
}

// MenuTreeselect GET /system/menu/treeselect（仅登录，无 permi）→ data:[TreeSelect...]。
func MenuTreeselect(r *ghttp.Request) {
	userId := middleware.CurrentLoginUser(r).UserId
	list := service.SelectMenuList(r.Context(), menuFilter(r), userId)
	response.Send(r, response.OkData(service.BuildMenuTreeSelect(list)))
}

// RoleMenuTreeselect GET /system/menu/roleMenuTreeselect/{roleId}（system:role:query）
// → 顶层 {checkedKeys:[...], menus:[TreeSelect...]}（对齐 ajax.put 顶层键，非 data 下）。
func RoleMenuTreeselect(r *ghttp.Request) {
	roleId := r.Get("roleId").Int64()
	userId := middleware.CurrentLoginUser(r).UserId
	// Java 此处 selectMenuList(getUserId()) 无过滤
	list := service.SelectMenuList(r.Context(), service.MenuListFilter{}, userId)
	body := response.Ok()
	body["checkedKeys"] = service.SelectMenuListByRoleId(r.Context(), roleId)
	body["menus"] = service.BuildMenuTreeSelect(list)
	response.Send(r, body)
}

// MenuAdd POST /system/menu（system:menu:add）。校验顺序：@Validated → 名称唯一 → 外链须 http → 路由唯一 → 服务纵深。
func MenuAdd(r *ghttp.Request) {
	var menu model.SysMenu
	if err := r.Parse(&menu); err != nil {
		response.Send(r, response.Err("请求参数有误"))
		return
	}
	if vs := validateMenu(&menu); len(vs) > 0 {
		response.Send(r, validErrBody(vs))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if !service.CheckMenuNameUnique(ctx, &menu) {
		response.Send(r, response.Err("新增菜单'"+menu.MenuName+"'失败，菜单名称已存在"))
		return
	}
	if menu.IsFrame == consts.YesFrame && !isHTTP(menu.Path) {
		response.Send(r, response.Err("新增菜单'"+menu.MenuName+"'失败，地址必须以http(s)://开头"))
		return
	}
	if !service.CheckRouteConfigUnique(ctx, &menu) {
		response.Send(r, response.Err("新增菜单'"+menu.MenuName+"'失败，路由名称或地址已存在"))
		return
	}
	rows, msg := service.InsertMenu(ctx, &menu, lu.Username)
	if msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	response.Send(r, toAjax(rows))
}

// MenuEdit PUT /system/menu（system:menu:edit）。校验顺序：@Validated → 名称唯一 → 外链 http → 自父 → 路由唯一 → 服务纵深。
func MenuEdit(r *ghttp.Request) {
	var menu model.SysMenu
	if err := r.Parse(&menu); err != nil {
		response.Send(r, response.Err("请求参数有误"))
		return
	}
	if vs := validateMenu(&menu); len(vs) > 0 {
		response.Send(r, validErrBody(vs))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if !service.CheckMenuNameUnique(ctx, &menu) {
		response.Send(r, response.Err("修改菜单'"+menu.MenuName+"'失败，菜单名称已存在"))
		return
	}
	if menu.IsFrame == consts.YesFrame && !isHTTP(menu.Path) {
		response.Send(r, response.Err("修改菜单'"+menu.MenuName+"'失败，地址必须以http(s)://开头"))
		return
	}
	if menu.MenuId == menu.ParentId {
		response.Send(r, response.Err("修改菜单'"+menu.MenuName+"'失败，上级菜单不能选择自己"))
		return
	}
	if !service.CheckRouteConfigUnique(ctx, &menu) {
		response.Send(r, response.Err("修改菜单'"+menu.MenuName+"'失败，路由名称或地址已存在"))
		return
	}
	rows, msg := service.UpdateMenu(ctx, &menu, lu.Username)
	if msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	// 注：Java 在 edit 成功后刷新全部在线用户权限（refreshAllOnlineUserPermission）。
	// P1a 单后端 + admin 通配权场景下为 no-op，非 admin 在线权限刷新随用户/角色模块(P1b)一并接入。
	response.Send(r, toAjax(rows))
}

// MenuUpdateSort PUT /system/menu/updateSort（system:menu:edit）。入参 {menuIds,orderNums} 逗号串。
func MenuUpdateSort(r *ghttp.Request) {
	menuIdsStr := r.Get("menuIds").String()
	orderNumsStr := r.Get("orderNums").String()
	if menuIdsStr == "" || orderNumsStr == "" {
		response.Send(r, response.Err("排序参数不能为空"))
		return
	}
	menuIds := splitCommas(menuIdsStr)
	orderNums := splitCommas(orderNumsStr)
	if len(menuIds) != len(orderNums) {
		response.Send(r, response.Err("菜单数量与排序号数量不一致"))
		return
	}
	ids := make([]int64, 0, len(menuIds))
	orders := make([]int, 0, len(orderNums))
	for i := range menuIds {
		id, err := parseInt(menuIds[i])
		if err != nil || id <= 0 {
			response.Send(r, response.Err("非法的菜单ID："+menuIds[i]))
			return
		}
		on, err := parseInt(orderNums[i])
		if err != nil || on < 0 {
			response.Send(r, response.Err("非法的排序号："+orderNums[i]))
			return
		}
		ids = append(ids, id)
		orders = append(orders, int(on))
	}
	service.UpdateMenuSort(r.Context(), ids, orders)
	response.Send(r, response.Ok())
}

// MenuRemove DELETE /system/menu/{menuId}（system:menu:remove）。有子菜单/已分配 → warn(601)。
func MenuRemove(r *ghttp.Request) {
	ctx := r.Context()
	menuId := r.Get("menuId").Int64()
	if service.HasChildByMenuId(ctx, menuId) {
		response.Send(r, response.Warn("存在子菜单,不允许删除"))
		return
	}
	if service.CheckMenuExistRole(ctx, menuId) {
		response.Send(r, response.Warn("菜单已分配,不允许删除"))
		return
	}
	response.Send(r, toAjax(service.DeleteMenuById(ctx, menuId)))
}

// validateMenu 复刻 SysMenu 的 @Validated 字段约束（NotBlank/Size），返回违规列表。
func validateMenu(m *model.SysMenu) []violation {
	var vs []violation
	if m.MenuName == "" {
		vs = append(vs, violation{"menuName", "菜单名称不能为空"})
	} else if runeLen(m.MenuName) > 50 {
		vs = append(vs, violation{"menuName", "菜单名称长度不能超过50个字符"})
	}
	if runeLen(m.Path) > 200 {
		vs = append(vs, violation{"path", "路由地址不能超过200个字符"})
	}
	if runeLen(m.Component) > 200 {
		vs = append(vs, violation{"component", "组件路径不能超过255个字符"})
	}
	if m.MenuType == "" {
		vs = append(vs, violation{"menuType", "菜单类型不能为空"})
	}
	if runeLen(m.Perms) > 100 {
		vs = append(vs, violation{"perms", "权限标识长度不能超过100个字符"})
	}
	return vs
}

func isHTTP(s string) bool {
	return len(s) >= 7 && (hasPrefix(s, "http://") || hasPrefix(s, "https://"))
}

func hasPrefix(s, p string) bool {
	return len(s) >= len(p) && s[:len(p)] == p
}
