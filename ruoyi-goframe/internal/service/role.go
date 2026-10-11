// Package service —— /system/role 读写，逐条复刻 SysRoleServiceImpl + SysRoleMapper.xml 的真实语义
// （列表条件过滤 + DataScope + distinct 分页、唯一性/超管/数据范围校验、先删后插的菜单/部门关联、软删除、授权用户增删）。
package service

import (
	"context"
	"database/sql"
	"fmt"

	"github.com/gogf/gf/v2/database/gdb"
	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/util/gconv"

	"ruoyi-goframe/internal/consts"
	"ruoyi-goframe/internal/model"
)

// selectRoleVo 对应 SysRoleMapper.xml <sql id="selectRoleVo">：distinct + 预关联 ur/user/dept（供数据范围拼接与去重）。
// 注意未选 create_by/update_by/update_time（出参保持零值，同 Java resultMap 对这些列不映射）。
const selectRoleVo = "select distinct r.role_id, r.role_name, r.role_key, r.role_sort, r.data_scope," +
	" r.menu_check_strictly, r.dept_check_strictly, r.status, r.del_flag, r.create_time, r.remark" +
	" from sys_role r" +
	" left join sys_user_role ur on ur.role_id = r.role_id" +
	" left join sys_user u on u.user_id = ur.user_id" +
	" left join sys_dept d on u.dept_id = d.dept_id"

// RoleListFilter 角色列表条件（对应 SysRole 绑定字段 + params 时间范围）。
type RoleListFilter struct {
	RoleId    int64
	RoleName  string
	Status    string
	RoleKey   string
	BeginTime string
	EndTime   string
}

// buildRoleListSQL 组装 selectRoleVo + del_flag='0' + 条件过滤 + 数据范围片段，返回无 order/limit 的基础查询。
func buildRoleListSQL(f RoleListFilter, dataScope string) (string, []interface{}) {
	sql := selectRoleVo + " where r.del_flag = '0'"
	args := make([]interface{}, 0, 6)
	if f.RoleId != 0 {
		sql += " and r.role_id = ?"
		args = append(args, f.RoleId)
	}
	if f.RoleName != "" {
		sql += " and r.role_name like concat('%', ?, '%')"
		args = append(args, f.RoleName)
	}
	if f.Status != "" {
		sql += " and r.status = ?"
		args = append(args, f.Status)
	}
	if f.RoleKey != "" {
		sql += " and r.role_key like concat('%', ?, '%')"
		args = append(args, f.RoleKey)
	}
	if f.BeginTime != "" {
		sql += " and date_format(r.create_time,'%Y%m%d') >= date_format(?, '%Y%m%d')"
		args = append(args, f.BeginTime)
	}
	if f.EndTime != "" {
		sql += " and date_format(r.create_time,'%Y%m%d') <= date_format(?, '%Y%m%d')"
		args = append(args, f.EndTime)
	}
	sql += " " + dataScope
	return sql, args
}

// SelectRoleList 对应 selectRoleList(role)：不分页（供 checkRoleDataScope 存在性判定、optionselect 全量）。
func SelectRoleList(ctx context.Context, f RoleListFilter, dataScope string) []model.SysRole {
	sql, args := buildRoleListSQL(f, dataScope)
	sql += " order by r.role_sort"
	recs, err := g.DB().GetAll(ctx, sql, args...)
	if err != nil {
		return nil
	}
	return scanRoles(recs)
}

// SelectRoleListPaged 对应 list 端点 startPage + selectRoleList：distinct 子查询计数 + role_sort 排序 + 分页。
func SelectRoleListPaged(ctx context.Context, f RoleListFilter, dataScope string, p Page) ([]model.SysRole, int64, error) {
	sql, args := buildRoleListSQL(f, dataScope)
	res, err := QueryPaged(ctx, p, sql, args, "r.role_sort")
	if err != nil {
		return nil, 0, err
	}
	return scanRoles(res.Rows), res.Total, nil
}

func scanRoles(recs gdb.Result) []model.SysRole {
	out := make([]model.SysRole, 0, len(recs))
	for _, r := range recs {
		var role model.SysRole
		if gconv.Struct(r.Map(), &role) != nil {
			continue
		}
		role.Admin = role.RoleId == 1 // 派生 isAdmin()
		out = append(out, role)
	}
	return out
}

// SelectRoleById 对应 selectRoleById(roleId)。不存在返回 nil。
func SelectRoleById(ctx context.Context, roleId int64) *model.SysRole {
	rec, err := g.DB().GetOne(ctx, selectRoleVo+" where r.role_id = ?", roleId)
	if err != nil || rec == nil || rec.IsEmpty() {
		return nil
	}
	var role model.SysRole
	if gconv.Struct(rec.Map(), &role) != nil {
		return nil
	}
	role.Admin = role.RoleId == 1
	return &role
}

// SelectRoleAll 对应 selectRoleAll：经 AOP 代理调 selectRoleList(new SysRole()) → 带 DataScope、无过滤、未删除。
// permission 为当前 HTTP 端点的 @PreAuthorize 串（对齐 PermissionContextHolder：@DataScope 注解未声明 permission 时回退到上下文权限）。
func SelectRoleAll(ctx context.Context, lu *model.LoginUser, permission string) []model.SysRole {
	scope := BuildDataScope(ctx, lu, permission, "d", "dept_id", "", "")
	return SelectRoleList(ctx, RoleListFilter{}, scope)
}

// selectRolePermissionByUserId 对应 SysRoleMapper.selectRolePermissionByUserId：
// selectRoleVo WHERE r.del_flag='0' and ur.user_id = ?（用户持有的有效角色）。
func selectRolePermissionByUserId(ctx context.Context, userId int64) []model.SysRole {
	recs, err := g.DB().GetAll(ctx, selectRoleVo+" WHERE r.del_flag = '0' and ur.user_id = ?", userId)
	if err != nil {
		return nil
	}
	return scanRoles(recs)
}

// SelectRolesByUserId 对应 selectRolesByUserId：取当前用户数据范围内全部角色（selectRoleAll），
// 命中目标用户持有的角色置 flag=true（供前端"已拥有角色"回显）。
func SelectRolesByUserId(ctx context.Context, lu *model.LoginUser, permission string, userId int64) []model.SysRole {
	userRoles := selectRolePermissionByUserId(ctx, userId)
	roles := SelectRoleAll(ctx, lu, permission)
	held := make(map[int64]bool, len(userRoles))
	for _, ur := range userRoles {
		held[ur.RoleId] = true
	}
	for i := range roles {
		if held[roles[i].RoleId] {
			roles[i].Flag = true
		}
	}
	return roles
}

// checkRoleUniqueBy 复用 checkRoleNameUnique/checkRoleKeyUnique 的 limit 1 短路查询。
func checkRoleUniqueBy(ctx context.Context, col, val string) *model.SysRole {
	if val == "" {
		return nil
	}
	rec, err := g.DB().GetOne(ctx, selectRoleVo+" where r."+col+" = ? and r.del_flag = '0' limit 1", val)
	if err != nil || rec == nil || rec.IsEmpty() {
		return nil
	}
	var role model.SysRole
	if gconv.Struct(rec.Map(), &role) != nil {
		return nil
	}
	return &role
}

// CheckRoleNameUnique 对应 checkRoleNameUnique：同名未删除角色存在且非自身 → false。
func CheckRoleNameUnique(ctx context.Context, role *model.SysRole) bool {
	info := checkRoleUniqueBy(ctx, "role_name", role.RoleName)
	if info != nil && info.RoleId != role.RoleId {
		return consts.NotUnique
	}
	return consts.Unique
}

// CheckRoleKeyUnique 对应 checkRoleKeyUnique：同权限字符未删除角色存在且非自身 → false。
func CheckRoleKeyUnique(ctx context.Context, role *model.SysRole) bool {
	info := checkRoleUniqueBy(ctx, "role_key", role.RoleKey)
	if info != nil && info.RoleId != role.RoleId {
		return consts.NotUnique
	}
	return consts.Unique
}

// CheckRoleAllowed 对应 checkRoleAllowed：操作已存在的超级管理员(roleId==1)角色 → 返回错误文案。
func CheckRoleAllowed(role *model.SysRole) string {
	if role.RoleId != 0 && role.RoleId == 1 {
		return "不允许操作超级管理员角色"
	}
	return ""
}

// CheckRoleDataScope 对应 checkRoleDataScope：非管理员逐个 roleId 走带 DataScope 的 selectRoleList，空 → 返回错误文案。
func CheckRoleDataScope(ctx context.Context, lu *model.LoginUser, permission string, roleIds ...int64) string {
	if lu == nil || lu.User == nil || IsAdmin(lu.User.UserId) {
		return ""
	}
	scope := BuildDataScope(ctx, lu, permission, "d", "dept_id", "", "")
	for _, roleId := range roleIds {
		list := SelectRoleList(ctx, RoleListFilter{RoleId: roleId}, scope)
		if len(list) == 0 {
			return "没有权限访问角色数据！"
		}
	}
	return ""
}

// CountUserRoleByRoleId 对应 countUserRoleByRoleId：该角色已分配的用户数。
func CountUserRoleByRoleId(ctx context.Context, roleId int64) int {
	v, err := g.DB().GetValue(ctx, "select count(1) from sys_user_role where role_id = ?", roleId)
	if err != nil || v.IsNil() {
		return 0
	}
	return v.Int()
}

// insertRoleMenu 对应 insertRoleMenu：先删后插由调用方处理，此处仅按 menuIds 批量写入角色-菜单关联。
func insertRoleMenu(ctx context.Context, tx gdb.TX, roleId int64, menuIds []int64) error {
	if len(menuIds) == 0 {
		return nil
	}
	for _, chunk := range chunkInt64(menuIds, 500) {
		sql := "insert into sys_role_menu(role_id, menu_id) values "
		args := make([]interface{}, 0, len(chunk)*2)
		for i, mid := range chunk {
			if i > 0 {
				sql += ","
			}
			sql += "(?, ?)"
			args = append(args, roleId, mid)
		}
		if _, err := tx.Exec(sql, args...); err != nil {
			return err
		}
	}
	return nil
}

// insertRoleDept 对应 insertRoleDept：按 deptIds 批量写入角色-部门（数据权限）关联。
func insertRoleDept(ctx context.Context, tx gdb.TX, roleId int64, deptIds []int64) error {
	if len(deptIds) == 0 {
		return nil
	}
	for _, chunk := range chunkInt64(deptIds, 500) {
		sql := "insert into sys_role_dept(role_id, dept_id) values "
		args := make([]interface{}, 0, len(chunk)*2)
		for i, did := range chunk {
			if i > 0 {
				sql += ","
			}
			sql += "(?, ?)"
			args = append(args, roleId, did)
		}
		if _, err := tx.Exec(sql, args...); err != nil {
			return err
		}
	}
	return nil
}

// InsertRole 对应 insertRole（@Transactional）：插入角色主行（回写自增 roleId）→ 写角色-菜单关联。
func InsertRole(ctx context.Context, role *model.SysRole, createBy string) (int, string) {
	err := g.DB().Transaction(ctx, func(ctx context.Context, tx gdb.TX) error {
		res, err := tx.Exec(
			"insert into sys_role(role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, remark, create_by, create_time)"+
				" values(?,?,?,?,?,?,?,?,?, sysdate())",
			role.RoleName, role.RoleKey, role.RoleSort, defaultStr(role.DataScope, "1"),
			role.MenuCheckStrictly, role.DeptCheckStrictly, defaultStr(role.Status, "0"), role.Remark, createBy)
		if err != nil {
			return err
		}
		id, err := res.LastInsertId()
		if err != nil {
			return err
		}
		role.RoleId = id
		return insertRoleMenu(ctx, tx, role.RoleId, role.MenuIds)
	})
	if err != nil {
		return 0, "新增角色'" + role.RoleName + "'失败，请联系管理员"
	}
	return 1, ""
}

// UpdateRole 对应 updateRole（@Transactional）：改主行 → 删角色-菜单关联 → 重插。
func UpdateRole(ctx context.Context, role *model.SysRole, updateBy string) (int, string) {
	err := g.DB().Transaction(ctx, func(ctx context.Context, tx gdb.TX) error {
		if _, err := updateRoleMain(ctx, tx, role, updateBy); err != nil {
			return err
		}
		if _, err := tx.Exec("delete from sys_role_menu where role_id = ?", role.RoleId); err != nil {
			return err
		}
		return insertRoleMenu(ctx, tx, role.RoleId, role.MenuIds)
	})
	if err != nil {
		return 0, "修改角色'" + role.RoleName + "'失败，请联系管理员"
	}
	return 1, ""
}

// UpdateRoleStatus 对应 updateRoleStatus：仅更新 status/update_by/update_time。
func UpdateRoleStatus(ctx context.Context, role *model.SysRole) (int, string) {
	err := g.DB().Transaction(ctx, func(ctx context.Context, tx gdb.TX) error {
		_, err := tx.Exec("update sys_role set status = ?, update_by = ?, update_time = sysdate() where role_id = ?",
			role.Status, role.UpdateBy, role.RoleId)
		return err
	})
	if err != nil {
		return 0, ""
	}
	return 1, ""
}

// AuthDataScope 对应 authDataScope（@Transactional）：改主行 → 删角色-部门关联 → 重插。
func AuthDataScope(ctx context.Context, role *model.SysRole) (int, string) {
	err := g.DB().Transaction(ctx, func(ctx context.Context, tx gdb.TX) error {
		if _, err := updateRoleMain(ctx, tx, role, role.UpdateBy); err != nil {
			return err
		}
		if _, err := tx.Exec("delete from sys_role_dept where role_id = ?", role.RoleId); err != nil {
			return err
		}
		return insertRoleDept(ctx, tx, role.RoleId, role.DeptIds)
	})
	if err != nil {
		return 0, "修改角色'" + role.RoleName + "'失败，请联系管理员"
	}
	return 1, ""
}

// updateRoleMain 对应 roleMapper.updateRole 的动态 SET（非空字段更新，update_time 强制刷新），返回受影响行数。
func updateRoleMain(ctx context.Context, tx gdb.TX, role *model.SysRole, updateBy string) (int, error) {
	set := ""
	args := make([]interface{}, 0, 8)
	add := func(clause string, val interface{}) {
		if set != "" {
			set += ", "
		}
		set += clause
		args = append(args, val)
	}
	if role.RoleName != "" {
		add("role_name = ?", role.RoleName)
	}
	if role.RoleKey != "" {
		add("role_key = ?", role.RoleKey)
	}
	if role.RoleSort != 0 {
		add("role_sort = ?", role.RoleSort)
	}
	if role.DataScope != "" {
		add("data_scope = ?", role.DataScope)
	}
	add("menu_check_strictly = ?", role.MenuCheckStrictly)
	add("dept_check_strictly = ?", role.DeptCheckStrictly)
	if role.Status != "" {
		add("status = ?", role.Status)
	}
	if role.Remark != "" {
		add("remark = ?", role.Remark)
	}
	if updateBy != "" {
		add("update_by = ?", updateBy)
	}
	set += ", update_time = sysdate()"
	args = append(args, role.RoleId)
	res, err := tx.Exec("update sys_role set "+set+" where role_id = ?", args...)
	if err != nil {
		return 0, err
	}
	return affectedOf(res), nil
}

// DeleteRoleByIds 对应 deleteRoleByIds（@Transactional）：逐个超管/数据范围/已分配校验 → 清关联 → 软删主行。
func DeleteRoleByIds(ctx context.Context, lu *model.LoginUser, permission string, roleIds []int64) (int, string) {
	for _, roleId := range roleIds {
		if msg := CheckRoleAllowed(&model.SysRole{RoleId: roleId}); msg != "" {
			return 0, msg
		}
		if msg := CheckRoleDataScope(ctx, lu, permission, roleId); msg != "" {
			return 0, msg
		}
		role := SelectRoleById(ctx, roleId)
		if role != nil && CountUserRoleByRoleId(ctx, roleId) > 0 {
			return 0, fmt.Sprintf("%s已分配,不能删除", role.RoleName)
		}
	}
	in := placeholders(len(roleIds))
	args := idsToInterfaces(roleIds)
	err := g.DB().Transaction(ctx, func(ctx context.Context, tx gdb.TX) error {
		if _, err := tx.Exec("delete from sys_role_menu where role_id in ("+in+")", args...); err != nil {
			return err
		}
		if _, err := tx.Exec("delete from sys_role_dept where role_id in ("+in+")", args...); err != nil {
			return err
		}
		_, err := tx.Exec("update sys_role set del_flag = '2' where role_id in ("+in+")", args...)
		return err
	})
	if err != nil {
		return 0, ""
	}
	return 1, ""
}

// DeleteAuthUser 对应 deleteAuthUser → deleteUserRoleInfo(userId, roleId)：取消单用户某角色。
func DeleteAuthUser(ctx context.Context, userId, roleId int64) (int, string) {
	res, err := g.DB().Exec(ctx, "delete from sys_user_role where user_id = ? and role_id = ?", userId, roleId)
	if err != nil {
		return 0, ""
	}
	return affectedOf(res), ""
}

// DeleteAuthUsers 对应 deleteAuthUsers → deleteUserRoleInfos(roleId, userIds)：批量取消某角色下的多个用户。
func DeleteAuthUsers(ctx context.Context, roleId int64, userIds []int64) (int, string) {
	if len(userIds) == 0 {
		return 0, ""
	}
	args := append([]interface{}{roleId}, idsToInterfaces(userIds)...)
	sql := "delete from sys_user_role where role_id = ? and user_id in (" + placeholders(len(userIds)) + ")"
	res, err := g.DB().Exec(ctx, sql, args...)
	if err != nil {
		return 0, ""
	}
	return affectedOf(res), ""
}

// InsertAuthUsers 对应 insertAuthUsers → batchUserRole：批量给某角色分配用户。
func InsertAuthUsers(ctx context.Context, roleId int64, userIds []int64) (int, string) {
	if len(userIds) == 0 {
		return 1, ""
	}
	for _, chunk := range chunkInt64(userIds, 500) {
		sql := "insert into sys_user_role(user_id, role_id) values "
		args := make([]interface{}, 0, len(chunk)*2)
		for i, uid := range chunk {
			if i > 0 {
				sql += ","
			}
			sql += "(?, ?)"
			args = append(args, uid, roleId)
		}
		if _, err := g.DB().Exec(ctx, sql, args...); err != nil {
			return 0, ""
		}
	}
	return 1, ""
}

// SelectAllocatedList 对应 selectAllocatedList(user)：已分配该角色的未删除用户（distinct + 登录名/手机模糊 + DataScope）。
func SelectAllocatedList(ctx context.Context, roleId int64, userName, phonenumber, dataScope string, p Page) ([]model.SysUser, int64, error) {
	sql := "select distinct u.user_id, u.dept_id, u.user_name, u.nick_name, u.email, u.phonenumber, u.status, u.create_time" +
		" from sys_user u" +
		" left join sys_dept d on u.dept_id = d.dept_id" +
		" left join sys_user_role ur on u.user_id = ur.user_id" +
		" left join sys_role r on r.role_id = ur.role_id" +
		" where u.del_flag = '0' and r.role_id = ?"
	args := []interface{}{roleId}
	sql, args = appendUserLikeFilters(sql, &args, userName, phonenumber)
	sql += " " + dataScope
	res, err := QueryPaged(ctx, p, sql, args, "")
	if err != nil {
		return nil, 0, err
	}
	return scanUsers(res.Rows), res.Total, nil
}

// SelectUnallocatedList 对应 selectUnallocatedList(user)：未分配该角色的未删除用户（排除持有者 + 登录名/手机模糊 + DataScope）。
func SelectUnallocatedList(ctx context.Context, roleId int64, userName, phonenumber, dataScope string, p Page) ([]model.SysUser, int64, error) {
	sql := "select distinct u.user_id, u.dept_id, u.user_name, u.nick_name, u.email, u.phonenumber, u.status, u.create_time" +
		" from sys_user u" +
		" left join sys_dept d on u.dept_id = d.dept_id" +
		" left join sys_user_role ur on u.user_id = ur.user_id" +
		" left join sys_role r on r.role_id = ur.role_id" +
		" where u.del_flag = '0' and (r.role_id != ? or r.role_id IS NULL)" +
		" and u.user_id not in (select u.user_id from sys_user u inner join sys_user_role ur on u.user_id = ur.user_id and ur.role_id = ?)"
	args := []interface{}{roleId, roleId}
	sql, args = appendUserLikeFilters(sql, &args, userName, phonenumber)
	sql += " " + dataScope
	res, err := QueryPaged(ctx, p, sql, args, "")
	if err != nil {
		return nil, 0, err
	}
	return scanUsers(res.Rows), res.Total, nil
}

// appendUserLikeFilters 追加登录名/手机号模糊条件（对齐 <if test="xxx != null and xxx != ''">）。
func appendUserLikeFilters(sql string, args *[]interface{}, userName, phonenumber string) (string, []interface{}) {
	if userName != "" {
		sql += " and u.user_name like concat('%', ?, '%')"
		*args = append(*args, userName)
	}
	if phonenumber != "" {
		sql += " and u.phonenumber like concat('%', ?, '%')"
		*args = append(*args, phonenumber)
	}
	return sql, *args
}

func scanUsers(recs gdb.Result) []model.SysUser {
	out := make([]model.SysUser, 0, len(recs))
	for _, r := range recs {
		var u model.SysUser
		if gconv.Struct(r.Map(), &u) == nil {
			out = append(out, u)
		}
	}
	return out
}

// affectedOf 从执行结果取受影响行数（错误按 0 处理）。
func affectedOf(res sql.Result) int {
	n, err := res.RowsAffected()
	if err != nil {
		return 0
	}
	return int(n)
}

// chunkInt64 将切片按 size 分块（批量 insert 防止单条 SQL 过长）。
func chunkInt64(ids []int64, size int) [][]int64 {
	if size <= 0 {
		size = 500
	}
	var out [][]int64
	for i := 0; i < len(ids); i += size {
		end := i + size
		if end > len(ids) {
			end = len(ids)
		}
		out = append(out, ids[i:end])
	}
	return out
}
