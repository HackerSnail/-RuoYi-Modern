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
	})
}
