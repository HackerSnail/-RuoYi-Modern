package service

import (
	"testing"

	"github.com/gogf/gf/v2/os/gtime"
)

// TestCronIsValid 覆盖合法/非法口径：6 段、`?` 仅日/周、值域、拒绝 L/W/#、拒绝第 7 段。
func TestCronIsValid(t *testing.T) {
	valid := []string{
		"0 0 3 * * ?",       // 种子：每天 03:00
		"0 0/5 * * * ?",     // 每 5 分钟
		"0 0 9 * * MON-FRI", // 工作日 09:00（周英文名）
		"* * * * * ?",       // 每秒（week 段 ?）
		"0 15 10 ? * MON-FRI",
		"0 0 12 1,15 * ?",  // 逗号枚举
		"0 0 8 * JAN-JUN ?", // 月英文名区间
	}
	for _, p := range valid {
		if !CronIsValid(p) {
			t.Errorf("expected VALID, got invalid: %q", p)
		}
	}

	invalid := []string{
		"",              // 空
		"* * * * *",     // 5 段
		"0 0 3 * * ? *", // 7 段
		"? 0 3 * * *",   // 秒段不允许 ?
		"0 ? 3 * * *",   // 分段不允许 ?
		"0 0 25 * * ?",  // 时越界 25>23
		"0 0 3 32 * ?",  // 日越界 32>31
		"0 0 3 * 13 ?",  // 月越界 13>12
		"0 0 3 * * 7",   // 周越界 7>6（gcron 约定 0-6）
		"0 0 3 * * L",   // 拒绝 Quartz 专有 L
		"0 0 3 LW * ?",  // 拒绝 W
		"0 0 3 * * 6#2", // 拒绝 #
		"0 0 3 * * FOO", // 未知周名
		"0 0 3 * * MON-FOO",
	}
	for _, p := range invalid {
		if CronIsValid(p) {
			t.Errorf("expected INVALID, got valid: %q", p)
		}
	}
}

// TestCronParseQuestionMarkEquivalence 验证 `?` 在日/周段等价 `*`：集合完全一致。
func TestCronParseQuestionMarkEquivalence(t *testing.T) {
	a, err := CronParse("0 0 3 ? * ?")
	if err != nil {
		t.Fatalf("parse ?/*: %v", err)
	}
	b, err := CronParse("0 0 3 * * *")
	if err != nil {
		t.Fatalf("parse */*: %v", err)
	}
	if len(a.daySet) != len(b.daySet) || len(a.weekSet) != len(b.weekSet) {
		t.Fatalf("?≡*: day %d/%d week %d/%d", len(a.daySet), len(b.daySet), len(a.weekSet), len(b.weekSet))
	}
	for i := 1; i <= 31; i++ {
		if _, ok := a.daySet[i]; !ok {
			t.Errorf("? day missing %d", i)
		}
		if _, ok := b.daySet[i]; !ok {
			t.Errorf("* day missing %d", i)
		}
	}
	for i := 0; i <= 6; i++ {
		if _, ok := a.weekSet[i]; !ok {
			t.Errorf("? week missing %d", i)
		}
	}
}

// TestCronNext 验证下次触发推算，含种子 cron、跨天、MON-FRI 跳过周末。
func TestCronNext(t *testing.T) {
	cases := []struct {
		name    string
		pattern string
		from    string
		want    string
	}{
		{"seed before 3am", "0 0 3 * * ?", "2026-10-12 02:00:00", "2026-10-12 03:00:00"},
		{"seed at exact 3am next day", "0 0 3 * * ?", "2026-10-12 03:00:00", "2026-10-13 03:00:00"},
		{"every 5 min", "0 0/5 * * * ?", "2026-10-12 10:03:00", "2026-10-12 10:05:00"},
		{"mon-fri from sat", "0 0 9 * * MON-FRI", "2026-10-10 10:00:00", "2026-10-12 09:00:00"}, // 2026-10-10 周六 → 周一
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			from := gtime.NewFromStr(c.from)
			if from == nil || from.IsZero() {
				t.Fatalf("bad from %q", c.from)
			}
			got := CronNext(c.pattern, from)
			if got == nil {
				t.Fatalf("expected %q, got nil", c.want)
			}
			if got.String() != c.want {
				t.Fatalf("pattern %q from %q: got %q want %q", c.pattern, c.from, got.String(), c.want)
			}
		})
	}
}

// TestCronNextNilAndInvalid nil 入参与非法 cron 均回 nil。
func TestCronNextNilAndInvalid(t *testing.T) {
	if got := CronNext("0 0 3 * * ?", nil); got != nil {
		t.Errorf("expected nil for nil from, got %v", got)
	}
	if got := CronNext("bad", gtime.NewFromStr("2026-10-12 00:00:00")); got != nil {
		t.Errorf("expected nil for invalid cron, got %v", got)
	}
}
