// Package controller —— /system/user 管理端 HTTP 处理器，逐条对齐 SysUserController 的入参/出参/校验顺序/文案
// + DataScope（permission 取当前端点 @PreAuthorize 串）+ 授权角色变更后的会话失效。
package controller

import (
	"bytes"
	"context"
	"fmt"
	"strings"

	"github.com/gogf/gf/v2/net/ghttp"
	"github.com/gogf/gf/v2/util/gconv"

	"ruoyi-goframe/internal/response"
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
	userPermiImport   = "system:user:import"
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
		response.Send(r, response.Err("查询失败"))
		return
	}
	response.Send(r, service.DataTable(total, rows))
}

// UserExport POST /system/user/export（system:user:export）→ 直写 xlsx 二进制流（对齐 SysUserController.export）。
// 入参同列表过滤条件 + DataScope；exportLimit 护栏：SQL 只拉 maxRows+1 条，超限回 JSON 错（此时未写二进制）。
func UserExport(r *ghttp.Request) {
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
	max := service.GetExportMaxRows(ctx)
	rows, err := service.SelectUserListLimited(ctx, f, userScope(r, userPermiList), max)
	if err != nil {
		response.Send(r, response.Err("导出失败"))
		return
	}
	if len(rows) > max {
		response.Send(r, response.Err(fmt.Sprintf("导出数据量 %d 条，超过单次导出上限 %d 条，请缩小查询条件后重试", len(rows), max)))
		return
	}
	var buf bytes.Buffer
	if err := service.ExportUser(&buf, rows); err != nil {
		response.SendSystemError(r, "导出失败，请联系系统管理员")
		return
	}
	// 对齐 Java：仅设 Content-Type，不写 Content-Disposition（文件名由前端 download() 生成）。
	r.Response.ClearBuffer()
	r.Response.Header().Set("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
	r.Response.Write(buf.Bytes())
}

// UserImportTemplate POST /system/user/importTemplate（对齐 importTemplate：无 @PreAuthorize、无 @Log）。
// 写用户导入模板（纯表头行，title 空、IMPORT 型无数据），响应同 export：仅 Content-Type、不写 Content-Disposition。
func UserImportTemplate(r *ghttp.Request) {
	var buf bytes.Buffer
	if err := service.ImportTemplateUser(&buf); err != nil {
		response.SendSystemError(r, "下载模板失败，请联系系统管理员")
		return
	}
	r.Response.ClearBuffer()
	r.Response.Header().Set("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
	r.Response.Write(buf.Bytes())
}

// UserImportData POST /system/user/importData（system:user:import + @Log IMPORT）。
// 对齐 importData：扩展名早拒(xls/xlsx) → 读表 → importUser 逐行导入（非事务，部分成功会落库） → 聚合文案。
func UserImportData(r *ghttp.Request) {
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	files := r.GetMultipartFiles("file")
	if len(files) == 0 || files[0].Size == 0 {
		response.Send(r, response.Err("导入文件不能为空"))
		return
	}
	fh := files[0]
	// 前置类型拦截（对齐 getExtension + isAllowedExtension(IMPORT_EXTENSION={xls,xlsx})）：早拒非 Excel，不把任意文件交给解析。
	ext := fileExtension(fh.Filename)
	if !isImportExtensionAllowed(ext) {
		response.Send(r, response.Err(fmt.Sprintf("导入文件类型(%s)非法，仅允许 xls, xlsx 格式", ext)))
		return
	}
	open, err := fh.Open()
	if err != nil {
		response.Send(r, response.Err("导入失败，请联系系统管理员"))
		return
	}
	defer open.Close()
	list, err := service.ReadUserImport(open)
	if err != nil {
		// 解析失败（非 xlsx / 旧版 .xls excelize 不支持等），对齐 Java importExcel 抛出→全局异常处理器
		response.Send(r, response.Err("导入失败，请联系系统管理员"))
		return
	}
	if len(list) == 0 {
		// 对齐 importUser 首行：空列表立即抛 “导入用户数据不能为空！”
		response.Send(r, response.Err("导入用户数据不能为空！"))
		return
	}
	updateSupport := r.Get("updateSupport").Bool()
	msg, failed := importUserRows(ctx, lu, list, updateSupport)
	if failed {
		response.Send(r, response.Err(msg))
		return
	}
	response.Send(r, response.OkMsg(msg))
}

// importUserRows 逐行复刻 SysUserServiceImpl.importUser（无 @Transactional，行级 try/catch 语义）：
// 返回（聚合文案, 是否有失败）。失败时文案为“很抱歉…”全串（controller 转 code500）；无失败为“恭喜您…”。
func importUserRows(ctx context.Context, lu *model.LoginUser, list []model.SysUser, updateSupport bool) (string, bool) {
	operName := ""
	if lu != nil {
		operName = lu.Username
	}
	encPwd := service.EncryptPassword(service.GetUserInitPassword(ctx))
	var successNum, failureNum int
	var successMsg, failureMsg strings.Builder
	for i := range list {
		user := list[i]
		rowErr := ""
		u := service.FindUserByName(ctx, user.UserName)
		if u == nil {
			// 新增分支：校验 → checkDeptDataScope → 加密初始密码 → insertUser（裸 mapper，无角色/岗位）
			if vs := validateUser(&user); len(vs) > 0 {
				rowErr = violationsToText(vs)
			} else if msg := service.CheckDeptDataScope(ctx, lu, userPermiImport, user.DeptId); msg != "" {
				rowErr = msg
			} else {
				user.Password = encPwd
				if _, msg := service.InsertUser(ctx, &user, operName); msg != "" {
					rowErr = msg
				} else {
					successNum++
					fmt.Fprintf(&successMsg, "<br/>%d、账号 %s 导入成功", successNum, user.UserName)
				}
			}
		} else if updateSupport {
			// 更新分支：校验 → checkUserAllowed(u) → checkUserDataScope(u.userId) → checkDeptDataScope(user.deptId)
			// → 回填 userId/deptId(=u 的) → updateUser 裸 mapper（仅主行，不动角色/岗位）
			if vs := validateUser(&user); len(vs) > 0 {
				rowErr = violationsToText(vs)
			} else if msg := service.CheckUserAllowed(u); msg != "" {
				rowErr = msg
			} else if msg := service.CheckUserDataScope(ctx, lu, userPermiImport, u.UserId); msg != "" {
				rowErr = msg
			} else if msg := service.CheckDeptDataScope(ctx, lu, userPermiImport, user.DeptId); msg != "" {
				rowErr = msg
			} else {
				user.UserId = u.UserId
				user.DeptId = u.DeptId
				if _, msg := service.UpdateUserForImport(ctx, &user, operName); msg != "" {
					rowErr = msg
				} else {
					successNum++
					fmt.Fprintf(&successMsg, "<br/>%d、账号 %s 更新成功", successNum, user.UserName)
				}
			}
		} else {
			failureNum++
			fmt.Fprintf(&failureMsg, "<br/>%d、账号 %s 已存在", failureNum, user.UserName)
		}
		if rowErr != "" {
			failureNum++
			fmt.Fprintf(&failureMsg, "<br/>%d、账号 %s 导入失败：%s", failureNum, user.UserName, rowErr)
		}
	}
	if failureNum > 0 {
		return fmt.Sprintf("很抱歉，导入失败！共 %d 条数据格式不正确，错误如下：%s", failureNum, failureMsg.String()), true
	}
	return fmt.Sprintf("恭喜您，数据已全部导入成功！共 %d 条，数据如下：%s", successNum, successMsg.String()), false
}

// violationsToText 把字段级校验违规拼成一行失败文案（对应 Java BeanValidators 抛出的 ConstraintViolationException 消息）。
func violationsToText(vs []violation) string {
	msgs := make([]string, 0, len(vs))
	for _, v := range vs {
		msgs = append(msgs, v.Message)
	}
	return strings.Join(msgs, ",")
}

// fileExtension 取文件名最后一个 '.' 之后的后缀（无点或点后为空则回空串，对齐 FilenameUtils.getExtension）。
func fileExtension(name string) string {
	if i := strings.LastIndexByte(name, '.'); i >= 0 && i < len(name)-1 {
		return name[i+1:]
	}
	return ""
}

// isImportExtensionAllowed 对齐 MimeTypeUtils.IMPORT_EXTENSION = {"xls","xlsx"}（equalsIgnoreCase）。
func isImportExtensionAllowed(ext string) bool {
	return strings.EqualFold(ext, "xls") || strings.EqualFold(ext, "xlsx")
}

// UserGetInfo GET /system/user/ 与 /system/user/{userId}（system:user:query）。
// 有 userId 时回 data/postIds/roleIds；始终回 roles（非管理员过滤超管角色）与 posts。
func UserGetInfo(r *ghttp.Request) {
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	userIdStr := r.GetRouter("userId").String()
	hasId := userIdStr != ""
	userId := gconv.Int64(userIdStr)
	body := response.Ok()
	if hasId {
		if msg := service.CheckUserDataScope(ctx, lu, userPermiQuery, userId); msg != "" {
			response.Send(r, response.Err(msg))
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
	response.Send(r, body)
}

// UserAdd POST /system/user（system:user:add）。校验顺序对齐 Java：部门/角色数据范围 → 唯一性 → 落库。
func UserAdd(r *ghttp.Request) {
	user, err := parseUserBody(r)
	if err != nil {
		response.Send(r, response.Err("请求参数有误"))
		return
	}
	if vs := validateUserHttp(&user); len(vs) > 0 {
		response.Send(r, validErrBody(vs))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if msg := service.CheckDeptDataScope(ctx, lu, userPermiAdd, user.DeptId); msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	if msg := service.CheckRoleDataScope(ctx, lu, userPermiAdd, user.RoleIds...); msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	if !service.CheckUserNameUnique(ctx, &user) {
		response.Send(r, response.Err("新增用户'"+user.UserName+"'失败，登录账号已存在"))
		return
	} else if user.Phonenumber != "" && !service.CheckPhoneUnique(ctx, &user) {
		response.Send(r, response.Err("新增用户'"+user.UserName+"'失败，手机号码已存在"))
		return
	} else if user.Email != "" && !service.CheckEmailUnique(ctx, &user) {
		response.Send(r, response.Err("新增用户'"+user.UserName+"'失败，邮箱账号已存在"))
		return
	}
	user.Password = service.EncryptPassword(user.Password)
	rows, msg := service.InsertUser(ctx, &user, lu.Username)
	if msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	response.Send(r, toAjax(rows))
}

// UserEdit PUT /system/user（system:user:edit）。超管/数据范围 → 部门/角色数据范围 → 唯一性 → 落库。
func UserEdit(r *ghttp.Request) {
	user, err := parseUserBody(r)
	if err != nil {
		response.Send(r, response.Err("请求参数有误"))
		return
	}
	if vs := validateUserHttp(&user); len(vs) > 0 {
		response.Send(r, validErrBody(vs))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if msg := service.CheckUserAllowed(&user); msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	if msg := service.CheckUserDataScope(ctx, lu, userPermiEdit, user.UserId); msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	if msg := service.CheckDeptDataScope(ctx, lu, userPermiEdit, user.DeptId); msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	if msg := service.CheckRoleDataScope(ctx, lu, userPermiEdit, user.RoleIds...); msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	if !service.CheckUserNameUnique(ctx, &user) {
		response.Send(r, response.Err("修改用户'"+user.UserName+"'失败，登录账号已存在"))
		return
	} else if user.Phonenumber != "" && !service.CheckPhoneUnique(ctx, &user) {
		response.Send(r, response.Err("修改用户'"+user.UserName+"'失败，手机号码已存在"))
		return
	} else if user.Email != "" && !service.CheckEmailUnique(ctx, &user) {
		response.Send(r, response.Err("修改用户'"+user.UserName+"'失败，邮箱账号已存在"))
		return
	}
	rows, msg := service.UpdateUser(ctx, &user, lu.Username)
	if msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	response.Send(r, toAjax(rows))
}

// UserRemove DELETE /system/user/{userIds}（system:user:remove）。拦截删自己 → 逐个超管/数据范围校验 → 软删。
func UserRemove(r *ghttp.Request) {
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	ids, ok := splitCommaInts(r.Get("userIds").String())
	if !ok || len(ids) == 0 {
		response.Send(r, response.Err("用户 ID 非法"))
		return
	}
	if lu != nil && containsInt64(ids, lu.UserId) {
		response.Send(r, response.Err("当前用户不能删除"))
		return
	}
	rows, msg := service.DeleteUserByIds(ctx, lu, userPermiRemove, ids)
	if msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	response.Send(r, toAjax(rows))
}

// UserResetPwd PUT /system/user/resetPwd（system:user:resetPwd）。超管/数据范围 → BCrypt → 改密。
func UserResetPwd(r *ghttp.Request) {
	user, err := parseUserBody(r)
	if err != nil {
		response.Send(r, response.Err("请求参数有误"))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if msg := service.CheckUserAllowed(&user); msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	if msg := service.CheckUserDataScope(ctx, lu, userPermiResetPwd, user.UserId); msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	user.Password = service.EncryptPassword(user.Password)
	rows, _ := service.ResetPwd(ctx, &user)
	response.Send(r, toAjax(rows))
}

// UserChangeStatus PUT /system/user/changeStatus（system:user:edit）。超管/数据范围 → 改状态。
func UserChangeStatus(r *ghttp.Request) {
	user, err := parseUserBody(r)
	if err != nil {
		response.Send(r, response.Err("请求参数有误"))
		return
	}
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	if msg := service.CheckUserAllowed(&user); msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	if msg := service.CheckUserDataScope(ctx, lu, userPermiEdit, user.UserId); msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	rows, _ := service.UpdateUserStatus(ctx, &user)
	response.Send(r, toAjax(rows))
}

// UserAuthRole GET /system/user/authRole/{userId}（system:user:query）。回 user + roles（非管理员过滤超管角色，含 flag 回显）。
func UserAuthRole(r *ghttp.Request) {
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	userId := r.Get("userId").Int64()
	user := service.SelectUserById(ctx, userId)
	roles := filterAdminRoles(service.SelectRolesByUserId(ctx, lu, userPermiQuery, userId), service.IsAdmin(userId))
	body := response.Ok()
	body["user"] = user
	body["roles"] = roles
	response.Send(r, body)
}

// UserInsertAuthRole PUT /system/user/authRole（system:user:edit）。数据范围校验 → 重绑角色 → 强制该用户重登。
func UserInsertAuthRole(r *ghttp.Request) {
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	userId := r.Get("userId").Int64()
	roleIds := parseIdList(r, "roleIds")
	if msg := service.CheckUserDataScope(ctx, lu, userPermiEdit, userId); msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	if msg := service.CheckRoleDataScope(ctx, lu, userPermiEdit, roleIds...); msg != "" {
		response.Send(r, response.Err(msg))
		return
	}
	service.InsertUserAuth(ctx, userId, roleIds)
	service.DelLoginUsers(ctx, userId)
	response.Send(r, response.Ok())
}

// UserDeptTree GET /system/user/deptTree（system:user:list）→ data:[TreeSelect...]（含 DataScope）。
func UserDeptTree(r *ghttp.Request) {
	ctx := r.Context()
	lu := middleware.CurrentLoginUser(r)
	f := service.DeptListFilter{DeptName: r.Get("deptName").String(), Status: r.Get("status").String()}
	list := service.SelectDeptList(ctx, f, service.BuildDataScope(ctx, lu, userPermiList, "d", "dept_id", "", ""))
	response.Send(r, response.OkData(service.BuildDeptTreeSelect(list)))
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

// validateUser 复刻 SysUser 的 @Validated 字段结构约束（userName NotBlank+Size30，nickName Size30，email Email+Size50，phonenumber Size11）。
// 不含 @Xss 脚本字符【拒绝】——该层只在 HTTP 写端点（@Validated）触发，见 validateUserHttp；Excel 导入不走 @Validated，继续用本函数。
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

// validateUserHttp 复刻 add/edit 端点 @Validated 触发的完整约束：结构校验（validateUser）之外，
// 追加 SysUser 上 @Xss 对 userName/nickName 的布尔【拒绝】。该拒绝层仅存在于 HTTP 写端点
// （Excel 导入不走 @Validated），故单独成函数，只在 UserAdd/UserEdit 调用，导入分支继续用 validateUser。
// 依赖 xssHtmlPattern（同包 notice.go，复刻 XssValidator.HTML_PATTERN 的 find 半匹配）。
func validateUserHttp(user *model.SysUser) []violation {
	var vs []violation
	if xssHtmlPattern.MatchString(user.NickName) {
		vs = append(vs, violation{"nickName", "用户昵称不能包含脚本字符"})
	}
	if xssHtmlPattern.MatchString(user.UserName) {
		vs = append(vs, violation{"userName", "用户账号不能包含脚本字符"})
	}
	return append(vs, validateUser(user)...)
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
