// Package service —— /system/menu 管理端读写，逐条复刻 SysMenuServiceImpl + SysMenuMapper.xml 的真实语义
// （列表条件过滤、下拉树、唯一性/路由冲突校验、写入纵深校验、批量排序、删除前置）。
// 出参实体与 Jackson 字段名/结构逐字对齐；树构建复用 menu.go 的 recursionFn/getChildList。
package service

import (
	"context"
	"regexp"
	"strings"

	"github.com/gogf/gf/v2/database/gdb"
	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/util/gconv"

	"ruoyi-goframe/internal/consts"
	"ruoyi-goframe/internal/model"
)

// selectMenuVo 对应 SysMenuMapper.xml 的 <sql id="selectMenuVo">：注意 perms 走 ifnull→''，
// 且未 select parent_name/create_by/update_by/update_time/remark（这些在出参里保持零值）。
const selectMenuVo = "select menu_id, menu_name, parent_id, order_num, path, component, `query`, route_name, is_frame, is_cache, menu_type, visible, status, ifnull(perms,'') as perms, icon, create_time from sys_menu"

// safeComponentPattern 复刻 SAFE_COMPONENT_PATTERN：仅字母/数字/下划线/中划线/斜杠。
var safeComponentPattern = regexp.MustCompile(`^[A-Za-z0-9_\-/]+$`)

// MenuListFilter 列表查询条件（对应 SysMenu 的 menuName/visible/status）。
type MenuListFilter struct {
	MenuName string
	Visible  string
	Status   string
}

// SelectMenuList 对应 selectMenuList(menu,userId)：管理员全量，非管理员按角色关联过滤。
func SelectMenuList(ctx context.Context, f MenuListFilter, userId int64) []*model.SysMenu {
	var recs gdb.Result
	var err error
	if IsAdmin(userId) {
		sql := selectMenuVo + buildMenuWhere(f, "where") + " order by parent_id, order_num"
		recs, err = g.DB().GetAll(ctx, sql)
	} else {
		sql := "select distinct m.menu_id, m.menu_name, m.parent_id, m.order_num, m.path, m.component, m.`query`, m.route_name, m.is_frame, m.is_cache, m.menu_type, m.visible, m.status, ifnull(m.perms,'') as perms, m.icon, m.create_time" +
			" from sys_menu m" +
			" left join sys_role_menu rm on m.menu_id = rm.menu_id" +
			" left join sys_user_role ur on rm.role_id = ur.role_id" +
			" left join sys_role ro on ur.role_id = ro.role_id" +
			" where ur.user_id = ?" + buildMenuWhereAliased(f) + " order by m.parent_id, m.order_num"
		recs, err = g.DB().GetAll(ctx, sql, userId)
	}
	if err != nil {
		return nil
	}
	return scanMenus(recs)
}

func buildMenuWhere(f MenuListFilter, _ string) string {
	var sb strings.Builder
	if f.MenuName != "" {
		sb.WriteString(" where menu_name like concat('%', '" + escLit(f.MenuName) + "', '%')")
	} else {
		sb.WriteString(" where 1=1")
	}
	if f.Visible != "" {
		sb.WriteString(" and visible = '" + escLit(f.Visible) + "'")
	}
	if f.Status != "" {
		sb.WriteString(" and status = '" + escLit(f.Status) + "'")
	}
	return sb.String()
}

func buildMenuWhereAliased(f MenuListFilter) string {
	var sb strings.Builder
	if f.MenuName != "" {
		sb.WriteString(" and m.menu_name like concat('%', '" + escLit(f.MenuName) + "', '%')")
	}
	if f.Visible != "" {
		sb.WriteString(" and m.visible = '" + escLit(f.Visible) + "'")
	}
	if f.Status != "" {
		sb.WriteString(" and m.status = '" + escLit(f.Status) + "'")
	}
	return sb.String()
}

// escLit 转义拼进 like/等值字符串字面量的单引号（参数值均来自已鉴权会话/查询串，纵深防注入）。
func escLit(s string) string { return strings.ReplaceAll(s, "'", "''") }

func scanMenus(recs gdb.Result) []*model.SysMenu {
	var out []*model.SysMenu
	for _, r := range recs {
		var m model.SysMenu
		if err := gconv.Struct(r.Map(), &m); err == nil {
			m.Children = []*model.SysMenu{} // 对齐 Java：flat 列表 children 恒为 []
			out = append(out, &m)
		}
	}
	return out
}

// SelectMenuById 对应 selectMenuById：单条，children=[]。
func SelectMenuById(ctx context.Context, menuId int64) *model.SysMenu {
	rec, err := g.DB().GetOne(ctx, selectMenuVo+" where menu_id = ?", menuId)
	if err != nil || rec == nil || rec.IsEmpty() {
		return nil
	}
	var m model.SysMenu
	if err := gconv.Struct(rec.Map(), &m); err != nil {
		return nil
	}
	m.Children = []*model.SysMenu{}
	return &m
}

// HasChildByMenuId 对应 hasChildByMenuId。
func HasChildByMenuId(ctx context.Context, menuId int64) bool {
	v, err := g.DB().GetValue(ctx, "select count(1) from sys_menu where parent_id = ?", menuId)
	return err == nil && !v.IsNil() && v.Int() > 0
}

// CheckMenuExistRole 对应 roleMenuMapper.checkMenuExistRole：菜单是否已被角色分配。
func CheckMenuExistRole(ctx context.Context, menuId int64) bool {
	v, err := g.DB().GetValue(ctx, "select count(1) from sys_role_menu where menu_id = ?", menuId)
	return err == nil && !v.IsNil() && v.Int() > 0
}

// CheckMenuNameUnique 对应 checkMenuNameUnique：同 parentId 下 menuName 唯一（排除自身）。
func CheckMenuNameUnique(ctx context.Context, menu *model.SysMenu) bool {
	parentId := menu.ParentId // 归一：sys_menu.parent_id 默认 0
	menuId := menu.MenuId
	rec, err := g.DB().GetOne(ctx, selectMenuVo+" where menu_name = ? and parent_id = ? limit 1", menu.MenuName, parentId)
	if err == nil && rec != nil && !rec.IsEmpty() {
		var info model.SysMenu
		if gconv.Struct(rec.Map(), &info) == nil && info.MenuId != menuId {
			return consts.NotUnique
		}
	}
	return consts.Unique
}

// CheckRouteConfigUnique 对应 checkRouteConfigUnique：同级路由 path 冲突 / 根级 path 全局唯一 / routeName 全局唯一。
func CheckRouteConfigUnique(ctx context.Context, menu *model.SysMenu) bool {
	menuId := menu.MenuId
	parentId := menu.ParentId
	path := menu.Path
	routeName := menu.RouteName
	if routeName == "" {
		routeName = path
	}
	recs, err := g.DB().GetAll(ctx,
		"select menu_id, menu_name, parent_id, path, route_name from sys_menu"+
			" where menu_type in ('M','C') and (path = ? or path = ? or route_name = ? or route_name = ?)",
		path, routeName, path, routeName)
	if err != nil {
		return consts.Unique
	}
	for _, rec := range recs {
		m := rec.Map()
		dbId := gconv.Int64(m["menu_id"])
		if dbId == menuId {
			continue
		}
		dbPath := gconv.String(m["path"])
		dbRoute := gconv.String(m["route_name"])
		if dbRoute == "" {
			dbRoute = dbPath
		}
		dbParent := gconv.Int64(m["parent_id"])
		if strings.EqualFold(path, dbPath) && parentId == dbParent {
			return consts.NotUnique
		} else if strings.EqualFold(path, dbPath) && parentId == consts.MenuRootID {
			return consts.NotUnique
		} else if strings.EqualFold(routeName, dbRoute) {
			return consts.NotUnique
		}
	}
	return consts.Unique
}

// InsertMenu 对应 insertMenu（含 validateMenuForWrite 纵深）。返回 (rows, 业务错误文案)。
func InsertMenu(ctx context.Context, menu *model.SysMenu, createBy string) (int, string) {
	if msg := validateMenuForWrite(ctx, menu, false); msg != "" {
		return 0, msg
	}
	menu.CreateBy = createBy
	res, err := g.DB().Exec(ctx,
		"insert into sys_menu(parent_id, menu_name, order_num, path, component, `query`, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)"+
			" values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, sysdate())",
		menu.ParentId, menu.MenuName, menu.OrderNum, menu.Path, menu.Component, menu.Query, menu.RouteName,
		menu.IsFrame, menu.IsCache, menu.MenuType, menu.Visible, menu.Status, menu.Perms, menu.Icon, menu.CreateBy)
	if err != nil {
		return 0, ""
	}
	_, _ = res.LastInsertId()
	return 1, ""
}

// UpdateMenu 对应 updateMenu（含 validateMenuForWrite 纵深）。
func UpdateMenu(ctx context.Context, menu *model.SysMenu, updateBy string) (int, string) {
	if msg := validateMenuForWrite(ctx, menu, true); msg != "" {
		return 0, msg
	}
	menu.UpdateBy = updateBy
	_, err := g.DB().Exec(ctx,
		"update sys_menu set menu_name=?, parent_id=?, order_num=?, path=?, component=?, `query`=?, route_name=?,"+
			" is_frame=?, is_cache=?, menu_type=?, visible=?, status=?, perms=?, icon=?, update_by=?, update_time=sysdate() where menu_id=?",
		menu.MenuName, menu.ParentId, menu.OrderNum, menu.Path, menu.Component, menu.Query, menu.RouteName,
		menu.IsFrame, menu.IsCache, menu.MenuType, menu.Visible, menu.Status, menu.Perms, menu.Icon, menu.UpdateBy, menu.MenuId)
	if err != nil {
		return 0, ""
	}
	return 1, ""
}

// validateMenuForWrite 复刻 validateMenuForWrite：唯一性/路由冲突/perms 通配/上级/组件路径纵深校验。
// 返回非空即业务错误文案（Controller 已先行做部分校验，此处为服务端兜底，抛出经全局异常处理回 HTTP400+文案）。
func validateMenuForWrite(ctx context.Context, menu *model.SysMenu, isUpdate bool) string {
	if !CheckMenuNameUnique(ctx, menu) {
		return "操作失败，菜单名称'" + menu.MenuName + "'在同级下已存在"
	}
	if !CheckRouteConfigUnique(ctx, menu) {
		return "操作失败，路由名称或地址'" + menu.Path + "'已存在"
	}
	if menu.Perms != "" && strings.Contains(menu.Perms, "*") {
		return "权限标识不能包含通配符 *，请填写完整权限串"
	}
	if msg := validateParent(ctx, menu, isUpdate); msg != "" {
		return msg
	}
	return validateComponentAndPath(menu)
}

func validateParent(ctx context.Context, menu *model.SysMenu, isUpdate bool) string {
	parentId := menu.ParentId
	if menu.MenuType == consts.TypeButton && parentId == consts.MenuRootID {
		return "按钮不能设为顶级菜单"
	}
	if isUpdate && menu.MenuId != 0 && menu.MenuId == parentId {
		return "上级菜单不能是自己"
	}
	if parentId != consts.MenuRootID {
		if SelectMenuById(ctx, parentId) == nil {
			return "上级菜单不存在"
		}
		if isUpdate && menu.MenuId != 0 {
			visited := map[int64]bool{}
			p := parentId
			for p != 0 && p != consts.MenuRootID && !visited[p] {
				visited[p] = true
				if p == menu.MenuId {
					return "上级菜单不能是自己的下级菜单"
				}
				pm := SelectMenuById(ctx, p)
				if pm == nil {
					break
				}
				p = pm.ParentId
			}
		}
	}
	return ""
}

func validateComponentAndPath(menu *model.SysMenu) string {
	c := menu.Component
	if c != "" && c != consts.Layout && c != consts.ParentView && c != consts.InnerLink {
		if strings.Contains(c, "..") || strings.Contains(c, "\\") || strings.HasPrefix(c, "/") || !safeComponentPattern.MatchString(c) {
			return "组件路径不合法：仅允许字母/数字/下划线/中划线/斜杠，且不得为绝对路径或含 '..'"
		}
	}
	path := menu.Path
	if path != "" && !ishttp(path) {
		if strings.Contains(path, "..") || strings.Contains(path, "\\") || strings.Contains(path, "<") ||
			strings.Contains(path, ">") || strings.Contains(path, "`") {
			return "路由地址包含非法字符"
		}
	}
	return ""
}

// UpdateMenuSort 对应 updateMenuSort（批量 CASE WHEN）。ids 与 orders 按下标配对。
func UpdateMenuSort(ctx context.Context, ids []int64, orders []int) {
	if len(ids) == 0 || len(ids) != len(orders) {
		return
	}
	// 按 case menu_id when ? then ? 顺序构造参数，末尾接 where menu_id in (...) 的 ids
	caseSql := ""
	args := make([]interface{}, 0, len(ids)*2+len(ids))
	for i := range ids {
		caseSql += " when ? then ?"
		args = append(args, ids[i], orders[i])
	}
	args = append(args, idsToInterfaces(ids)...)
	sql := "update sys_menu set order_num = case menu_id" + caseSql + " else order_num end where menu_id in (" + placeholders(len(ids)) + ")"
	_, _ = g.DB().Exec(ctx, sql, args...)
}

// DeleteMenuById 对应 deleteMenuById（物理删除）。
func DeleteMenuById(ctx context.Context, menuId int64) int {
	_, err := g.DB().Exec(ctx, "delete from sys_menu where menu_id = ?", menuId)
	if err != nil {
		return 0
	}
	return 1
}

// SelectMenuListByRoleId 对应 selectMenuListByRoleId(menuCheckStrictly=false)：角色已分配菜单 ID 列表。
func SelectMenuListByRoleId(ctx context.Context, roleId int64) []int64 {
	recs, err := g.DB().GetAll(ctx,
		"select m.menu_id from sys_menu m"+
			" left join sys_role_menu rm on m.menu_id = rm.menu_id"+
			" where rm.role_id = ? order by m.parent_id, m.order_num", roleId)
	if err != nil {
		return []int64{}
	}
	out := []int64{}
	for _, rec := range recs {
		out = append(out, gconv.Int64(rec.Map()["menu_id"]))
	}
	return out
}

// BuildMenuTreeSelect 对应 buildMenuTreeSelect(menus)：先 buildMenuTree 再转 TreeSelect。
func BuildMenuTreeSelect(list []*model.SysMenu) []*model.TreeSelect {
	trees := BuildMenuTree(list)
	out := make([]*model.TreeSelect, 0, len(trees))
	for _, m := range trees {
		out = append(out, menuToTreeSelect(m))
	}
	return out
}

// BuildMenuTree 对应 buildMenuTree：parentId 不在结果集 id 集合内者视为顶级；全空则回原列表。
func BuildMenuTree(list []*model.SysMenu) []*model.SysMenu {
	idSet := map[int64]bool{}
	for _, m := range list {
		idSet[m.MenuId] = true
	}
	var res []*model.SysMenu
	for _, m := range list {
		if !idSet[m.ParentId] {
			recursionFn(list, m)
			res = append(res, m)
		}
	}
	if len(res) == 0 {
		return list
	}
	return res
}

func menuToTreeSelect(m *model.SysMenu) *model.TreeSelect {
	ts := &model.TreeSelect{Id: m.MenuId, Label: m.MenuName, Disabled: false}
	if len(m.Children) > 0 {
		children := make([]*model.TreeSelect, 0, len(m.Children))
		for _, c := range m.Children {
			children = append(children, menuToTreeSelect(c))
		}
		ts.Children = children
	}
	return ts
}

func idsToInterfaces(ids []int64) []interface{} {
	out := make([]interface{}, len(ids))
	for i, id := range ids {
		out[i] = id
	}
	return out
}

func placeholders(n int) string {
	if n == 0 {
		return ""
	}
	var sb strings.Builder
	for i := 0; i < n; i++ {
		if i > 0 {
			sb.WriteString(",")
		}
		sb.WriteString("?")
	}
	return sb.String()
}
