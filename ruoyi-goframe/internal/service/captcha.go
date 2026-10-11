// Package service —— 验证码生成/校验，对齐 CaptchaController(/captchaImage) 与 SysLoginService.validateCaptcha。
//   - captchaType=math → 算术型（DriverMath），答案为计算结果串；=char → 字符型。与 Java 一致。
//   - 答案存 Redis captcha_codes:{uuid}，TTL 2 分钟；登录校验：先删后比、大小写不敏感（同 Java deleteObject→equalsIgnoreCase）。
package service

import (
	"context"
	"strings"

	"github.com/gogf/gf/v2/frame/g"
	"github.com/mojocn/base64Captcha"

	"ruoyi-goframe/internal/consts"
)

// GenerateCaptcha 生成验证码，返回 (uuid, 原始base64图片[无 data: 前缀], 明文答案)。
func GenerateCaptcha(ctx context.Context) (uuid, imgB64, answer string) {
	// captchaType=math → 算术型（与 Java 默认一致）。P0 仅实现 math；char 型留后续增量。
	driver := base64Captcha.NewDriverMath(80, 240, 3, 0, nil, nil)
	c := base64Captcha.NewCaptcha(driver, base64Captcha.DefaultMemStore)

	// 注意：base64Captcha v1.3.1 的 Generate() 返回顺序为 (id, content, err)——id 是存储键、content 是图片 base64。
	id, content, err := c.Generate()
	if err != nil {
		return "", "", ""
	}
	// 答案从 base64Captcha 内存仓取回，再镜像到 Redis captcha_codes:{id}（与 Java 同键，供登录校验）。
	answer = base64Captcha.DefaultMemStore.Get(id, false)
	uuid = id
	// 与 Java 一样：img 为纯 base64（前端自行拼 "data:image/gif;base64," + img）。剥掉可能的 data URI 前缀。
	if strings.HasPrefix(content, "data:") {
		if i := strings.Index(content, ","); i >= 0 {
			content = content[i+1:]
		}
	}
	ttlSec := consts.CaptchaExpirationMinutes * 60
	_, _ = g.Redis().Do(ctx, "SETEX", consts.RedisCaptchaCode+uuid, ttlSec, answer)
	return uuid, content, answer
}

// validateCaptcha 校验并一次性消费验证码。返回错误消息（"" 表示通过）。
// captchaEnabled=false 时直接通过（同 Java：不校验）。
func validateCaptcha(ctx context.Context, code, uuid string) string {
	if !getCaptchaEnabled(ctx) {
		return ""
	}
	if uuid == "" {
		return "验证码已失效"
	}
	key := consts.RedisCaptchaCode + uuid
	v, err := g.Redis().Get(ctx, key)
	if err != nil || v == nil || v.IsNil() || v.String() == "" {
		return "验证码已失效"
	}
	// 先删后比：无论对错都消费掉，防重放（同 Java deleteObject 位置）
	_, _ = g.Redis().Del(ctx, key)
	if !strings.EqualFold(code, v.String()) {
		return "验证码错误"
	}
	return ""
}
