// Package router —— 路由注册。公开端点(login/captchaImage/logout)无需鉴权；
// getInfo/getRouters 挂 Auth 中间件，语义对齐 SecurityConfig 的 permitAll 白名单 + anyRequest().authenticated()。
package router

import (
	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/controller"
	"ruoyi-goframe/internal/middleware"
)

// Register 挂载全部 P0 路由。
func Register(s *ghttp.Server) {
	// 跨域：前端 RuoYi-Vue3 以 Authorization 头携带令牌，CORS 需回显该请求头，gf 默认按请求头动态放行。
	s.Use(ghttp.MiddlewareCORS)

	// 公开端点（对应 permitAll：/login /captchaImage；/logout 由登出处理，尽力删除会话）
	s.BindHandler("GET:/ping", controller.Ping)
	s.BindHandler("GET:/captchaImage", controller.CaptchaImage)
	s.BindHandler("POST:/login", controller.Login)
	s.BindHandler("POST:/logout", controller.Logout)

	// 受保护端点：必须携带有效令牌
	s.Group("/", func(group *ghttp.RouterGroup) {
		group.Middleware(middleware.Auth)
		group.GET("/getInfo", controller.GetInfo)
		group.GET("/getRouters", controller.GetRouters)
	})

	// P1a 菜单/部门。用根分组 + 完整路径注册（避免子分组内 g.POST("") 空 pattern 无法映射到裸路径 /system/menu）。
	// 各端点对齐 @PreAuthorize；treeselect 仅需登录态无 permi。GF 路由只接受单个 handler，权限用 Permi 与控制器组合。
	s.Group("/", func(g *ghttp.RouterGroup) {
		g.Middleware(middleware.Auth)
		// 菜单 /system/menu
		g.GET("/system/menu/list", middleware.Permi("system:menu:list", controller.MenuList))
		g.GET("/system/menu/treeselect", controller.MenuTreeselect)
		g.GET("/system/menu/roleMenuTreeselect/{roleId}", middleware.Permi("system:role:query", controller.RoleMenuTreeselect))
		g.POST("/system/menu", middleware.Permi("system:menu:add", controller.MenuAdd))
		g.PUT("/system/menu/updateSort", middleware.Permi("system:menu:edit", controller.MenuUpdateSort))
		g.PUT("/system/menu", middleware.Permi("system:menu:edit", controller.MenuEdit))
		g.GET("/system/menu/{menuId}", middleware.Permi("system:menu:query", controller.MenuGetInfo))
		g.DELETE("/system/menu/{menuId}", middleware.Permi("system:menu:remove", controller.MenuRemove))
		// 部门 /system/dept
		g.GET("/system/dept/list", middleware.Permi("system:dept:list", controller.DeptList))
		g.GET("/system/dept/list/exclude/{deptId}", middleware.Permi("system:dept:list", controller.DeptExclude))
		g.POST("/system/dept", middleware.Permi("system:dept:add", controller.DeptAdd))
		g.PUT("/system/dept/updateSort", middleware.Permi("system:dept:edit", controller.DeptUpdateSort))
		g.PUT("/system/dept", middleware.Permi("system:dept:edit", controller.DeptEdit))
		g.GET("/system/dept/{deptId}", middleware.Permi("system:dept:query", controller.DeptGetInfo))
		g.DELETE("/system/dept/{deptId}", middleware.Permi("system:dept:remove", controller.DeptRemove))
		// 角色 /system/role（CRUD + optionselect + deptTree；authUser 系列随后注册）
		g.GET("/system/role/list", middleware.Permi("system:role:list", controller.RoleList))
		g.GET("/system/role/optionselect", middleware.Permi("system:role:query", controller.RoleOptionSelect))
		g.GET("/system/role/deptTree/{roleId}", middleware.Permi("system:role:query", controller.RoleDeptTree))
		g.POST("/system/role", middleware.Permi("system:role:add", controller.RoleAdd))
		g.PUT("/system/role/dataScope", middleware.Permi("system:role:edit", controller.RoleDataScope))
		g.PUT("/system/role/changeStatus", middleware.Permi("system:role:edit", controller.RoleChangeStatus))
		g.PUT("/system/role", middleware.Permi("system:role:edit", controller.RoleEdit))
		g.GET("/system/role/{roleId}", middleware.Permi("system:role:query", controller.RoleGetInfo))
		g.DELETE("/system/role/{roleIds}", middleware.Permi("system:role:remove", controller.RoleRemove))
		// 角色授权用户 /system/role/authUser
		g.GET("/system/role/authUser/allocatedList", middleware.Permi("system:role:list", controller.AllocatedList))
		g.GET("/system/role/authUser/unallocatedList", middleware.Permi("system:role:list", controller.UnallocatedList))
		g.PUT("/system/role/authUser/cancel", middleware.Permi("system:role:edit", controller.CancelAuthUser))
		g.PUT("/system/role/authUser/cancelAll", middleware.Permi("system:role:edit", controller.CancelAuthUserAll))
		g.PUT("/system/role/authUser/selectAll", middleware.Permi("system:role:edit", controller.SelectAuthUserAll))
		// 用户 /system/user（静态路径先于 {userId} 动态路径注册，利用 GF 字面量优先于通配的匹配）
		g.GET("/system/user/list", middleware.Permi("system:user:list", controller.UserList))
		g.GET("/system/user/deptTree", middleware.Permi("system:user:list", controller.UserDeptTree))
		g.GET("/system/user/authRole/{userId}", middleware.Permi("system:user:query", controller.UserAuthRole))
		g.GET("/system/user/", middleware.Permi("system:user:query", controller.UserGetInfo))
		g.POST("/system/user", middleware.Permi("system:user:add", controller.UserAdd))
		g.PUT("/system/user/resetPwd", middleware.Permi("system:user:resetPwd", controller.UserResetPwd))
		g.PUT("/system/user/changeStatus", middleware.Permi("system:user:edit", controller.UserChangeStatus))
		g.PUT("/system/user/authRole", middleware.Permi("system:user:edit", controller.UserInsertAuthRole))
		g.PUT("/system/user", middleware.Permi("system:user:edit", controller.UserEdit))
		g.GET("/system/user/{userId}", middleware.Permi("system:user:query", controller.UserGetInfo))
		g.DELETE("/system/user/{userIds}", middleware.Permi("system:user:remove", controller.UserRemove))
		// 岗位 /system/post（optionselect/list 静态先于 {postId} 动态；Java optionselect 无 @PreAuthorize，仅挂 Auth）
		g.GET("/system/post/list", middleware.Permi("system:post:list", controller.PostList))
		g.GET("/system/post/optionselect", controller.PostOptionSelect)
		g.POST("/system/post", middleware.Permi("system:post:add", controller.PostAdd))
		g.PUT("/system/post", middleware.Permi("system:post:edit", controller.PostEdit))
		g.GET("/system/post/{postId}", middleware.Permi("system:post:query", controller.PostGetInfo))
		g.DELETE("/system/post/{postIds}", middleware.Permi("system:post:remove", controller.PostRemove))
		// 参数 /system/config（configKey 公开只读无 permi；refreshCache 静态先于 {configIds} 动态）
		g.GET("/system/config/list", middleware.Permi("system:config:list", controller.ConfigList))
		g.GET("/system/config/configKey/{configKey}", controller.ConfigGetKey)
		g.POST("/system/config", middleware.Permi("system:config:add", controller.ConfigAdd))
		g.PUT("/system/config", middleware.Permi("system:config:edit", controller.ConfigEdit))
		g.DELETE("/system/config/refreshCache", middleware.Permi("system:config:refresh", controller.ConfigRefreshCache))
		g.GET("/system/config/{configId}", middleware.Permi("system:config:query", controller.ConfigGetInfo))
		g.DELETE("/system/config/{configIds}", middleware.Permi("system:config:remove", controller.ConfigRemove))
		// 字典类型 /system/dict/type（optionselect/refreshCache 静态先于 {dictId}/{dictIds} 动态；optionselect 无 permi）
		g.GET("/system/dict/type/list", middleware.Permi("system:dict:list", controller.DictTypeList))
		g.GET("/system/dict/type/optionselect", controller.DictTypeOptionSelect)
		g.POST("/system/dict/type", middleware.Permi("system:dict:add", controller.DictTypeAdd))
		g.PUT("/system/dict/type", middleware.Permi("system:dict:edit", controller.DictTypeEdit))
		g.DELETE("/system/dict/type/refreshCache", middleware.Permi("system:dict:refresh", controller.DictTypeRefreshCache))
		g.GET("/system/dict/type/{dictId}", middleware.Permi("system:dict:query", controller.DictTypeGetInfo))
		g.DELETE("/system/dict/type/{dictIds}", middleware.Permi("system:dict:remove", controller.DictTypeRemove))
		// 字典数据 /system/dict/data（list/type 静态先于 {dictCode}/{dictCodes} 动态；data/type/{dictType} 无 permi，走敏感黑名单）
		g.GET("/system/dict/data/list", middleware.Permi("system:dict:list", controller.DictDataList))
		g.GET("/system/dict/data/type/{dictType}", controller.DictDataType)
		g.POST("/system/dict/data", middleware.Permi("system:dict:add", controller.DictDataAdd))
		g.PUT("/system/dict/data", middleware.Permi("system:dict:edit", controller.DictDataEdit))
		g.GET("/system/dict/data/{dictCode}", middleware.Permi("system:dict:query", controller.DictDataGetInfo))
		g.DELETE("/system/dict/data/{dictCodes}", middleware.Permi("system:dict:remove", controller.DictDataRemove))
		// 通知公告 /system/notice（C1 核心 CRUD；getInfo 无 permi 仅需登录；list 静态先于 {noticeId}/{noticeIds} 动态）
		g.GET("/system/notice/list", middleware.Permi("system:notice:list", controller.NoticeList))
		g.POST("/system/notice", middleware.Permi("system:notice:add", controller.NoticeAdd))
		g.PUT("/system/notice", middleware.Permi("system:notice:edit", controller.NoticeEdit))
		g.GET("/system/notice/{noticeId}", controller.NoticeGetInfo)
		g.DELETE("/system/notice/{noticeIds}", middleware.Permi("system:notice:remove", controller.NoticeRemove))
	})
}
