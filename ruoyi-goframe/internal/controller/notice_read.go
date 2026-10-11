// Package controller —— /system/notice 公告已读追踪子系统（C2）HTTP 处理器，逐条对齐 SysNoticeController
// 的 listTop/markRead/markReadAll/repush/readUsers.list/unreadUsers.list 入参/出参/文案。
// listTop/markRead/markReadAll 无 @PreAuthorize（仅登录态）；repush 走 system:notice:edit；read/unreadUsers 走 system:notice:list。
package controller

import (
	"strings"

	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/api"
	"ruoyi-goframe/internal/middleware"
	"ruoyi-goframe/internal/model"
	"ruoyi-goframe/internal/service"
)

// topNoticeDefault / topNoticeMax 复刻 SysNoticeController 常量：默认 5 条，size 夹到 [1,50]。
const (
	topNoticeDefault = 5
	topNoticeMax     = 50
)

// currentUserId 取当前登录用户 id：优先 lu.User.UserId，回退 lu.UserId（对齐 getUserId()）。
func currentUserId(r *ghttp.Request) int64 {
	lu := middleware.CurrentLoginUser(r)
	if lu == nil {
		return 0
	}
	if lu.User != nil && lu.User.UserId != 0 {
		return lu.User.UserId
	}
	return lu.UserId
}

// NoticeListTop GET /system/notice/listTop（仅需登录）→ {code:200,msg,data:[...],unreadCount:N}。
// size 夹到 [1,50]（默认 5）；unreadCount 仅统计本次返回列表内的未读条数（对齐 Java stream filter）。
func NoticeListTop(r *ghttp.Request) {
	ctx := r.Context()
	size := r.Get("size").Int()
	limit := topNoticeDefault
	if size > 0 {
		limit = size
		if limit > topNoticeMax {
			limit = topNoticeMax
		}
	}
	list := service.SelectNoticeListWithReadStatus(ctx, currentUserId(r), limit)
	if list == nil {
		list = []model.SysNotice{}
	}
	var unread int64
	for i := range list {
		if !list[i].IsRead {
			unread++
		}
	}
	m := api.Ok()
	m["data"] = list
	m["unreadCount"] = unread
	api.Send(r, m)
}

// NoticeMarkRead POST /system/notice/markRead（仅需登录）。noticeId 由前端可控传入：空/非正即拒（早于落库），
// 避免命中 notice_id NOT NULL；userId 一律取当前登录用户，不接受前端传入。
func NoticeMarkRead(r *ghttp.Request) {
	v := r.Get("noticeId")
	if v.IsNil() || v.String() == "" || v.Int64() <= 0 {
		api.Send(r, api.Err("公告ID非法"))
		return
	}
	service.MarkRead(r.Context(), v.Int64(), currentUserId(r))
	api.Send(r, api.Ok())
}

// NoticeMarkReadAll POST /system/notice/markReadAll（仅需登录）。逗号串逐项须为正数：
// 空串（length 0）无副作用直接成功；含非数字/非正项即拒（对齐 Convert.toLongArray + 逐项校验）。
func NoticeMarkReadAll(r *ghttp.Request) {
	idsStr := strings.TrimSpace(r.Get("ids").String())
	if idsStr == "" {
		api.Send(r, api.Ok())
		return
	}
	ids, ok := splitCommaInts(idsStr)
	if !ok {
		api.Send(r, api.Err("公告ID非法"))
		return
	}
	for _, id := range ids {
		if id <= 0 {
			api.Send(r, api.Err("公告ID非法"))
			return
		}
	}
	if len(ids) == 0 {
		api.Send(r, api.Ok())
		return
	}
	service.MarkReadBatch(r.Context(), currentUserId(r), ids)
	api.Send(r, api.Ok())
}

// NoticeRepush PUT /system/notice/repush/{noticeId}（system:notice:edit）。清空该公告全部已读记录，使全员回到未读；
// 无存在性校验，恒回操作成功（对齐 Java）。
func NoticeRepush(r *ghttp.Request) {
	service.DeleteNoticeReadByNoticeIds(r.Context(), []int64{r.Get("noticeId").Int64()})
	api.Send(r, api.Ok())
}

// NoticeReadUsersList GET /system/notice/readUsers/list（system:notice:list）→ TableDataInfo（Map 行，order read_time desc）。
func NoticeReadUsersList(r *ghttp.Request) {
	rows, total, err := service.SelectReadUsersPaged(r.Context(), service.ParsePage(r), r.Get("noticeId").Int64(), r.Get("searchValue").String())
	if err != nil {
		api.Send(r, api.Err("查询失败"))
		return
	}
	api.Send(r, service.DataTable(total, rows))
}

// NoticeUnreadUsersList GET /system/notice/unreadUsers/list（system:notice:list）→ TableDataInfo（Map 行，催读，order user_id）。
func NoticeUnreadUsersList(r *ghttp.Request) {
	rows, total, err := service.SelectUnreadUsersPaged(r.Context(), service.ParsePage(r), r.Get("noticeId").Int64(), r.Get("searchValue").String())
	if err != nil {
		api.Send(r, api.Err("查询失败"))
		return
	}
	api.Send(r, service.DataTable(total, rows))
}
