// Package service —— 签名/摘要工具，逐条对齐 com.ruoyi.common.utils.sign.SignUtils。
//
// 用途：在线用户监控的脱敏会话标识（handle = 截断 HMAC-SHA256）。与 Java 完全同构——
// SHA-256/HMAC-SHA256 均输出小写十六进制；空/nil 密钥前置拦截，绝不静默返回可疑值。
// 不用于密码存储（密码走 BCrypt）。
package service

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
)

// SHA256Hex 计算 SHA-256 摘要（64 位小写 hex）。对应 SignUtils.sha256。
func SHA256Hex(data string) string {
	sum := sha256.Sum256([]byte(data))
	return hex.EncodeToString(sum[:])
}

// HMACSHA256Hex 计算 HMAC-SHA256 签名（64 位小写 hex）。对应 SignUtils.hmacSha256。
// key 为空串时返回空串：Java 侧对空/nil 密钥抛 ServiceException("HMAC 密钥不能为空")，
// 本仓调用方（sessionHandleKey）由 token.secret 派生恒非空；此处以空串兜底防御，
// 调用链上层对空 handle 已按"残缺会话/非法标识"拒绝，不会误产生有效签名。
func HMACSHA256Hex(data, key string) string {
	if key == "" {
		return ""
	}
	mac := hmac.New(sha256.New, []byte(key))
	mac.Write([]byte(data))
	return hex.EncodeToString(mac.Sum(nil))
}
