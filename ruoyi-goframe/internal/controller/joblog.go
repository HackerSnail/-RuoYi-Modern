// Package controller —— 定时任务调度日志 /monitor/jobLog。对齐 SysJobLogController 的 list/export/getInfo/remove/clean。
// 权限：list→monitor:job:list；export→monitor:job:export；getInfo→monitor:job:query；remove/clean→monitor:job:remove（clean 复用 remove，Java 原样）。
package controller

import (
	"io"

	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/response"
	"ruoyi-goframe/internal/service"
)

// JobLogList GET /monitor/jobLog/list → TableDataInfo（分页，默认 create_time desc）。
func JobLogList(r *ghttp.Request) {
	ctx := r.Context()
	f := service.JobLogListFilter{
		JobName:      r.Get("jobName").String(),
		JobGroup:     r.Get("jobGroup").String(),
		Status:       r.Get("status").String(),
		InvokeTarget: r.Get("invokeTarget").String(),
		BeginTime:    paramRange(r, "beginTime"),
		EndTime:      paramRange(r, "endTime"),
	}
	rows, total, err := service.SelectJobLogListPaged(ctx, f, service.ParsePage(r))
	if err != nil {
		response.Send(r, response.Err("查询失败"))
		return
	}
	response.Send(r, service.DataTable(total, rows))
}

// JobLogExport POST /monitor/jobLog/export（monitor:job:export + @Log EXPORT）：同 list 的过滤入参（无 DataScope），过 exportLimit 护栏后写 xlsx。
func JobLogExport(r *ghttp.Request) {
	ctx := r.Context()
	f := service.JobLogListFilter{
		JobName:      r.Get("jobName").String(),
		JobGroup:     r.Get("jobGroup").String(),
		Status:       r.Get("status").String(),
		InvokeTarget: r.Get("invokeTarget").String(),
		BeginTime:    paramRange(r, "beginTime"),
		EndTime:      paramRange(r, "endTime"),
	}
	max := service.GetExportMaxRows(ctx)
	rows, err := service.SelectJobLogListLimited(ctx, f, max)
	if err != nil {
		response.Send(r, response.Err("导出失败"))
		return
	}
	if exportOverLimit(r, len(rows), max) {
		return
	}
	writeXlsx(r, func(w io.Writer) error { return service.ExportJobLog(w, rows) })
}

// JobLogGetInfo GET /monitor/jobLog/{jobLogId} → success(jobLog)；不存在回 error("调度日志不存在：id")。
func JobLogGetInfo(r *ghttp.Request) {
	ctx := r.Context()
	id, err := parseInt(r.GetRouter("jobLogId").String())
	if err != nil {
		response.Send(r, response.Err("参数格式有误"))
		return
	}
	jobLog := service.SelectJobLogById(ctx, id)
	if jobLog == nil {
		response.Send(r, response.Err("调度日志不存在："+r.GetRouter("jobLogId").String()))
		return
	}
	response.Send(r, response.OkData(jobLog))
}

// JobLogRemove DELETE /monitor/jobLog/{jobLogIds} → toAjax(受影响行数)。空选/超上限守卫收口于 service。
func JobLogRemove(r *ghttp.Request) {
	ctx := r.Context()
	ids, ok := splitCommaInts(r.GetRouter("jobLogIds").String())
	if !ok {
		response.Send(r, response.Err("参数格式有误"))
		return
	}
	rows, err := service.DeleteJobLogByIds(ctx, ids)
	if err != nil {
		response.Send(r, response.Err(err.Error()))
		return
	}
	response.Send(r, toAjax(rows))
}

// JobLogClean DELETE /monitor/jobLog/clean → truncate 后 success()（Java 无二次确认门、不回数）。
func JobLogClean(r *ghttp.Request) {
	ctx := r.Context()
	if err := service.CleanJobLog(ctx); err != nil {
		response.Send(r, response.Err("清空失败"))
		return
	}
	response.Send(r, response.Ok())
}
