// Package service —— 定时任务 sys_job 服务层，逐条对齐 SysJobServiceImpl + SysJobMapper.xml。
//
// 契约要点（不得错位）：
//   - 列表：<where> jobName/invokeTarget 模糊、jobGroup/status 精确（字符串 presence 区分未提交与空串）；
//     默认排序 job_id asc（Java selectJobList 无 ORDER BY，InnoDB 实按 PK 返回，此处显式稳定化，见 spec 偏差1）。
//   - 每行 nextValidTime 由 cron 依当前时刻推算（对齐 Java SysJob.getNextValidTime()：空/非法 cron → nil）。
//   - insertJob：新增默认置暂停 status="1"（避免入库即意外触发）；misfire/concurrent 空补 "0"（对齐 Java 字段默认）；
//     useGeneratedKeys 语义回写自增 jobId。updateJob：动态 SET 仅非空、update_time=sysdate()。
//   - deleteJobByIds：空/超 500 入口即拒；逐条「先删库成功、再摘调度器」（对齐 Java 各方法 @Transactional 顺序）。
//   - validateJob：写库统一安全收口（cron/invokeTarget 判空 + rmi/ldap/http 拒绝 + 违规特征 + 白名单=注册表命中）。
package service

import (
	"context"
	"fmt"
	"io"
	"strings"

	"github.com/gogf/gf/v2/database/gdb"
	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/os/gtime"
	"github.com/gogf/gf/v2/util/gconv"

	"ruoyi-goframe/internal/model"
)

// selectJobVo 对应 SysJobMapper.xml <sql id="selectJobVo">（13 列，与列表/详情一致；last_fire_time 不对外）。
const selectJobVo = "select job_id, job_name, job_group, invoke_target, cron_expression, misfire_policy, " +
	"concurrent, status, create_by, create_time, update_by, update_time, remark from sys_job"

// selectJobAllVo 启动加载专用：额外带 last_fire_time 供开机补跑判定（spec 3b）。
const selectJobAllVo = "select job_id, job_name, job_group, invoke_target, cron_expression, misfire_policy, " +
	"concurrent, status, create_by, create_time, update_by, update_time, remark, last_fire_time from sys_job"

// 调度任务常量，对齐 ScheduleConstants（status/concurrent/misfire 皆字符串）。
const (
	jobStatusNormal     = "0" // ScheduleConstants.Status.NORMAL
	jobStatusPause      = "1" // ScheduleConstants.Status.PAUSE
	jobConcurrentAllow  = "0" // ScheduleConstants.CONCURRENT_ALLOW
	jobConcurrentDeny   = "1" // ScheduleConstants.CONCURRENT_DENY
	jobMisfireDefault   = "0" // ScheduleConstants.MISFIRE_DEFAULT
	jobDeleteMaxBatch   = 500 // SysJobServiceImpl.MAX_DELETE_BATCH
	jobLogStatusSuccess = "0" // Constants.SUCCESS
	jobLogStatusFail    = "1" // Constants.FAIL
	jobExceptionInfoMax = 2000
)

// jobErrorStr 违规特征字符串黑名单，逐字对齐 Constants.JOB_ERROR_STR。
// 注：真实收口是白名单（注册表命中），黑名单为多一层尽力拦截，命中即拒。
var jobErrorStr = []string{
	"java.net.URL", "javax.naming.InitialContext", "org.yaml.snakeyaml",
	"org.springframework", "org.apache", "com.ruoyi.common.utils.file",
	"com.ruoyi.common.config", "com.ruoyi.generator",
}

// JobListFilter 定时任务列表查询条件（字符串 presence 区分未提交与空串）。
type JobListFilter struct {
	JobName      string
	JobGroup     string
	Status       string
	InvokeTarget string
}

// buildJobListSQL 组装列表基础查询（<where> 逐条 AND，不含 ORDER BY，排序交 QueryPaged/Limited）。
func buildJobListSQL(f JobListFilter) (string, []interface{}) {
	sql := selectJobVo
	conds := make([]string, 0, 4)
	args := make([]interface{}, 0, 4)
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
	if len(conds) > 0 {
		sql += " where " + strings.Join(conds, " AND ")
	}
	return sql, args
}

func scanJobs(recs gdb.Result) []model.SysJob {
	out := make([]model.SysJob, 0, len(recs))
	for _, r := range recs {
		var j model.SysJob
		if gconv.Struct(r.Map(), &j) == nil {
			out = append(out, j)
		}
	}
	return out
}

// nextValidTimeOf 依 cron 从当前时刻推算下一格触发时刻；空/非法 cron → nil（对齐 Java getter 的兜底）。
func nextValidTimeOf(cron string) *gtime.Time {
	if strings.TrimSpace(cron) == "" {
		return nil
	}
	return CronNext(cron, gtime.Now())
}

// SelectJobListPaged 对应 list：过滤 + 分页（默认 job_id asc，可被 orderByColumn 覆盖），逐行填充 nextValidTime。
func SelectJobListPaged(ctx context.Context, f JobListFilter, p Page) ([]model.SysJob, int64, error) {
	sql, args := buildJobListSQL(f)
	res, err := QueryPaged(ctx, p, sql, args, "job_id asc")
	if err != nil {
		return nil, 0, err
	}
	list := scanJobs(res.Rows)
	for i := range list {
		list[i].NextValidTime = nextValidTimeOf(list[i].CronExpression)
	}
	return list, res.Total, nil
}

// SelectJobListLimited 对应 exportLimit 包裹的 selectJobList：同列表 SQL + 排序 + limit max+1（导出护栏）。
func SelectJobListLimited(ctx context.Context, f JobListFilter, maxRows int) ([]model.SysJob, error) {
	sql, args := buildJobListSQL(f)
	sql += " order by job_id asc limit ?"
	args = append(args, maxRows+1)
	recs, err := g.DB().GetAll(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	return scanJobs(recs), nil
}

// SelectJobById 对应 selectJobById：按主键取单条并填 nextValidTime；未命中回 nil（交 controller 判不存在）。
func SelectJobById(ctx context.Context, jobId int64) *model.SysJob {
	rec, err := g.DB().GetOne(ctx, selectJobVo+" where job_id = ?", jobId)
	if err != nil || rec == nil || rec.IsEmpty() {
		return nil
	}
	var j model.SysJob
	if gconv.Struct(rec.Map(), &j) != nil {
		return nil
	}
	j.NextValidTime = nextValidTimeOf(j.CronExpression)
	return &j
}

// SelectJobAll 对应 selectJobAll：启动全量加载（额外带 last_fire_time 供补跑判定，不填 nextValidTime）。
func SelectJobAll(ctx context.Context) ([]model.SysJob, error) {
	recs, err := g.DB().GetAll(ctx, selectJobAllVo)
	if err != nil {
		return nil, err
	}
	return scanJobs(recs), nil
}

// UpdateJobLastFireTime 本仓增强：executeJob 每次执行收尾回写实际执行完成时刻（含失败），供开机补跑判定。
func UpdateJobLastFireTime(ctx context.Context, jobId int64, t *gtime.Time) error {
	_, err := g.DB().Exec(ctx, "update sys_job set last_fire_time = ? where job_id = ?", t, jobId)
	return err
}

// InsertJob 对应 insertJob：动态列仅非空、create_time=sysdate()、新增默认暂停、misfire/concurrent 空补默认；
// 回写自增 jobId。返回落库后的 jobId。
func InsertJob(ctx context.Context, job *model.SysJob) (int64, error) {
	job.Status = jobStatusPause // 对齐 insertJob：job.setStatus(PAUSE)，避免入库即意外触发
	if job.MisfirePolicy == "" {
		job.MisfirePolicy = jobMisfireDefault
	}
	if job.Concurrent == "" {
		job.Concurrent = jobConcurrentAllow
	}
	cols := make([]string, 0, 11)
	vals := make([]string, 0, 11)
	args := make([]interface{}, 0, 10)
	addStr := func(col, v string) {
		if v != "" {
			cols = append(cols, col)
			vals = append(vals, "?")
			args = append(args, v)
		}
	}
	if job.JobId != 0 {
		cols = append(cols, "job_id")
		vals = append(vals, "?")
		args = append(args, job.JobId)
	}
	addStr("job_name", job.JobName)
	addStr("job_group", job.JobGroup)
	addStr("invoke_target", job.InvokeTarget)
	addStr("cron_expression", job.CronExpression)
	addStr("misfire_policy", job.MisfirePolicy)
	addStr("concurrent", job.Concurrent)
	addStr("status", job.Status)
	addStr("remark", job.Remark)
	addStr("create_by", job.CreateBy)
	cols = append(cols, "create_time")
	vals = append(vals, "sysdate()") // 对齐 Java：create_time 走 sysdate() 字面量
	sql := "insert into sys_job(" + strings.Join(cols, ",") + ") values(" + strings.Join(vals, ",") + ")"
	res, err := g.DB().Exec(ctx, sql, args...)
	if err != nil {
		return 0, err
	}
	if job.JobId == 0 {
		if id, _ := res.LastInsertId(); id > 0 {
			job.JobId = id
		}
	}
	return job.JobId, nil
}

// UpdateJob 对应 updateJob：动态 SET 仅非空、update_time 强制 sysdate()。
// status：Java 判 !=null；本仓 status 域仅 "0"/"1"（空串不会合法提交），故以非空近似——前端编辑省略 status
// 时保持库值不变，显式携带则更新，与 Java 观测行为一致。
func UpdateJob(ctx context.Context, job *model.SysJob) (int, error) {
	sets := make([]string, 0, 10)
	args := make([]interface{}, 0, 10)
	addStr := func(col, v string) {
		if v != "" {
			sets = append(sets, col+" = ?")
			args = append(args, v)
		}
	}
	addStr("job_name", job.JobName)
	addStr("job_group", job.JobGroup)
	addStr("invoke_target", job.InvokeTarget)
	addStr("cron_expression", job.CronExpression)
	addStr("misfire_policy", job.MisfirePolicy)
	addStr("concurrent", job.Concurrent)
	addStr("status", job.Status)
	addStr("remark", job.Remark)
	addStr("update_by", job.UpdateBy)
	sets = append(sets, "update_time = sysdate()")
	sql := "update sys_job set " + strings.Join(sets, ", ") + " where job_id = ?"
	args = append(args, job.JobId)
	res, err := g.DB().Exec(ctx, sql, args...)
	if err != nil {
		return 0, err
	}
	affected, _ := res.RowsAffected()
	return int(affected), nil
}

// DeleteJobById 对应 deleteJobById：物理删除，返回受影响行数（调用方据此决定是否摘调度器）。
func DeleteJobById(ctx context.Context, jobId int64) (int, error) {
	res, err := g.DB().Exec(ctx, "delete from sys_job where job_id = ?", jobId)
	if err != nil {
		return 0, err
	}
	affected, _ := res.RowsAffected()
	return int(affected), nil
}

// DeleteJobByIds 对应 deleteJobByIds→deleteJob：空/超 500 入口即拒；逐条 select 存在则「先删库、rows>0 再摘调度器」，
// 不存在跳过（避免陈旧 ID）。摘调度器与删库同在本 service 层收口，controller remove 不再跨层编排。
func DeleteJobByIds(ctx context.Context, ids []int64) (int, error) {
	if len(ids) == 0 {
		return 0, fmt.Errorf("请选择要删除的任务")
	}
	if len(ids) > jobDeleteMaxBatch {
		return 0, fmt.Errorf("单次最多删除 %d 条任务，当前 %d 条", jobDeleteMaxBatch, len(ids))
	}
	rows := 0
	for _, id := range ids {
		job := SelectJobById(ctx, id)
		if job == nil {
			continue // 库中不存在（陈旧 ID 或并发已删）跳过，对齐 Java
		}
		n, err := DeleteJobById(ctx, id)
		if err != nil {
			return rows, err
		}
		if n > 0 {
			unregisterJob(id) // 先删库成功，再摘调度器
		}
		rows += n
	}
	return rows, nil
}

// containsAnyIgnoreCase 复刻 StringUtils.containsAnyIgnoreCase：忽略大小写命中任一 needle 即真。
func containsAnyIgnoreCase(hay string, needles []string) bool {
	l := strings.ToLower(hay)
	for _, n := range needles {
		if strings.Contains(l, strings.ToLower(n)) {
			return true
		}
	}
	return false
}

// validateJob 逐字对齐 ScheduleUtils.validateJob：写库（add/edit）前统一收口，文案前缀 定时任务'<jobName>'保存失败。
// 顺序：cron 空 → invokeTarget 空 → cron 非法 → rmi → ldap(s) → http(s) → 违规特征黑名单 → 白名单（注册表命中）。
func validateJob(job *model.SysJob) error {
	jobName := job.JobName
	cron := job.CronExpression
	invokeTarget := job.InvokeTarget
	if strings.TrimSpace(cron) == "" {
		return fmt.Errorf("定时任务'%s'保存失败，Cron表达式不能为空", jobName)
	}
	if strings.TrimSpace(invokeTarget) == "" {
		return fmt.Errorf("定时任务'%s'保存失败，调用目标字符串不能为空", jobName)
	}
	lower := strings.ToLower(invokeTarget)
	if !CronIsValid(cron) {
		return fmt.Errorf("定时任务'%s'保存失败，Cron表达式不正确", jobName)
	} else if strings.Contains(lower, "rmi:") {
		return fmt.Errorf("定时任务'%s'保存失败，目标字符串不允许'rmi'调用", jobName)
	} else if strings.Contains(lower, "ldap:") || strings.Contains(lower, "ldaps:") {
		return fmt.Errorf("定时任务'%s'保存失败，目标字符串不允许'ldap(s)'调用", jobName)
	} else if strings.Contains(lower, "http://") || strings.Contains(lower, "https://") {
		return fmt.Errorf("定时任务'%s'保存失败，目标字符串不允许'http(s)'调用", jobName)
	} else if containsAnyIgnoreCase(invokeTarget, jobErrorStr) {
		return fmt.Errorf("定时任务'%s'保存失败，目标字符串存在违规", jobName)
	} else if !taskRegistered(invokeTarget) {
		return fmt.Errorf("定时任务'%s'保存失败，目标字符串不在白名单内", jobName)
	}
	return nil
}

// colJob 把「从 *model.SysJob 取值」的闭包适配成引擎的 func(any) any。
func colJob(fn func(*model.SysJob) any) func(any) any {
	return func(row any) any {
		j, ok := row.(*model.SysJob)
		if !ok || j == nil {
			return nil
		}
		return fn(j)
	}
}

// jobExportColumns 定时任务导出列：严格按 SysJob 的 @Excel 声明序（8 列；startTime/endTime 无 @Excel 不导出）。
// jobId 为 NUMERIC；misfirePolicy/concurrent/status 走 readConverterExp（文案逐字对齐 Java）。
func jobExportColumns() []ExcelColumn {
	return []ExcelColumn{
		{Name: "任务序号", Kind: ExcelKindNumeric, Value: colJob(func(j *model.SysJob) any { return j.JobId })},
		{Name: "任务名称", Value: colJob(func(j *model.SysJob) any { return j.JobName })},
		{Name: "任务组名", Value: colJob(func(j *model.SysJob) any { return j.JobGroup })},
		{Name: "调用目标字符串", Value: colJob(func(j *model.SysJob) any { return j.InvokeTarget })},
		{Name: "执行表达式", Value: colJob(func(j *model.SysJob) any { return j.CronExpression })},
		{Name: "计划执行错误策略", Converter: "0=默认,1=立即触发执行,2=触发一次执行,3=不触发立即执行", Value: colJob(func(j *model.SysJob) any { return j.MisfirePolicy })},
		{Name: "是否并发执行", Converter: "0=允许,1=禁止", Value: colJob(func(j *model.SysJob) any { return j.Concurrent })},
		{Name: "任务状态", Converter: "0=正常,1=暂停", Value: colJob(func(j *model.SysJob) any { return j.Status })},
	}
}

// ExportJob 把定时任务列表写成 xlsx 流：sheet 名 "定时任务"（对齐 Java util.exportExcel(..., "定时任务")）。
func ExportJob(w io.Writer, list []model.SysJob) error {
	rows := make([]any, len(list))
	for i := range list {
		rows[i] = &list[i]
	}
	return WriteExcel(w, "定时任务", jobExportColumns(), rows)
}
