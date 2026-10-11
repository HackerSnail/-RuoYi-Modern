// Package service —— 令牌签发/校验/会话缓存，逐条对齐 com.ruoyi.framework.web.service.TokenService。
//
// 契约要点：
//   - 令牌是 HS512 签名的 JWT，claims 仅含 {login_user_key: 会话uuid, sub: 用户名}（无 exp，
//     过期由 Redis TTL 兜底）——与 Java createToken 完全同构。
//   - 会话对象 LoginUser 以 JSON 存于 Redis login_tokens:{uuid}，TTL = token.expireTime 分钟；
//     距到期不足 20 分钟时自动续期（refreshToken）。
//   - 从请求头 token.header(默认 Authorization) 取值，去掉 "Bearer " 前缀后验签。
package service

import (
	"context"
	"encoding/json"
	"strings"
	"time"

	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/util/guid"
	"github.com/golang-jwt/jwt/v5"

	"ruoyi-goframe/internal/consts"
	"ruoyi-goframe/internal/model"
)

const refreshAheadMillis = 20 * 60 * 1000 // 临期 20 分钟自动续期，同 Java MILLIS_MINUTE_TWENTY

// tokenConfig 读取 token.* 配置（进程内只读，构造一次）。
func tokenConfig(ctx context.Context) (header, secret string, expireMin int) {
	header = g.Cfg().MustGet(ctx, "token.header", "Authorization").String()
	secret = g.Cfg().MustGet(ctx, "token.secret").String()
	expireMin = g.Cfg().MustGet(ctx, "token.expireTime", 30).Int()
	return
}

// TokenHeader 返回令牌请求头名（默认 Authorization），供鉴权中间件取值。
func TokenHeader(ctx context.Context) string {
	h, _, _ := tokenConfig(ctx)
	return h
}

// CreateToken 生成会话并签发令牌：写 uuid → 续期缓存 → 返回 JWT。对应 TokenService.createToken。
func CreateToken(ctx context.Context, loginUser *model.LoginUser) (string, error) {
	header, secret, expireMin := tokenConfig(ctx)
	_ = header
	uuid := guid.S()
	loginUser.Token = uuid
	refreshToken(ctx, loginUser, expireMin)

	claims := jwt.MapClaims{
		consts.LoginUserKey: uuid,
		consts.JWTUsername:  loginUser.Username,
	}
	tok := jwt.NewWithClaims(jwt.SigningMethodHS512, claims)
	return tok.SignedString([]byte(secret))
}

// refreshToken 续期：更新登录/过期时间，写入 Redis login_tokens:{uuid}（SETEX，TTL=expireMin 分钟）。
func refreshToken(ctx context.Context, loginUser *model.LoginUser, expireMin int) {
	now := time.Now().UnixMilli()
	loginUser.LoginTime = now
	loginUser.ExpireTime = now + int64(expireMin)*60*1000
	b, err := json.Marshal(loginUser)
	if err != nil {
		return
	}
	key := consts.RedisLoginToken + loginUser.Token
	_, _ = g.Redis().Do(ctx, "SETEX", key, expireMin*60, string(b))
}

// RefreshTokenIfNeeded 临期续期（供鉴权中间件每请求调用）。
func RefreshTokenIfNeeded(ctx context.Context, loginUser *model.LoginUser) {
	if loginUser.ExpireTime-time.Now().UnixMilli() <= refreshAheadMillis {
		_, _, expireMin := tokenConfig(ctx)
		refreshToken(ctx, loginUser, expireMin)
	}
}

// GetLoginUser 校验 JWT 并取回会话；令牌非法或会话不存在(过期/被强退)返回 nil。对应 TokenService.getLoginUser。
func GetLoginUser(ctx context.Context, authorization string) *model.LoginUser {
	_, secret, _ := tokenConfig(ctx)
	token := stripBearer(authorization)
	if token == "" {
		return nil
	}
	parsed, err := jwt.Parse(token, func(*jwt.Token) (interface{}, error) {
		return []byte(secret), nil
	}, jwt.WithValidMethods([]string{"HS512"}))
	if err != nil || !parsed.Valid {
		return nil
	}
	claims, ok := parsed.Claims.(jwt.MapClaims)
	if !ok {
		return nil
	}
	uuid, _ := claims[consts.LoginUserKey].(string)
	if uuid == "" {
		return nil
	}
	v, err := g.Redis().Get(ctx, consts.RedisLoginToken+uuid)
	if err != nil || v == nil || v.IsNil() || v.String() == "" {
		return nil
	}
	var lu model.LoginUser
	if err := json.Unmarshal([]byte(v.String()), &lu); err != nil {
		return nil
	}
	return &lu
}

// DeleteLoginUser 按会话 uuid 删除在线会话。
func DeleteLoginUser(ctx context.Context, uuid string) {
	if uuid == "" {
		return
	}
	_, _ = g.Redis().Del(ctx, consts.RedisLoginToken+uuid)
}

// stripBearer 去掉 "Bearer " 前缀，取实际令牌串。
func stripBearer(authorization string) string {
	if strings.HasPrefix(authorization, consts.TokenPrefix) {
		return strings.TrimPrefix(authorization, consts.TokenPrefix)
	}
	return authorization
}
