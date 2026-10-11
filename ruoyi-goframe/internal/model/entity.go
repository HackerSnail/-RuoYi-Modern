// Package model 定义与 Java 后端逐字段对齐的出参实体。
// json tag 必须复刻 Jackson 的 camelCase 输出（前端按 userName/nickName/... 读取）。
package model

import "github.com/gogf/gf/v2/os/gtime"

// SysUser 对应 com.ruoyi.common.core.domain.entity.SysUser 的 getInfo 出参子集。
// Password 刻意不序列化（Java 侧虽含字段但前端不用；不外泄哈希更安全）。
type SysUser struct {
	UserId      int64        `json:"userId"      orm:"user_id"`
	DeptId      int64        `json:"deptId"      orm:"dept_id"`
	UserName    string       `json:"userName"    orm:"user_name"`
	NickName    string       `json:"nickName"    orm:"nick_name"`
	Email       string       `json:"email"`
	Phonenumber string       `json:"phonenumber"`
	Sex         string       `json:"sex"`
	Avatar      string       `json:"avatar"`
	Status      string       `json:"status"`
	DelFlag     string       `json:"delFlag"     orm:"del_flag"`
	LoginIp     string       `json:"loginIp"     orm:"login_ip"`
	LoginDate   *gtime.Time  `json:"loginDate"   orm:"login_date"`
	CreateTime  *gtime.Time  `json:"createTime"  orm:"create_time"`
	PwdUpdateDate *gtime.Time `json:"pwdUpdateDate" orm:"pwd_update_date"`
	Dept        *SysDept     `json:"dept"`
	// Password 不参与 JSON：登录校验用，不回传
	Password string `json:"-"`
}

// SysDept 用户所属部门（getInfo 里 user.dept 子对象）。
type SysDept struct {
	DeptId   int64  `json:"deptId"   orm:"dept_id"`
	DeptName string `json:"deptName" orm:"dept_name"`
}

// SysRole 角色精简（getInfo.roles 取 role_key 集合；此处备用）。
type SysRole struct {
	RoleId   int64  `json:"roleId"   orm:"role_id"`
	RoleName string `json:"roleName" orm:"role_name"`
	RoleKey  string `json:"roleKey"  orm:"role_key"`
	Status   string `json:"status"`
}

// SysMenu 对应 sys_menu，字段与列名一一对应。children 供树构建填充，不参与路由出参序列化。
type SysMenu struct {
	MenuId    int64      `json:"menuId"    orm:"menu_id"`
	MenuName  string     `json:"menuName"  orm:"menu_name"`
	ParentId  int64      `json:"parentId"  orm:"parent_id"`
	OrderNum  int        `json:"orderNum"  orm:"order_num"`
	Path      string     `json:"path"`
	Component string     `json:"component"`
	Query     string     `json:"query"`
	RouteName string     `json:"routeName" orm:"route_name"`
	IsFrame   string     `json:"isFrame"   orm:"is_frame"`
	IsCache   string     `json:"isCache"   orm:"is_cache"`
	MenuType  string     `json:"menuType"  orm:"menu_type"`
	Visible   string     `json:"visible"`
	Status    string     `json:"status"`
	Perms     string     `json:"perms"`
	Icon      string     `json:"icon"`
	Children  []*SysMenu `json:"-"`
}

// LoginBody 对应 com.ruoyi.common.core.domain.model.LoginBody：/login 请求体。
type LoginBody struct {
	Username string `json:"username"`
	Password string `json:"password"`
	Code     string `json:"code"`
	UUID     string `json:"uuid"`
}

// LoginUser 缓存于 Redis login_tokens:{uuid} 的会话对象。Go 自签自发自己校验，形状只需自洽；
// 保留 Java 侧常用字段名，便于后续两端观察对齐。
type LoginUser struct {
	UserId      int64      `json:"userId"`
	Username    string     `json:"username"`
	Token       string     `json:"token"` // 会话 uuid（Redis 键后缀）
	LoginTime   int64      `json:"loginTime"`
	ExpireTime  int64      `json:"expireTime"`
	Ipaddr      string     `json:"ipaddr"`
	LoginLocation string   `json:"loginLocation"`
	Browser     string     `json:"browser"`
	Os          string     `json:"os"`
	Roles       []string   `json:"roles"`
	Permissions []string   `json:"permissions"`
	User        *SysUser   `json:"user"`
}
