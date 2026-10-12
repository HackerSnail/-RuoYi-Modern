package service

import (
	"strings"
	"testing"
)

// TestCleanHTML 锁定净化语义与 jsoup Safelist.relaxed 对齐：危险构造清除、安全富文本保留、纯文本不变。
func TestCleanHTML(t *testing.T) {
	// 危险：script/事件属性/危险协议，结果绝不应再含这些可执行构造。
	dangerous := []string{
		`<script>alert(1)</script>`,
		`<p onclick="evil()">hi</p>`,
		`<a href="javascript:alert(document.cookie)">x</a>`,
		`<img src="data:image/svg+xml;base64,AAAA" onerror="evil()">`,
		`<div style="background:url(javascript:evil())">t</div>`,
	}
	for _, in := range dangerous {
		out := CleanHTML(in)
		low := strings.ToLower(out)
		if strings.Contains(low, "<script") || strings.Contains(low, "onerror") ||
			strings.Contains(low, "onclick") || strings.Contains(low, "javascript:") ||
			strings.Contains(low, "data:image") || strings.Contains(low, "style=") {
			t.Errorf("CleanHTML(%q)=%q 仍残留危险构造", in, out)
		}
	}

	// 安全富文本与相对链接应被保留（等价 preserveRelativeLinks(true)）。
	keep := []struct {
		in   string
		want string
	}{
		{`<p>正文</p>`, `<p>正文</p>`},
		{`<b>粗</b><i>斜</i>`, `<b>粗</b><i>斜</i>`},
		{`<img src="/profile/upload/2026/a.png">`, `<img src="/profile/upload/2026/a.png">`},
		{`<a href="/system/notice/list">链</a>`, `<a href="/system/notice/list">链</a>`},
		{`hello world 无需净化`, `hello world 无需净化`},
	}
	for _, c := range keep {
		if got := CleanHTML(c.in); got != c.want {
			t.Errorf("CleanHTML(%q)=%q, 期望保留为 %q", c.in, got, c.want)
		}
	}

	// 空串原样返回；首尾空白裁掉（复刻 clean(x).trim()）。
	if CleanHTML("") != "" {
		t.Errorf("CleanHTML(\"\") 应为空串")
	}
	if got := CleanHTML("  <p>x</p>  "); got != "<p>x</p>" {
		t.Errorf("CleanHTML 应裁首尾空白, got=%q", got)
	}
}

// TestCleanHTMLNoticePayload 贴近真实场景：公告正文夹带 script，净化后正文可见、脚本消失。
func TestCleanHTMLNoticePayload(t *testing.T) {
	in := `<p>通知：<script>window.location='http://evil'</script>请查收</p>`
	out := CleanHTML(in)
	if strings.Contains(strings.ToLower(out), "script") || strings.Contains(out, "evil") {
		t.Errorf("公告正文净化后仍残留脚本: %q", out)
	}
	if !strings.Contains(out, "通知：") || !strings.Contains(out, "请查收") {
		t.Errorf("公告正文的合法文字应保留: %q", out)
	}
}
