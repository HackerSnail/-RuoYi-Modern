// Package controller —— /monitor/online 在线用户监控 HTTP 处理器，逐条对齐 SysUserOnlineController。
// 两接口均带 @PreAuthorize（list 走 monitor:online:list，forceLogout 走 monitor:online:forceLogout）。
package controller

import (
	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/response"
	"ruoyi-goframe/internal/service"
)

// OnlineList GET /monitor/online/list?ipaddr=&userName=（monitor:online:list）→ TableDataInfo。
// 数据源为 Redis 在线会话（非 DB），无 startPage：total = 过滤后行数。
func OnlineList(r *ghttp.Request) {
	ctx := r.Context()
	rows := service.ListOnline(ctx, r.Get("ipaddr").String(), r.Get("userName").String())
	response.Send(r, service.DataTable(int64(len(rows)), rows))
}

// OnlineForceLogout DELETE /monitor/online/{tokenId}（monitor:online:forceLogout）→ toAjax(count)。
// tokenId 是脱敏 handle：格式非法收敛为业务失败（对应 Java ServiceException→HTTP 400）；
// count=0 表示会话已过期/已重登（handle 失配），回"操作失败"；命中删除回"操作成功"。
func OnlineForceLogout(r *ghttp.Request) {
	ctx := r.Context()
	count, err := service.DelLoginUserByHandle(ctx, r.GetRouter("tokenId").String())
	if err != nil {
		response.Send(r, response.Err(err.Error()))
		return
	}
	if count > 0 {
		response.Send(r, response.Ok())
		return
	}
	response.Send(r, response.Err("操作失败"))
}
