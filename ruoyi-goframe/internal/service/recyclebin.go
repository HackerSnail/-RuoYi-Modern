// Package service —— 回收站（软删除记录 del_flag='2' 的列表/恢复/彻底删除），逐条对齐
// RecycleBinServiceImpl + SysRecycleBinController + SysUser/Role/DeptMapper.xml 的真实语义。
//
// 契约要点（不得错位）：
//   - 三类实体（user/role/dept）共用一套流程，按 RecycleBinType 在服务层内部分派；type→权限串的派生
//     对齐 RecycleBinType（LIST/restore/purge 权限、code 白名单）。
//   - 数据权限采"软删口径"：作用对象是 del_flag='2'，既有 check*DataScope（查活跃记录）不可用，
//     故可见性判定走同样带 DataScope 的软删列表（按 id 过滤查一次判空即拒）；
//     超管经 BuildDataScope 短路返空片段必命中，非超管越界查空即拒（对齐 getAopProxy 范式）。
//   - restore/purge 逐项独立、部分成功：每项仅一条状态变更单语句（天然原子），批内一项失败不回滚其余，
//     方法本身不开事务。业务护栏文案原样回传（catch(ServiceException)）；非预期异常经 recover 兜住，
//     对外仅回"操作失败，请联系管理员"，真实原因落 error 日志（catch(Exception) 脱敏）。
package service

import (
	"context"
	"fmt"

	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/os/glog"
	"github.com/gogf/gf/v2/util/gconv"

	"ruoyi-goframe/internal/model"
)

// delFlagDeleted / delFlagNormal 复刻 UserStatus.DELETED.getCode()='2' 与正常 '0'。
const (
	delFlagDeleted = "2"
	delFlagNormal  = "0"
)

// RecycleBinType 回收站实体类型（对齐 com.ruoyi.common.enums.RecycleBinType 的纯元数据）。
type RecycleBinType struct {
	code        string
	displayName string
	permPrefix  string // restore/purge 权限前缀
}

var (
	rbUser = RecycleBinType{code: "user", displayName: "用户", permPrefix: "system:user"}
	rbRole = RecycleBinType{code: "role", displayName: "角色", permPrefix: "system:role"}
	rbDept = RecycleBinType{code: "dept", displayName: "部门", permPrefix: "system:dept"}
)

// rbRecycleBinListPermi 回收站查看权限（三类共用），对齐 RecycleBinType.LIST_PERMISSION。
const rbRecycleBinListPermi = "system:recyclebin:list"

// ParseRecycleBinType 按编码解析类型（controller 入口白名单校验）；非法编码回 ok=false。
func ParseRecycleBinType(code string) (RecycleBinType, bool) {
	for _, t := range []RecycleBinType{rbUser, rbRole, rbDept} {
		if t.code == code {
			return t, true
		}
	}
	return RecycleBinType{}, false
}

// RestorePermission 恢复动作权限串，如 system:user:restore。
func (t RecycleBinType) RestorePermission() string { return t.permPrefix + ":restore" }

// PurgePermission 彻底删除动作权限串，如 system:user:purge。
func (t RecycleBinType) PurgePermission() string { return t.permPrefix + ":purge" }

// ListPermission 查看权限（三类共用）。
func RecycleBinListPermission() string { return rbRecycleBinListPermi }

// Code 类型编码（user/role/dept），供控制器分派。
func (t RecycleBinType) Code() string { return t.code }

// rbBizError 业务护栏异常（越权拒绝/身份冲突/悬空引用/缺确认/自删锁死等），文案面向操作者可安全回传。
type rbBizError struct{ msg string }

func (e *rbBizError) Error() string { return e.msg }

func rbBizf(format string, a ...interface{}) error { return &rbBizError{msg: fmt.Sprintf(format, a...)} }

// ============================ 结果累加 ============================

func rbStrPtr(s string) *string { return &s }

func rbAddSuccess(res *model.RecycleBinOpResult, id int64, name string) {
	res.Items = append(res.Items, model.RecycleBinOpItem{Id: id, Name: rbStrPtr(name), Status: "SUCCESS", Reason: nil})
	res.SuccessCount++
	res.Total++
}

func rbAddFailure(res *model.RecycleBinOpResult, id int64, reason string) {
	res.Items = append(res.Items, model.RecycleBinOpItem{Id: id, Name: nil, Status: "FAILED", Reason: rbStrPtr(reason)})
	res.FailCount++
	res.Total++
}

// ============================ Deleted 列表（del_flag='2'） ============================

// buildDeletedUserListSQL 复刻 selectDeletedUserList：同活跃列表的 SELECT/JOIN/条件过滤，仅 del_flag 前置改 '2'。
func buildDeletedUserListSQL(f UserListFilter, dataScope string) (string, []interface{}) {
	sql := "select u.user_id, u.dept_id, u.nick_name, u.user_name, u.email, u.avatar, u.phonenumber, u.sex," +
		" u.status, u.del_flag, u.login_ip, u.login_date, u.create_by, u.create_time, u.remark, d.dept_name, d.leader" +
		" from sys_user u left join sys_dept d on u.dept_id = d.dept_id where u.del_flag = '" + delFlagDeleted + "'"
	args := make([]interface{}, 0, 8)
	if f.UserId != 0 {
		sql += " and u.user_id = ?"
		args = append(args, f.UserId)
	}
	if f.UserName != "" {
		sql += " and u.user_name like concat('%', ?, '%')"
		args = append(args, f.UserName)
	}
	if f.Status != "" {
		sql += " and u.status = ?"
		args = append(args, f.Status)
	}
	if f.Phonenumber != "" {
		sql += " and u.phonenumber like concat('%', ?, '%')"
		args = append(args, f.Phonenumber)
	}
	if f.BeginTime != "" {
		sql += " and date_format(u.create_time,'%Y%m%d') >= date_format(?, '%Y%m%d')"
		args = append(args, f.BeginTime)
	}
	if f.EndTime != "" {
		sql += " and date_format(u.create_time,'%Y%m%d') <= date_format(?, '%Y%m%d')"
		args = append(args, f.EndTime)
	}
	if f.DeptId != 0 {
		sql += " and (u.dept_id = ? OR u.dept_id IN ( SELECT t.dept_id FROM sys_dept t WHERE find_in_set(?, ancestors) ))"
		args = append(args, f.DeptId, f.DeptId)
	}
	sql += " " + dataScope
	return sql, args
}

// buildDeletedRoleListSQL 复刻 selectDeletedRoleList：selectRoleVo + del_flag='2' + 条件 + 数据范围片段。
func buildDeletedRoleListSQL(f RoleListFilter, dataScope string) (string, []interface{}) {
	sql := selectRoleVo + " where r.del_flag = '" + delFlagDeleted + "'"
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

// buildDeletedDeptListSQL 复刻 selectDeletedDeptList：selectDeptVo + d.del_flag='2' + 条件 + 数据范围片段。
func buildDeletedDeptListSQL(f DeptListFilter, dataScope string) (string, []interface{}) {
	sql := selectDeptVo + " where d.del_flag = '" + delFlagDeleted + "'"
	args := make([]interface{}, 0, 4)
	if f.DeptId != 0 {
		sql += " and d.dept_id = ?"
		args = append(args, f.DeptId)
	}
	if f.ParentId != 0 {
		sql += " and d.parent_id = ?"
		args = append(args, f.ParentId)
	}
	if f.DeptName != "" {
		sql += " and d.dept_name like concat('%', ?, '%')"
		args = append(args, f.DeptName)
	}
	if f.Status != "" {
		sql += " and d.status = ?"
		args = append(args, f.Status)
	}
	sql += " " + dataScope
	return sql, args
}

// SelectDeletedUserListPaged 对应 selectDeletedUserList（分页，Java 该查询无默认排序）。
func SelectDeletedUserListPaged(ctx context.Context, f UserListFilter, dataScope string, p Page) ([]model.SysUser, int64, error) {
	sql, args := buildDeletedUserListSQL(f, dataScope)
	res, err := QueryPaged(ctx, p, sql, args, "")
	if err != nil {
		return nil, 0, err
	}
	return scanUserListRows(res.Rows), res.Total, nil
}

// SelectDeletedRoleListPaged 对应 selectDeletedRoleList（分页，默认 role_sort 排序）。
func SelectDeletedRoleListPaged(ctx context.Context, f RoleListFilter, dataScope string, p Page) ([]model.SysRole, int64, error) {
	sql, args := buildDeletedRoleListSQL(f, dataScope)
	res, err := QueryPaged(ctx, p, sql, args, "r.role_sort")
	if err != nil {
		return nil, 0, err
	}
	return scanRoles(res.Rows), res.Total, nil
}

// SelectDeletedDeptListPaged 对应 selectDeletedDeptList（分页，默认 parent_id, order_num 排序）。
func SelectDeletedDeptListPaged(ctx context.Context, f DeptListFilter, dataScope string, p Page) ([]*model.SysDept, int64, error) {
	sql, args := buildDeletedDeptListSQL(f, dataScope)
	res, err := QueryPaged(ctx, p, sql, args, "d.parent_id, d.order_num")
	if err != nil {
		return nil, 0, err
	}
	return scanDepts(res.Rows), res.Total, nil
}

// deletedUserInScope 判可见性：经带 DataScope 的软删列表按 id 过滤，非空即在范围内（超管短路恒真）。
func deletedUserInScope(ctx context.Context, lu *model.LoginUser, permission string, userId int64) bool {
	scope := BuildDataScope(ctx, lu, permission, "d", "dept_id", "u", "user_id")
	sql, args := buildDeletedUserListSQL(UserListFilter{UserId: userId}, scope)
	v, err := g.DB().GetValue(ctx, "select count(0) from ("+sql+") _c", args...)
	return err == nil && !v.IsNil() && v.Int64() > 0
}

func deletedRoleInScope(ctx context.Context, lu *model.LoginUser, permission string, roleId int64) bool {
	scope := BuildDataScope(ctx, lu, permission, "d", "dept_id", "", "")
	sql, args := buildDeletedRoleListSQL(RoleListFilter{RoleId: roleId}, scope)
	v, err := g.DB().GetValue(ctx, "select count(0) from ("+sql+") _c", args...)
	return err == nil && !v.IsNil() && v.Int64() > 0
}

func deletedDeptInScope(ctx context.Context, lu *model.LoginUser, permission string, deptId int64) bool {
	scope := BuildDataScope(ctx, lu, permission, "d", "dept_id", "", "")
	sql, args := buildDeletedDeptListSQL(DeptListFilter{DeptId: deptId}, scope)
	v, err := g.DB().GetValue(ctx, "select count(0) from ("+sql+") _c", args...)
	return err == nil && !v.IsNil() && v.Int64() > 0
}

// ============================ 按主键取软删记录（护栏所需字段） ============================

func selectDeletedUser(ctx context.Context, userId int64) *model.SysUser {
	rec, err := g.DB().GetOne(ctx,
		"select user_id, dept_id, user_name, phonenumber, email from sys_user where user_id = ? and del_flag = '"+delFlagDeleted+"'", userId)
	if err != nil || rec == nil || rec.IsEmpty() {
		return nil
	}
	var u model.SysUser
	if gconv.Struct(rec.Map(), &u) != nil {
		return nil
	}
	return &u
}

func selectDeletedRole(ctx context.Context, roleId int64) *model.SysRole {
	rec, err := g.DB().GetOne(ctx,
		"select role_id, role_name, role_key from sys_role where role_id = ? and del_flag = '"+delFlagDeleted+"'", roleId)
	if err != nil || rec == nil || rec.IsEmpty() {
		return nil
	}
	var r model.SysRole
	if gconv.Struct(rec.Map(), &r) != nil {
		return nil
	}
	return &r
}

func selectDeletedDept(ctx context.Context, deptId int64) *model.SysDept {
	rec, err := g.DB().GetOne(ctx,
		"select dept_id, dept_name, parent_id from sys_dept where dept_id = ? and del_flag = '"+delFlagDeleted+"'", deptId)
	if err != nil || rec == nil || rec.IsEmpty() {
		return nil
	}
	var d model.SysDept
	if gconv.Struct(rec.Map(), &d) != nil {
		return nil
	}
	return &d
}

// selectDeptDelFlag 取任意部门（含软删/活跃）的 del_flag；行不存在回 exists=false。
func selectDeptDelFlag(ctx context.Context, deptId int64) (delFlag string, exists bool) {
	rec, err := g.DB().GetOne(ctx, "select del_flag from sys_dept where dept_id = ?", deptId)
	if err != nil || rec == nil || rec.IsEmpty() {
		return "", false
	}
	return gconv.String(rec.Map()["del_flag"]), true
}

// execAffected 统一从 Exec 结果取受影响行数（对齐 operlog 范式）。
func execAffected(ctx context.Context, sql string, args ...interface{}) (int64, error) {
	res, err := g.DB().Exec(ctx, sql, args...)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// ============================ 状态变更（restore / 物理删） ============================

func restoreUserById(ctx context.Context, userId int64) (int64, error) {
	return execAffected(ctx, "update sys_user set del_flag = '"+delFlagNormal+"' where user_id = ? and del_flag = '"+delFlagDeleted+"'", userId)
}

func restoreRoleById(ctx context.Context, roleId int64) (int64, error) {
	return execAffected(ctx, "update sys_role set del_flag = '"+delFlagNormal+"' where role_id = ? and del_flag = '"+delFlagDeleted+"'", roleId)
}

func restoreDeptById(ctx context.Context, deptId int64) (int64, error) {
	return execAffected(ctx, "update sys_dept set del_flag = '"+delFlagNormal+"' where dept_id = ? and del_flag = '"+delFlagDeleted+"'", deptId)
}

func deleteUserPhysically(ctx context.Context, userId int64) (int64, error) {
	return execAffected(ctx, "delete from sys_user where user_id = ?", userId)
}

func deleteRolePhysically(ctx context.Context, roleId int64) (int64, error) {
	return execAffected(ctx, "delete from sys_role where role_id = ?", roleId)
}

func deleteDeptPhysically(ctx context.Context, deptId int64) (int64, error) {
	return execAffected(ctx, "delete from sys_dept where dept_id = ?", deptId)
}

// hasRecycledChildByDeptId 复刻 hasRecycledChildByDeptId：del_flag='2' 且挂在该部门下的子部门。
func hasRecycledChildByDeptId(ctx context.Context, deptId int64) bool {
	v, err := g.DB().GetValue(ctx, "select count(1) from sys_dept where del_flag = '"+delFlagDeleted+"' and parent_id = ? limit 1", deptId)
	return err == nil && !v.IsNil() && v.Int() > 0
}

// countRecycledUserByDeptId 复刻 countRecycledUserByDeptId：del_flag='2' 且挂在该部门下的用户。
func countRecycledUserByDeptId(ctx context.Context, deptId int64) bool {
	v, err := g.DB().GetValue(ctx, "select count(1) from sys_user where del_flag = '"+delFlagDeleted+"' and dept_id = ? limit 1", deptId)
	return err == nil && !v.IsNil() && v.Int() > 0
}

// ============================ 恢复 ============================

// Restore 批量恢复软删记录（del_flag '2'→'0'），逐项独立、部分成功，恒返回结果（整体不抛错）。
// permission 为动作权限串（透传给 DataScope 判可见性，对齐 PermissionContextHolder）。
func Restore(ctx context.Context, lu *model.LoginUser, t RecycleBinType, permission string, ids []int64) *model.RecycleBinOpResult {
	res := &model.RecycleBinOpResult{Items: []model.RecycleBinOpItem{}}
	if len(ids) == 0 {
		return res
	}
	for _, id := range ids {
		name, err := rbSafeItem(func() (string, error) { return restoreOne(ctx, lu, t, permission, id) })
		if err == nil {
			rbAddSuccess(res, id, name)
		} else if biz, ok := err.(*rbBizError); ok {
			rbAddFailure(res, id, biz.msg)
		} else {
			glog.Errorf(ctx, "回收站恢复失败 type=%s id=%d: %v", t.code, id, err)
			rbAddFailure(res, id, "操作失败，请联系管理员")
		}
	}
	return res
}

// rbSafeItem 兜住 fn 内的非预期 panic，转成普通 error（对齐 catch(Exception)→通用文案 + 落日志）。
func rbSafeItem(fn func() (string, error)) (name string, err error) {
	defer func() {
		if r := recover(); r != nil {
			name = ""
			err = fmt.Errorf("panic: %v", r)
		}
	}()
	return fn()
}

func restoreOne(ctx context.Context, lu *model.LoginUser, t RecycleBinType, permission string, id int64) (string, error) {
	switch t.code {
	case rbUser.code:
		return restoreUserOne(ctx, lu, permission, id)
	case rbRole.code:
		return restoreRoleOne(ctx, lu, permission, id)
	case rbDept.code:
		return restoreDeptOne(ctx, lu, permission, id)
	default:
		return "", rbBizf("不支持的回收站类型：%s", t.code)
	}
}

func restoreUserOne(ctx context.Context, lu *model.LoginUser, permission string, userId int64) (string, error) {
	trash := selectDeletedUser(ctx, userId)
	if trash == nil {
		return "", rbBizf("用户不在回收站")
	}
	if !deletedUserInScope(ctx, lu, permission, userId) {
		return "", rbBizf("没有权限访问用户数据（超出数据范围）")
	}
	if msg := CheckUserAllowed(&model.SysUser{UserId: userId}); msg != "" {
		return "", &rbBizError{msg}
	}
	if !CheckUserNameUnique(ctx, &model.SysUser{UserName: trash.UserName}) {
		return "", rbBizf("存在同名活跃用户：%s", trash.UserName)
	}
	if trash.Phonenumber != "" && !CheckPhoneUnique(ctx, &model.SysUser{Phonenumber: trash.Phonenumber}) {
		return "", rbBizf("手机号已被活跃用户占用")
	}
	if trash.Email != "" && !CheckEmailUnique(ctx, &model.SysUser{Email: trash.Email}) {
		return "", rbBizf("邮箱已被活跃用户占用")
	}
	if trash.DeptId != 0 {
		delFlag, exists := selectDeptDelFlag(ctx, trash.DeptId)
		if !exists {
			return "", rbBizf("所属部门不存在，无法恢复")
		}
		if delFlag == delFlagDeleted {
			return "", rbBizf("请先恢复所属部门")
		}
	}
	rows, err := restoreUserById(ctx, userId)
	if err != nil {
		return "", err
	}
	if rows == 0 {
		return "", rbBizf("恢复失败：记录状态已变更")
	}
	return trash.UserName, nil
}

func restoreRoleOne(ctx context.Context, lu *model.LoginUser, permission string, roleId int64) (string, error) {
	trash := selectDeletedRole(ctx, roleId)
	if trash == nil {
		return "", rbBizf("角色不在回收站")
	}
	if !deletedRoleInScope(ctx, lu, permission, roleId) {
		return "", rbBizf("没有权限访问角色数据（超出数据范围）")
	}
	if msg := CheckRoleAllowed(trash); msg != "" {
		return "", &rbBizError{msg}
	}
	if !CheckRoleKeyUnique(ctx, trash) {
		return "", rbBizf("存在相同标识的活跃角色：%s", trash.RoleKey)
	}
	if !CheckRoleNameUnique(ctx, trash) {
		return "", rbBizf("存在同名活跃角色：%s", trash.RoleName)
	}
	rows, err := restoreRoleById(ctx, roleId)
	if err != nil {
		return "", err
	}
	if rows == 0 {
		return "", rbBizf("恢复失败：记录状态已变更")
	}
	return trash.RoleName, nil
}

func restoreDeptOne(ctx context.Context, lu *model.LoginUser, permission string, deptId int64) (string, error) {
	trash := selectDeletedDept(ctx, deptId)
	if trash == nil {
		return "", rbBizf("部门不在回收站")
	}
	if !deletedDeptInScope(ctx, lu, permission, deptId) {
		return "", rbBizf("没有权限访问部门数据（超出数据范围）")
	}
	if !CheckDeptNameUnique(ctx, trash) {
		return "", rbBizf("同一上级下存在同名活跃部门：%s", trash.DeptName)
	}
	if trash.ParentId != 0 {
		delFlag, exists := selectDeptDelFlag(ctx, trash.ParentId)
		if !exists {
			return "", rbBizf("上级部门不存在，无法恢复")
		}
		if delFlag == delFlagDeleted {
			return "", rbBizf("请先恢复上级部门")
		}
	}
	rows, err := restoreDeptById(ctx, deptId)
	if err != nil {
		return "", err
	}
	if rows == 0 {
		return "", rbBizf("恢复失败：记录状态已变更")
	}
	return trash.DeptName, nil
}

// ============================ 彻底删除 ============================

// Purge 批量彻底删除（物理 DELETE，不可逆）。confirm 非 true 时整请求拒绝（回 errMsg，不逐项处理）。
func Purge(ctx context.Context, lu *model.LoginUser, t RecycleBinType, permission string, ids []int64, confirm bool) (*model.RecycleBinOpResult, string) {
	if !confirm {
		return nil, "彻底删除必须二次确认（confirm=true）"
	}
	res := &model.RecycleBinOpResult{Items: []model.RecycleBinOpItem{}}
	if len(ids) == 0 {
		return res, ""
	}
	for _, id := range ids {
		name, err := rbSafeItem(func() (string, error) { return purgeOne(ctx, lu, t, permission, id) })
		if err == nil {
			rbAddSuccess(res, id, name)
		} else if biz, ok := err.(*rbBizError); ok {
			rbAddFailure(res, id, biz.msg)
		} else {
			glog.Errorf(ctx, "回收站彻底删除失败 type=%s id=%d: %v", t.code, id, err)
			rbAddFailure(res, id, "操作失败，请联系管理员")
		}
	}
	return res, ""
}

func purgeOne(ctx context.Context, lu *model.LoginUser, t RecycleBinType, permission string, id int64) (string, error) {
	switch t.code {
	case rbUser.code:
		return purgeUserOne(ctx, lu, permission, id)
	case rbRole.code:
		return purgeRoleOne(ctx, lu, permission, id)
	case rbDept.code:
		return purgeDeptOne(ctx, lu, permission, id)
	default:
		return "", rbBizf("不支持的回收站类型：%s", t.code)
	}
}

func purgeUserOne(ctx context.Context, lu *model.LoginUser, permission string, userId int64) (string, error) {
	trash := selectDeletedUser(ctx, userId)
	if trash == nil {
		return "", rbBizf("用户不在回收站")
	}
	if !deletedUserInScope(ctx, lu, permission, userId) {
		return "", rbBizf("没有权限访问用户数据（超出数据范围）")
	}
	if msg := CheckUserAllowed(&model.SysUser{UserId: userId}); msg != "" {
		return "", &rbBizError{msg}
	}
	// 自删锁死：禁止销毁当前登录用户自身（SecurityUtils.getUserId）。
	if lu != nil && userId == lu.UserId {
		return "", rbBizf("不能彻底删除当前登录用户")
	}
	rows, err := deleteUserPhysically(ctx, userId)
	if err != nil {
		return "", err
	}
	if rows == 0 {
		return "", rbBizf("删除失败：记录不存在")
	}
	return trash.UserName, nil
}

func purgeRoleOne(ctx context.Context, lu *model.LoginUser, permission string, roleId int64) (string, error) {
	trash := selectDeletedRole(ctx, roleId)
	if trash == nil {
		return "", rbBizf("角色不在回收站")
	}
	if !deletedRoleInScope(ctx, lu, permission, roleId) {
		return "", rbBizf("没有权限访问角色数据（超出数据范围）")
	}
	if msg := CheckRoleAllowed(trash); msg != "" {
		return "", &rbBizError{msg}
	}
	rows, err := deleteRolePhysically(ctx, roleId)
	if err != nil {
		return "", err
	}
	if rows == 0 {
		return "", rbBizf("删除失败：记录不存在")
	}
	return trash.RoleName, nil
}

func purgeDeptOne(ctx context.Context, lu *model.LoginUser, permission string, deptId int64) (string, error) {
	trash := selectDeletedDept(ctx, deptId)
	if trash == nil {
		return "", rbBizf("部门不在回收站")
	}
	if !deletedDeptInScope(ctx, lu, permission, deptId) {
		return "", rbBizf("没有权限访问部门数据（超出数据范围）")
	}
	// 阻断造孤儿的物理删：活跃下级 / 回收站下级部门 / 在职用户 / 回收站用户，任一命中即拒。
	if HasChildByDeptId(ctx, deptId) {
		return "", rbBizf("存在活跃下级部门，不可销毁")
	}
	if hasRecycledChildByDeptId(ctx, deptId) {
		return "", rbBizf("回收站中存在下级部门，不可销毁（请先恢复或销毁下级）")
	}
	if CheckDeptExistUser(ctx, deptId) {
		return "", rbBizf("部门下存在在职用户，不可销毁")
	}
	if countRecycledUserByDeptId(ctx, deptId) {
		return "", rbBizf("回收站中存在该部门下的用户，不可销毁（请先恢复或销毁相关用户）")
	}
	rows, err := deleteDeptPhysically(ctx, deptId)
	if err != nil {
		return "", err
	}
	if rows == 0 {
		return "", rbBizf("删除失败：记录不存在")
	}
	return trash.DeptName, nil
}
