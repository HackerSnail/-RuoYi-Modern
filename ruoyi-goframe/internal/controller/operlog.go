// Package controller —— 操作日志 /monitor/operlog。对齐 SysOperlogController 的 list/remove/clean（export 顺延）。
// 权限非对称：list→monitor:operlog:list；remove→monitor:operlog:remove；clean→独立 monitor:operlog:clean（全表不可逆，单列授权）。
package controller

import (
	"fmt"
	"io"
	"strings"

	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/api"
	"ruoyi-goframe/internal/service"
)

// operLogCleanConfirmToken 清空操作日志二次确认口令，须精确相等（复刻 SysOperlogController.CLEAN_CONFIRM_TOKEN）。
const operLogCleanConfirmToken = "CLEAN_ALL_OPERLOG"

// parseBusinessTypes 读取 businessTypes（支持重复参数 与 逗号 两种形态）→ []int64。
func parseBusinessTypes(r *ghttp.Request) []int64 {
	vals := r.URL.Query()["businessTypes"]
	if len(vals) == 0 {
		return nil
	}
	ids, _ := splitCommaInts(strings.Join(vals, ","))
	return ids
}

// OperLogList GET /monitor/operlog/list → TableDataInfo（分页，默认 oper_id desc）。
func OperLogList(r *ghttp.Request) {
	ctx := r.Context()
	btStr := r.Get("businessType").String()
	stStr := r.Get("status").String()
	f := service.OperLogListFilter{
		OperIp:          r.Get("operIp").String(),
		Title:           r.Get("title").String(),
		HasBusinessType: btStr != "",
		BusinessType:    r.Get("businessType").Int(),
		BusinessTypes:   parseBusinessTypes(r),
		HasStatus:       stStr != "",
		Status:          r.Get("status").Int(),
		OperName:        r.Get("operName").String(),
		BeginTime:       paramRange(r, "beginTime"),
		EndTime:         paramRange(r, "endTime"),
	}
	rows, total, err := service.SelectOperLogListPaged(ctx, f, service.ParsePage(r))
	if err != nil {
		api.Send(r, api.Err("查询失败"))
		return
	}
	api.Send(r, service.DataTable(total, rows))
}

// OperLogExport POST /monitor/operlog/export（monitor:operlog:export + @Log EXPORT）：同 list 的过滤入参（无 DataScope），过 exportLimit 护栏后写 xlsx。
func OperLogExport(r *ghttp.Request) {
	ctx := r.Context()
	btStr := r.Get("businessType").String()
	stStr := r.Get("status").String()
	f := service.OperLogListFilter{
		OperIp:          r.Get("operIp").String(),
		Title:           r.Get("title").String(),
		HasBusinessType: btStr != "",
		BusinessType:    r.Get("businessType").Int(),
		BusinessTypes:   parseBusinessTypes(r),
		HasStatus:       stStr != "",
		Status:          r.Get("status").Int(),
		OperName:        r.Get("operName").String(),
		BeginTime:       paramRange(r, "beginTime"),
		EndTime:         paramRange(r, "endTime"),
	}
	max := service.GetExportMaxRows(ctx)
	rows, err := service.SelectOperLogListLimited(ctx, f, max)
	if err != nil {
		api.Send(r, api.Err("导出失败"))
		return
	}
	if exportOverLimit(r, len(rows), max) {
		return
	}
	writeXlsx(r, func(w io.Writer) error { return service.ExportOperLog(w, rows) })
}

// OperLogRemove DELETE /monitor/operlog/{operIds} → toAjax(受影响行数)。
func OperLogRemove(r *ghttp.Request) {
	ctx := r.Context()
	ids, ok := splitCommaInts(r.GetRouter("operIds").String())
	if !ok {
		api.Send(r, api.Err("参数格式有误"))
		return
	}
	rows, err := service.DeleteOperLogByIds(ctx, ids)
	if err != nil {
		api.Send(r, api.Err(err.Error()))
		return
	}
	if rows > 0 {
		api.Send(r, api.Ok())
		return
	}
	api.Send(r, api.Err("操作失败"))
}

// OperLogClean DELETE /monitor/operlog/clean?confirm= → 二次确认口令强校验后 truncate，回"已清空 N 条操作日志"。
func OperLogClean(r *ghttp.Request) {
	ctx := r.Context()
	if r.Get("confirm").String() != operLogCleanConfirmToken {
		api.Send(r, api.Err("清空操作日志需要二次确认，已拒绝执行"))
		return
	}
	rows, err := service.CleanOperLog(ctx)
	if err != nil {
		api.Send(r, api.Err("清空失败"))
		return
	}
	api.Send(r, api.OkMsg(fmt.Sprintf("已清空 %d 条操作日志", rows)))
}
