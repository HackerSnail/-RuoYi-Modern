// Package service —— 在线会话失效/刷新，逐条对齐 TokenService 的
// delLoginUsers / delLoginUsersByRoleId / refreshPermissionByRoleId / collectOnlineLoginUsers。
//
// 契约与差异说明：
//   - Java collectOnlineLoginUsers 用 redisCache.keys()(KEYS) + multiGetObject；此处改用 SCAN 游标循环
//     遍历 login_tokens:* 再逐键 GET，避免 KEYS 在共享库上的阻塞面，语义等价（还原每个 LoginUser 快照）。
//   - Java 判定"是否持有某角色"读缓存 loginUser.getUser().getRoles()（SysUser 有 roles 字段）；
//     Go 会话快照 SysUser 不含 roles，故改为实时查 sys_user_role 取该角色当前持有人 userId 集合，
//     命中且非管理员者处理。admin(user_id==1) 一律跳过（与 Java isAdmin() 短路一致）。
//   - 强制下线 = 删除 login_tokens:{uuid}（对应 delLoginUser(token)），下次请求 JWT 校验取不到会话即 401；
//     软刷新 = 重算 getMenuPermission 后 refreshToken（会话保持，对应 refreshPermissionByRoleId）。
package service

import (
	"context"
	"encoding/json"
	"strings"

	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/util/gconv"

	"ruoyi-goframe/internal/consts"
	"ruoyi-goframe/internal/model"
)

// onlineSession 一条在线会话：会话 uuid（Redis 键后缀）+ 反序列化出的 LoginUser 快照。
type onlineSession struct {
	uuid string
	lu   *model.LoginUser
}

// collectOnlineSessions 对应 collectOnlineLoginUsers：SCAN login_tokens:* 后逐键 GET 还原 LoginUser，
// 跳过读取/反序列化失败或已消失的键。
func collectOnlineSessions(ctx context.Context) []onlineSession {
	var out []onlineSession
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
		for _, key := range gconv.SliceStr(arr[1]) {
			uuid := strings.TrimPrefix(key, consts.RedisLoginToken)
			v, err := g.Redis().Get(ctx, key)
			if err != nil || v == nil || v.IsNil() || v.String() == "" {
				continue
			}
			var lu model.LoginUser
			if json.Unmarshal([]byte(v.String()), &lu) != nil {
				continue
			}
			out = append(out, onlineSession{uuid: uuid, lu: &lu})
		}
		if cursor == 0 {
			break
		}
	}
	return out
}

// selectUserIdsByRoleId 实时取当前持有指定角色的 userId 集合（sys_user_role），对齐"持有该角色"判定。
func selectUserIdsByRoleId(ctx context.Context, roleId int64) map[int64]bool {
	set := map[int64]bool{}
	recs, err := g.DB().GetAll(ctx, "select user_id from sys_user_role where role_id = ?", roleId)
	if err != nil {
		return set
	}
	for _, rec := range recs {
		set[gconv.Int64(rec.Map()["user_id"])] = true
	}
	return set
}

// sessionUserId 取会话归属用户ID：优先快照 User.UserId，回退顶层 UserId。
func sessionUserId(lu *model.LoginUser) int64 {
	if lu == nil {
		return 0
	}
	if lu.User != nil && lu.User.UserId != 0 {
		return lu.User.UserId
	}
	return lu.UserId
}

// DelLoginUsers 对应 delLoginUsers：按 userId 匹配删除其全部在线会话（含 admin，Java 亦不豁免）。返回下线数。
func DelLoginUsers(ctx context.Context, userId int64) int {
	if userId == 0 {
		return 0
	}
	count := 0
	for _, s := range collectOnlineSessions(ctx) {
		if s.lu == nil || s.lu.User == nil {
			continue
		}
		if userId == s.lu.User.UserId {
			DeleteLoginUser(ctx, s.uuid)
			count++
		}
	}
	return count
}

// DelLoginUsersByRoleId 对应 delLoginUsersByRoleId：强制持有该角色的在线非管理员用户重新登录。返回下线数。
func DelLoginUsersByRoleId(ctx context.Context, roleId int64) int {
	if roleId == 0 {
		return 0
	}
	holders := selectUserIdsByRoleId(ctx, roleId)
	if len(holders) == 0 {
		return 0
	}
	count := 0
	for _, s := range collectOnlineSessions(ctx) {
		uid := sessionUserId(s.lu)
		if uid == 0 || IsAdmin(uid) {
			continue // 管理员拥有所有权限，无需因某角色变更下线
		}
		if !holders[uid] {
			continue
		}
		DeleteLoginUser(ctx, s.uuid)
		count++
	}
	if count > 0 {
		g.Log().Infof(ctx, "角色[%d]停用/数据范围变更，已强制 %d 个持有该角色的在线会话重新登录", roleId, count)
	}
	return count
}

// RefreshPermissionByRoleId 对应 refreshPermissionByRoleId：重算持有该角色的在线非管理员用户权限集并回写缓存（会话保持）。
func RefreshPermissionByRoleId(ctx context.Context, roleId int64) {
	if roleId == 0 {
		return
	}
	holders := selectUserIdsByRoleId(ctx, roleId)
	if len(holders) == 0 {
		return
	}
	for _, s := range collectOnlineSessions(ctx) {
		lu := s.lu
		uid := sessionUserId(lu)
		if lu == nil || lu.User == nil || IsAdmin(uid) {
			continue
		}
		if !holders[uid] {
			continue
		}
		lu.Permissions = getMenuPermission(ctx, lu.User)
		RefreshSession(ctx, lu)
		g.Log().Infof(ctx, "角色[%d]权限变更，已刷新在线用户[%s]的权限缓存", roleId, lu.Username)
	}
}
