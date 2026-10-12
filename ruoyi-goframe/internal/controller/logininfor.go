// Package controller —— 登录日志 /monitor/logininfor。对齐 SysLogininforController 的 list/export/remove/clean/unlock。
// 注意：clean 复用 monitor:logininfor:remove 权限（与 operlog 的独立 clean 权限不同，此为 Java 原样）；unlock 用 monitor:logininfor:unlock。
package controller

import (
	"fmt"
	"io"

	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/api"
	"ruoyi-goframe/internal/service"
)

// logininforCleanConfirmToken 清空登录日志二次确认口令（复刻 SysLogininforController.CLEAN_CONFIRM_TOKEN）。
const logininforCleanConfirmToken = "CLEAN_ALL_LOGININFOR"

// LogininforList GET /monitor/logininfor/list → TableDataInfo（分页，默认 info_id desc）。
func LogininforList(r *ghttp.Request) {
	ctx := r.Context()
	f := service.LogininforListFilter{
		Ipaddr:    r.Get("ipaddr").String(),
		Status:    r.Get("status").String(),
		UserName:  r.Get("userName").String(),
		BeginTime: paramRange(r, "beginTime"),
		EndTime:   paramRange(r, "endTime"),
	}
	rows, total, err := service.SelectLogininforListPaged(ctx, f, service.ParsePage(r))
	if err != nil {
		api.Send(r, api.Err("查询失败"))
		return
	}
	api.Send(r, service.DataTable(total, rows))
}

// LogininforExport POST /monitor/logininfor/export（monitor:logininfor:export + @Log EXPORT）：同 list 的过滤入参（无 DataScope），过 exportLimit 护栏后写 xlsx。
func LogininforExport(r *ghttp.Request) {
	ctx := r.Context()
	f := service.LogininforListFilter{
		Ipaddr:    r.Get("ipaddr").String(),
		Status:    r.Get("status").String(),
		UserName:  r.Get("userName").String(),
		BeginTime: paramRange(r, "beginTime"),
		EndTime:   paramRange(r, "endTime"),
	}
	max := service.GetExportMaxRows(ctx)
	rows, err := service.SelectLogininforListLimited(ctx, f, max)
	if err != nil {
		api.Send(r, api.Err("导出失败"))
		return
	}
	if exportOverLimit(r, len(rows), max) {
		return
	}
	writeXlsx(r, func(w io.Writer) error { return service.ExportLogininfor(w, rows) })
}

// LogininforRemove DELETE /monitor/logininfor/{infoIds} → toAjax(受影响行数)。
// 服务层：空即拒；实删 0 行抛"删除失败，所选登录日志可能已被清空"（与 operlog 的 toAjax(0)→"操作失败"文案不同，照 Java）。
func LogininforRemove(r *ghttp.Request) {
	ctx := r.Context()
	ids, ok := splitCommaInts(r.GetRouter("infoIds").String())
	if !ok {
		api.Send(r, api.Err("参数格式有误"))
		return
	}
	if _, err := service.DeleteLogininforByIds(ctx, ids); err != nil {
		api.Send(r, api.Err(err.Error()))
		return
	}
	api.Send(r, api.Ok())
}

// LogininforClean DELETE /monitor/logininfor/clean?confirm= → 二次确认口令强校验后 truncate，回"已清空登录日志，约 N 条（估算值）"。
func LogininforClean(r *ghttp.Request) {
	ctx := r.Context()
	if r.Get("confirm").String() != logininforCleanConfirmToken {
		api.Send(r, api.Err("清空登录日志需要二次确认，已拒绝执行"))
		return
	}
	approx, err := service.CleanLogininfor(ctx)
	if err != nil {
		api.Send(r, api.Err("清空失败"))
		return
	}
	api.Send(r, api.OkMsg(fmt.Sprintf("已清空登录日志，约 %d 条（估算值）", approx)))
}

// LogininforUnlock GET /monitor/logininfor/unlock/{userName} → 清除该用户密码错误次数缓存，解除锁定。
func LogininforUnlock(r *ghttp.Request) {
	service.ClearLoginRecordCache(r.Context(), r.GetRouter("userName").String())
	api.Send(r, api.Ok())
}
