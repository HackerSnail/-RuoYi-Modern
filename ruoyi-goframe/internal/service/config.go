// Package service —— sys_config 读取。对齐 Java ISysConfigService 的 selectConfigByKey / selectCaptchaEnabled。
// P0 直接查库（未接 Redis sys_config:* 缓存，属后续增量）；语义与 Java 一致：值即 config_value 字符串。
package service

import (
	"context"
	"strings"

	"github.com/gogf/gf/v2/frame/g"
)

// selectConfigByKey 按 config_key 取 config_value；不存在返回空串。
func selectConfigByKey(ctx context.Context, key string) string {
	v, err := g.DB().Model("sys_config").Ctx(ctx).Fields("config_value").Where("config_key = ?", key).Value()
	if err != nil || v == nil || v.IsNil() {
		return ""
	}
	return strings.TrimSpace(v.String())
}

// getCaptchaEnabled 对应 selectCaptchaEnabled：sys.account.captchaEnabled == "true"。
func getCaptchaEnabled(ctx context.Context) bool {
	return strings.EqualFold(selectConfigByKey(ctx, "sys.account.captchaEnabled"), "true")
}

// GetCaptchaEnabled 对外暴露验证码开关（供 /captchaImage 回传 captchaEnabled）。
func GetCaptchaEnabled(ctx context.Context) bool { return getCaptchaEnabled(ctx) }

// getIntConfig 取整型配置，空/非法返回 defaultVal。对应 Convert.toInt 兜底。
func getIntConfig(ctx context.Context, key string, defaultVal int) int {
	s := selectConfigByKey(ctx, key)
	if s == "" {
		return defaultVal
	}
	n, err := parseIntStrict(s)
	if err != nil {
		return defaultVal
	}
	return n
}

func parseIntStrict(s string) (int, error) {
	var n int
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0, errBadInt
		}
		n = n*10 + int(c-'0')
	}
	return n, nil
}

var errBadInt = &stringErr{"invalid int config"}

type stringErr struct{ msg string }

func (e *stringErr) Error() string { return e.msg }
