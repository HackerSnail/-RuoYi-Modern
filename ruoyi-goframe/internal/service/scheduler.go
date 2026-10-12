// Package service —— scheduler：基于 GoFrame 原生 gcron 的定时任务调度子系统。
//
// 与 Java Quartz 的对应与取舍（见 spec）：
//   - 进程级单例 gcron 承接调度与生命周期；entry 名 "job_<jobId>"（唯一，类比 TASK_CLASS_NAME+jobId）。
//   - concurrent="1"(禁止) → AddSingleton（同任务不并发）；"0"(允许) → Add。
//   - status!=正常 → Stop(name)（保持注册但不触发，对齐 Java PAUSE→pauseJob）；恢复 → Start(name)。
//   - 删除 → Remove(name)；立即运行 → 命中注册后以库记录 go executeJob 一次（不动原计划，对齐 triggerJob）。
//   - Java @PostConstruct init 的 scheduler.clear()+全量重建：本内存引擎每进程新建即空，天然等价，无需显式清空。
//   - 开机补跑（spec 3b，last_fire_time 增强）：catchUpMissed 扫描仅对正常态、已见过（LastFireTime!=nil）
//     且 [LastFireTime, now) 内确有漏点的任务补跑一次（≈FIRE_AND_PROCEED 单发）；首见只打基准不补。
package service

import (
	"context"
	"fmt"
	"strconv"

	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/os/gcron"
	"github.com/gogf/gf/v2/os/gtime"

	"ruoyi-goframe/internal/model"
)

// cronScheduler 进程级调度器单例（独立于 gcron 包级默认 cron，避免与业务其它 gcron 任务相互干扰）。
var cronScheduler = gcron.New()

// schedulerEnabled 运行期开关（由 StartScheduler 依 job.scheduler.enabled 设定）。
// 关闭时所有调度动作（注册/摘除/启停/触发）退化为无害 no-op，add/edit 仅落库不驱动调度，对齐纯 DO_NOTHING。
var schedulerEnabled bool

// jobEntryName 任务唯一 entry 名，类比 Java ScheduleUtils.getJobKey 的 TASK_CLASS_NAME+jobId。
func jobEntryName(jobId int64) string {
	return "job_" + strconv.FormatInt(jobId, 10)
}

// StartScheduler 启动调度器：读开关；关则返回；开则从 sys_job 全量加载逐条注册（非正常态注册后 Stop），
// 最后按 catchUpMissed 执行开机补跑。对齐 Java SysJobServiceImpl.init（以库为准全量重建）。
func StartScheduler(ctx context.Context) {
	if !g.Cfg().MustGet(ctx, "job.scheduler.enabled", true).Bool() {
		g.Log().Infof(ctx, "定时任务调度器已禁用(job.scheduler.enabled=false)，跳过启动")
		return
	}
	jobs, err := SelectJobAll(ctx)
	if err != nil {
		g.Log().Errorf(ctx, "定时任务调度器启动失败：加载 sys_job 出错: %v", err)
		return
	}
	schedulerEnabled = true // 开关开启：此后 add/edit/changeStatus/run 的调度动作生效
	for i := range jobs {
		job := jobs[i]
		if _, _, ok := lookupTask(job.InvokeTarget); !ok {
			// 未命中注册表：本 fork 理论不存在（种子皆为已注册 bean）；跳过并告警，不阻断启动。
			g.Log().Warningf(ctx, "定时任务[%s/%d]调用目标不在注册表，跳过注册: %s", job.JobName, job.JobId, job.InvokeTarget)
			continue
		}
		if err = registerJob(ctx, &job); err != nil {
			g.Log().Warningf(ctx, "定时任务[%s/%d]注册失败: %v", job.JobName, job.JobId, err)
			continue
		}
		if job.Status != jobStatusNormal {
			cronScheduler.Stop(jobEntryName(job.JobId)) // 暂停态：保持注册但不触发
		}
	}
	if g.Cfg().MustGet(ctx, "job.scheduler.catchUpMissed", true).Bool() {
		catchUpMissed(ctx, jobs)
	}
	g.Log().Infof(ctx, "定时任务调度器启动完成，共加载 %d 条任务", len(jobs))
}

// StopScheduler 停止调度器：阻塞等待在途任务收尾（对齐优雅停机）。
func StopScheduler() {
	cronScheduler.StopGracefully()
}

// registerJob 注册/重建一个任务的 cron 计划：同名先摘除再新增（对齐 Java checkExists→deleteJob→scheduleJob）。
// 按 concurrent 选 AddSingleton/Add；job 快照按值捕获，闭包执行时不受调用方后续修改影响。
func registerJob(ctx context.Context, job *model.SysJob) error {
	if !schedulerEnabled {
		return nil
	}
	name := jobEntryName(job.JobId)
	if cronScheduler.Search(name) != nil {
		cronScheduler.Remove(name)
	}
	if _, _, ok := lookupTask(job.InvokeTarget); !ok {
		return fmt.Errorf("调用目标不在注册表: %s", job.InvokeTarget)
	}
	snapshot := *job
	fn := func(c context.Context) { executeJob(c, &snapshot) }
	var err error
	if snapshot.Concurrent == jobConcurrentDeny {
		_, err = cronScheduler.AddSingleton(ctx, snapshot.CronExpression, fn, name)
	} else {
		_, err = cronScheduler.Add(ctx, snapshot.CronExpression, fn, name)
	}
	return err
}

// unregisterJob 摘除任务的调度计划（Remove 对不存在的名字为无害 no-op）。
func unregisterJob(jobId int64) {
	if !schedulerEnabled {
		return
	}
	cronScheduler.Remove(jobEntryName(jobId))
}

// pauseJob/resumeJob 依名字启停（entry 存在才生效）；返回是否命中，供 controller 判定。
func pauseJob(jobId int64) bool {
	if !schedulerEnabled {
		return false
	}
	name := jobEntryName(jobId)
	if cronScheduler.Search(name) == nil {
		return false
	}
	cronScheduler.Stop(name)
	return true
}

func resumeJob(jobId int64) bool {
	if !schedulerEnabled {
		return false
	}
	name := jobEntryName(jobId)
	if cronScheduler.Search(name) == nil {
		return false
	}
	cronScheduler.Start(name)
	return true
}

// triggerOnce 立即执行一次（仅对已注册任务）：以库中最新记录在新协程跑 executeJob，不影响原 cron 计划。
// 未注册 → false（≈ Java checkExists 未命中）。对齐 Java run：只取库记录执行，不信前端传入的 invokeTarget。
func triggerOnce(ctx context.Context, jobId int64) bool {
	if !schedulerEnabled {
		return false
	}
	if cronScheduler.Search(jobEntryName(jobId)) == nil {
		return false
	}
	job := SelectJobById(ctx, jobId)
	if job == nil {
		return false
	}
	snapshot := *job
	// 用独立于请求生命周期的 context：http 处理器返回后 gf 会取消 r.Context()，
	// 若沿用则 executeJob 内部的 DB 写入（sys_job_log/last_fire_time）会因 context canceled 而丢失。
	go executeJob(context.Background(), &snapshot)
	return true
}

// executeJob 真正执行体，逐字对齐 AbstractQuartzJob：计耗时→查注册表调 handler（recover 兜底 panic）→
// 组 job_message="jobName 总共耗时：ms毫秒"→落 sys_job_log（失败仅记日志不外抛）→收尾刷 last_fire_time。
func executeJob(ctx context.Context, job *model.SysJob) {
	start := gtime.Now()
	status := jobLogStatusSuccess
	var exception string
	func() {
		defer func() {
			if e := recover(); e != nil {
				status = jobLogStatusFail
				exception = truncateJobException(fmt.Sprintf("%v", e))
			}
		}()
		handler, args, ok := lookupTask(job.InvokeTarget)
		if !ok {
			status = jobLogStatusFail
			exception = truncateJobException(fmt.Sprintf("目标字符串不在白名单内: %s", job.InvokeTarget))
			return
		}
		if err := handler(ctx, args); err != nil {
			status = jobLogStatusFail
			exception = truncateJobException(err.Error())
		}
	}()
	end := gtime.Now()
	ms := end.Sub(start).Milliseconds()
	jobMessage := job.JobName + " 总共耗时：" + strconv.FormatInt(ms, 10) + "毫秒"
	log := &model.SysJobLog{
		JobName:       job.JobName,
		JobGroup:      job.JobGroup,
		InvokeTarget:  job.InvokeTarget,
		JobMessage:    jobMessage,
		Status:        status,
		ExceptionInfo: exception,
		StartTime:     start,
		EndTime:       end,
	}
	if err := InsertJobLog(ctx, log); err != nil {
		g.Log().Errorf(ctx, "调度日志落库失败 job=%s: %v", job.JobName, err)
	}
	// 收尾无条件刷新 last_fire_time（含失败：失败也算「已尝试」，避免下次开机对同一漏点无限重试）。
	if err := UpdateJobLastFireTime(ctx, job.JobId, end); err != nil {
		g.Log().Errorf(ctx, "刷新 last_fire_time 失败 job=%d: %v", job.JobId, err)
	}
}

// catchUpMissed 开机补跑（spec 3b）：仅正常态且已见过（LastFireTime!=nil）的任务，若 [LastFireTime, now) 内
// 本应有一格却没跑，则补跑一次；首见任务只打基准不补（防首次部署对历史漏点突然补触发/重复执行）。
func catchUpMissed(ctx context.Context, jobs []model.SysJob) {
	bootNow := gtime.Now()
	for i := range jobs {
		job := jobs[i]
		if job.Status != jobStatusNormal {
			continue // 暂停态视为用户有意停，不补
		}
		if job.LastFireTime == nil {
			if err := UpdateJobLastFireTime(ctx, job.JobId, bootNow); err != nil {
				g.Log().Errorf(ctx, "补跑基准写入失败 job=%d: %v", job.JobId, err)
			}
			continue
		}
		missed := CronNext(job.CronExpression, job.LastFireTime)
		if missed != nil && missed.Before(bootNow) {
			snapshot := job
			g.Log().Infof(ctx, "定时任务[%s/%d]检测到漏点(%s)，开机补跑一次", job.JobName, job.JobId, missed.String())
			go executeJob(ctx, &snapshot)
		}
	}
}

// truncateJobException 异常信息截断到 2000 字符（rune 计），对齐 Java StringUtils.substring(msg,0,2000)。
func truncateJobException(s string) string {
	r := []rune(s)
	if len(r) <= jobExceptionInfoMax {
		return s
	}
	return string(r[:jobExceptionInfoMax])
}

// ---------------- 服务层编排：写库与调度器同步（对齐 Java SysJobServiceImpl 各 @Transactional 方法的顺序） ----------------

// AddJob 对齐 insertJob：validateJob → InsertJob（默认暂停、回写 jobId）→ 注册调度并按状态停触发。
// 注册失败视同事务回滚：删回刚插入的行并返错（对齐 @Transactional rollbackFor）。
func AddJob(ctx context.Context, job *model.SysJob) (int, error) {
	if err := validateJob(job); err != nil {
		return 0, err
	}
	if _, err := InsertJob(ctx, job); err != nil { // InsertJob 已置 status=暂停并回写 job.JobId
		return 0, err
	}
	if err := registerJob(ctx, job); err != nil {
		_, _ = DeleteJobById(ctx, job.JobId) // 回滚补偿
		return 0, err
	}
	if schedulerEnabled && job.Status != jobStatusNormal {
		cronScheduler.Stop(jobEntryName(job.JobId)) // 新增恒暂停：注册后停触发（对齐 createScheduleJob→pauseJob）
	}
	return 1, nil
}

// EditJob 对齐 updateJob：validateJob → UpdateJob（动态 SET）→ rows>0 重建调度（先摘再注册，取库中最新记录）。
func EditJob(ctx context.Context, job *model.SysJob) (int, error) {
	if err := validateJob(job); err != nil {
		return 0, err
	}
	rows, err := UpdateJob(ctx, job)
	if err != nil {
		return rows, err
	}
	if rows > 0 {
		unregisterJob(job.JobId)
		if fresh := SelectJobById(ctx, job.JobId); fresh != nil {
			if err = registerJob(ctx, fresh); err != nil {
				g.Log().Errorf(ctx, "编辑任务重建调度失败 job=%d: %v", job.JobId, err)
				return rows, err
			}
			if schedulerEnabled && fresh.Status != jobStatusNormal {
				cronScheduler.Stop(jobEntryName(job.JobId))
			}
		}
	}
	return rows, nil
}

// ChangeJobStatus 对齐 changeStatus→resumeJob/pauseJob：先更 DB 状态成功、再同步调度器（启/停）。
// job 应为 controller 依 Java 取库基准记录后仅改 status 传入（其余字段为库中原值）。
func ChangeJobStatus(ctx context.Context, job *model.SysJob) (int, error) {
	rows, err := UpdateJob(ctx, job)
	if err != nil {
		return rows, err
	}
	if rows > 0 {
		if job.Status == jobStatusNormal {
			resumeJob(job.JobId)
		} else {
			pauseJob(job.JobId)
		}
	}
	return rows, nil
}

// RunJob 对齐 run：仅对已注册任务以库记录立即执行一次，不影响原计划；未注册/调度关闭 → false。
func RunJob(ctx context.Context, jobId int64) bool {
	return triggerOnce(ctx, jobId)
}
