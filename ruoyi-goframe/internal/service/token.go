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
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/util/gconv"
	"github.com/gogf/gf/v2/util/guid"
	"github.com/golang-jwt/jwt/v5"

	"ruoyi-goframe/internal/consts"
	"ruoyi-goframe/internal/model"
)

const refreshAheadMillis = 20 * 60 * 1000 // 临期 20 分钟自动续期，同 Java MILLIS_MINUTE_TWENTY

// 会话标识（handle）相关常量，逐字对齐 TokenService。
const (
	sessionHandleDomain = "|online-session-handle" // 域分离后缀：与 JWT 签名密钥同源不同用
	sessionHandleLength = 32                       // 下发标识长度（HMAC-SHA256 64 位 hex 取前 32 = 128bit）
)

// sessionHandlePattern 合法 handle 形态：恰好 32 位小写 hex。先按格式拒绝，避免任意串进入全量扫描。
var sessionHandlePattern = regexp.MustCompile(`^[0-9a-f]{32}$`)

// sessionHandleKey 缓存：由 token.secret 经 SHA-256 域分离派生，进程内惰性算一次（secret 运行期只读不变）。
var (
	sessionHandleKeyOnce sync.Once
	sessionHandleKeyVal  string
)

// getSessionHandleKey 取回派生密钥 sha256hex(token.secret + 域后缀)。对应 TokenService.sessionHandleKey 构造期计算。
func getSessionHandleKey(ctx context.Context) string {
	sessionHandleKeyOnce.Do(func() {
		_, secret, _ := tokenConfig(ctx)
		sessionHandleKeyVal = SHA256Hex(secret + sessionHandleDomain)
	})
	return sessionHandleKeyVal
}

// ToSessionHandle 把会话 uuid 换算成可下发的脱敏展示标识：截断 HMAC-SHA256(handleKey, uuid)。
// 单向不可逆、不含 uuid 片段；uuid 为空（残缺会话）返回空串，前端该行自然拿不到强退入口。对应 TokenService.toSessionHandle。
func ToSessionHandle(ctx context.Context, uuid string) string {
	if uuid == "" {
		return ""
	}
	sum := HMACSHA256Hex(uuid, getSessionHandleKey(ctx))
	if len(sum) < sessionHandleLength {
		return ""
	}
	return sum[:sessionHandleLength]
}

// scanLoginTokenKeys 用 SCAN 游标遍历 login_tokens:* 返回全部键（强退反查用，只取键不 GET 值）。
func scanLoginTokenKeys(ctx context.Context) []string {
	var keys []string
	pattern := consts.RedisLoginToken + "*"
	var cursor int64
	for {
		reply, err := g.Redis().Do(ctx, "SCAN", cursor, "MATCH", pattern, "COUNT", 200)
		if err != nil {
			break
		}
		arr := reply.Array()
		if len(arr) < 2 {
			break
		}
		cursor = gconv.Int64(arr[0])
		keys = append(keys, gconv.SliceStr(arr[1])...)
		if cursor == 0 {
			break
		}
	}
	return keys
}

// DelLoginUserByHandle 按脱敏标识强下线的逆用路径：先格式校验，再扫描在线会话键对每个 uuid 重算 handle 比对、命中即删。
// 不建反向映射表（须在 create/refresh/delete 三处同步写删且 TTL 漂移留孤儿键）；代价 O(N) 扫描，靠"格式先拒 + 命中即停"收敛。
// 返回被清除会话数（0 表示会话已过期/已重登，handle 失配）；handle 非法返回 error（控制器收敛为业务失败）。对应 TokenService.delLoginUserByHandle。
func DelLoginUserByHandle(ctx context.Context, handle string) (int, error) {
	if !sessionHandlePattern.MatchString(handle) {
		return 0, &SessionHandleInvalidError{}
	}
	prefix := consts.RedisLoginToken
	for _, key := range scanLoginTokenKeys(ctx) {
		if key == "" || !strings.HasPrefix(key, prefix) || len(key) <= len(prefix) {
			continue
		}
		if handle == ToSessionHandle(ctx, key[len(prefix):]) {
			_, _ = g.Redis().Del(ctx, key)
			return 1, nil
		}
	}
	return 0, nil
}

// SessionHandleInvalidError 会话标识格式非法（对应 Java ServiceException("会话标识不合法")，控制器转业务失败 HTTP 400）。
type SessionHandleInvalidError struct{}

func (e *SessionHandleInvalidError) Error() string { return "会话标识不合法" }

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

// DelLoginUserByUuid 为会话失效逻辑提供与 Java delLoginUser(token) 等价的删除入口（按 uuid 删除）。
func DelLoginUserByUuid(ctx context.Context, uuid string) {
	DeleteLoginUser(ctx, uuid)
}

// RefreshSession 软刷新在线会话：重算登录/过期时间并回写 Redis（会话保持、不下线），
// 对应 TokenService.refreshToken(loginUser)。供角色权限变更后刷新持有者权限集复用。
func RefreshSession(ctx context.Context, lu *model.LoginUser) {
	if lu == nil || lu.Token == "" {
		return
	}
	_, _, expireMin := tokenConfig(ctx)
	refreshToken(ctx, lu, expireMin)
}

// stripBearer 去掉 "Bearer " 前缀，取实际令牌串。
func stripBearer(authorization string) string {
	if strings.HasPrefix(authorization, consts.TokenPrefix) {
		return strings.TrimPrefix(authorization, consts.TokenPrefix)
	}
	return authorization
}
