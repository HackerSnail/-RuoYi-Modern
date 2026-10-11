// Package service —— 数据范围过滤，逐条复刻 DataScopeAspect.dataScopeFilter 的 SQL 拼接语义。
//
// 关键契约：
//   - 超级管理员(userId==1)不过滤（对齐 handleDataScope 的 currentUser.isAdmin() 短路）。
//   - permission 参数 = 该接口 @PreAuthorize 的权限串（对齐 PermissionContextHolder；本 fork 由调用方显式传入）。
//   - 停用角色跳过；同一 dataScope 只拼一次；含 ALL 时清空并短路（不再追加限制）。
//   - 角色均不匹配 permission 时 conditions 为空 → 追加 "dept_id = 0" 不查任何数据。
//   - 结果以 " AND ( ... )" 形式返回，去掉首部多余 " OR "（对齐 sqlString.substring(4)）。
package service

import (
	"context"
	"strings"

	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/util/gconv"

	"ruoyi-goframe/internal/consts"
	"ruoyi-goframe/internal/model"
)

// scopeRole 数据范围计算所需的角色最小信息（roleId/dataScope/status + 该角色的权限串集合）。
type scopeRole struct {
	RoleId      int64
	DataScope   string
	Status      string
	Permissions []string
}

// BuildDataScope 复刻 dataScopeFilter：返回拼进查询 WHERE 的片段（无过滤返回空串）。
// deptAlias/deptField 对应部门维度的表别名与列名；userAlias/userField 对应仅本人维度的用户表别名与列名（无则传空）。
func BuildDataScope(ctx context.Context, lu *model.LoginUser, permission, deptAlias, deptField, userAlias, userField string) string {
	if lu == nil || lu.User == nil {
		return ""
	}
	u := lu.User
	// 超级管理员不过滤数据（对齐 handleDataScope）
	if IsAdmin(u.UserId) {
		return ""
	}
	deptAlias = safeAlias(deptAlias)
	userAlias = safeAlias(userAlias)
	deptField = safeAlias(deptField)
	userField = safeAlias(userField)

	roles := selectRolesForScope(ctx, u.UserId)

	var sb strings.Builder
	var conditions []string
	// 阶段1：预收集拥有"自定义"数据权限、状态正常且匹配当前权限串的角色 ID
	var scopeCustomIds []int64
	for _, r := range roles {
		if r.DataScope == consts.DataScopeCustom && r.Status == consts.RoleNormal &&
			(permission == "" || permsContainsAny(r.Permissions, permission)) {
			scopeCustomIds = append(scopeCustomIds, r.RoleId)
		}
	}
	// 阶段2：遍历角色拼接 SQL
	for _, r := range roles {
		ds := r.DataScope
		if strIndex(conditions, ds) >= 0 || r.Status == consts.RoleDisable {
			continue
		}
		if permission != "" && !permsContainsAny(r.Permissions, permission) {
			continue
		}
		switch ds {
		case consts.DataScopeAll:
			// 全部数据权限：清空已拼接并短路（sqlString 为空即不加过滤）
			sb.Reset()
			conditions = append(conditions, ds)
			return finishScope(sb, conditions, deptAlias, deptField) // 内部 sb 为空 → 返回 ""
		case consts.DataScopeCustom:
			if len(scopeCustomIds) > 1 {
				sb.WriteString(format(" OR {}.{} IN ( SELECT dept_id FROM sys_role_dept WHERE role_id in ({}) ) ",
					deptAlias, deptField, joinInt64(scopeCustomIds, ",")))
			} else {
				sb.WriteString(format(" OR {}.{} IN ( SELECT dept_id FROM sys_role_dept WHERE role_id = {} ) ",
					deptAlias, deptField, r.RoleId))
			}
		case consts.DataScopeDept:
			sb.WriteString(format(" OR {}.{} = {} ", deptAlias, deptField, u.DeptId))
		case consts.DataScopeDeptAndChild:
			sb.WriteString(format(" OR {}.{} IN ( SELECT dept_id FROM sys_dept WHERE dept_id = {} or find_in_set( {} , ancestors ) )",
				deptAlias, deptField, u.DeptId, u.DeptId))
		case consts.DataScopeSelf:
			if strings.TrimSpace(userAlias) != "" {
				sb.WriteString(format(" OR {}.{} = {} ", userAlias, userField, u.UserId))
			} else {
				sb.WriteString(format(" OR {}.{} = 0 ", deptAlias, deptField))
			}
		}
		conditions = append(conditions, ds)
	}
	return finishScope(sb, conditions, deptAlias, deptField)
}

// finishScope 收尾：conditions 空则兜底 "dept_id = 0"；非空则以 " AND ( ... )" 去掉首部 " OR "。
func finishScope(sb strings.Builder, conditions []string, deptAlias, deptField string) string {
	if len(conditions) == 0 {
		sb.WriteString(format(" OR {}.{} = 0 ", deptAlias, deptField))
	}
	s := sb.String()
	if strings.TrimSpace(s) == "" {
		return ""
	}
	return " AND (" + s[4:] + ")"
}

// selectRolesForScope 取用户角色（roleId/dataScope/status），正常非管理员角色附带其菜单权限集合，
// 对齐登录期 SysPermissionService.getMenuPermission 对 role.setPermissions 的填充。
func selectRolesForScope(ctx context.Context, userId int64) []scopeRole {
	recs, err := g.DB().GetAll(ctx,
		"select r.role_id, r.data_scope, r.status from sys_role r"+
			" left join sys_user_role ur on ur.role_id = r.role_id"+
			" where ur.user_id = ? and r.del_flag = '0'", userId)
	if err != nil || recs == nil {
		return nil
	}
	var out []scopeRole
	for _, rec := range recs {
		m := rec.Map()
		r := scopeRole{
			RoleId:    gconv.Int64(m["role_id"]),
			DataScope: gconv.String(m["data_scope"]),
			Status:    gconv.String(m["status"]),
		}
		// 仅正常且非管理员(role_id!=1)角色加载权限，与 Java 一致
		if r.Status == consts.RoleNormal && r.RoleId != 1 {
			r.Permissions = selectMenuPermsByRoleId(ctx, r.RoleId)
		}
		out = append(out, r)
	}
	return out
}

// selectMenuPermsByRoleId 取某角色的菜单权限集合（逗号串再拆分去重），对应 SysMenuMapper.selectMenuPermsByRoleId。
func selectMenuPermsByRoleId(ctx context.Context, roleId int64) []string {
	rs, err := g.DB().GetAll(ctx,
		"select distinct m.perms from sys_menu m"+
			" left join sys_role_menu rm on m.menu_id = rm.menu_id"+
			" where m.status = '0' and rm.role_id = ?", roleId)
	if err != nil || rs == nil {
		return nil
	}
	var raw []string
	for _, rec := range rs {
		for _, v := range rec {
			s := v.String()
			if s != "" {
				raw = append(raw, s)
			}
			break
		}
	}
	return splitPerms(raw)
}

// permsContainsAny 复刻 StringUtils.containsAny(collection, toStrArray(permission))：
// permission 以逗号分隔，任一子串命中角色权限即真。
func permsContainsAny(perms []string, permission string) bool {
	if len(perms) == 0 {
		return false
	}
	for _, target := range splitByComma(permission) {
		t := strings.TrimSpace(target)
		if t == "" {
			continue
		}
		for _, p := range perms {
			if p == t {
				return true
			}
		}
	}
	return false
}

// safeAlias 复刻 ALIAS_PATTERN 白名单：空白原样返回，非空白但含非法字符则返回空（拒绝拼接，避免注入面）。
func safeAlias(alias string) string {
	if strings.TrimSpace(alias) == "" {
		return alias
	}
	for _, c := range alias {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '.') {
			return ""
		}
	}
	return alias
}

func strIndex(s []string, v string) int {
	for i, x := range s {
		if x == v {
			return i
		}
	}
	return -1
}

func joinInt64(ids []int64, sep string) string {
	parts := make([]string, len(ids))
	for i, id := range ids {
		parts[i] = gconv.String(id)
	}
	return strings.Join(parts, sep)
}

// format 极简 {} 占位替换（对齐 StringUtils.format），按顺序把 {} 换成参数值。
func format(pattern string, args ...interface{}) string {
	var sb strings.Builder
	ai := 0
	for i := 0; i < len(pattern); i++ {
		if pattern[i] == '{' && i+1 < len(pattern) && pattern[i+1] == '}' && ai < len(args) {
			sb.WriteString(gconv.String(args[ai]))
			ai++
			i++
		} else {
			sb.WriteByte(pattern[i])
		}
	}
	return sb.String()
}
