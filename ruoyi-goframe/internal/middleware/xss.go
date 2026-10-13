// Package middleware —— 存储型 XSS 净化（方案 S：按字段/HTML sink 收口）。
// 复刻 Java XssFilter 的挂载位置（/system、/monitor、/tool 三组，对齐 application.yml
// xss.urlPatterns；不碰 /login /common /profile /unlockscreen），但把净化范围收敛到
// "真正承载 HTML 的写字段"：仅对 JSON 请求体里白名单键（当前 noticeContent，公告正文=
// 存储型 XSS 主向量）做 CleanHTML；其余键（password/remark/SQL 等纯文本）以 json.RawMessage
// 原样透传，连字节都不改写。
//
// 与"整段 body+query 净化"（route B）的取舍：整段净化会把纯文本字段里的 & < > ' 也 HTML 转义
// （jsoup 与 bluemonday 皆如此），既污染密码（含 ' 或 & 的密码被改写后致 BCrypt 比对失败）、
// 又损坏 gen 的 SQL 文本（WHERE a<b 被当标签）。方案 S 只在 HTML sink 字段净化，规避这些误伤；
// RuoYi 其余写值在前端均按文本渲染、非 XSS sink，故按字段净化即可闭合存储型 XSS。HTML sink
// 清单见 xssJSONFields，后续若新增富文本写入字段须在此登记。query 参数不再净化（搜索词含 & <
// 时 Java 反会把它转义成实体、扭曲 LIKE 语义，S 明确不再复制该行为）。
package middleware

import (
	"bytes"
	"encoding/json"
	"io"
	"strings"

	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/service"
)

// xssJSONFields 承载 HTML 的写字段 JSON 键白名单（存储型 XSS sink）；仅这些键会被净化。
var xssJSONFields = map[string]struct{}{
	"noticeContent": {}, // 公告正文：前端以富文本渲染，是本仓唯一确认的 HTML sink
}

// Xss 请求级 XSS 净化中间件（方案 S）。命中作用域路径时，仅改写 JSON 体里白名单 HTML 字段的值，
// 其余字节原样透传；非作用域路径或空体一律放行不改。须在业务 handler 之前、早于任何触发 gf
// 请求体缓存的调用执行——这里直接从底层 r.Body 读出、净化后回填新 reader，令下游 r.Get/r.Parse
// 惰性读到的即已处理内容（gf 无公开 SetBody，故不能走 r.GetBody 缓存路径）。
func Xss(r *ghttp.Request) {
	// 作用域门控：严格对齐 Java xss.urlPatterns=/system/*,/monitor/*,/tool/*。
	// /common、/profile、/unlockscreen 不在其列，一律原样放行。
	if !inXssScope(r.URL.Path) {
		r.Middleware.Next()
		return
	}
	contentType := r.Header.Get("Content-Type")
	if isJSONContentType(contentType) && r.ContentLength != 0 {
		raw, err := io.ReadAll(r.Body)
		_ = r.Body.Close()
		out := raw
		if err == nil && len(raw) > 0 {
			if cleaned, changed, ok := sanitizeJSONFields(raw, xssJSONFields); ok && changed {
				out = cleaned
			}
		}
		// 无论是否改写都须回填 reader（body 已被读空），令下游正常读取。
		r.Body = io.NopCloser(bytes.NewReader(out))
		r.ContentLength = int64(len(out))
	}

	r.Middleware.Next()
}

// sanitizeJSONFields 仅净化 obj 中命中 allow 的字符串键值，其余键保留其原始 json.RawMessage 字节
// （故非白名单字段连编码都不重排）。返回 (改写后的 JSON, 是否发生变化, 是否可安全处理)：
// 非 JSON 对象体（数组/表单/畸形）第三个返回值为 false，调用方据此原样保留、不改写。
func sanitizeJSONFields(raw []byte, allow map[string]struct{}) ([]byte, bool, bool) {
	var obj map[string]json.RawMessage
	if err := json.Unmarshal(raw, &obj); err != nil {
		return raw, false, false
	}
	changed := false
	for k, v := range obj {
		if _, ok := allow[k]; !ok {
			continue
		}
		var s string
		if err := json.Unmarshal(v, &s); err != nil {
			continue // 该键非字符串（null/对象/数组等）：不动
		}
		if c := service.CleanHTML(s); c != s {
			if nb := jsonString(c); nb != nil {
				obj[k] = nb
				changed = true
			}
		}
	}
	if !changed {
		return raw, false, true
	}
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false) // 不因重编码把已净化内容里的 < > & 再转义回实体（解码语义不变，输出更贴近原文）
	if err := enc.Encode(obj); err != nil {
		return raw, false, true
	}
	return bytes.TrimRight(buf.Bytes(), "\n"), true, true
}

// jsonString 以关闭 HTML 转义的方式把字符串编码成 JSON 字面量 RawMessage（保留已净化的 <>&
// 原样而非退回 \u003c，与整段重编码的 SetEscapeHTML(false) 保持一致）。失败返回 nil。
func jsonString(v string) json.RawMessage {
	var b bytes.Buffer
	enc := json.NewEncoder(&b)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil
	}
	return bytes.TrimRight(b.Bytes(), "\n")
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
