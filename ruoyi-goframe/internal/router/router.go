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
}
