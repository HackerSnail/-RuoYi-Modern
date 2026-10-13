// Package middleware —— JWT 鉴权过滤器，对齐 JwtAuthenticationTokenFilter：
// 从请求头取令牌 → TokenService.getLoginUser → 命中则续期并注入上下文；未命中回 HTTP 401（同 AuthenticationEntryPointImpl）。
package middleware

import (
	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/response"
	"ruoyi-goframe/internal/model"
	"ruoyi-goframe/internal/service"
)

// CtxKeyLoginUser 是当前请求上下文里存放会话对象的键。
const CtxKeyLoginUser = "loginUser"

// Auth 保护型路由中间件：无有效会话即 401 终止。
func Auth(r *ghttp.Request) {
	header := service.TokenHeader(r.Context())
	authorization := r.GetHeader(header)
	lu := service.GetLoginUser(r.Context(), authorization)
	if lu == nil {
		response.SendAuthError(r)
		r.ExitAll()
		return
	}
	service.RefreshTokenIfNeeded(r.Context(), lu)
	r.SetCtxVar(CtxKeyLoginUser, lu)
	r.Middleware.Next()
}

// CurrentLoginUser 从上下文取会话对象（供受保护 handler 使用）。
func CurrentLoginUser(r *ghttp.Request) *model.LoginUser {
	v := r.GetCtxVar(CtxKeyLoginUser)
	if v == nil || v.IsNil() {
		return nil
	}
	lu, _ := v.Interface().(*model.LoginUser)
	return lu
}
