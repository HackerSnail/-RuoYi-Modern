// Package service —— 在线用户监控展示层，逐条对齐 SysUserOnlineServiceImpl + SysUserOnlineController 的过滤/脱敏/排序语义。
//
// 契约要点（不得语义错位）：
//   - 数据源是 Redis login_tokens:*（非数据库），不做 SQL 分页；total = 过滤后行数。
//   - 判空前置：LoginUser 或其内层 SysUser 缺失视为残缺会话直接跳过（一条坏会话不能拖垮整表）。
//   - 空白条件（纯空格亦算空）绝不进 equals：否则 equals("","") 会误命中所有对应字段为空的会话（"全命中"陷阱）。
//   - tokenId 只承载脱敏 handle（由会话键 uuid 派生），本层不读会话 uuid 原值出参。
//   - 结果按 loginTime 倒序（SCAN 顺序任意，不能靠 reverse 冒充时间序）。
package service

import (
	"context"
	"sort"
	"strings"

	"ruoyi-goframe/internal/model"
)

// ListOnline 汇总在线会话为展示列表。ipaddr/userName 为可选过滤条件（AND 语义：两者都给须都命中）。
// 对应 SysUserOnlineController.list + SysUserOnlineServiceImpl 的 selectOnlineBy* 分派。
func ListOnline(ctx context.Context, ipaddr, userName string) []*model.SysUserOnline {
	hasIp := !isBlankStr(ipaddr)
	hasName := !isBlankStr(userName)
	out := make([]*model.SysUserOnline, 0)
	for _, s := range collectOnlineSessions(ctx) {
		lu := s.lu
		// isShowable：LoginUser 或内层 SysUser 缺失即残缺，跳过（判空必须前置于任何取值）。
		if lu == nil || lu.User == nil {
			continue
		}
		// 分支匹配：空白条件不参与比较（防全命中），命中才纳入。
		if hasIp && hasName {
			if lu.Ipaddr != ipaddr || lu.Username != userName {
				continue
			}
		} else if hasIp {
			if lu.Ipaddr != ipaddr {
				continue
			}
		} else if hasName {
			if lu.Username != userName {
				continue
			}
		}
		row := &model.SysUserOnline{
			// tokenId 只承载脱敏 handle：真实会话 uuid（键后缀）不外泄。
			TokenId:       ToSessionHandle(ctx, s.uuid),
			UserName:      lu.Username,
			Ipaddr:        lu.Ipaddr,
			LoginLocation: lu.LoginLocation,
			Browser:       lu.Browser,
			Os:            lu.Os,
			LoginTime:     lu.LoginTime,
		}
		if lu.User.Dept != nil {
			row.DeptName = lu.User.Dept.DeptName
		}
		out = append(out, row)
	}
	// 按登录时间倒序（最近登录在前）；缺失 loginTime 视为最旧自然落末尾。
	sort.Slice(out, func(i, j int) bool { return out[i].LoginTime > out[j].LoginTime })
	return out
}

// isBlankStr 复刻本仓 StringUtils.isEmpty(String)：nil 或纯空白（含空格）均为空。
func isBlankStr(s string) bool {
	return strings.TrimSpace(s) == ""
}
