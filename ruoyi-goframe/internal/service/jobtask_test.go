package service

import (
	"reflect"
	"testing"
)

// TestLookupTask 覆盖注册表命中/未命中与参数解析（不执行 handler）。
func TestLookupTask(t *testing.T) {
	cases := []struct {
		target   string
		wantOK   bool
		wantArgs []string
	}{
		{"operLogCleanTask.clean('90')", true, []string{"90"}}, // 引号字符串参
		{"operLogCleanTask.clean(90)", true, []string{"90"}},   // 整型字面量
		{`operLogCleanTask.clean("90")`, true, []string{"90"}}, // 双引号
		{"operLogCleanTask.clean", true, nil},                  // 无括号
		{"operLogCleanTask.clean()", true, nil},                // 空括号
		{"logininforCleanTask.clean", true, nil},
		{"logininforCleanTask.clean('30')", true, []string{"30"}},
		{"ryTask.demoMethod", false, nil},                                             // 未注册 bean
		{"com.ruoyi.quartz.task.OperLogCleanTask.clean('90')", false, []string{"90"}}, // 全类名：解析正确但不在注册表（bean 名 key 不匹配）
	}
	for _, c := range cases {
		h, args, ok := lookupTask(c.target)
		if ok != c.wantOK {
			t.Errorf("%q: ok=%v want %v", c.target, ok, c.wantOK)
		}
		if c.wantOK && h == nil {
			t.Errorf("%q: registered but handler nil", c.target)
		}
		if !c.wantOK && h != nil {
			t.Errorf("%q: unregistered but handler non-nil", c.target)
		}
		if !reflect.DeepEqual(args, c.wantArgs) {
			t.Errorf("%q: args=%v want %v", c.target, args, c.wantArgs)
		}
	}
}

// TestInvokeTargetParseFQN 逐段验证全类名写法的 bean/method 解析（复刻 JobInvokeUtil.get*）。
func TestInvokeTargetParseFQN(t *testing.T) {
	const target = "com.ruoyi.quartz.task.OperLogCleanTask.clean('90')"
	head := subBefore(target, "(")
	bean := subBeforeLast(head, ".")
	method := subAfterLast(head, ".")
	if bean != "com.ruoyi.quartz.task.OperLogCleanTask" {
		t.Errorf("bean=%q", bean)
	}
	if method != "clean" {
		t.Errorf("method=%q", method)
	}
	if got := parseInvokeArgs(target); !reflect.DeepEqual(got, []string{"90"}) {
		t.Errorf("args=%v", got)
	}
}

// TestParseInvokeArgsQuoteAware 引号内逗号不拆，对齐 Java 正向前瞻 split。
func TestParseInvokeArgsQuoteAware(t *testing.T) {
	got := parseInvokeArgs("someBean.someMethod('a,b', 3)")
	want := []string{"a,b", "3"}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("args=%v want %v", got, want)
	}
	// 空白清洗 + 混合引号
	got = parseInvokeArgs("b.m( 'x' , \"y\" )")
	want = []string{"x", "y"}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("args=%v want %v", got, want)
	}
}
