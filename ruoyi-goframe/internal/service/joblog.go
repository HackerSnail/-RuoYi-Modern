// Package service —— 定时任务调度日志 sys_job_log 服务层，逐条对齐 SysJobLogServiceImpl + SysJobLogMapper.xml。
//
// 契约要点（不得错位）：
//   - 列表：<where> jobName/invokeTarget 模糊、jobGroup 精确、status 精确(字符串 presence)、
//     params[beginTime]/[endTime] 走 date_format(create_time,'%Y%m%d') 区间（照 Java 原样，非裸比较）；默认 create_time desc。
//   - deleteJobLogByIds：空即拒"请选择要删除的调度日志"；单次 >500 即拒；底层真实 DELETE → 返受影响行数，交 toAjax 判定。
//   - cleanJobLog：truncate 属 DDL、Java 返回 void，故此处不回数（controller 直接 success()，无二次确认门）。
//   - selectJobLogById：未命中回 nil，交 controller 判"调度日志不存在"。
package service

import (
	"context"
	"fmt"
	"io"
	"strings"

	"github.com/gogf/gf/v2/database/gdb"
	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/util/gconv"

	"ruoyi-goframe/internal/model"
)

// selectJobLogVo 对应 SysJobLogMapper.xml <sql id="selectJobLogVo">（10 列，含 create_time）。
const selectJobLogVo = "select job_log_id, job_name, job_group, invoke_target, job_message, status, " +
	"exception_info, start_time, end_time, create_time from sys_job_log"

// jobLogDeleteMaxBatch 批量删除单次上限，对齐 SysJobLogServiceImpl.MAX_DELETE_BATCH。
const jobLogDeleteMaxBatch = 500

// JobLogListFilter 调度日志列表查询条件。Status 用字符串 presence 区分"未提交"与"提交空串"。
type JobLogListFilter struct {
	JobName      string
	JobGroup     string
	Status       string
	InvokeTarget string
	BeginTime    string
	EndTime      string
}

// buildJobLogListSQL 组装列表基础查询（<where> 逐条 AND，不含 ORDER BY，排序交 QueryPaged/Limited 决定）。
func buildJobLogListSQL(f JobLogListFilter) (string, []interface{}) {
	sql := selectJobLogVo
	conds := make([]string, 0, 6)
	args := make([]interface{}, 0, 6)
	if f.JobName != "" {
		conds = append(conds, "job_name like concat('%', ?, '%')")
		args = append(args, f.JobName)
	}
	if f.JobGroup != "" {
		conds = append(conds, "job_group = ?")
		args = append(args, f.JobGroup)
	}
	if f.Status != "" {
		conds = append(conds, "status = ?")
		args = append(args, f.Status)
	}
	if f.InvokeTarget != "" {
		conds = append(conds, "invoke_target like concat('%', ?, '%')")
		args = append(args, f.InvokeTarget)
	}
	if f.BeginTime != "" {
		conds = append(conds, "date_format(create_time,'%Y%m%d') >= date_format(?, '%Y%m%d')")
		args = append(args, f.BeginTime)
	}
	if f.EndTime != "" {
		conds = append(conds, "date_format(create_time,'%Y%m%d') <= date_format(?, '%Y%m%d')")
		args = append(args, f.EndTime)
	}
	if len(conds) > 0 {
		sql += " where " + strings.Join(conds, " AND ")
	}
	return sql, args
}

func scanJobLogs(recs gdb.Result) []model.SysJobLog {
	out := make([]model.SysJobLog, 0, len(recs))
	for _, r := range recs {
		var o model.SysJobLog
		if gconv.Struct(r.Map(), &o) == nil {
			out = append(out, o)
		}
	}
	return out
}

// SelectJobLogListPaged 对应 list 端点 startPage + selectJobLogList（默认 create_time desc，可被 orderByColumn 覆盖）。
func SelectJobLogListPaged(ctx context.Context, f JobLogListFilter, p Page) ([]model.SysJobLog, int64, error) {
	sql, args := buildJobLogListSQL(f)
	res, err := QueryPaged(ctx, p, sql, args, "create_time desc")
	if err != nil {
		return nil, 0, err
	}
	return scanJobLogs(res.Rows), res.Total, nil
}

// SelectJobLogListLimited 对应 exportLimit 包裹的 selectJobLogList（无 DataScope，默认 create_time desc）：同列表 SQL + 排序 + limit max+1。
func SelectJobLogListLimited(ctx context.Context, f JobLogListFilter, maxRows int) ([]model.SysJobLog, error) {
	sql, args := buildJobLogListSQL(f)
	sql += " order by create_time desc limit ?"
	args = append(args, maxRows+1)
	recs, err := g.DB().GetAll(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	return scanJobLogs(recs), nil
}

// SelectJobLogById 对应 selectJobLogById：按主键取单条；未命中回 nil（交 controller 判不存在）。
func SelectJobLogById(ctx context.Context, jobLogId int64) *model.SysJobLog {
	rec, err := g.DB().GetOne(ctx, selectJobLogVo+" where job_log_id = ?", jobLogId)
	if err != nil || rec == nil || rec.IsEmpty() {
		return nil
	}
	var o model.SysJobLog
	if gconv.Struct(rec.Map(), &o) != nil {
		return nil
	}
	return &o
}

// colJobLog 把「从 *model.SysJobLog 取值」的闭包适配成引擎的 func(any) any。
func colJobLog(fn func(*model.SysJobLog) any) func(any) any {
	return func(row any) any {
		o, ok := row.(*model.SysJobLog)
		if !ok || o == nil {
			return nil
		}
		return fn(o)
	}
}

// jobLogExportColumns 调度日志导出列：严格按 SysJobLog 的 @Excel 声明序（startTime/endTime 无 @Excel 不导出）。
// jobLogId 为 NUMERIC；status 走 readConverterExp。
func jobLogExportColumns() []ExcelColumn {
	return []ExcelColumn{
		{Name: "日志序号", Kind: ExcelKindNumeric, Value: colJobLog(func(o *model.SysJobLog) any { return o.JobLogId })},
		{Name: "任务名称", Value: colJobLog(func(o *model.SysJobLog) any { return o.JobName })},
		{Name: "任务组名", Value: colJobLog(func(o *model.SysJobLog) any { return o.JobGroup })},
		{Name: "调用目标字符串", Value: colJobLog(func(o *model.SysJobLog) any { return o.InvokeTarget })},
		{Name: "日志信息", Value: colJobLog(func(o *model.SysJobLog) any { return o.JobMessage })},
		{Name: "执行状态", Converter: "0=正常,1=失败", Value: colJobLog(func(o *model.SysJobLog) any { return o.Status })},
		{Name: "异常信息", Value: colJobLog(func(o *model.SysJobLog) any { return o.ExceptionInfo })},
	}
}

// ExportJobLog 把调度日志列表写成 xlsx 流：sheet 名 "调度日志"（对齐 Java util.exportExcel(..., "调度日志")）。
func ExportJobLog(w io.Writer, list []model.SysJobLog) error {
	rows := make([]any, len(list))
	for i := range list {
		rows[i] = &list[i]
	}
	return WriteExcel(w, "调度日志", jobLogExportColumns(), rows)
}

// DeleteJobLogByIds 对应 deleteJobLogByIds：空/超限入口即拒（中文文案照 Java ServiceException），底层 DELETE 返受影响行数。
func DeleteJobLogByIds(ctx context.Context, jobLogIds []int64) (int, error) {
	if len(jobLogIds) == 0 {
		return 0, fmt.Errorf("请选择要删除的调度日志")
	}
	if len(jobLogIds) > jobLogDeleteMaxBatch {
		return 0, fmt.Errorf("单次最多删除 %d 条调度日志，当前 %d 条", jobLogDeleteMaxBatch, len(jobLogIds))
	}
	ph := make([]string, len(jobLogIds))
	args := make([]interface{}, len(jobLogIds))
	for i, id := range jobLogIds {
		ph[i] = "?"
		args[i] = id
	}
	sql := "delete from sys_job_log where job_log_id in (" + strings.Join(ph, ",") + ")"
	res, err := g.DB().Exec(ctx, sql, args...)
	if err != nil {
		return 0, err
	}
	affected, _ := res.RowsAffected()
	return int(affected), nil
}

// CleanJobLog 对应 cleanJobLog：truncate sys_job_log（DDL 不返行数，Java 返回 void）。
func CleanJobLog(ctx context.Context) error {
	_, err := g.DB().Exec(ctx, "truncate table sys_job_log")
	return err
}

// InsertJobLog 对应 insertJobLog（addJobLog）：动态列仅非空、create_time=sysdate()。供调度执行体（executeJob）
// 每次运行后落一行 sys_job_log（成功 status=0、失败 status=1 + exception_info 截断 2000）。
func InsertJobLog(ctx context.Context, l *model.SysJobLog) error {
	cols := make([]string, 0, 10)
	vals := make([]string, 0, 10)
	args := make([]interface{}, 0, 9)
	addStr := func(col, v string) {
		if v != "" {
			cols = append(cols, col)
			vals = append(vals, "?")
			args = append(args, v)
		}
	}
	if l.JobLogId != 0 {
		cols = append(cols, "job_log_id")
		vals = append(vals, "?")
		args = append(args, l.JobLogId)
	}
	addStr("job_name", l.JobName)
	addStr("job_group", l.JobGroup)
	addStr("invoke_target", l.InvokeTarget)
	addStr("job_message", l.JobMessage)
	addStr("status", l.Status)
	addStr("exception_info", l.ExceptionInfo)
	if l.StartTime != nil {
		cols = append(cols, "start_time")
		vals = append(vals, "?")
		args = append(args, l.StartTime)
	}
	if l.EndTime != nil {
		cols = append(cols, "end_time")
		vals = append(vals, "?")
		args = append(args, l.EndTime)
	}
	cols = append(cols, "create_time")
	vals = append(vals, "sysdate()")
	sql := "insert into sys_job_log(" + strings.Join(cols, ",") + ") values(" + strings.Join(vals, ",") + ")"
	_, err := g.DB().Exec(ctx, sql, args...)
	return err
}
