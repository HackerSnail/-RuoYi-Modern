// Package controller —— P0 认证全链路 HTTP 处理器：/captchaImage /login /logout /getInfo /getRouters。
// 出参一律走 api 包，确保 {code,msg,data} 结构与真实 HTTP 状态码与 Java 后端逐字一致。
package controller

import (
	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/api"
	"ruoyi-goframe/internal/consts"
	"ruoyi-goframe/internal/middleware"
	"ruoyi-goframe/internal/model"
	"ruoyi-goframe/internal/service"
)

// Ping 健康检查（非 RuoYi 契约接口，仅供部署探活/自测）。
func Ping(r *ghttp.Request) {
	api.Send(r, api.OkData("pong"))
}

// CaptchaImage GET /captchaImage → {captchaEnabled, uuid?, img?}。关闭时只回 captchaEnabled=false。
func CaptchaImage(r *ghttp.Request) {
	ctx := r.Context()
	body := api.Ok()
	enabled := service.GetCaptchaEnabled(ctx)
	body["captchaEnabled"] = enabled
	if !enabled {
		api.Send(r, body)
		return
	}
	uuid, img, _ := service.GenerateCaptcha(ctx)
	body["uuid"] = uuid
	body["img"] = img
	api.Send(r, body)
}

// Login POST /login → 成功 {code:200, msg:操作成功, token, 密码状态字段}；失败 {code:500}→HTTP400 + 具体文案。
func Login(r *ghttp.Request) {
	var lb model.LoginBody
	if err := r.Parse(&lb); err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	ip := r.GetClientIp()
	ua := r.GetHeader("User-Agent")

	token, _, msg := service.Login(r.Context(), lb, ip, ua)
	if msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	body := api.Ok()
	body[consts.Token] = token
	// 密码状态契约（admin/未启用周期时均为 false；不占用顶层 code，避免与前端成功判定冲突）
	body["isDefaultModifyPwd"] = false
	body["isPasswordExpired"] = false
	body["forceChangePassword"] = false
	api.Send(r, body)
}

// Register POST /register（公开）→ 对应 SysRegisterController.register。
// 先查注册开关（sys.account.registerUser）；未开启直接拒；否则调 service.Register，msg 空即 success。
func Register(r *ghttp.Request) {
	ctx := r.Context()
	if !service.IsRegisterEnabled(ctx) {
		api.Send(r, api.Err("当前系统没有开启注册功能！"))
		return
	}
	var lb model.LoginBody
	if err := r.Parse(&lb); err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	ip := r.GetClientIp()
	ua := r.GetHeader("User-Agent")
	if msg := service.Register(ctx, lb, ip, ua); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	api.Send(r, api.Ok())
}

// Logout POST /logout → 尽力删除当前会话，回 "退出成功"。
func Logout(r *ghttp.Request) {
	ctx := r.Context()
	auth := r.GetHeader(service.TokenHeader(ctx))
	if lu := service.GetLoginUser(ctx, auth); lu != nil {
		service.DeleteLoginUser(ctx, lu.Token)
		// 对齐 Java LogoutSuccessHandlerImpl：仅当取到会话才记 LOGOUT 审计（→status '0'）。
		service.AsyncRecordLogininfor(lu.Username, consts.EvLogout, "退出成功", r.GetClientIp(), r.GetHeader("User-Agent"))
	}
	api.Send(r, api.OkMsg("退出成功"))
}

// GetInfo GET /getInfo → 顶层 user/roles/permissions/pwdChrtype + 密码状态。
func GetInfo(r *ghttp.Request) {
	lu := middleware.CurrentLoginUser(r)
	body := api.Ok()
	for k, v := range service.BuildUserInfoPayload(r.Context(), lu) {
		body[k] = v
	}
	api.Send(r, body)
}

// GetRouters GET /getRouters → {code:200, msg, data:[RouterVo...]}，空也回 []。
func GetRouters(r *ghttp.Request) {
	lu := middleware.CurrentLoginUser(r)
	menus := service.SelectMenuTreeByUserId(r.Context(), lu.UserId)
	list := service.BuildMenus(menus)
	if list == nil {
		list = []*model.RouterVo{}
	}
	body := api.Ok()
	body["data"] = list
	api.Send(r, body)
}
