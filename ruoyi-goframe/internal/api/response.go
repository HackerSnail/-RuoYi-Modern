// Package api 复刻 Java 侧统一响应封装 AjaxResult 与 HttpStatusResponseAdvice 的传输层状态码收口。
//
// 关键点（务必与后端一致，否则前端判定错位）：
//  1. body 恒为对象 {code,msg,data?}；data 为空则不放该键（对应 StringUtils.isNotNull 判定）。
//  2. /login、/getInfo 把 token / user / roles / permissions 等放在 body 顶层（非 data 下），
//     调用方以 SendRaw 追加顶层键后发出。
//  3. 传输层 HTTP 状态按 body.code 映射：白名单语义码透传，其余(含业务 500、warn 601)回退 400；
//     真·系统故障由 SendSystemError 显式置 HTTP 500。
package api

import (
	"net/http"

	"github.com/gogf/gf/v2/net/ghttp"
	"ruoyi-goframe/internal/consts"
)

// M 是响应体别名：JSON 对象。
type M = map[string]interface{}

// passThroughCodes：原样透传为 HTTP 状态的已知语义码。刻意不含 500(业务失败默认码→回退 400)与 601。
var passThroughCodes = map[int]bool{
	200: true, 201: true, 202: true, 204: true,
	301: true, 303: true, 304: true,
	400: true, 401: true, 403: true, 404: true, 405: true, 409: true, 415: true,
	501: true,
}

// resolveHTTPStatus 业务码 → HTTP 状态：白名单原样，其余回退 400。
func resolveHTTPStatus(code int) int {
	if passThroughCodes[code] {
		return code
	}
	return http.StatusBadRequest
}

// ---- 便捷构造：对应 AjaxResult.success/warn/error 各重载 ----

func ok() M { return M{"code": consts.CodeSuccess, "msg": "操作成功"} }

// Ok 返回成功（无 data）。
func Ok() M { return ok() }

// OkData 成功 + data。data 为 nil 时不放 data 键（对齐 isNotNull）。
func OkData(data interface{}) M {
	m := ok()
	if data != nil {
		m["data"] = data
	}
	return m
}

// OkMsg 成功 + 自定义 msg。
func OkMsg(msg string) M { return M{"code": consts.CodeSuccess, "msg": msg} }

// Warn 警告（code=601，传输层将回退 400）。
func Warn(msg string) M { return M{"code": consts.CodeWarn, "msg": msg} }

// Err 业务失败（code=500，传输层回退 400）。
func Err(msg string) M { return M{"code": consts.CodeError, "msg": msg} }

// ErrCode 自定义码 + msg。
func ErrCode(code int, msg string) M { return M{"code": code, "msg": msg} }

// Send 按 body.code 计算真实 HTTP 状态并写出 JSON。
func Send(r *ghttp.Request, body M) {
	code, _ := body["code"].(int)
	if code == 0 {
		code = consts.CodeSuccess
	}
	writeJSON(r, resolveHTTPStatus(code), body)
}

// SendSystemError 真·系统故障：显式 HTTP 500 + code 500（与业务失败的 400 区分开）。
func SendSystemError(r *ghttp.Request, msg string) {
	writeJSON(r, http.StatusInternalServerError, M{"code": consts.CodeError, "msg": msg})
}

// SendAuthError 认证失败：固定 HTTP 401 + code 401（对齐 AuthenticationEntryPointImpl）。
func SendAuthError(r *ghttp.Request) {
	writeJSON(r, http.StatusUnauthorized, M{"code": consts.CodeUnauthorized, "msg": "认证失败，无法访问系统资源"})
}

// SendForbidden 鉴权失败：固定 HTTP 403 + code 403。
func SendForbidden(r *ghttp.Request, msg string) {
	writeJSON(r, http.StatusForbidden, M{"code": consts.CodeForbidden, "msg": msg})
}

func writeJSON(r *ghttp.Request, status int, body M) {
	r.Response.ClearBuffer()
	r.Response.Header().Set("Content-Type", "application/json;charset=utf-8")
	r.Response.Status = status
	r.Response.WriteJson(body)
}
