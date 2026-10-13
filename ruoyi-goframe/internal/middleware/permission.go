// Package middleware —— 权限校验，对齐 Spring Security @PreAuthorize("@ss.hasPermi('xxx')")：
// 命中放行；未命中抛 AccessDeniedException 的等价效果 → GlobalExceptionHandler 回 {code:403,"没有权限，请联系管理员授权"}，
// 403 属透传白名单码，故传输层为真实 HTTP 403。
package middleware

import (
	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/response"
	"ruoyi-goframe/internal/service"
)

// HasPermi 返回一个要求当前会话具备 permission 的中间件（admin 的 *:*:* 天然放行）。
// 注意：GF 的 group.GET/POST/... 只接受单个 handler 对象，故 route 级权限须用 Permi 组合，
// 不能把 HasPermi 与控制器作为两个参数并列传入（否则控制器被当 params 丢弃、Next() 空栈致 404）。
func HasPermi(permission string) ghttp.HandlerFunc {
	return func(r *ghttp.Request) {
		if !checkPermi(r, permission) {
			return
		}
		r.Middleware.Next()
	}
}

// Permi 将权限校验与真实控制器组合成单个 handler，供 route.GET/POST/... 直接注册；
// 校验不通过则已写出 401/403 响应并中止，通过则调用 next 控制器。
func Permi(permission string, next ghttp.HandlerFunc) ghttp.HandlerFunc {
	return func(r *ghttp.Request) {
		if !checkPermi(r, permission) {
			return
		}
		next(r)
	}
}

// checkPermi 执行鉴权+权限校验，返回是否放行；未放行时已写响应并 ExitAll。
func checkPermi(r *ghttp.Request, permission string) bool {
	lu := CurrentLoginUser(r)
	if lu == nil {
		response.SendAuthError(r)
		r.ExitAll()
		return false
	}
	if !service.HasPermission(lu.Permissions, permission) {
		response.SendForbidden(r, "没有权限，请联系管理员授权")
		r.ExitAll()
		return false
	}
	return true
}
