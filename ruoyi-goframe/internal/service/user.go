// Package service —— /system/user 读写，逐条复刻 SysUserServiceImpl + SysUserMapper.xml 的真实语义
// （列表条件过滤 + DataScope、唯一性/超管/数据范围校验、先删后插的角色/岗位关联、软删除、授权角色、密码 BCrypt）。
package service

import (
	"context"

	"github.com/gogf/gf/v2/database/gdb"
	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/os/gtime"
	"github.com/gogf/gf/v2/util/gconv"
	"golang.org/x/crypto/bcrypt"

	"ruoyi-goframe/internal/model"
)

// selectUserVo 对应 SysUserMapper.xml <sql id="selectUserVo">：JOIN 部门 + 用户角色关联 + 角色（详情一次性带出 dept 与 roles）。
const selectUserVo = "select u.user_id, u.dept_id, u.user_name, u.nick_name, u.email, u.avatar, u.phonenumber, u.password," +
	" u.sex, u.status, u.del_flag, u.login_ip, u.login_date, u.pwd_update_date, u.create_by, u.create_time, u.update_by," +
	" u.update_time, u.remark, d.dept_id, d.parent_id, d.ancestors, d.dept_name, d.order_num, d.leader, d.status as dept_status," +
	" r.role_id, r.role_name, r.role_key, r.role_sort, r.data_scope, r.status as role_status" +
	" from sys_user u" +
	" left join sys_dept d on u.dept_id = d.dept_id" +
	" left join sys_user_role ur on u.user_id = ur.user_id" +
	" left join sys_role r on r.role_id = ur.role_id"

// UserListFilter 用户列表条件（对应 SysUser 绑定字段 + params 时间范围）。
type UserListFilter struct {
	UserId      int64
	UserName    string
	Status      string
	Phonenumber string
	DeptId      int64
	BeginTime   string
	EndTime     string
}

// buildUserListSQL 组装列表基础查询（无 order/limit）+ 条件过滤 + 数据范围片段。
func buildUserListSQL(f UserListFilter, dataScope string) (string, []interface{}) {
	sql := "select u.user_id, u.dept_id, u.nick_name, u.user_name, u.email, u.avatar, u.phonenumber, u.sex," +
		" u.status, u.del_flag, u.login_ip, u.login_date, u.create_by, u.create_time, u.remark, d.dept_name, d.leader" +
		" from sys_user u left join sys_dept d on u.dept_id = d.dept_id where u.del_flag = '0'"
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

// SelectUserList 对应 selectUserList(user)：不分页（供 checkUserDataScope 存在性判定）。
func SelectUserList(ctx context.Context, f UserListFilter, dataScope string) []model.SysUser {
	sql, args := buildUserListSQL(f, dataScope)
	recs, err := g.DB().GetAll(ctx, sql, args...)
	if err != nil {
		return nil
	}
	return scanUserListRows(recs)
}

// SelectUserListPaged 对应 list 端点 startPage + selectUserList：子查询计数 + 分页。
func SelectUserListPaged(ctx context.Context, f UserListFilter, dataScope string, p Page) ([]model.SysUser, int64, error) {
	sql, args := buildUserListSQL(f, dataScope)
	res, err := QueryPaged(ctx, p, sql, args, "")
	if err != nil {
		return nil, 0, err
	}
	return scanUserListRows(res.Rows), res.Total, nil
}

// scanUserListRows 扫描列表行（含 dept_name/leader 内嵌部门，无角色列 → roles 保持 nil）。
func scanUserListRows(recs gdb.Result) []model.SysUser {
	out := make([]model.SysUser, 0, len(recs))
	for _, rec := range recs {
		m := rec.Map()
		var u model.SysUser
		if gconv.Struct(m, &u) != nil {
			continue
		}
		u.Admin = u.UserId == 1
		// 列表内嵌部门：对齐 Java deptResult（仅取 dept_id/dept_name/leader）
		if u.DeptId != 0 || gconv.String(m["dept_name"]) != "" {
			u.Dept = &model.SysDept{DeptId: u.DeptId, DeptName: gconv.String(m["dept_name"]), Leader: gconv.String(m["leader"])}
		}
		out = append(out, u)
	}
	return out
}

// SelectUserById 对应 selectUserById：selectUserVo JOIN，折叠多角色到 user.roles，装配内嵌 dept。不存在返回 nil。
func SelectUserById(ctx context.Context, userId int64) *model.SysUser {
	recs, err := g.DB().GetAll(ctx, selectUserVo+" where u.user_id = ?", userId)
	if err != nil || len(recs) == 0 {
		return nil
	}
	m := recs[0].Map()
	var u model.SysUser
	if gconv.Struct(m, &u) != nil {
		return nil
	}
	u.RoleId = 0 // JOIN 的 role_id 不应回填 user.roleId（Java resultMap 不映射）
	u.Admin = u.UserId == 1
	if u.DeptId != 0 || gconv.String(m["dept_name"]) != "" {
		u.Dept = &model.SysDept{
			DeptId:   gconv.Int64(m["dept_id"]),
			ParentId: gconv.Int64(m["parent_id"]),
			Ancestors: gconv.String(m["ancestors"]),
			DeptName: gconv.String(m["dept_name"]),
			OrderNum: gconv.Int(m["order_num"]),
			Leader:   gconv.String(m["leader"]),
			Status:   gconv.String(m["dept_status"]),
		}
	}
	// 折叠角色集合（按 role_id 去重；role_id 为空则跳过）
	seen := map[int64]bool{}
	for _, rec := range recs {
		rm := rec.Map()
		rid := gconv.Int64(rm["role_id"])
		if rid == 0 || seen[rid] {
			continue
		}
		seen[rid] = true
		u.Roles = append(u.Roles, &model.SysRole{
			RoleId:    rid,
			RoleName:  gconv.String(rm["role_name"]),
			RoleKey:   gconv.String(rm["role_key"]),
			RoleSort:  gconv.Int(rm["role_sort"]),
			DataScope: gconv.String(rm["data_scope"]),
			Status:    gconv.String(rm["role_status"]),
			Admin:     rid == 1,
		})
	}
	return &u
}

// uniqueUserIdBy 复用 checkXxxUnique 的 limit 1 短路查询，返回占用该列的用户 ID（无则 -1）。
func uniqueUserIdBy(ctx context.Context, col, val string) int64 {
	if val == "" {
		return -1
	}
	rec, err := g.DB().GetOne(ctx, "select user_id from sys_user where "+col+" = ? and del_flag = '0' limit 1", val)
	if err != nil || rec == nil || rec.IsEmpty() {
		return -1
	}
	return gconv.Int64(rec.Map()["user_id"])
}

func uniqueSelfOrNone(userId, foundUserId int64) bool {
	// foundUserId==-1 无人占用 → 唯一；否则仅当等于自身（编辑）才唯一。
	selfId := userId
	if selfId == 0 {
		selfId = -1
	}
	return foundUserId == -1 || foundUserId == selfId
}

// CheckUserNameUnique 对应 checkUserNameUnique。
func CheckUserNameUnique(ctx context.Context, user *model.SysUser) bool {
	return uniqueSelfOrNone(user.UserId, uniqueUserIdBy(ctx, "user_name", user.UserName))
}

// CheckPhoneUnique 对应 checkPhoneUnique。
func CheckPhoneUnique(ctx context.Context, user *model.SysUser) bool {
	return uniqueSelfOrNone(user.UserId, uniqueUserIdBy(ctx, "phonenumber", user.Phonenumber))
}

// CheckEmailUnique 对应 checkEmailUnique。
func CheckEmailUnique(ctx context.Context, user *model.SysUser) bool {
	return uniqueSelfOrNone(user.UserId, uniqueUserIdBy(ctx, "email", user.Email))
}

// CheckUserAllowed 对应 checkUserAllowed：操作已存在的超级管理员(userId==1)用户 → 返回错误文案。
func CheckUserAllowed(user *model.SysUser) string {
	if user.UserId != 0 && user.UserId == 1 {
		return "不允许操作超级管理员用户"
	}
	return ""
}

// CheckUserDataScope 对应 checkUserDataScope：非管理员走带 DataScope 的 selectUserList，空 → 返回错误文案。
// permission 为当前 HTTP 端点的 @PreAuthorize 串（对齐 PermissionContextHolder）。
func CheckUserDataScope(ctx context.Context, lu *model.LoginUser, permission string, userId int64) string {
	if lu == nil || lu.User == nil || IsAdmin(lu.User.UserId) {
		return ""
	}
	scope := BuildDataScope(ctx, lu, permission, "d", "dept_id", "u", "user_id")
	list := SelectUserList(ctx, UserListFilter{UserId: userId}, scope)
	if len(list) == 0 {
		return "没有权限访问用户数据！"
	}
	return ""
}

// EncryptPassword BCrypt 加密（对齐 SecurityUtils.encryptPassword，默认强度 10）。
func EncryptPassword(raw string) string {
	b, err := bcrypt.GenerateFromPassword([]byte(raw), bcrypt.DefaultCost)
	if err != nil {
		return ""
	}
	return string(b)
}

// insertUserRoleTx 对应 insertUserRole(userId, roleIds)：批量写入用户-角色关联。
func insertUserRoleTx(ctx context.Context, tx gdb.TX, userId int64, roleIds []int64) error {
	for _, chunk := range chunkInt64(roleIds, 500) {
		sql := "insert into sys_user_role(user_id, role_id) values "
		args := make([]interface{}, 0, len(chunk)*2)
		for i, rid := range chunk {
			if i > 0 {
				sql += ","
			}
			sql += "(?, ?)"
			args = append(args, userId, rid)
		}
		if _, err := tx.Exec(sql, args...); err != nil {
			return err
		}
	}
	return nil
}

// insertUserPostTx 对应 insertUserPost：批量写入用户-岗位关联。
func insertUserPostTx(ctx context.Context, tx gdb.TX, userId int64, postIds []int64) error {
	for _, chunk := range chunkInt64(postIds, 500) {
		sql := "insert into sys_user_post(user_id, post_id) values "
		args := make([]interface{}, 0, len(chunk)*2)
		for i, pid := range chunk {
			if i > 0 {
				sql += ","
			}
			sql += "(?, ?)"
			args = append(args, userId, pid)
		}
		if _, err := tx.Exec(sql, args...); err != nil {
			return err
		}
	}
	return nil
}

// InsertUser 对应 insertUser（@Transactional）：插入用户主行（动态非空列，回写自增 userId）→ 岗位关联 → 角色关联。
func InsertUser(ctx context.Context, user *model.SysUser, createBy string) (int, string) {
	cols := make([]string, 0, 12)
	vals := make([]interface{}, 0, 12)
	add := func(col string, val interface{}) {
		cols = append(cols, col)
		vals = append(vals, val)
	}
	if user.DeptId != 0 {
		add("dept_id", user.DeptId)
	}
	if user.UserName != "" {
		add("user_name", user.UserName)
	}
	if user.NickName != "" {
		add("nick_name", user.NickName)
	}
	if user.Email != "" {
		add("email", user.Email)
	}
	if user.Avatar != "" {
		add("avatar", user.Avatar)
	}
	if user.Phonenumber != "" {
		add("phonenumber", user.Phonenumber)
	}
	if user.Sex != "" {
		add("sex", user.Sex)
	}
	if user.Password != "" {
		add("password", user.Password)
	}
	if user.Status != "" {
		add("status", user.Status)
	}
	if createBy != "" {
		add("create_by", createBy)
	}
	if user.Remark != "" {
		add("remark", user.Remark)
	}
	cols = append(cols, "create_time")
	vals = append(vals, gtime.Now())
	sql := "insert into sys_user(" + joinStrings(cols, ", ") + ") values(" + placeholders(len(cols)) + ")"
	err := g.DB().Transaction(ctx, func(ctx context.Context, tx gdb.TX) error {
		res, err := tx.Exec(sql, vals...)
		if err != nil {
			return err
		}
		id, err := res.LastInsertId()
		if err != nil {
			return err
		}
		user.UserId = id
		if len(user.PostIds) > 0 {
			if err := insertUserPostTx(ctx, tx, user.UserId, user.PostIds); err != nil {
				return err
			}
		}
		if len(user.RoleIds) > 0 {
			if err := insertUserRoleTx(ctx, tx, user.UserId, user.RoleIds); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return 0, "新增用户'" + user.UserName + "'失败，请联系管理员"
	}
	return 1, ""
}

// RegisterUser 对应 registerUser(=userMapper.insertUser 动态列)：仅插 sys_user 主行，不建角色/岗位关联。
// 入列与 Java 注册链一致：user_name、nick_name(=username)、password(BCrypt)、pwd_update_date、create_time；
// status/del_flag 依赖 DB 默认 '0'（Java 注册未显式赋值，同样走默认）。返回 (是否成功, DB 错误)。
func RegisterUser(ctx context.Context, user *model.SysUser) (bool, error) {
	res, err := g.DB().Exec(ctx,
		"insert into sys_user(user_name, nick_name, password, pwd_update_date, create_time) values(?, ?, ?, ?, sysdate())",
		user.UserName, user.NickName, user.Password, gtime.Now())
	if err != nil {
		return false, err
	}
	affected, _ := res.RowsAffected()
	return affected > 0, nil
}

// UpdateUser 对应 updateUser（@Transactional）：先删角色/岗位关联再重建 → 改主行（动态非空列，update_time 刷新）。
func UpdateUser(ctx context.Context, user *model.SysUser, updateBy string) (int, string) {
	sets := make([]string, 0, 12)
	args := make([]interface{}, 0, 12)
	add := func(clause string, val interface{}) {
		sets = append(sets, clause)
		args = append(args, val)
	}
	if user.DeptId != 0 {
		add("dept_id = ?", user.DeptId)
	}
	if user.NickName != "" {
		add("nick_name = ?", user.NickName)
	}
	if user.Email != "" {
		add("email = ?", user.Email)
	}
	if user.Phonenumber != "" {
		add("phonenumber = ?", user.Phonenumber)
	}
	if user.Sex != "" {
		add("sex = ?", user.Sex)
	}
	if user.Avatar != "" {
		add("avatar = ?", user.Avatar)
	}
	if user.Password != "" {
		add("password = ?", user.Password)
	}
	if user.Status != "" {
		add("status = ?", user.Status)
	}
	if updateBy != "" {
		add("update_by = ?", updateBy)
	}
	if user.Remark != "" {
		add("remark = ?", user.Remark)
	}
	sets = append(sets, "update_time = ?")
	args = append(args, gtime.Now())
	updateSql := "update sys_user set " + joinStrings(sets, ", ") + " where user_id = ?"
	args = append(args, user.UserId)
	err := g.DB().Transaction(ctx, func(ctx context.Context, tx gdb.TX) error {
		if _, err := tx.Exec("delete from sys_user_role where user_id = ?", user.UserId); err != nil {
			return err
		}
		if len(user.RoleIds) > 0 {
			if err := insertUserRoleTx(ctx, tx, user.UserId, user.RoleIds); err != nil {
				return err
			}
		}
		if _, err := tx.Exec("delete from sys_user_post where user_id = ?", user.UserId); err != nil {
			return err
		}
		if len(user.PostIds) > 0 {
			if err := insertUserPostTx(ctx, tx, user.UserId, user.PostIds); err != nil {
				return err
			}
		}
		res, err := tx.Exec(updateSql, args...)
		if err != nil {
			return err
		}
		_ = res
		return nil
	})
	if err != nil {
		return 0, "修改用户'" + user.UserName + "'失败，请联系管理员"
	}
	return 1, ""
}

// InsertUserAuth 对应 insertUserAuth（@Transactional）：先删该用户角色关联再按 roleIds 重插。
func InsertUserAuth(ctx context.Context, userId int64, roleIds []int64) string {
	err := g.DB().Transaction(ctx, func(ctx context.Context, tx gdb.TX) error {
		if _, err := tx.Exec("delete from sys_user_role where user_id = ?", userId); err != nil {
			return err
		}
		if len(roleIds) > 0 {
			return insertUserRoleTx(ctx, tx, userId, roleIds)
		}
		return nil
	})
	if err != nil {
		return ""
	}
	return ""
}

// UpdateUserStatus 对应 updateUserStatus：仅更新 status + update_time（Java mapper 未含 update_by）。
func UpdateUserStatus(ctx context.Context, user *model.SysUser) (int, string) {
	res, err := g.DB().Exec(ctx, "update sys_user set status = ?, update_time = sysdate() where user_id = ?",
		user.Status, user.UserId)
	if err != nil {
		return 0, ""
	}
	return affectedOf(res), ""
}

// ResetPwd 对应 resetPwd：刷新 pwd_update_date + 新密文 + update_time。
func ResetPwd(ctx context.Context, user *model.SysUser) (int, string) {
	res, err := g.DB().Exec(ctx,
		"update sys_user set pwd_update_date = sysdate(), password = ?, update_time = sysdate() where user_id = ?",
		user.Password, user.UserId)
	if err != nil {
		return 0, ""
	}
	return affectedOf(res), ""
}

// DeleteUserByIds 对应 deleteUserByIds（@Transactional）：逐个超管/数据范围校验 → 清角色/岗位关联 → 软删主行。
func DeleteUserByIds(ctx context.Context, lu *model.LoginUser, permission string, userIds []int64) (int, string) {
	for _, userId := range userIds {
		if msg := CheckUserAllowed(&model.SysUser{UserId: userId}); msg != "" {
			return 0, msg
		}
		if msg := CheckUserDataScope(ctx, lu, permission, userId); msg != "" {
			return 0, msg
		}
	}
	in := placeholders(len(userIds))
	args := idsToInterfaces(userIds)
	var affected int
	err := g.DB().Transaction(ctx, func(ctx context.Context, tx gdb.TX) error {
		if _, err := tx.Exec("delete from sys_user_role where user_id in ("+in+")", args...); err != nil {
			return err
		}
		if _, err := tx.Exec("delete from sys_user_post where user_id in ("+in+")", args...); err != nil {
			return err
		}
		res, err := tx.Exec("update sys_user set del_flag = '2' where user_id in ("+in+")", args...)
		if err != nil {
			return err
		}
		affected = affectedOf(res)
		return nil
	})
	if err != nil {
		return 0, ""
	}
	return affected, ""
}

// ---- 个人中心 /system/user/profile（对齐 SysProfileController + SysUserServiceImpl profile 专法）----

// MatchesPassword BCrypt 比对（对齐 SecurityUtils.matchesPassword）：纯比对，不含登录重试锁定。
func MatchesPassword(rawPassword, encodedPassword string) bool {
	return bcrypt.CompareHashAndPassword([]byte(encodedPassword), []byte(rawPassword)) == nil
}

// SelectUserPasswordById 单取用户密文：model.SysUser.Password 带 json:"-" 不被 gconv 回填，
// 故像 selectUserByUserName 那样显式查列。不存在返回空串。
func SelectUserPasswordById(ctx context.Context, userId int64) string {
	rec, err := g.DB().GetOne(ctx, "select password from sys_user where user_id = ? and del_flag = '0'", userId)
	if err != nil || rec == nil || rec.IsEmpty() {
		return ""
	}
	return gconv.String(rec.Map()["password"])
}

// SelectUserRoleGroup 对应 selectUserRoleGroup：该用户名下未删除角色名以 "," 连接（无则空串）。
func SelectUserRoleGroup(ctx context.Context, userName string) string {
	recs, err := g.DB().GetAll(ctx,
		"select r.role_name from sys_role r"+
			" left join sys_user_role ur on ur.role_id = r.role_id"+
			" left join sys_user u on u.user_id = ur.user_id"+
			" where r.del_flag = '0' and u.user_name = ?", userName)
	if err != nil {
		return ""
	}
	names := make([]string, 0, len(recs))
	for _, rec := range recs {
		names = append(names, gconv.String(rec.Map()["role_name"]))
	}
	return joinStrings(names, ",")
}

// SelectUserPostGroup 对应 selectUserPostGroup：该用户名下岗位名以 "," 连接（无则空串）。
func SelectUserPostGroup(ctx context.Context, userName string) string {
	recs, err := g.DB().GetAll(ctx,
		"select p.post_name from sys_post p"+
			" left join sys_user_post up on up.post_id = p.post_id"+
			" left join sys_user u on u.user_id = up.user_id"+
			" where u.user_name = ?", userName)
	if err != nil {
		return ""
	}
	names := make([]string, 0, len(recs))
	for _, rec := range recs {
		names = append(names, gconv.String(rec.Map()["post_name"]))
	}
	return joinStrings(names, ",")
}

// UpdateUserProfile 复刻 updateUser 动态列 mapper 的可观测语义子集：仅落 profile 明确可改的四列 + update_time。
// 指针 nil 表示请求未含该键（对齐 Java setXxx(null) → mapper `<if xxx != null>` 跳过该列）：
//   - nick_name/sex：非 nil 且非空串才写（Java 条件 `!= null and != ''`，故无法清空）；
//   - email/phonenumber：非 nil 即写（Java 条件仅 `!= null`，故可提交空串清空）。
// 注：Java 会把缓存快照其余非空列（dept_id/avatar/status…）一并回写，但那对同值 DB 是无操作，
// 且有「用陈旧缓存覆盖管理员后续编辑」隐患，故此处刻意省略——最终库状态一致（update_time 恒变，affected≥1）。
func UpdateUserProfile(ctx context.Context, userId int64, nickName, email, phonenumber, sex *string) int {
	sets := make([]string, 0, 5)
	args := make([]interface{}, 0, 5)
	if nickName != nil && *nickName != "" {
		sets, args = append(sets, "nick_name = ?"), append(args, *nickName)
	}
	if email != nil {
		sets, args = append(sets, "email = ?"), append(args, *email)
	}
	if phonenumber != nil {
		sets, args = append(sets, "phonenumber = ?"), append(args, *phonenumber)
	}
	if sex != nil && *sex != "" {
		sets, args = append(sets, "sex = ?"), append(args, *sex)
	}
	sets = append(sets, "update_time = ?")
	args = append(args, gtime.Now())
	args = append(args, userId)
	res, err := g.DB().Exec(ctx, "update sys_user set "+joinStrings(sets, ", ")+" where user_id = ?", args...)
	if err != nil {
		return 0
	}
	return affectedOf(res)
}

// UpdateUserAvatar 对应 updateUserAvatar：仅回写 avatar + update_time，返回是否更新成功。
func UpdateUserAvatar(ctx context.Context, userId int64, avatar string) bool {
	res, err := g.DB().Exec(ctx, "update sys_user set avatar = ?, update_time = sysdate() where user_id = ?", avatar, userId)
	if err != nil {
		return false
	}
	return affectedOf(res) > 0
}

// joinStrings 以 sep 连接字符串切片。
func joinStrings(parts []string, sep string) string {
	out := ""
	for i, p := range parts {
		if i > 0 {
			out += sep
		}
		out += p
	}
	return out
}
