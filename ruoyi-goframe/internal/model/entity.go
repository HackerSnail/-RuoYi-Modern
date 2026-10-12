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
	CreateBy    string       `json:"createBy"    orm:"create_by"`
	UpdateTime  *gtime.Time  `json:"updateTime"  orm:"update_time"`
	UpdateBy    string       `json:"updateBy"    orm:"update_by"`
	Remark      string       `json:"remark"`
	PwdUpdateDate *gtime.Time `json:"pwdUpdateDate" orm:"pwd_update_date"`
	Dept        *SysDept     `json:"dept"`
	Roles       []*SysRole   `json:"roles"`
	RoleIds     []int64      `json:"roleIds"`
	PostIds     []int64      `json:"postIds"`
	RoleId      int64        `json:"roleId"`
	// Admin 派生自 isAdmin()（userId==1），非 DB 列，scan 时回填。
	Admin bool `json:"admin"`
	// Password 不参与 JSON：入参由控制器经 r.Get("password") 单独读取；出参恒不外泄。
	Password string `json:"-"`
}

// SysPost 岗位：对齐 com.ruoyi.system.domain.SysPost + BaseEntity 的 Jackson 输出。
//   - flag 为原始 boolean（默认 false），Jackson 始终输出；
//   - selectPostVo 不取 update_by/update_time，出参保持零值（同 Java resultMap 对这些列不映射）。
type SysPost struct {
	PostId     int64       `json:"postId"     orm:"post_id"`
	PostCode   string      `json:"postCode"   orm:"post_code"`
	PostName   string      `json:"postName"   orm:"post_name"`
	PostSort   int         `json:"postSort"   orm:"post_sort"`
	Status     string      `json:"status"`
	Flag       bool        `json:"flag"`
	CreateBy   string      `json:"createBy"   orm:"create_by"`
	CreateTime *gtime.Time `json:"createTime" orm:"create_time"`
	UpdateBy   string      `json:"updateBy"   orm:"update_by"`
	UpdateTime *gtime.Time `json:"updateTime" orm:"update_time"`
	Remark     string      `json:"remark"`
}

// SysConfig 参数配置：对齐 com.ruoyi.system.domain.SysConfig + BaseEntity 的 Jackson 输出。
// selectConfigVo 取全部列（含 update_by/update_time/remark），出参完整映射。
type SysConfig struct {
	ConfigId    int64       `json:"configId"    orm:"config_id"`
	ConfigName  string      `json:"configName"  orm:"config_name"`
	ConfigKey   string      `json:"configKey"   orm:"config_key"`
	ConfigValue string      `json:"configValue" orm:"config_value"`
	ConfigType  string      `json:"configType"  orm:"config_type"`
	CreateBy    string      `json:"createBy"    orm:"create_by"`
	CreateTime  *gtime.Time `json:"createTime"  orm:"create_time"`
	UpdateBy    string      `json:"updateBy"    orm:"update_by"`
	UpdateTime  *gtime.Time `json:"updateTime"  orm:"update_time"`
	Remark      string      `json:"remark"`
}

// SysDictType 字典类型：对齐 com.ruoyi.common.core.domain.entity.SysDictType + BaseEntity 的 Jackson 输出。
// selectDictTypeVo 仅取 dict_id/dict_name/dict_type/status/create_by/create_time/remark（不取 update_*），
// 故 UpdateBy/UpdateTime 保持零值（同 Java resultMap 对这些列不映射）。
type SysDictType struct {
	DictId     int64       `json:"dictId"     orm:"dict_id"`
	DictName   string      `json:"dictName"   orm:"dict_name"`
	DictType   string      `json:"dictType"   orm:"dict_type"`
	Status     string      `json:"status"`
	CreateBy   string      `json:"createBy"   orm:"create_by"`
	CreateTime *gtime.Time `json:"createTime" orm:"create_time"`
	UpdateBy   string      `json:"updateBy"   orm:"update_by"`
	UpdateTime *gtime.Time `json:"updateTime" orm:"update_time"`
	Remark     string      `json:"remark"`
}

// SysDictData 字典数据：对齐 com.ruoyi.common.core.domain.entity.SysDictData + BaseEntity 的 Jackson 输出。
// selectDictDataVo 不取 update_by/update_time，出参保持零值。Default 派生自 getDefault()（isDefault=="Y"），
// 与 getIsDefault() 并存——Jackson 会同时输出 "isDefault"(String) 与 "default"(boolean)。
type SysDictData struct {
	DictCode   int64       `json:"dictCode"   orm:"dict_code"`
	DictSort   int64       `json:"dictSort"   orm:"dict_sort"`
	DictLabel  string      `json:"dictLabel"  orm:"dict_label"`
	DictValue  string      `json:"dictValue"  orm:"dict_value"`
	DictType   string      `json:"dictType"   orm:"dict_type"`
	CssClass   string      `json:"cssClass"   orm:"css_class"`
	ListClass  string      `json:"listClass"  orm:"list_class"`
	IsDefault  string      `json:"isDefault"  orm:"is_default"`
	Status     string      `json:"status"`
	CreateBy   string      `json:"createBy"   orm:"create_by"`
	CreateTime *gtime.Time `json:"createTime" orm:"create_time"`
	UpdateBy   string      `json:"updateBy"   orm:"update_by"`
	UpdateTime *gtime.Time `json:"updateTime" orm:"update_time"`
	Remark     string      `json:"remark"`
	// Default 派生自 getDefault()：isDefault=="Y" 为 true，非 DB 列，scan 时回填。
	Default bool `json:"default"`
}

// SysNotice 对应 sys_notice + BaseEntity（com.ruoyi.system.domain.SysNotice）。
// selectNoticeVo 取全列（含 update_by/update_time/remark，notice_content 经 cast(... as char) 转字符串）。
// IsRead 派生自 getIsRead()：@JsonProperty("isRead") 恒序列化（CRUD 查询不取该列 → 默认 false），非 DB 列。
type SysNotice struct {
	NoticeId     int64       `json:"noticeId"     orm:"notice_id"`
	NoticeTitle  string      `json:"noticeTitle"  orm:"notice_title"`
	NoticeType   string      `json:"noticeType"   orm:"notice_type"`
	NoticeContent string     `json:"noticeContent" orm:"notice_content"`
	Status       string      `json:"status"`
	CreateBy     string      `json:"createBy"   orm:"create_by"`
	CreateTime   *gtime.Time `json:"createTime" orm:"create_time"`
	UpdateBy     string      `json:"updateBy"   orm:"update_by"`
	UpdateTime   *gtime.Time `json:"updateTime" orm:"update_time"`
	Remark       string      `json:"remark"`
	IsRead       bool        `json:"isRead"`
}

// SysDept 部门：既用于 getInfo 里 user.dept 子对象，也用于 /system/dept 全量出参。
// 字段对齐 com.ruoyi.common.core.domain.entity.SysDept + BaseEntity（Jackson 默认输出全部字段，含 null）。
type SysDept struct {
	DeptId     int64        `json:"deptId"     orm:"dept_id"`
	ParentId   int64        `json:"parentId"   orm:"parent_id"`
	Ancestors  string       `json:"ancestors"`
	DeptName   string       `json:"deptName"   orm:"dept_name"`
	OrderNum   int          `json:"orderNum"   orm:"order_num"`
	Leader     string       `json:"leader"`
	Phone      string       `json:"phone"`
	Email      string       `json:"email"`
	Status     string       `json:"status"`
	DelFlag    string       `json:"delFlag"    orm:"del_flag"`
	ParentName string       `json:"parentName" orm:"parent_name"`
	CreateBy   string       `json:"createBy"   orm:"create_by"`
	CreateTime *gtime.Time  `json:"createTime" orm:"create_time"`
	UpdateBy   string       `json:"updateBy"   orm:"update_by"`
	UpdateTime *gtime.Time  `json:"updateTime" orm:"update_time"`
	Remark     string       `json:"remark"`
	Children   []*SysDept   `json:"children"`
}

// SysRole 角色：既用于 /system/role 全量出参（对齐 SysRole + BaseEntity 的 Jackson 输出），
// 也用于 getInfo.roles / DataScope。出参字段名严格复刻 Jackson camelCase getter。
//   - menuCheckStrictly/deptCheckStrictly/flag 为原始 boolean，Jackson 始终输出；
//   - admin 派生自 isAdmin()（roleId==1），非 DB 列，scan 时回填；
//   - createBy/updateBy/updateTime 不在 selectRoleVo 选择列内（DB 不取），保持零值（前端不读，P1a 先例）；
//   - menuIds/deptIds 为入参（角色保存时提交的菜单/部门勾选），不出自查询。
type SysRole struct {
	RoleId            int64       `json:"roleId"            orm:"role_id"`
	RoleName          string      `json:"roleName"          orm:"role_name"`
	RoleKey           string      `json:"roleKey"           orm:"role_key"`
	RoleSort          int         `json:"roleSort"          orm:"role_sort"`
	DataScope         string      `json:"dataScope"         orm:"data_scope"`
	MenuCheckStrictly bool        `json:"menuCheckStrictly" orm:"menu_check_strictly"`
	DeptCheckStrictly bool        `json:"deptCheckStrictly" orm:"dept_check_strictly"`
	Status            string      `json:"status"`
	DelFlag           string      `json:"delFlag"    orm:"del_flag"`
	Flag              bool        `json:"flag"`
	Admin             bool        `json:"admin"`
	CreateBy          string      `json:"createBy"   orm:"create_by"`
	CreateTime        *gtime.Time `json:"createTime" orm:"create_time"`
	UpdateBy          string      `json:"updateBy"   orm:"update_by"`
	UpdateTime        *gtime.Time `json:"updateTime" orm:"update_time"`
	Remark            string      `json:"remark"`
	MenuIds           []int64     `json:"menuIds"`
	DeptIds           []int64     `json:"deptIds"`
	// Permissions 为该角色被分配的权限串集合载体（不参与前端出参，DataScope 另有 scopeRole 计算）。
	Permissions []string `json:"-"`
}

// SysMenu 对应 sys_menu + BaseEntity，字段/列名一一对应。children 供树构建填充，
// 且必须像 Java 那样始终序列化（列表/详情回 []，非省略）。
type SysMenu struct {
	MenuId     int64      `json:"menuId"     orm:"menu_id"`
	MenuName   string     `json:"menuName"   orm:"menu_name"`
	ParentName string     `json:"parentName" orm:"parent_name"`
	ParentId   int64      `json:"parentId"   orm:"parent_id"`
	OrderNum   int        `json:"orderNum"   orm:"order_num"`
	Path       string     `json:"path"`
	Component  string     `json:"component"`
	Query      string     `json:"query"`
	RouteName  string     `json:"routeName"  orm:"route_name"`
	IsFrame    string     `json:"isFrame"    orm:"is_frame"`
	IsCache    string     `json:"isCache"    orm:"is_cache"`
	MenuType   string     `json:"menuType"   orm:"menu_type"`
	Visible    string     `json:"visible"`
	Status     string     `json:"status"`
	Perms      string     `json:"perms"`
	Icon       string     `json:"icon"`
	CreateBy   string     `json:"createBy"   orm:"create_by"`
	CreateTime *gtime.Time `json:"createTime" orm:"create_time"`
	UpdateBy   string     `json:"updateBy"   orm:"update_by"`
	UpdateTime *gtime.Time `json:"updateTime" orm:"update_time"`
	Remark     string     `json:"remark"`
	Children   []*SysMenu `json:"children"`
}

// TreeSelect 对应 com.ruoyi.common.core.domain.TreeSelect：下拉树节点 {id,label,disabled,children?}。
// disabled 为原始 boolean，Jackson 始终输出；children @JsonInclude(NON_EMPTY) → 空则省略。
type TreeSelect struct {
	Id       int64        `json:"id"`
	Label    string       `json:"label"`
	Disabled bool         `json:"disabled"`
	Children []*TreeSelect `json:"children,omitempty"`
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

// SysUserOnline 在线会话出参：对齐 com.ruoyi.system.domain.SysUserOnline 的 Jackson 输出。
// tokenId 承载脱敏会话标识（服务端由会话 uuid 单向派生），既非 uuid 也非 JWT，仅供回传定位强退目标。
// loginTime 为登录时间戳（毫秒，源自 LoginUser.loginTime；Java 原始 long 恒非空）。
type SysUserOnline struct {
	TokenId       string `json:"tokenId"`
	DeptName      string `json:"deptName"`
	UserName      string `json:"userName"`
	Ipaddr        string `json:"ipaddr"`
	LoginLocation string `json:"loginLocation"`
	Browser       string `json:"browser"`
	Os            string `json:"os"`
	LoginTime     int64  `json:"loginTime"`
}

// SysCache 缓存监控展示行：对齐 com.ruoyi.system.domain.SysCache 的 Jackson 输出。
// 四字段恒序列化（Java 无 @JsonInclude，默认全出）。getNames 阶段 cacheName 保留尾冒号原样；
// getValue 阶段经三参构造剥前缀（cacheName 去冒号作分组名、cacheKey 去掉带冒号前缀）。
type SysCache struct {
	CacheName  string `json:"cacheName"`
	CacheKey   string `json:"cacheKey"`
	CacheValue string `json:"cacheValue"`
	Remark     string `json:"remark"`
}
