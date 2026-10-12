// Package consts 集中定义与 Java 后端逐字对齐的常量：响应码、令牌/缓存键、菜单与用户标识。
// 这些值必须与 com.ruoyi.common.constant.{HttpStatus,Constants,CacheConstants,UserConstants} 保持一致，
// 否则前端 RuoYi-Vue3 的状态判定、令牌解析、路由渲染都会错位。
package consts

// 业务响应码（对应 HttpStatus）。前端 axios 拦截器与错误收口都按这些码判定。
const (
	CodeSuccess = 200 // 操作成功
	CodeError   = 500 // 通用业务失败（与真·系统故障同码，靠传输层状态码区分）
	CodeWarn    = 601 // 警告（非 HTTP 码）
	CodeBadRequest     = 400
	CodeUnauthorized   = 401
	CodeForbidden      = 403
	CodeInternalServerError = 500
)

// 令牌相关（对应 Constants）。
const (
	Token         = "token"      // 登录响应里令牌所处的顶层字段名
	TokenPrefix   = "Bearer "    // Authorization 头前缀
	LoginUserKey  = "login_user_key" // JWT claim：会话 uuid
	JWTUsername   = "sub"        // JWT claim：用户名
	CaptchaExpirationMinutes = 2 // 验证码有效期
)

// Redis 缓存键前缀（对应 CacheConstants）。与 Java 完全同名，便于同库观察。
const (
	RedisLoginToken  = "login_tokens:"
	RedisCaptchaCode = "captcha_codes:"
	RedisSysConfig   = "sys_config:"
	RedisSysDict     = "sys_dict:"
	RedisRepeatSubmit = "repeat_submit:"
	RedisRateLimit    = "rate_limit:"
	RedisPwdErrCnt   = "pwd_err_cnt:"
)

// 权限/角色标识（对应 Constants）。
const (
	AllPermission = "*:*:*"
	SuperAdmin    = "admin"
)

// 菜单/用户标识（对应 UserConstants）。
const (
	Normal       = "0"
	Exception    = "1"
	Yes          = "Y"
	YesFrame     = "0" // 是否外链：是（UserConstants.YES_FRAME）
	NoFrame      = "1" // 是否外链：否（UserConstants.NO_FRAME）
	MenuShow     = "0"
	MenuHide     = "1"
	MenuCacheNo  = "1" // is_cache=1 不缓存
	TypeDir      = "M" // 目录
	TypeMenu     = "C" // 菜单
	TypeButton   = "F" // 按钮
	Layout       = "Layout"
	ParentView   = "ParentView"
	InnerLink    = "InnerLink"
	MenuRootID   = int64(0)

	// 部门/角色状态（对应 UserConstants.DEPT_*/ROLE_*）。
	DeptNormal  = "0"
	DeptDisable = "1"
	RoleNormal  = "0"
	RoleDisable = "1"

	// 顶级节点祖级列表值（UserConstants.ROOT_ANCESTORS）。
	RootAncestors = "0"
)

// 内链替换用到的协议/域名前缀（对应 Constants.HTTP/HTTPS/WWW）。
const (
	HttpPrefix  = "http://"
	HttpsPrefix = "https://"
	WwwPrefix   = "www."
)

// 数据范围（对应 Constants.Dept.DATA_SCOPE_*）：1全部 2自定义 3本部门 4本部门及以下 5仅本人。
const (
	DataScopeAll          = "1"
	DataScopeCustom       = "2"
	DataScopeDept         = "3"
	DataScopeDeptAndChild = "4"
	DataScopeSelf         = "5"
)

// 唯一性校验返回（对应 UserConstants.UNIQUE/NOT_UNIQUE，Java 为 boolean）。
const (
	Unique    = true
	NotUnique = false
)

// 会话 uuid 长度：与 Java IdUtils.fastUUID 一致用标准 UUID（36 位带 -）。
// 前端不解析令牌内容，只透传，故形态只需服务端自洽。
