// Package controller —— 定时任务 /monitor/job。逐字对齐 SysJobController 的 list/export/getInfo/add/edit/changeStatus/run/remove。
// 权限与 @Log（title="定时任务"）在 router 侧经 middleware 收口；安全校验（cron/invokeTarget/RCE 白名单）下沉 service.AddJob/EditJob。
// add/edit 仅补 createBy/updateBy（不信任前端，取当前登录用户）；changeStatus 以库基准记录仅改 status；run 仅按 jobId 触发已注册任务。
package controller

import (
	"io"
	"strconv"

	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/response"
	"ruoyi-goframe/internal/middleware"
	"ruoyi-goframe/internal/model"
	"ruoyi-goframe/internal/service"
)

// JobList GET /monitor/job/list → TableDataInfo（分页，默认 job_id asc，每行含 nextValidTime）。
func JobList(r *ghttp.Request) {
	ctx := r.Context()
	f := service.JobListFilter{
		JobName:      r.Get("jobName").String(),
		JobGroup:     r.Get("jobGroup").String(),
		Status:       r.Get("status").String(),
		InvokeTarget: r.Get("invokeTarget").String(),
	}
	rows, total, err := service.SelectJobListPaged(ctx, f, service.ParsePage(r))
	if err != nil {
		response.Send(r, response.Err("查询失败"))
		return
	}
	response.Send(r, service.DataTable(total, rows))
}

// JobExport POST /monitor/job/export（monitor:job:export + @Log EXPORT）：同 list 过滤，过 exportLimit 护栏后写 xlsx。
func JobExport(r *ghttp.Request) {
	ctx := r.Context()
	f := service.JobListFilter{
		JobName:      r.Get("jobName").String(),
		JobGroup:     r.Get("jobGroup").String(),
		Status:       r.Get("status").String(),
		InvokeTarget: r.Get("invokeTarget").String(),
	}
	max := service.GetExportMaxRows(ctx)
	rows, err := service.SelectJobListLimited(ctx, f, max)
	if err != nil {
		response.Send(r, response.Err("导出失败"))
		return
	}
	if exportOverLimit(r, len(rows), max) {
		return
	}
	writeXlsx(r, func(w io.Writer) error { return service.ExportJob(w, rows) })
}

// JobGetInfo GET /monitor/job/{jobId} → success(job)（含 nextValidTime）。对齐 Java：不存在也回 data:null，不报错。
func JobGetInfo(r *ghttp.Request) {
	ctx := r.Context()
	id, err := parseInt(r.GetRouter("jobId").String())
	if err != nil {
		response.Send(r, response.Err("参数格式有误"))
		return
	}
	response.Send(r, response.OkData(service.SelectJobById(ctx, id)))
}

// JobAdd POST /monitor/job（monitor:job:add + @Log INSERT）：补 createBy → validateJob → 入库(默认暂停) → 注册调度。
func JobAdd(r *ghttp.Request) {
	var job model.SysJob
	if err := r.Parse(&job); err != nil {
		response.Send(r, response.Err("请求参数有误"))
		return
	}
	if lu := middleware.CurrentLoginUser(r); lu != nil {
		job.CreateBy = lu.Username // 不信任前端传入的 createBy
	}
	rows, err := service.AddJob(r.Context(), &job)
	if err != nil {
		response.Send(r, response.Err(err.Error()))
		return
	}
	response.Send(r, toAjax(rows))
}

// JobEdit PUT /monitor/job（monitor:job:edit + @Log UPDATE）：补 updateBy → validateJob → 更新 → 重建调度。
func JobEdit(r *ghttp.Request) {
	var job model.SysJob
	if err := r.Parse(&job); err != nil {
		response.Send(r, response.Err("请求参数有误"))
		return
	}
	if lu := middleware.CurrentLoginUser(r); lu != nil {
		job.UpdateBy = lu.Username // 不信任前端传入的 updateBy
	}
	rows, err := service.EditJob(r.Context(), &job)
	if err != nil {
		response.Send(r, response.Err(err.Error()))
		return
	}
	response.Send(r, toAjax(rows))
}

// JobChangeStatus PUT /monitor/job/changeStatus（monitor:job:changeStatus + @Log UPDATE）：
// 以库完整记录为基准仅改 status（避免前端只传部分字段致其他配置被置空），再同步调度器启/停。
func JobChangeStatus(r *ghttp.Request) {
	var job model.SysJob
	if err := r.Parse(&job); err != nil {
		response.Send(r, response.Err("请求参数有误"))
		return
	}
	ctx := r.Context()
	newJob := service.SelectJobById(ctx, job.JobId)
	if newJob == nil {
		response.Send(r, response.Err("任务不存在："+strconv.FormatInt(job.JobId, 10)))
		return
	}
	if job.Status == "" {
		response.Send(r, response.Err("状态不能为空"))
		return
	}
	newJob.Status = job.Status
	rows, err := service.ChangeJobStatus(ctx, newJob)
	if err != nil {
		response.Send(r, response.Err(err.Error()))
		return
	}
	response.Send(r, toAjax(rows))
}

// JobRun PUT /monitor/job/run（monitor:job:changeStatus + @Log UPDATE）：仅取 jobId 触发已注册任务立即执行一次。
// 安全：service.RunJob 只按库记录执行、不采用请求体 invokeTarget，故不构成绕过白名单的执行通道。
func JobRun(r *ghttp.Request) {
	var job model.SysJob
	if err := r.Parse(&job); err != nil {
		response.Send(r, response.Err("请求参数有误"))
		return
	}
	if service.RunJob(r.Context(), job.JobId) {
		response.Send(r, response.Ok())
		return
	}
	response.Send(r, response.Err("任务不存在或已过期！"))
}

// JobRemove DELETE /monitor/job/{jobIds}（monitor:job:remove + @Log DELETE）：空选/超 500 守卫收口于 service。
func JobRemove(r *ghttp.Request) {
	ctx := r.Context()
	ids, ok := splitCommaInts(r.GetRouter("jobIds").String())
	if !ok {
		response.Send(r, response.Err("参数格式有误"))
		return
	}
	rows, err := service.DeleteJobByIds(ctx, ids)
	if err != nil {
		response.Send(r, response.Err(err.Error()))
		return
	}
	response.Send(r, toAjax(rows))
}
