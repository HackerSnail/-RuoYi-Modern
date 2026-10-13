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

// TestXssMiddlewareScopeAndSanitize 起随机端口 gf 测试服务，端到端验证方案 S 的三条契约：
//   - 作用域内、白名单 HTML 字段（noticeContent）在业务 handler 读到前被净化（script 剥、富文本留）；
//   - 同处作用域但非白名单的纯文本字段（password/remark/SQL）连字节都不改写，原样透传；
//   - 非作用域路径 /unlockscreen 原样放行。
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
		group.POST("/system/user", echo)
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

	// 1) 白名单 HTML 字段净化：夹带 script 的公告正文应被清洗，合法富文本与非白名单键保留。
	in1 := `{"noticeTitle":"t","noticeContent":"<p>正文</p><script>alert(1)</script>"}`
	got1 := client.PostContent(ctx, base+"/system/notice", in1)
	low1 := strings.ToLower(got1)
	if strings.Contains(low1, "<script") {
		t.Errorf("noticeContent 应被净化, 仍含 script: %q", got1)
	}
	if !strings.Contains(got1, "<p>正文</p>") {
		t.Errorf("noticeContent 合法富文本应保留, got=%q", got1)
	}
	if !strings.Contains(got1, `"noticeTitle":"t"`) {
		t.Errorf("非白名单键 noticeTitle 应原样保留, got=%q", got1)
	}

	// 2) 方案 S 核心保证：作用域内但非 HTML sink 的纯文本字段，含 & ' < > 也须逐字节原样透传，
	//    避免 route B 那种把密码/SQL/备注 HTML 转义的误伤。
	in2 := `{"password":"a&b'c<d","remark":"Tom & Jerry <b>x</b>","sql":"where a<>b"}`
	got2 := client.PostContent(ctx, base+"/system/user", in2)
	if got2 != in2 {
		t.Errorf("非白名单字段应字节级原样透传, got=%q want=%q", got2, in2)
	}

	// 3) 非作用域：/unlockscreen 密码原串含 "<"，须一字不改透传。
	in3 := `{"password":"a<b>c"}`
	got3 := client.PostContent(ctx, base+"/unlockscreen", in3)
	if got3 != in3 {
		t.Errorf("/unlockscreen 不在净化作用域内应原样放行, got=%q want=%q", got3, in3)
	}
}
