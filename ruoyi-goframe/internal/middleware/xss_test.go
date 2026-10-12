package middleware

import (
	"context"
	"fmt"
	"strings"
	"testing"

	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/net/ghttp"
	"github.com/gogf/gf/v2/util/guid"
)

// TestXssMiddlewareScopeAndSanitize 起一个随机端口的 gf 测试服务，端到端验证：
//   - /system/* 的 JSON 体在业务 handler 读到之前已被净化（script 被剥、富文本保留）；
//   - 非作用域路径 /unlockscreen 原样放行（含 "<" 的密码不被改写，护住 BCrypt 比对）。
func TestXssMiddlewareScopeAndSanitize(t *testing.T) {
	s := g.Server(guid.S())
	s.SetPort(0)
	s.SetDumpRouterMap(false)
	s.SetAccessLogEnabled(false)
	s.SetErrorLogEnabled(false)
	s.Group("/", func(group *ghttp.RouterGroup) {
		group.Middleware(Xss)
		echo := func(r *ghttp.Request) { r.Response.Write(r.GetBody()) }
		group.POST("/system/notice", echo)
		group.POST("/unlockscreen", echo)
	})
	if err := s.Start(); err != nil {
		t.Fatalf("启动测试服务失败: %v", err)
	}
	defer s.Shutdown()

	ctx := context.Background()
	base := fmt.Sprintf("http://127.0.0.1:%d", s.GetListenedPort())
	client := g.Client()
	client.SetHeader("Content-Type", "application/json")

	// 作用域内：夹带 script 的公告体应被净化后落到 handler。
	in := `{"noticeTitle":"t","noticeContent":"<p>正文</p><script>alert(1)</script>"}`
	got := client.PostContent(ctx, base+"/system/notice", in)
	if strings.Contains(strings.ToLower(got), "<script") {
		t.Errorf("/system 请求体未被净化, 仍含 script: %q", got)
	}
	if !strings.Contains(got, "<p>正文</p>") {
		t.Errorf("/system 合法富文本应保留, got=%q", got)
	}

	// 非作用域：/unlockscreen 的密码原串含 "<"，须一字不改地透传给 handler。
	pw := `{"password":"a<b>c"}`
	got2 := client.PostContent(ctx, base+"/unlockscreen", pw)
	if got2 != pw {
		t.Errorf("/unlockscreen 不在净化作用域内应原样放行, got=%q want=%q", got2, pw)
	}
}
