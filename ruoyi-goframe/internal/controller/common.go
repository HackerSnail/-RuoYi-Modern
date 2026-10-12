// Package controller —— P1a 公共 HTTP 辅助：toAjax（对齐 BaseController.toAjax）、
// @Validated 字段校验失败响应（对齐 GlobalExceptionHandler.buildValidationError）、逗号串解析。
package controller

import (
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/api"
	"ruoyi-goframe/internal/consts"
)

// paramRange 读取 RuoYi 前端日期范围（beginTime/endTime）。
// 前端把它们放进 params 对象，axios GET 序列化成 URL 编码键 params%5BbeginTime%5D；
// gf 的 r.Get("params[beginTime]") 读不到（未与编码后的键匹配，实测恒空），
// 须经 r.URL.Query() 取——其键已被 url 解码为 params[beginTime]（businessTypes 走同径已验证）。
func paramRange(r *ghttp.Request, key string) string {
	return r.URL.Query().Get("params[" + key + "]")
}

// toAjax 对应 BaseController.toAjax(rows)：>0 回 {code:200,"操作成功"}，否则 {code:500,"操作失败"}。
func toAjax(rows int) api.M {
	if rows > 0 {
		return api.Ok()
	}
	return api.M{"code": consts.CodeError, "msg": "操作失败"}
}

// violation 一条字段级校验违规（对齐 buildValidationError 的 {field,message}）。
type violation struct {
	Field   string `json:"field"`
	Message string `json:"message"`
}

// validErrBody 组装校验失败响应体：msg 为各违规 message 的 "; " 拼接，data 为字段级明细。
// 对应 AjaxResult.error(msg, details) → {code:500,msg,data}，经收口映射为 HTTP 400。
func validErrBody(vs []violation) api.M {
	if len(vs) == 0 {
		return api.Err("参数校验失败")
	}
	msgs := make([]string, 0, len(vs))
	details := make([]interface{}, 0, len(vs))
	for _, v := range vs {
		msgs = append(msgs, v.Message)
		details = append(details, v)
	}
	return api.ErrData(strings.Join(msgs, "; "), details)
}

// runeLen 字符数（对齐 Java @Size 按字符计）。
func runeLen(s string) int { return utf8.RuneCountInString(s) }

// splitCommaInts 逗号分隔串解析为 int64 切片；遇非数字返回 (已解析部分, false)。
func splitCommaInts(s string) ([]int64, bool) {
	if s == "" {
		return nil, true
	}
	parts := strings.Split(s, ",")
	out := make([]int64, 0, len(parts))
	for _, p := range parts {
		n, err := strconv.ParseInt(strings.TrimSpace(p), 10, 64)
		if err != nil {
			return out, false
		}
		out = append(out, n)
	}
	return out, true
}

// splitCommaIntsLenient 解析，非法项回退默认 -1（对齐 Convert.toLong(x,-1)）。
func splitCommaIntsLenient(s string) []int64 {
	parts := strings.Split(s, ",")
	out := make([]int64, 0, len(parts))
	for _, p := range parts {
		n, err := strconv.ParseInt(strings.TrimSpace(p), 10, 64)
		if err != nil {
			out = append(out, -1)
			continue
		}
		out = append(out, n)
	}
	return out
}

// splitCommas 拆逗号串为字符串切片（保留原始项，用于排序号字符串）。
func splitCommas(s string) []string {
	if s == "" {
		return nil
	}
	return strings.Split(s, ",")
}

// parseInt 解析十进制整数（已 trim），非法返回错误。对应 Convert.toLong/toInt 的解析面。
func parseInt(s string) (int64, error) {
	return strconv.ParseInt(strings.TrimSpace(s), 10, 64)
}
