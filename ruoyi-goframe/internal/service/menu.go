// Package service —— sys_menu → 前端路由树，逐行复刻 SysMenuServiceImpl.buildMenus 及全部辅助判定。
// 目标：/getRouters 输出与 Java 完全一致的 RouterVo 结构（含 Layout/ParentView/InnerLink、
// 一级菜单包虚拟子路由、目录 alwaysShow+noRedirect、内链特殊字符替换）。
package service

import (
	"context"
	"strings"

	"github.com/gogf/gf/v2/database/gdb"
	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/util/gconv"

	"ruoyi-goframe/internal/consts"
	"ruoyi-goframe/internal/model"
)

const menuCols = "menu_id, menu_name, parent_id, order_num, path, component, `query`, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon"

// SelectMenuTreeByUserId 取用户可见菜单并组装成树（根 parentId=0）。对应 selectMenuTreeByUserId。
func SelectMenuTreeByUserId(ctx context.Context, userId int64) []*model.SysMenu {
	var list []*model.SysMenu
	var recs gdb.Result
	var err error
	if IsAdmin(userId) {
		recs, err = g.DB().GetAll(ctx, "select "+menuCols+" from sys_menu where menu_type in ('M','C') and status = '0' order by parent_id, order_num")
	} else {
		recs, err = g.DB().GetAll(ctx, "select distinct m.menu_id, m.menu_name, m.parent_id, m.order_num, m.path, m.component, m.`query`, m.route_name, m.is_frame, m.is_cache, m.menu_type, m.visible, m.status, m.perms, m.icon"+
			" from sys_menu m"+
			" left join sys_role_menu rm on m.menu_id = rm.menu_id"+
			" left join sys_user_role ur on rm.role_id = ur.role_id"+
			" left join sys_role ro on ur.role_id = ro.role_id"+
			" where ur.user_id = ? and m.menu_type in ('M','C') and m.status = '0' and ro.status = '0'"+
			" order by m.parent_id, m.order_num", userId)
	}
	if err != nil {
		return nil
	}
	for _, r := range recs {
		var m model.SysMenu
		if err := gconv.Struct(r.Map(), &m); err == nil {
			list = append(list, &m)
		}
	}
	return getChildPerms(list, consts.MenuRootID)
}

// BuildMenus 将菜单树转为 RouterVo 列表。对应 buildMenus。
func BuildMenus(menus []*model.SysMenu) []*model.RouterVo {
	var routers []*model.RouterVo
	for _, menu := range menus {
		router := &model.RouterVo{}
		router.Hidden = consts.MenuHide == menu.Visible
		router.Name = getRouteName(menu)
		router.Path = getRouterPath(menu)
		router.Component = getComponent(menu)
		router.Query = menu.Query
		router.Meta = &model.MetaVo{
			Title:   menu.MenuName,
			Icon:    menu.Icon,
			NoCache: consts.MenuCacheNo == menu.IsCache,
			Link:    httpLink(menu.Path),
		}
		cMenus := menu.Children
		if len(cMenus) > 0 && consts.TypeDir == menu.MenuType {
			// 目录含子菜单：作为父路由展开
			yes := true
			router.AlwaysShow = &yes
			router.Redirect = "noRedirect"
			router.Children = BuildMenus(cMenus)
		} else if isMenuFrame(menu) {
			// 一级普通菜单：包一层虚拟子路由
			router.Meta = nil
			child := &model.RouterVo{
				Path:      menu.Path,
				Component: menu.Component,
				Name:      getRouteNameBy(menu.RouteName, menu.Path),
				Meta: &model.MetaVo{
					Title:   menu.MenuName,
					Icon:    menu.Icon,
					NoCache: consts.MenuCacheNo == menu.IsCache,
					Link:    httpLink(menu.Path),
				},
				Query: menu.Query,
			}
			router.Children = []*model.RouterVo{child}
		} else if parentIdOrRoot(menu) == consts.MenuRootID && isInnerLink(menu) {
			// 根级内链：InnerLink 包裹外部 http 地址
			router.Meta = &model.MetaVo{Title: menu.MenuName, Icon: menu.Icon}
			router.Path = "/"
			routerPath := innerLinkReplaceEach(menu.Path)
			child := &model.RouterVo{
				Path:      routerPath,
				Component: consts.InnerLink,
				Name:      getRouteNameBy(menu.RouteName, routerPath),
				Meta:      &model.MetaVo{Title: menu.MenuName, Icon: menu.Icon, Link: httpLink(menu.Path)},
			}
			router.Children = []*model.RouterVo{child}
		}
		routers = append(routers, router)
	}
	return routers
}

func getRouteName(menu *model.SysMenu) string {
	if isMenuFrame(menu) {
		return ""
	}
	return getRouteNameBy(menu.RouteName, menu.Path)
}

func getRouteNameBy(name, path string) string {
	routerName := path
	if name != "" {
		routerName = name
	}
	return capitalize(routerName)
}

func getRouterPath(menu *model.SysMenu) string {
	routerPath := menu.Path
	if parentIdOrRoot(menu) != consts.MenuRootID && isInnerLink(menu) {
		routerPath = innerLinkReplaceEach(routerPath)
	}
	if parentIdOrRoot(menu) == consts.MenuRootID && consts.TypeDir == menu.MenuType && consts.NoFrame == menu.IsFrame {
		routerPath = "/" + menu.Path
	} else if isMenuFrame(menu) {
		routerPath = "/"
	}
	return routerPath
}

func getComponent(menu *model.SysMenu) string {
	component := consts.Layout
	if menu.Component != "" && !isMenuFrame(menu) {
		component = menu.Component
	} else if menu.Component == "" && parentIdOrRoot(menu) != consts.MenuRootID && isInnerLink(menu) {
		component = consts.InnerLink
	} else if menu.Component == "" && isParentView(menu) {
		component = consts.ParentView
	}
	return component
}

func isMenuFrame(menu *model.SysMenu) bool {
	return parentIdOrRoot(menu) == consts.MenuRootID && consts.TypeMenu == menu.MenuType && consts.NoFrame == menu.IsFrame
}

func isParentView(menu *model.SysMenu) bool {
	return parentIdOrRoot(menu) != consts.MenuRootID && consts.TypeDir == menu.MenuType
}

func isInnerLink(menu *model.SysMenu) bool {
	return consts.NoFrame == menu.IsFrame && ishttp(menu.Path)
}

func parentIdOrRoot(menu *model.SysMenu) int64 {
	return menu.ParentId // sys_menu.parent_id 默认 0，非空
}

// httpLink 仅当为 http(s) 地址时才作为 meta.link（对齐 MetaVo 全参构造里的 StringUtils.ishttp 判定）。
func httpLink(path string) string {
	if ishttp(path) {
		return path
	}
	return ""
}

func innerLinkReplaceEach(path string) string {
	r := strings.NewReplacer("http://", "", "https://", "", "www.", "", ".", "/", ":", "/")
	return r.Replace(path)
}

func ishttp(s string) bool {
	return strings.HasPrefix(s, "http://") || strings.HasPrefix(s, "https://")
}

func capitalize(s string) string {
	if s == "" {
		return s
	}
	r := []rune(s)
	if r[0] >= 'a' && r[0] <= 'z' {
		r[0] = r[0] - 32
	}
	return string(r)
}

// getChildPerms 按 parentId 组装子树（同 Java getChildPerms + recursionFn）。
func getChildPerms(list []*model.SysMenu, parentId int64) []*model.SysMenu {
	var res []*model.SysMenu
	for _, t := range list {
		if t.ParentId == parentId {
			recursionFn(list, t)
			res = append(res, t)
		}
	}
	return res
}

func recursionFn(list []*model.SysMenu, t *model.SysMenu) {
	childList := getChildList(list, t)
	t.Children = childList
	for _, c := range childList {
		if len(getChildList(list, c)) > 0 {
			recursionFn(list, c)
		}
	}
}

func getChildList(list []*model.SysMenu, t *model.SysMenu) []*model.SysMenu {
	var res []*model.SysMenu
	for _, n := range list {
		if n.ParentId == t.MenuId {
			res = append(res, n)
		}
	}
	return res
}
