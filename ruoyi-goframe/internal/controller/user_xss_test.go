package controller

import (
	"testing"

	"ruoyi-goframe/internal/model"
)

func hasViolationMsg(vs []violation, want string) bool {
	for _, v := range vs {
		if v.Message == want {
			return true
		}
	}
	return false
}

// TestValidateUserHttpXssReject 锁定 add/edit 端点对 Java @Validated→@Xss 拒绝层的复刻：
// 账号/昵称含 HTML 标签即拒绝，文案逐字对齐 SysUser 注解 message。
func TestValidateUserHttpXssReject(t *testing.T) {
	vs := validateUserHttp(&model.SysUser{UserName: `<script>x</script>`, NickName: `<img src=y onerror=1>`})
	if !hasViolationMsg(vs, "用户账号不能包含脚本字符") {
		t.Errorf("含脚本的 userName 应被 @Xss 拒绝, got=%+v", vs)
	}
	if !hasViolationMsg(vs, "用户昵称不能包含脚本字符") {
		t.Errorf("含脚本的 nickName 应被 @Xss 拒绝, got=%+v", vs)
	}

	if got := validateUserHttp(&model.SysUser{UserName: "zhangsan", NickName: "张三"}); len(got) != 0 {
		t.Errorf("合法账号/昵称不应有违规, got=%+v", got)
	}
}

// TestValidateUserImportNoXss 锁定边界：Excel 导入分支不 @Validated，validateUser 不得含 @Xss 拒绝，
// 即便值含 HTML 也仅按结构校验（对齐 Java importUser 不触发 bean 校验）。
func TestValidateUserImportNoXss(t *testing.T) {
	vs := validateUser(&model.SysUser{UserName: `<a href=x>`, NickName: `<b>`})
	if hasViolationMsg(vs, "用户账号不能包含脚本字符") || hasViolationMsg(vs, "用户昵称不能包含脚本字符") {
		t.Errorf("导入用的 validateUser 不应含 @Xss 拒绝, got=%+v", vs)
	}
}
