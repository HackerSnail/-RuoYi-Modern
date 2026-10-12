// Package controller —— /system/user 管理端 HTTP 处理器，逐条对齐 SysUserController 的入参/出参/校验顺序/文案
// + DataScope（permission 取当前端点 @PreAuthorize 串）+ 授权角色变更后的会话失效。
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
	userPermiList     = "system:user:list"
	userPermiQuery    = "system:user:query"
	userPermiAdd      = "system:user:add"
	userPermiEdit     = "system:user:edit"
	userPermiRemove   = "system:user:remove"
	userPermiResetPwd = "system:user:resetPwd"
)

// userScope 计算当前会话对用户查询的数据范围（deptAlias=d、userAlias=u，供 DATA_SCOPE_SELF 兜底；admin 返回空串）。
func userScope(r *ghttp.Request, permission string) string {
	lu := middleware.CurrentLoginUser(r)
	return service.BuildDataScope(r.Context(), lu, permission, "d", "dept_id", "u", "user_id")
}

// UserList GET /system/user/list（system:user:list）→ TableDataInfo（分页 + DataScope）。
func UserList(r *ghttp.Request) {
	ctx := r.Context()
	f := service.UserListFilter{
		UserId:      r.Get("userId").Int64(),
		UserName:    r.Get("userName").String(),
		Status:      r.Get("status").String(),
		Phonenumber: r.Get("phonenumber").String(),
		DeptId:      r.Get("deptId").Int64(),
		BeginTime:   paramRange(r, "beginTime"),
		EndTime:     paramRange(r, "endTime"),
	}
	rows, total, err := service.SelectUserListPaged(ctx, f, userScope(r, userPermiList), service.ParsePage(r))
	if err != nil {
		api.Send(r, api.Err("查询失败"))
		return
	}
	api.Send(r, service.DataTable(total, rows))
}

// UserGetInfo GET /system/user/ 与 /system/user/{userId}（system:user:query）。
// 有 userId 时回 data/postIds/roleIds；始终回 roles（非管理员过滤超管角色）与 posts。
func UserGetInfo(r *ghttp.Request) {
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	userIdStr := r.GetRouter("userId").String()
	hasId := userIdStr != ""
	userId := gconv.Int64(userIdStr)
	body := api.Ok()
	if hasId {
		if msg := service.CheckUserDataScope(ctx, lu, userPermiQuery, userId); msg != "" {
			api.Send(r, api.Err(msg))
			return
		}
		sysUser := service.SelectUserById(ctx, userId)
		body["data"] = sysUser
		body["postIds"] = service.SelectPostListByUserId(ctx, userId)
		roleIds := make([]int64, 0)
		if sysUser != nil {
			for _, role := range sysUser.Roles {
				roleIds = append(roleIds, role.RoleId)
			}
		}
		body["roleIds"] = roleIds
	}
	roles := filterAdminRoles(service.SelectRoleAll(ctx, lu, userPermiQuery), service.IsAdmin(userId))
	body["roles"] = roles
	body["posts"] = service.SelectPostAll(ctx)
	api.Send(r, body)
}

// UserAdd POST /system/user（system:user:add）。校验顺序对齐 Java：部门/角色数据范围 → 唯一性 → 落库。
func UserAdd(r *ghttp.Request) {
	user, err := parseUserBody(r)
	if err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	if vs := validateUser(&user); len(vs) > 0 {
		api.Send(r, validErrBody(vs))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if msg := service.CheckDeptDataScope(ctx, lu, userPermiAdd, user.DeptId); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	if msg := service.CheckRoleDataScope(ctx, lu, userPermiAdd, user.RoleIds...); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	if !service.CheckUserNameUnique(ctx, &user) {
		api.Send(r, api.Err("新增用户'"+user.UserName+"'失败，登录账号已存在"))
		return
	} else if user.Phonenumber != "" && !service.CheckPhoneUnique(ctx, &user) {
		api.Send(r, api.Err("新增用户'"+user.UserName+"'失败，手机号码已存在"))
		return
	} else if user.Email != "" && !service.CheckEmailUnique(ctx, &user) {
		api.Send(r, api.Err("新增用户'"+user.UserName+"'失败，邮箱账号已存在"))
		return
	}
	user.Password = service.EncryptPassword(user.Password)
	rows, msg := service.InsertUser(ctx, &user, lu.Username)
	if msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	api.Send(r, toAjax(rows))
}

// UserEdit PUT /system/user（system:user:edit）。超管/数据范围 → 部门/角色数据范围 → 唯一性 → 落库。
func UserEdit(r *ghttp.Request) {
	user, err := parseUserBody(r)
	if err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	if vs := validateUser(&user); len(vs) > 0 {
		api.Send(r, validErrBody(vs))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if msg := service.CheckUserAllowed(&user); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	if msg := service.CheckUserDataScope(ctx, lu, userPermiEdit, user.UserId); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	if msg := service.CheckDeptDataScope(ctx, lu, userPermiEdit, user.DeptId); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	if msg := service.CheckRoleDataScope(ctx, lu, userPermiEdit, user.RoleIds...); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	if !service.CheckUserNameUnique(ctx, &user) {
		api.Send(r, api.Err("修改用户'"+user.UserName+"'失败，登录账号已存在"))
		return
	} else if user.Phonenumber != "" && !service.CheckPhoneUnique(ctx, &user) {
		api.Send(r, api.Err("修改用户'"+user.UserName+"'失败，手机号码已存在"))
		return
	} else if user.Email != "" && !service.CheckEmailUnique(ctx, &user) {
		api.Send(r, api.Err("修改用户'"+user.UserName+"'失败，邮箱账号已存在"))
		return
	}
	rows, msg := service.UpdateUser(ctx, &user, lu.Username)
	if msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	api.Send(r, toAjax(rows))
}

// UserRemove DELETE /system/user/{userIds}（system:user:remove）。拦截删自己 → 逐个超管/数据范围校验 → 软删。
func UserRemove(r *ghttp.Request) {
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	ids, ok := splitCommaInts(r.Get("userIds").String())
	if !ok || len(ids) == 0 {
		api.Send(r, api.Err("用户 ID 非法"))
		return
	}
	if lu != nil && containsInt64(ids, lu.UserId) {
		api.Send(r, api.Err("当前用户不能删除"))
		return
	}
	rows, msg := service.DeleteUserByIds(ctx, lu, userPermiRemove, ids)
	if msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	api.Send(r, toAjax(rows))
}

// UserResetPwd PUT /system/user/resetPwd（system:user:resetPwd）。超管/数据范围 → BCrypt → 改密。
func UserResetPwd(r *ghttp.Request) {
	user, err := parseUserBody(r)
	if err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if msg := service.CheckUserAllowed(&user); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	if msg := service.CheckUserDataScope(ctx, lu, userPermiResetPwd, user.UserId); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	user.Password = service.EncryptPassword(user.Password)
	rows, _ := service.ResetPwd(ctx, &user)
	api.Send(r, toAjax(rows))
}

// UserChangeStatus PUT /system/user/changeStatus（system:user:edit）。超管/数据范围 → 改状态。
func UserChangeStatus(r *ghttp.Request) {
	user, err := parseUserBody(r)
	if err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if msg := service.CheckUserAllowed(&user); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	if msg := service.CheckUserDataScope(ctx, lu, userPermiEdit, user.UserId); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	rows, _ := service.UpdateUserStatus(ctx, &user)
	api.Send(r, toAjax(rows))
}

// UserAuthRole GET /system/user/authRole/{userId}（system:user:query）。回 user + roles（非管理员过滤超管角色，含 flag 回显）。
func UserAuthRole(r *ghttp.Request) {
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	userId := r.Get("userId").Int64()
	user := service.SelectUserById(ctx, userId)
	roles := filterAdminRoles(service.SelectRolesByUserId(ctx, lu, userPermiQuery, userId), service.IsAdmin(userId))
	body := api.Ok()
	body["user"] = user
	body["roles"] = roles
	api.Send(r, body)
}

// UserInsertAuthRole PUT /system/user/authRole（system:user:edit）。数据范围校验 → 重绑角色 → 强制该用户重登。
func UserInsertAuthRole(r *ghttp.Request) {
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	userId := r.Get("userId").Int64()
	roleIds := parseIdList(r, "roleIds")
	if msg := service.CheckUserDataScope(ctx, lu, userPermiEdit, userId); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	if msg := service.CheckRoleDataScope(ctx, lu, userPermiEdit, roleIds...); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	service.InsertUserAuth(ctx, userId, roleIds)
	service.DelLoginUsers(ctx, userId)
	api.Send(r, api.Ok())
}

// UserDeptTree GET /system/user/deptTree（system:user:list）→ data:[TreeSelect...]（含 DataScope）。
func UserDeptTree(r *ghttp.Request) {
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	f := service.DeptListFilter{DeptName: r.Get("deptName").String(), Status: r.Get("status").String()}
	list := service.SelectDeptList(ctx, f, service.BuildDataScope(ctx, lu, userPermiList, "d", "dept_id", "", ""))
	api.Send(r, api.OkData(service.BuildDeptTreeSelect(list)))
}

// parseUserBody 解析 JSON body 到 SysUser，并单独读取 password（Password 为 json:"-"，Parse 不填充）。
func parseUserBody(r *ghttp.Request) (model.SysUser, error) {
	var user model.SysUser
	if err := r.Parse(&user); err != nil {
		return user, err
	}
	user.Password = r.Get("password").String()
	return user, nil
}

// validateUser 复刻 SysUser 的 @Validated 字段约束（userName NotBlank+Size30，nickName Size30，email Email+Size50，phonenumber Size11）。
// Xss 脚本字符校验按 P1a/P1b-A 先例不在此层复刻（前端与入库前另有处理）。
func validateUser(user *model.SysUser) []violation {
	var vs []violation
	if strings.TrimSpace(user.UserName) == "" {
		vs = append(vs, violation{"userName", "用户账号不能为空"})
	} else if runeLen(user.UserName) > 30 {
		vs = append(vs, violation{"userName", "用户账号长度不能超过30个字符"})
	}
	if runeLen(user.NickName) > 30 {
		vs = append(vs, violation{"nickName", "用户昵称长度不能超过30个字符"})
	}
	if user.Email != "" && !isEmailFormat(user.Email) {
		vs = append(vs, violation{"email", "邮箱格式不正确"})
	}
	if runeLen(user.Email) > 50 {
		vs = append(vs, violation{"email", "邮箱长度不能超过50个字符"})
	}
	if runeLen(user.Phonenumber) > 11 {
		vs = append(vs, violation{"phonenumber", "手机号码长度不能超过11个字符"})
	}
	return vs
}

// isEmailFormat 轻量邮箱格式校验（对齐 jakarta @Email 的常见接受面：本地@域名.顶级）。
func isEmailFormat(s string) bool {
	at := strings.Index(s, "@")
	if at <= 0 || at == len(s)-1 {
		return false
	}
	domain := s[at+1:]
	dot := strings.Index(domain, ".")
	return dot > 0 && dot < len(domain)-1 && !strings.ContainsAny(s, " \t")
}

// filterAdminRoles 非管理员时过滤掉超级管理员角色（对齐 getInfo/authRole：!isAdmin 则 filter(!r.isAdmin())）。
func filterAdminRoles(roles []model.SysRole, isAdmin bool) []model.SysRole {
	if isAdmin {
		return roles
	}
	out := make([]model.SysRole, 0, len(roles))
	for _, role := range roles {
		if !role.Admin {
			out = append(out, role)
		}
	}
	return out
}

// parseIdList 解析 ID 数组参数：优先重复 query/form 多值，再 JSON 数组，回退逗号串。
func parseIdList(r *ghttp.Request, key string) []int64 {
	if vals := r.URL.Query()[key]; len(vals) > 0 {
		ids, _ := splitCommaInts(strings.Join(vals, ","))
		return ids
	}
	v := r.Get(key)
	if arr := gconv.Int64s(v.Val()); len(arr) > 0 {
		return arr
	}
	ids, _ := splitCommaInts(v.String())
	return ids
}

func containsInt64(ids []int64, target int64) bool {
	for _, id := range ids {
		if id == target {
			return true
		}
	}
	return false
}
