// Package middleware —— 存储型 XSS 请求级净化，复刻 Java XssFilter + XssHttpServletRequestWrapper。
// 仅挂 /system、/monitor、/tool 三组（对齐 application.yml xss.urlPatterns；不碰 /login /common /profile）。
// 两条净化通道与 Java 一一对应：
//  1. JSON 请求体：整段 EscapeUtil.clean（对应 wrapper.getInputStream 里对原始 JSON 串的清洗），
//     这是公告内容等 @RequestBody 写接口（存储型 XSS 主向量）的收口点；净化后回写 r.SetBody，
//     下游 r.Parse 拿到的即已清洗的 JSON。
//  2. query / 表单参数：逐值 clean().trim()（对应 wrapper.getParameter/getParameterValues 家族）。
//     gf 参数模型与 Servlet 不同，此处按 URL/表单可改写者尽力对齐；RuoYi 写接口均走 JSON 体，
//     故通道 1 已覆盖真实落库面，通道 2 为纵深补充。
package middleware

import (
	"bytes"
	"io"
	"net/url"
	"strings"

	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/service"
)

// Xss 请求级 XSS 净化中间件，须在业务 handler 之前、且早于任何触发 gf 请求体缓存的调用执行。
// gf 会把首次读到的 body 缓存在私有字段且无公开 setter，故这里不碰 r.GetBody()，而是直接从底层
// r.Body 读出原始 JSON、净化后以新 reader 回填，令下游 r.Parse/GetBody 惰性读到的即已清洗内容。
func Xss(r *ghttp.Request) {
	// 作用域门控：严格对齐 Java xss.urlPatterns=/system/*,/monitor/*,/tool/*。
	// /common、/profile、/unlockscreen 不在其列——尤其 /unlockscreen 的 {password} 若被净化会破坏
	// BCrypt 比对（与 Java 排除它的意图一致），故非白名单前缀一律原样放行。
	if !inXssScope(r.URL.Path) {
		r.Middleware.Next()
		return
	}
	contentType := r.Header.Get("Content-Type")
	if isJSONContentType(contentType) && r.ContentLength != 0 {
		// 通道 1：整段净化原始 JSON 体（等价 Java 对 getInputStream 的包装）。
		raw, err := io.ReadAll(r.Body)
		_ = r.Body.Close()
		cleaned := raw
		if err == nil && len(raw) > 0 {
			cleaned = []byte(service.CleanHTML(string(raw)))
		}
		// 读失败或空体：原样回填，交由下游按既有语义处理（不落净化错误）。
		r.Body = io.NopCloser(bytes.NewReader(cleaned))
		r.ContentLength = int64(len(cleaned))
	}

	// 通道 2：query 参数逐值净化。仅在存在查询串时改写 RawQuery，clean 后重新编码回写。
	if raw := r.URL.RawQuery; raw != "" {
		vals, err := url.ParseQuery(raw)
		if err == nil && hasDirty(vals) {
			for k, vs := range vals {
				for i, v := range vs {
					vs[i] = service.CleanHTML(v)
				}
				vals[k] = vs
			}
			r.URL.RawQuery = vals.Encode()
		}
	}

	r.Middleware.Next()
}

// inXssScope 判定请求路径是否落在 Java xss.urlPatterns 的三个前缀内。
func inXssScope(path string) bool {
	return strings.HasPrefix(path, "/system/") ||
		strings.HasPrefix(path, "/monitor/") ||
		strings.HasPrefix(path, "/tool/")
}

// isJSONContentType 判定是否为 JSON 体（application/json 或 +json；含 charset 亦可）。
func isJSONContentType(contentType string) bool {
	ct := strings.ToLower(strings.TrimSpace(contentType))
	if i := strings.IndexByte(ct, ';'); i >= 0 {
		ct = strings.TrimSpace(ct[:i])
	}
	return strings.HasSuffix(ct, "/json") || strings.Contains(ct, "+json")
}

// hasDirty 快速判断查询值里是否含疑似 HTML 构造（'<' 或 '&'），无则跳过整轮 clean/重编码，
// 避免对纯普通参数无谓地改写编码（如空格变 '+'）。
func hasDirty(vals url.Values) bool {
	for _, vs := range vals {
		for _, v := range vs {
			if strings.ContainsAny(v, "<&") {
				return true
			}
		}
	}
	return false
}
