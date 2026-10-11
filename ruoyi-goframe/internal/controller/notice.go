// Package controller —— /system/notice 通知公告 HTTP 处理器（C1 核心 CRUD），逐条对齐 SysNoticeController
// 的入参/出参/校验顺序/文案。已读追踪子系统（listTop/markRead/markReadAll/repush/readUsers/unreadUsers）在 C2。
package controller

import (
	"regexp"

	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/api"
	"ruoyi-goframe/internal/middleware"
	"ruoyi-goframe/internal/model"
	"ruoyi-goframe/internal/service"
)

// xssHtmlPattern 复刻 XssValidator.HTML_PATTERN = "<(\S*?)[^>]*>.*?|<.*? />"，Java 以 matcher().find() 半匹配判定，
// 故 Go 用 MatchString（RE2 默认即半匹配），不锚定。命中任一 HTML 标签片段即判含脚本字符。
var xssHtmlPattern = regexp.MustCompile(`<(\S*?)[^>]*>.*?|<.*? />`)

// noticeTypePattern 复刻 SysNotice.noticeType 的 @Pattern(regexp="[12]")：HV 按整串 matches() 判定，
// 故 Go 等价须锚定为 ^[12]$（不锚会放过 "1x"）。
var noticeTypePattern = regexp.MustCompile(`^[12]$`)

// noticeStatusPattern 复刻 SysNotice.status 的 @Pattern(regexp="[01]")，同上锚定。
var noticeStatusPattern = regexp.MustCompile(`^[01]$`)

// NoticeList GET /system/notice/list（system:notice:list）→ TableDataInfo（分页，无 DataScope）。
func NoticeList(r *ghttp.Request) {
	ctx := r.Context()
	f := service.NoticeListFilter{
		NoticeTitle: r.Get("noticeTitle").String(),
		NoticeType:  r.Get("noticeType").String(),
		CreateBy:    r.Get("createBy").String(),
	}
	rows, total, err := service.SelectNoticeListPaged(ctx, f, service.ParsePage(r))
	if err != nil {
		api.Send(r, api.Err("查询失败"))
		return
	}
	api.Send(r, service.DataTable(total, rows))
}

// NoticeGetInfo GET /system/notice/{noticeId}（无 @PreAuthorize，仅需登录态）→ data:{notice}。
func NoticeGetInfo(r *ghttp.Request) {
	api.Send(r, api.OkData(service.SelectNoticeById(r.Context(), r.Get("noticeId").Int64())))
}

// NoticeAdd POST /system/notice（system:notice:add）。@Validated → insertNotice。
func NoticeAdd(r *ghttp.Request) {
	var notice model.SysNotice
	if err := r.Parse(&notice); err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	if vs := validateNotice(&notice); len(vs) > 0 {
		api.Send(r, validErrBody(vs))
		return
	}
	lu := middleware.CurrentLoginUser(r)
	api.Send(r, toAjax(service.InsertNotice(r.Context(), &notice, lu.Username)))
}

// NoticeEdit PUT /system/notice（system:notice:edit）。@Validated → updateNotice（含存在性护栏）。
// notice_content 允许清空：以请求体是否携带该键判定（present 即写入，含空串）。
func NoticeEdit(r *ghttp.Request) {
	var notice model.SysNotice
	if err := r.Parse(&notice); err != nil {
		api.Send(r, api.Err("请求参数有误"))
		return
	}
	if vs := validateNotice(&notice); len(vs) > 0 {
		api.Send(r, validErrBody(vs))
		return
	}
	contentPresent := !r.Get("noticeContent").IsNil()
	lu := middleware.CurrentLoginUser(r)
	rows, msg := service.UpdateNotice(r.Context(), &notice, lu.Username, contentPresent)
	if msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	api.Send(r, toAjax(rows))
}

// NoticeRemove DELETE /system/notice/{noticeIds}（system:notice:remove）。事务内级联清已读后硬删；成功恒回操作成功。
func NoticeRemove(r *ghttp.Request) {
	ctx := r.Context()
	ids, ok := splitCommaInts(r.Get("noticeIds").String())
	if !ok {
		api.Send(r, api.Err("公告 ID 非法"))
		return
	}
	if _, msg := service.DeleteNoticeByIds(ctx, ids); msg != "" {
		api.Send(r, api.Err(msg))
		return
	}
	api.Send(r, api.Ok())
}

// validateNotice 复刻 SysNotice 的 @Validated 约束：
//   - noticeTitle：@Xss（含 HTML 标签拒绝）+ @NotBlank + @Size max50；空值只报 NotBlank（沿用 P1c/P1d 既定取舍）；
//     非空时先 @Xss（声明序在前）后 @Size。
//   - noticeType：@Pattern [12]（空值按 @Pattern 放行，沿用 config 先例仅非空才校验）。
//   - status：@Pattern [01]（同上）。
func validateNotice(n *model.SysNotice) []violation {
	var vs []violation
	if n.NoticeTitle == "" {
		vs = append(vs, violation{"noticeTitle", "公告标题不能为空"})
	} else {
		if xssHtmlPattern.MatchString(n.NoticeTitle) {
			vs = append(vs, violation{"noticeTitle", "公告标题不能包含脚本字符"})
		}
		if runeLen(n.NoticeTitle) > 50 {
			vs = append(vs, violation{"noticeTitle", "公告标题不能超过50个字符"})
		}
	}
	if n.NoticeType != "" && !noticeTypePattern.MatchString(n.NoticeType) {
		vs = append(vs, violation{"noticeType", "公告类型只能是1(通知)或2(公告)"})
	}
	if n.Status != "" && !noticeStatusPattern.MatchString(n.Status) {
		vs = append(vs, violation{"status", "公告状态只能是0(正常)或1(关闭)"})
	}
	return vs
}
