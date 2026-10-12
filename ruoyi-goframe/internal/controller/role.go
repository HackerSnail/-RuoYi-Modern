// Package controller —— /system/role 管理端 HTTP 处理器，逐条对齐 SysRoleController 的入参/出参/校验顺序/文案 + DataScope + 会话失效。
package controller

import (
	"strings"

	"github.com/gogf/gf/v2/net/ghttp"
	"github.com/gogf/gf/v2/util/gconv"

	"ruoyi-goframe/internal/api"
	"ruoyi-goframe/internal/middleware"
	"ruoyi-goframe/internal/model"
	"ruoyi-goframe/internal/service"
)

const (
	rolePermiList   = "system:role:list"
	rolePermiQuery  = "system:role:query"
	rolePermiAdd    = "system:role:add"
	rolePermiEdit   = "system:role:edit"
	rolePermiRemove = "system:role:remove"
)

// roleScope 计算当前会话对角色查询的数据范围片段（admin 返回空串）。
func roleScope(r *ghttp.Request, permission string) string {
	lu := middleware.CurrentLoginUser(r)
	return service.BuildDataScope(r.Context(), lu, permission, "d", "dept_id", "", "")
}

// RoleList GET /system/role/list（system:role:list）→ TableDataInfo（分页 + DataScope，distinct 计数）。
func RoleList(r *ghttp.Request) {
	ctx := r.Context()
	f := service.RoleListFilter{
		RoleId:    r.Get("roleId").Int64(),
		RoleName:  r.Get("roleName").String(),
		Status:    r.Get("status").String(),
		RoleKey:   r.Get("roleKey").String(),
		BeginTime: paramRange(r, "beginTime"),
		EndTime:   paramRange(r, "endTime"),
	}
	rows, total, err := service.SelectRoleListPaged(ctx, f, roleScope(r, rolePermiList), service.ParsePage(r))
	if err != nil {
		api.Send(r, api.Err("查询失败"))
		return
	}
	api.Send(r, service.DataTable(total, rows))
}

// RoleGetInfo GET /system/role/{roleId}（system:role:query）→ data:{role}；数据范围外回 code500 文案。
func RoleGetInfo(r *ghttp.Request) {
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	roleId := r.Get("roleId").Int64()
	if msg := service.CheckRoleDataScope(ctx, lu, rolePermiQuery, roleId); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	api.Send(r, api.OkData(service.SelectRoleById(ctx, roleId)))
}

// RoleAdd POST /system/role（system:role:add）。@Validated → 名称唯一 → 权限字符唯一 → insertRole(含菜单)。
func RoleAdd(r *ghttp.Request) {
	var role model.SysRole
	if err := r.Parse(&role); err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	if vs := validateRole(&role, roleSortPresent(r)); len(vs) > 0 {
		api.Send(r, validErrBody(vs))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if !service.CheckRoleNameUnique(ctx, &role) {
		api.Send(r, api.Err("新增角色'"+role.RoleName+"'失败，角色名称已存在"))
		return
	}
	if !service.CheckRoleKeyUnique(ctx, &role) {
		api.Send(r, api.Err("新增角色'"+role.RoleName+"'失败，角色权限已存在"))
		return
	}
	rows, msg := service.InsertRole(ctx, &role, lu.Username)
	if msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	api.Send(r, toAjax(rows))
}

// RoleEdit PUT /system/role（system:role:edit）。超管/数据范围 → 名称/权限唯一 → updateRole → 刷新持有者权限。
func RoleEdit(r *ghttp.Request) {
	var role model.SysRole
	if err := r.Parse(&role); err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	if vs := validateRole(&role, roleSortPresent(r)); len(vs) > 0 {
		api.Send(r, validErrBody(vs))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if msg := service.CheckRoleAllowed(&role); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	if msg := service.CheckRoleDataScope(ctx, lu, rolePermiEdit, role.RoleId); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	if !service.CheckRoleNameUnique(ctx, &role) {
		api.Send(r, api.Err("修改角色'"+role.RoleName+"'失败，角色名称已存在"))
		return
	}
	if !service.CheckRoleKeyUnique(ctx, &role) {
		api.Send(r, api.Err("修改角色'"+role.RoleName+"'失败，角色权限已存在"))
		return
	}
	rows, msg := service.UpdateRole(ctx, &role, lu.Username)
	if msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	if rows > 0 {
		service.RefreshPermissionByRoleId(ctx, role.RoleId)
		api.Send(r, api.Ok())
		return
	}
	api.Send(r, api.Err("修改角色'"+role.RoleName+"'失败，请联系管理员"))
}

// RoleDataScope PUT /system/role/dataScope（system:role:edit）。超管/数据范围 → authDataScope → 强制持有者重登。
func RoleDataScope(r *ghttp.Request) {
	var role model.SysRole
	if err := r.Parse(&role); err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if msg := service.CheckRoleAllowed(&role); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	if msg := service.CheckRoleDataScope(ctx, lu, rolePermiEdit, role.RoleId); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	rows, _ := service.AuthDataScope(ctx, &role)
	if rows > 0 {
		service.DelLoginUsersByRoleId(ctx, role.RoleId)
	}
	api.Send(r, toAjax(rows))
}

// RoleChangeStatus PUT /system/role/changeStatus（system:role:edit）。超管/数据范围 → 改状态 → 强制持有者重登。
func RoleChangeStatus(r *ghttp.Request) {
	var role model.SysRole
	if err := r.Parse(&role); err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if msg := service.CheckRoleAllowed(&role); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	if msg := service.CheckRoleDataScope(ctx, lu, rolePermiEdit, role.RoleId); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	role.UpdateBy = lu.Username
	rows, _ := service.UpdateRoleStatus(ctx, &role)
	if rows > 0 {
		service.DelLoginUsersByRoleId(ctx, role.RoleId)
	}
	api.Send(r, toAjax(rows))
}

// RoleRemove DELETE /system/role/{roleIds}（system:role:remove）。超管/数据范围/已分配护栏后批量软删。
func RoleRemove(r *ghttp.Request) {
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	ids, ok := splitCommaInts(r.Get("roleIds").String())
	if !ok || len(ids) == 0 {
		api.Send(r, api.Err("角色 ID 非法"))
		return
	}
	rows, msg := service.DeleteRoleByIds(ctx, lu, rolePermiRemove, ids)
	if msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	api.Send(r, toAjax(rows))
}

// RoleOptionSelect GET /system/role/optionselect（system:role:query）→ data:[SysRole...]（含 DataScope，未删除全量）。
func RoleOptionSelect(r *ghttp.Request) {
	lu := middleware.CurrentLoginUser(r)
	list := service.SelectRoleAll(r.Context(), lu, rolePermiQuery)
	api.Send(r, api.OkData(list))
}

// RoleDeptTree GET /system/role/deptTree/{roleId}（system:role:query）→ 顶层 {checkedKeys, depts}。
func RoleDeptTree(r *ghttp.Request) {
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	roleId := r.Get("roleId").Int64()
	if msg := service.CheckRoleDataScope(ctx, lu, rolePermiQuery, roleId); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	role := service.SelectRoleById(ctx, roleId)
	if role == nil {
		api.Send(r, api.Err("角色不存在或已被删除"))
		return
	}
	checkedKeys := service.SelectDeptListByRoleId(ctx, roleId, role.DeptCheckStrictly)
	depts := service.BuildDeptTreeSelect(service.SelectDeptList(ctx, service.DeptListFilter{},
		service.BuildDataScope(ctx, lu, rolePermiQuery, "d", "dept_id", "", "")))
	body := api.Ok()
	body["checkedKeys"] = checkedKeys
	body["depts"] = depts
	api.Send(r, body)
}

// roleSortPresent 判定 body 是否显式携带 roleSort（对齐 @NotNull：缺字段即违规，0 仍合法）。
func roleSortPresent(r *ghttp.Request) bool {
	v := r.Get("roleSort")
	return !v.IsNil() && v.String() != ""
}

// validateRole 复刻 SysRole 的 @Validated 字段约束（roleName/roleKey NotBlank+Size，roleSort NotNull）。
func validateRole(role *model.SysRole, sortPresent bool) []violation {
	var vs []violation
	if role.RoleName == "" {
		vs = append(vs, violation{"roleName", "角色名称不能为空"})
	} else if runeLen(role.RoleName) > 30 {
		vs = append(vs, violation{"roleName", "角色名称长度不能超过30个字符"})
	}
	if role.RoleKey == "" {
		vs = append(vs, violation{"roleKey", "权限字符不能为空"})
	} else if runeLen(role.RoleKey) > 100 {
		vs = append(vs, violation{"roleKey", "权限字符长度不能超过100个字符"})
	}
	if !sortPresent {
		vs = append(vs, violation{"roleSort", "显示顺序不能为空"})
	}
	return vs
}

// roleUserScope 计算 authUser 用户查询的数据范围（deptAlias=d、userAlias=u，供 DATA_SCOPE_SELF 兜底）。
func roleUserScope(r *ghttp.Request) string {
	lu := middleware.CurrentLoginUser(r)
	return service.BuildDataScope(r.Context(), lu, rolePermiList, "d", "dept_id", "u", "user_id")
}

// AllocatedList GET /system/role/authUser/allocatedList（system:role:list）→ 已分配该角色的用户分页。
func AllocatedList(r *ghttp.Request) {
	ctx := r.Context()
	rows, total, err := service.SelectAllocatedList(ctx, r.Get("roleId").Int64(),
		r.Get("userName").String(), r.Get("phonenumber").String(), roleUserScope(r), service.ParsePage(r))
	if err != nil {
		api.Send(r, api.Err("查询失败"))
		return
	}
	api.Send(r, service.DataTable(total, rows))
}

// UnallocatedList GET /system/role/authUser/unallocatedList（system:role:list）→ 未分配该角色的用户分页。
func UnallocatedList(r *ghttp.Request) {
	ctx := r.Context()
	rows, total, err := service.SelectUnallocatedList(ctx, r.Get("roleId").Int64(),
		r.Get("userName").String(), r.Get("phonenumber").String(), roleUserScope(r), service.ParsePage(r))
	if err != nil {
		api.Send(r, api.Err("查询失败"))
		return
	}
	api.Send(r, service.DataTable(total, rows))
}

// CancelAuthUser PUT /system/role/authUser/cancel（system:role:edit）。取消单用户某角色 → 强制该用户重登。
func CancelAuthUser(r *ghttp.Request) {
	ctx := r.Context()
	userId := r.Get("userId").Int64()
	roleId := r.Get("roleId").Int64()
	rows, _ := service.DeleteAuthUser(ctx, userId, roleId)
	if rows > 0 {
		service.DelLoginUsers(ctx, userId)
	}
	api.Send(r, toAjax(rows))
}

// CancelAuthUserAll PUT /system/role/authUser/cancelAll（system:role:edit）。批量取消某角色下多用户 → 逐个强制重登。
func CancelAuthUserAll(r *ghttp.Request) {
	ctx := r.Context()
	roleId := r.Get("roleId").Int64()
	userIds := parseUserIds(r)
	rows, _ := service.DeleteAuthUsers(ctx, roleId, userIds)
	if rows > 0 {
		for _, uid := range userIds {
			service.DelLoginUsers(ctx, uid)
		}
	}
	api.Send(r, toAjax(rows))
}

// SelectAuthUserAll PUT /system/role/authUser/selectAll（system:role:edit）。数据范围校验 → 批量分配 → 逐个强制重登。
func SelectAuthUserAll(r *ghttp.Request) {
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	roleId := r.Get("roleId").Int64()
	userIds := parseUserIds(r)
	if msg := service.CheckRoleDataScope(ctx, lu, rolePermiEdit, roleId); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	rows, _ := service.InsertAuthUsers(ctx, roleId, userIds)
	if rows > 0 {
		for _, uid := range userIds {
			service.DelLoginUsers(ctx, uid)
		}
	}
	api.Send(r, toAjax(rows))
}

// parseUserIds 解析 userIds 参数：优先重复 query 多值（userIds=1&userIds=2），再试 JSON 数组，回退逗号串。
func parseUserIds(r *ghttp.Request) []int64 {
	if vals := r.URL.Query()["userIds"]; len(vals) > 0 {
		ids, _ := splitCommaInts(strings.Join(vals, ","))
		return ids
	}
	v := r.Get("userIds")
	if arr := gconv.Int64s(v.Val()); len(arr) > 0 {
		return arr
	}
	ids, _ := splitCommaInts(v.String())
	return ids
}
