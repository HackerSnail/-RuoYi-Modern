// Package service —— 权限判定，对齐 PermissionService.hasPermi 的 hasPermissions：
// 拥有通配 *:*:* 或精确（trim 后）匹配的权限串即通过。
package service

import (
	"strings"

	"ruoyi-goframe/internal/consts"
)

// HasPermission 判断权限集合是否覆盖目标权限（admin 的 *:*:* 覆盖一切）。
func HasPermission(perms []string, permission string) bool {
	if permission == "" || len(perms) == 0 {
		return false
	}
	trimmed := strings.TrimSpace(permission)
	for _, p := range perms {
		if p == consts.AllPermission || p == trimmed {
			return true
		}
	}
	return false
}
