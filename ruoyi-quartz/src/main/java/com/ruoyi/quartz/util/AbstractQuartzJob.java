package com.ruoyi.quartz.util;

import java.time.Duration;
import java.time.LocalDateTime;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.constant.ScheduleConstants;
import com.ruoyi.common.utils.ExceptionUtil;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.quartz.domain.SysJob;
import com.ruoyi.quartz.domain.SysJobLog;
import com.ruoyi.quartz.service.ISysJobLogService;

/**
 * 抽象quartz调用
 *
 * @author ruoyi
 */
public abstract class AbstractQuartzJob implements Job {
    private static final Logger log = LoggerFactory.getLogger(AbstractQuartzJob.class);

    /**
     * 线程本地变量
     */
    private static ThreadLocal<LocalDateTime> threadLocal = new ThreadLocal<>();

    /**
     * Quartz 回调入口：采用模板方法模式，串起 before→doExecute→after 三个阶段。
     * 任务快照还原（从 JobDataMap 拷贝属性）同样纳入本 try，任一步骤抛异常都由 catch 兜住并经
     * after 落库本次调度的耗时与异常堆栈，避免出现“拷贝失败却按成功记录”的静默路径。
     */
    @Override
    public void execute(JobExecutionContext context) {
        SysJob sysJob = new SysJob();
        try {
            // 从合并后的 JobDataMap 还原出调度时的任务快照（创建 trigger 时放入的 TASK_PROPERTIES）
            // 注意 Spring BeanUtils 参数顺序为 (source, target)，与已移除的自定义 copyBeanProp(dest, src) 相反
            BeanUtils.copyProperties(context.getMergedJobDataMap().get(ScheduleConstants.TASK_PROPERTIES), sysJob);
            before(context, sysJob);
            // 交由子类实现真正的反射调用逻辑（sysJob 于本方法开头 new，恒非 null，无需判空）
            doExecute(context, sysJob);
            after(context, sysJob, null);
        } catch (Exception e) {
            // 任务执行失败不向外抛出（避免影响 Quartz 线程），改为在 after 中落库失败日志
            log.error("任务执行异常  - ：", e);
            after(context, sysJob, e);
        } finally {
            // 计时基准的清理集中于此、无条件执行：after 在成功/失败两条路径都会先读取它，
            // 而无论 after 是否抛异常都不让脏值残留到被 Quartz 线程池复用的线程
            threadLocal.remove();
        }
    }

    /**
     * 执行前
     *
     * @param context 工作执行上下文对象
     * @param sysJob 系统计划任务
     */
    protected void before(JobExecutionContext context, SysJob sysJob) {
        // 记录开始时间戳，供 after 计算耗时；用 ThreadLocal 隔离并发调度的执行线程
        threadLocal.set(LocalDateTime.now());
    }

    /**
     * 执行后：组装本次调度的耗时/状态/异常并落 sys_job_log。
     * <p>
     * 本方法对外承诺“绝不抛异常”——落库失败只在内部记日志兜底。否则一旦 SpringUtils.getBean
     * 或落库出错，异常会从 execute 的 catch 块向外传播，既干扰 Quartz 工作线程、又会触发二次落库尝试。
     *
     * @param context 工作执行上下文对象
     * @param sysJob 系统计划任务
     */
    protected void after(JobExecutionContext context, SysJob sysJob, Exception e) {
        // 落库全程兜底，失败只记日志不外抛（清理已移交 execute 的 finally）
        try {
            LocalDateTime startTime = threadLocal.get();
            final SysJobLog sysJobLog = new SysJobLog();
            sysJobLog.setJobName(sysJob.getJobName());
            sysJobLog.setJobGroup(sysJob.getJobGroup());
            sysJobLog.setInvokeTarget(sysJob.getInvokeTarget());
            // 若异常发生在 before() 之前（如任务快照 TASK_PROPERTIES 缺失致 copyProperties 抛错），
            // threadLocal 尚未 set → startTime 为 null；兜底取 endTime，避免 Duration.between(null,..) NPE
            LocalDateTime endTime = LocalDateTime.now();
            sysJobLog.setStartTime(startTime != null ? startTime : endTime);
            sysJobLog.setEndTime(endTime);
            long runMs = Duration.between(sysJobLog.getStartTime(), sysJobLog.getEndTime()).toMillis();
            sysJobLog.setJobMessage(sysJobLog.getJobName() + " 总共耗时：" + runMs + "毫秒");
            if (e != null) {
                sysJobLog.setStatus(Constants.FAIL);
                // 异常信息截断到 2000 字符，避免超长堆栈撑爆日志表的字符串列
                String errorMsg = StringUtils.substring(ExceptionUtil.getExceptionMessage(e), 0, 2000);
                sysJobLog.setExceptionInfo(errorMsg);
            } else {
                sysJobLog.setStatus(Constants.SUCCESS);
            }

            // 写入数据库当中（Job 非 Spring 托管，故通过 SpringUtils 手动拉取日志 Service）
            SpringUtils.getBean(ISysJobLogService.class).addJobLog(sysJobLog);
        } catch (Exception logEx) {
            log.error("调度日志落库失败", logEx);
        }
    }

    /**
     * 执行方法，由子类重载
     *
     * @param context 工作执行上下文对象
     * @param sysJob 系统计划任务
     * @throws Exception 执行过程中的异常
     */
    protected abstract void doExecute(JobExecutionContext context, SysJob sysJob) throws Exception;
}
