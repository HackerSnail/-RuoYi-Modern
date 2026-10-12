// Package service —— 登录链路与用户信息，对齐 SysLoginService.login / UserDetailsServiceImpl /
// SysPermissionService / SysLoginController.getInfo 的真实语义。
//
// 关键契约（照 Java 逐条复刻，不得语义错位）：
//   - 校验顺序：验证码 → 前置(空/长度) → 认证(用户存在/状态/密码/重试锁定)。
//   - 本 fork 的认证阶段(用户名不存在/已删除/已停用/密码错/锁定)经 Spring 包装后统一对外为
//     "用户不存在/密码错误"（SysLoginService 对 AuthenticationException 一律抛 UserPasswordNotMatch）；
//     而"验证码错误/已失效""* 必须填写"在认证阶段之前抛出，保留各自文案。
//   - 失败计数 pwd_err_cnt:{username}，达 maxRetryCount 锁定 lockTime 分钟；成功清零。BCrypt 比对。
package service

import (
	"context"
	"fmt"

	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/util/gconv"
	"golang.org/x/crypto/bcrypt"

	"ruoyi-goframe/internal/consts"
	"ruoyi-goframe/internal/model"
)

const msgBadCred = "用户不存在/密码错误"

// Login 执行登录。成功返回 (jwt令牌, 会话对象, "")；失败返回 ("", nil, 对外文案)。
func Login(ctx context.Context, lb model.LoginBody, ip, userAgent string) (string, *model.LoginUser, string) {
	// 1. 验证码
	if m := validateCaptcha(ctx, lb.Code, lb.UUID); m != "" {
		AsyncRecordLogininfor(lb.Username, consts.EvLoginFail, m, ip, userAgent)
		return "", nil, m
	}
	// 2. 前置校验（空 → not.null；长度越界 → 认证文案，同 Java 顺序）
	if lb.Username == "" || lb.Password == "" {
		AsyncRecordLogininfor(lb.Username, consts.EvLoginFail, "* 必须填写", ip, userAgent)
		return "", nil, "* 必须填写"
	}
	if len(lb.Password) < 5 || len(lb.Password) > 20 {
		AsyncRecordLogininfor(lb.Username, consts.EvLoginFail, msgBadCred, ip, userAgent)
		return "", nil, msgBadCred
	}
	if len(lb.Username) < 2 || len(lb.Username) > 20 {
		AsyncRecordLogininfor(lb.Username, consts.EvLoginFail, msgBadCred, ip, userAgent)
		return "", nil, msgBadCred
	}
	// IP 黑名单校验（对齐 Java loginPreCheck 末尾的 sys.login.blackIPList → BlackListException）：
	// 命中即拒并记登录失败，位置在用户名长度之后、认证之前，文案逐字取 i18n login.blocked。
	if IsMatchedIp(selectConfigByKey(ctx, "sys.login.blackIPList"), ip) {
		const msgIPBlocked = "很遗憾，访问IP已被列入系统黑名单"
		AsyncRecordLogininfor(lb.Username, consts.EvLoginFail, msgIPBlocked, ip, userAgent)
		return "", nil, msgIPBlocked
	}
	// 3. 认证：加载用户 + 状态 + BCrypt（失败统一 msgBadCred）
	user := selectUserByUserName(ctx, lb.Username)
	if user == nil || !checkUserAllowed(ctx, lb.Username, user) || !verifyPassword(ctx, lb.Username, lb.Password, user) {
		AsyncRecordLogininfor(lb.Username, consts.EvLoginFail, msgBadCred, ip, userAgent)
		return "", nil, msgBadCred
	}
	clearPwdErrCount(ctx, lb.Username)

	// 4. 装配会话（角色/权限）
	roles := getRolePermission(ctx, user)
	perms := getMenuPermission(ctx, user)
	lu := &model.LoginUser{
		UserId:      user.UserId,
		Username:    user.UserName,
		Ipaddr:      ip,
		Browser:     userAgent,
		Roles:       roles,
		Permissions: perms,
		User:        user,
	}
	token, err := CreateToken(ctx, lu)
	if err != nil {
		return "", nil, msgBadCred
	}
	recordLoginInfo(ctx, user.UserId, ip)
	AsyncRecordLogininfor(lb.Username, consts.EvLoginSuccess, "登录成功", ip, userAgent)
	return token, lu, ""
}

// Register 执行注册，对应 SysRegisterService.register：严格照其顺序与文案。
// 返回 "" 表成功；否则为对外失败文案。审计落库异步且与 Java 一一对应：
//   - 验证码失败：validateCaptcha 内部已记并抛异常 → 此处记一次后即返回（不再走末尾统一审计）；
//   - 入库 DB 异常（Java catch 后记“注册异常”并重新抛出）：同样记一次后即返回；
//   - 其余（各校验失败 / 成功）：均走末尾统一审计一次（REGISTER 事件→status '0'，成败由 msg 承载）。
func Register(ctx context.Context, lb model.LoginBody, ip, userAgent string) string {
	username, password := lb.Username, lb.Password
	// 1. 验证码（内含开关）：非空即失败文案（“验证码已失效/错误”），审计一次并早返。
	if m := validateCaptcha(ctx, lb.Code, lb.UUID); m != "" {
		AsyncRecordLogininfor(username, consts.EvRegister, m, ip, userAgent)
		return m
	}
	// 2. 前置校验（顺序、文案逐字照 Java）
	var msg string
	u := &model.SysUser{UserName: username}
	switch {
	case username == "":
		msg = "用户名不能为空"
	case password == "":
		msg = "用户密码不能为空"
	case len(username) < consts.UserNameMinLen || len(username) > consts.UserNameMaxLen:
		msg = "账户长度必须在2到20个字符之间"
	case len(password) < consts.PasswordMinLen || len(password) > consts.PasswordMaxLen:
		msg = "密码长度必须在5到20个字符之间"
	case !CheckUserNameUnique(ctx, u):
		msg = "保存用户'" + username + "'失败，注册账号已存在"
	default:
		u.NickName = username
		u.Password = EncryptPassword(password)
		ok, err := RegisterUser(ctx, u)
		if err != nil {
			// Java：唯一索引并发冲突等 DB 异常→记审计后原样抛出交全局异常处理（回 500）。Go 无异常层，以文案回错。
			msg = "注册异常：" + err.Error()
			AsyncRecordLogininfor(username, consts.EvRegister, msg, ip, userAgent)
			return msg
		}
		if !ok {
			msg = "注册失败,请联系系统管理人员"
		}
	}
	// 3. 统一注册结果审计：成功记“注册成功”，任一校验/入库失败记失败原因。
	auditMsg := msg
	if auditMsg == "" {
		auditMsg = "注册成功"
	}
	AsyncRecordLogininfor(username, consts.EvRegister, auditMsg, ip, userAgent)
	return msg
}

// selectUserByUserName 按用户名取未删除用户（含密文，供 BCrypt）。对应 del_flag='0' 过滤。
func selectUserByUserName(ctx context.Context, username string) *model.SysUser {
	rec, err := g.DB().Model("sys_user").Ctx(ctx).
		Where("user_name = ?", username).Where("del_flag = ?", "0").One()
	if err != nil || rec == nil || rec.IsEmpty() {
		return nil
	}
	var u model.SysUser
	if err := gconv.Struct(rec.Map(), &u); err != nil {
		return nil
	}
	u.Password = gconv.String(rec.Map()["password"]) // 密文单独取（Password json:"-"，不外泄）
	return &u
}

// checkUserAllowed 停用(status!=0)拒绝。已删除已被查询过滤。失败对外仍为 msgBadCred（调用处统一）。
func checkUserAllowed(ctx context.Context, username string, u *model.SysUser) bool {
	return u.Status == consts.Normal
}

// verifyPassword 含重试锁定 + BCrypt 比对；失败递增计数。
func verifyPassword(ctx context.Context, username, rawPw string, u *model.SysUser) bool {
	key := consts.RedisPwdErrCnt + username
	maxRetry := g.Cfg().MustGet(ctx, "user.password.maxRetryCount", 5).Int()
	lockMin := g.Cfg().MustGet(ctx, "user.password.lockTime", 10).Int()

	cnt := 0
	if v, err := g.Redis().Get(ctx, key); err == nil && v != nil && !v.IsNil() {
		cnt = v.Int()
	}
	if cnt >= maxRetry {
		return false
	}
	if bcrypt.CompareHashAndPassword([]byte(u.Password), []byte(rawPw)) != nil {
		_, _ = g.Redis().Do(ctx, "SETEX", key, lockMin*60, cnt+1)
		return false
	}
	return true
}

func clearPwdErrCount(ctx context.Context, username string) {
	_, _ = g.Redis().Del(ctx, consts.RedisPwdErrCnt+username)
}

// ValidateUnlock 屏幕解锁密码校验，逐字复刻 SysPasswordService.validateUnlock：复用登录的失败计数键
// (pwd_err_cnt:{username}) 与阈值(maxRetryCount/lockTime)。达上限→锁定文案；BCrypt 不匹配→计数 +1、
// SETEX lockTime*60 并回错误；匹配→清零。返回 "" 表示通过，否则为对外 ServiceException 文案。
// 刻意与登录共享同一份密码尝试预算：解锁失败也会锁定登录（防跨接口暴破，同 Java）。
func ValidateUnlock(ctx context.Context, username, rawPw string, u *model.SysUser) string {
	key := consts.RedisPwdErrCnt + username
	maxRetry := g.Cfg().MustGet(ctx, "user.password.maxRetryCount", 5).Int()
	lockMin := g.Cfg().MustGet(ctx, "user.password.lockTime", 10).Int()

	cnt := 0
	if v, err := g.Redis().Get(ctx, key); err == nil && v != nil && !v.IsNil() {
		cnt = v.Int()
	}
	if cnt >= maxRetry {
		return fmt.Sprintf("密码错误次数过多，账号已锁定，请 %d 分钟后再试", lockMin)
	}
	if bcrypt.CompareHashAndPassword([]byte(u.Password), []byte(rawPw)) != nil {
		_, _ = g.Redis().Do(ctx, "SETEX", key, lockMin*60, cnt+1)
		return "密码错误，请重新输入"
	}
	clearPwdErrCount(ctx, username)
	return ""
}

// UnlockScreen 复刻 SysIndexController.unlockScreen 的完整校验链：按当前登录用户名取用户（含密文），
// 不存在→"会话已失效，请重新登录"；否则调 ValidateUnlock。返回 "" 表示解锁成功，否则为对外失败文案。
func UnlockScreen(ctx context.Context, username, rawPw string) string {
	user := selectUserByUserName(ctx, username)
	if user == nil {
		return "会话已失效，请重新登录"
	}
	return ValidateUnlock(ctx, username, rawPw, user)
}

// recordLoginInfo 更新最后登录 IP 与时间（对齐 SysLoginService.recordLoginInfo）。
func recordLoginInfo(ctx context.Context, userId int64, ip string) {
	_, _ = g.DB().Exec(ctx, "UPDATE sys_user SET login_ip = ?, login_date = NOW() WHERE user_id = ?", ip, userId)
}

// ---- 角色/权限（对齐 SysPermissionService）----

// isAdmin 超级管理员：userId==1（对齐 SecurityUtils.isAdmin / SysUser.isAdmin）。
func IsAdmin(userId int64) bool { return userId == 1 }

func getRolePermission(ctx context.Context, u *model.SysUser) []string {
	if IsAdmin(u.UserId) {
		return []string{consts.SuperAdmin}
	}
	return queryColumnList(ctx,
		"select distinct r.role_key from sys_user_role ur, sys_role r "+
			"where ur.role_id = r.role_id and r.status = '0' and r.del_flag = '0' and ur.user_id = ?", u.UserId)
}

func getMenuPermission(ctx context.Context, u *model.SysUser) []string {
	if IsAdmin(u.UserId) {
		return []string{consts.AllPermission}
	}
	perms := queryColumnList(ctx,
		"select distinct m.perms from sys_menu m, sys_role_menu rm, sys_user_role ur "+
			"where m.menu_id = rm.menu_id and rm.role_id = ur.role_id and m.status = '0' and ur.user_id = ?", u.UserId)
	// 逗号分隔的 perms 串需拆分（同 Java selectMenuPermsByUserId 后 split(",")）
	return splitPerms(perms)
}

func queryColumnList(ctx context.Context, sql string, arg interface{}) []string {
	rs, err := g.DB().GetAll(ctx, sql, arg)
	if err != nil || rs == nil {
		return nil
	}
	var out []string
	for _, rec := range rs {
		for _, v := range rec {
			s := v.String()
			if s != "" {
				out = append(out, s)
			}
			break
		}
	}
	return out
}

func splitPerms(in []string) []string {
	var out []string
	seen := map[string]bool{}
	for _, item := range in {
		for _, p := range splitByComma(item) {
			if p != "" && !seen[p] {
				seen[p] = true
				out = append(out, p)
			}
		}
	}
	return out
}

func splitByComma(s string) []string {
	var out []string
	cur := []rune{}
	for _, c := range s {
		if c == ',' {
			out = append(out, string(cur))
			cur = cur[:0]
		} else {
			cur = append(cur, c)
		}
	}
	out = append(out, string(cur))
	return out
}

// BuildUserInfoPayload 组装 /getInfo 的顶层键（user/roles/permissions/... 均在 body 顶层，非 data 下）。
// 对应 SysLoginController.getInfo：回传 user、roles、permissions、pwdChrtype 与密码状态三字段。
func BuildUserInfoPayload(ctx context.Context, lu *model.LoginUser) g.Map {
	user := lu.User
	if user != nil && user.DeptId > 0 {
		user.Dept = loadDept(ctx, user.DeptId)
	}
	roles := getRolePermission(ctx, user)
	perms := getMenuPermission(ctx, user)
	return g.Map{
		"user":                user,
		"roles":               roles,
		"permissions":         perms,
		"pwdChrtype":          defaultStr(selectConfigByKey(ctx, "sys.account.chrtype"), "0"),
		"isDefaultModifyPwd":  false,
		"isPasswordExpired":   false,
		"forceChangePassword": false,
	}
}

func loadDept(ctx context.Context, deptId int64) *model.SysDept {
	rec, err := g.DB().Model("sys_dept").Ctx(ctx).Where("dept_id = ?", deptId).One()
	if err != nil || rec == nil || rec.IsEmpty() {
		return nil
	}
	var d model.SysDept
	_ = gconv.Struct(rec.Map(), &d)
	return &d
}

func defaultStr(s, def string) string {
	if s == "" {
		return def
	}
	return s
}
