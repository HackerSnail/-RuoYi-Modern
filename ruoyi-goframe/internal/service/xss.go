// Package service —— 存储型 XSS 白名单净化，复刻 Java EscapeUtil.clean（jsoup Safelist.relaxed()
// .preserveRelativeLinks(true)，prettyPrint(false)）。本 fork application.yml 已开启 xss.enabled，
// 且不再豁免 /system/notice，故公告内容等写接口入库前须经此净化闭合存储型 XSS。
//
// 与 jsoup relaxed 的对齐策略：
//   - 放行常见富文本块级/行内标签（p/br/a/b/i/em/strong/u/h1-6/blockquote/pre/code/span/div/table… 系列）。
//   - 仅放行 a 的 href、img 的 src 两个 URL 属性；不放开 style/class/on*（jsoup relaxed 同样剥离，
//     Java 注释亦明示"若将来需保留内联 style/class 应改为放宽 SAFELIST 属性"）。
//   - 相对链接（如 /profile/upload/x.png）保留（对应 preserveRelativeLinks(true)）：bluemonday 默认
//     不强制 URL 可解析，故相对 href/src 不被剥。
//   - 危险协议 javascript:/data: 与 script/style 由未列入白名单自动剥离；on* 事件属性同理被去除。
//
// 说明：输出与 jsoup 非字节级一致（不同实现），但安全语义等价——危险构造被清除、安全富文本被保留。
package service

import (
	"strings"

	"github.com/microcosm-cc/bluemonday"
)

// xssPolicy 镜像 jsoup Safelist.relaxed() 的标签/属性白名单，包级单例（bluemonday Policy 并发安全）。
var xssPolicy = buildXssPolicy()

func buildXssPolicy() *bluemonday.Policy {
	p := bluemonday.NewPolicy()
	p.AllowElements(
		"a", "b", "blockquote", "br", "caption", "cite", "code", "col", "colgroup",
		"dd", "div", "dl", "dt", "em", "h1", "h2", "h3", "h4", "h5", "h6", "i", "img",
		"li", "ol", "p", "pre", "q", "small", "span", "strike", "strong", "sub", "sup",
		"table", "tbody", "td", "tfoot", "th", "thead", "tr", "u", "ul",
	)
	// URL 协议：允许常见安全协议；javascript:/data:/vbscript: 等未列出即被剥。
	p.AllowURLSchemes("http", "https", "ftp", "mailto")
	// 保留相对链接（/profile/upload/x.png 等），对应 jsoup 的 preserveRelativeLinks(true)：
	// AllowURLSchemes 已置 RequireParseableURLs(true)，不开此项则无 scheme 的相对 URL 会被剥。
	p.AllowRelativeURLs(true)
	// 仅这两个元素携带 URL 属性，与 jsoup relaxed 的 :link(href)/img(src) 一致；不放行其余属性（含 style/class/on*）。
	p.AllowAttrs("href").OnElements("a")
	p.AllowAttrs("src").OnElements("img")
	return p
}

// CleanHTML 净化单段 HTML，复刻 EscapeUtil.clean(x).trim()：空串原样返回，其余去标签保白名单并裁首尾空白。
func CleanHTML(s string) string {
	if s == "" {
		return ""
	}
	return strings.TrimSpace(xssPolicy.Sanitize(s))
}
