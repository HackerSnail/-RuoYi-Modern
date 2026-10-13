package com.ruoyi.quartz.util;

import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.springframework.aop.support.AopUtils;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.constant.ScheduleConstants;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.exception.job.TaskException;
import com.ruoyi.common.exception.job.TaskException.Code;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.quartz.domain.SysJob;

/**
 * 定时任务工具类
 * 
 * @author HackerSnail <2306002088@qq.com>
 *
 */
public class ScheduleUtils {
    /**
     * 得到quartz任务类
     *
     * @param sysJob 执行计划
     * @return 具体执行任务类（根据 concurrent 标志选择允许/禁止并发执行的 Job 实现）
     */
    private static Class<? extends Job> getQuartzJobClass(SysJob sysJob) {
        // concurrent 为允许时用普通 Job；否则用带 @DisallowConcurrentExecution 的子类，同一 JobDetail
        // 不会并发执行
        boolean isConcurrent = ScheduleConstants.CONCURRENT_ALLOW.equals(sysJob.getConcurrent());
        return isConcurrent ? QuartzJobExecution.class : QuartzDisallowConcurrentExecution.class;
    }

    /**
     * 构建任务触发对象
     */
    public static TriggerKey getTriggerKey(Long jobId, String jobGroup) {
        return TriggerKey.triggerKey(ScheduleConstants.TASK_CLASS_NAME + jobId, jobGroup);
    }

    /**
     * 构建任务键对象
     */
    public static JobKey getJobKey(Long jobId, String jobGroup) {
        return JobKey.jobKey(ScheduleConstants.TASK_CLASS_NAME + jobId, jobGroup);
    }

    /**
     * 创建定时任务：拼装 JobDetail + CronTrigger 注册到调度器，并根据状态决定是否立即暂停。
     */
    public static void createScheduleJob(Scheduler scheduler, SysJob job) throws SchedulerException, TaskException {
        Class<? extends Job> jobClass = getQuartzJobClass(job);
        // 构建job信息
        Long jobId = job.getJobId();
        String jobGroup = job.getJobGroup();
        JobDetail jobDetail = JobBuilder.newJob(jobClass).withIdentity(getJobKey(jobId, jobGroup)).build();

        // 表达式调度构建器
        CronScheduleBuilder cronScheduleBuilder = CronScheduleBuilder.cronSchedule(job.getCronExpression());
        // 根据 misfire 策略配置补偿行为
        cronScheduleBuilder = handleCronScheduleMisfirePolicy(job, cronScheduleBuilder);

        // 按新的cronExpression表达式构建一个新的trigger
        CronTrigger trigger = TriggerBuilder.newTrigger().withIdentity(getTriggerKey(jobId, jobGroup))
                .withSchedule(cronScheduleBuilder).build();

        // 放入参数，运行时的方法可以获取（将 SysJob 快照随 JobDetail 存入，供 AbstractQuartzJob 还原上下文）
        jobDetail.getJobDataMap().put(ScheduleConstants.TASK_PROPERTIES, job);

        // 判断是否存在
        if (scheduler.checkExists(getJobKey(jobId, jobGroup))) {
            // 防止创建时存在数据问题 先移除，然后在执行创建操作
            scheduler.deleteJob(getJobKey(jobId, jobGroup));
        }

        // 判断任务是否过期
        if (StringUtils.isNotNull(CronUtils.getNextExecution(job.getCronExpression()))) {
            // 执行调度任务
            scheduler.scheduleJob(jobDetail, trigger);
        }

        // 暂停任务
        if (job.getStatus().equals(ScheduleConstants.Status.PAUSE.getValue())) {
            scheduler.pauseJob(ScheduleUtils.getJobKey(jobId, jobGroup));
        }
    }

    /**
     * 设置定时任务策略
     */
    public static CronScheduleBuilder handleCronScheduleMisfirePolicy(SysJob job, CronScheduleBuilder cb)
            throws TaskException {
        switch (job.getMisfirePolicy()) {
            case ScheduleConstants.MISFIRE_DEFAULT:
                return cb;
            case ScheduleConstants.MISFIRE_IGNORE_MISFIRES:
                return cb.withMisfireHandlingInstructionIgnoreMisfires();
            case ScheduleConstants.MISFIRE_FIRE_AND_PROCEED:
                return cb.withMisfireHandlingInstructionFireAndProceed();
            case ScheduleConstants.MISFIRE_DO_NOTHING:
                return cb.withMisfireHandlingInstructionDoNothing();
            default:
                throw new TaskException("The task misfire policy '" + job.getMisfirePolicy()
                        + "' cannot be used in cron schedule tasks", Code.CONFIG_ERROR);
        }
    }

    /**
     * 检查包名是否为白名单配置。
     * 防止调度表被注入任意 invokeTarget 导致 RCE：只有白名单包下的类/bean 才允许被调度。
     * 
     * @param invokeTarget 目标字符串
     * @return 结果
     */
    public static boolean whiteList(String invokeTarget) {
        String packageName = StringUtils.substringBefore(invokeTarget, "(");
        int count = StringUtils.countMatches(packageName, ".");
        if (count > 1) {
            // 全类名写法：校验 invokeTarget 是否落在白名单包内（边界感知，拒绝伪同级包）
            return isInTaskPackage(invokeTarget);
        }
        // bean 名写法：取真实目标类（穿透 CGLIB/JDK 代理）所在包，再做白名单 + 黑名单双重校验；
        // 用 AopUtils.getTargetClass 而非 getClass()，避免代理类包名误判（含 JDK 动态代理的误拒）
        Object obj = SpringUtils.getBean(StringUtils.split(invokeTarget, ".")[0]);
        String beanPackageName = AopUtils.getTargetClass(obj).getPackage().getName();
        return isInTaskPackage(beanPackageName)
                && !StringUtils.startsWithAny(beanPackageName, Constants.JOB_ERROR_STR);
    }

    /**
     * 判断给定名称是否落在任务白名单包内（含其子包）。
     * <p>
     * 边界感知：要求等于白名单包名或以“包名 + '.'”开头，从而拒绝 {@code com.ruoyi.quartz.taskevil} 这类
     * 伪同级包绕过；供 {@link #whiteList(String)} 与 {@code JobInvokeUtil} 执行点 guard 共用，
     * 作为 RCE 白名单的唯一判据。
     *
     * @param name 全类名或包名
     * @return 是否命中白名单包（含子包）
     */
    public static boolean isInTaskPackage(String name) {
        if (StringUtils.isEmpty(name)) {
            return false;
        }
        for (String pkg : Constants.JOB_WHITELIST_STR) {
            if (name.equals(pkg) || name.startsWith(pkg + ".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 调度任务安全校验：入库/更新前的统一收口，防止调度表被利用为 RCE 入口。
     *
     * <p>
     * 校验顺序：先判 cron 与 invokeTarget 非空（空 invokeTarget 会一路穿过特征匹配最终击穿
     * {@link #whiteList(String)} 触发 NPE，且必然不合法，须最早拒绝）→ cron 语法合法 →
     * 拒绝 rmi/ldap(s)/http(s) 远程加载 → 拒绝违规特征字符串 → 必须命中白名单包。
     * 由 {@code SysJobServiceImpl.insertJob/updateJob} 调用，覆盖所有写库收口（含非 HTTP 入口），
     * 不再依赖 Controller 层的按 HTTP 动词重复校验。
     * </p>
     *
     * <p>
     * 定位说明：rmi/ldap/http 与 JOB_ERROR_STR 属“字符串特征黑名单”，只能拦已知形态，不是安全边界；
     * 真正的收口是 {@link #whiteList(String)} 白名单（边界感知包前缀）。黑名单为多一层尽力拦截，
     * 命中即拒，漏网仍由白名单兜住。
     * </p>
     *
     * @param job 待入库/更新的调度任务
     * @throws ServiceException 校验不通过（消息与原 Controller 校验链语义一致）
     */
    public static void validateJob(SysJob job) {
        String jobName = job.getJobName();
        String cron = job.getCronExpression();
        String invokeTarget = job.getInvokeTarget();
        if (StringUtils.isBlank(cron)) {
            throw new ServiceException("定时任务'" + jobName + "'保存失败，Cron表达式不能为空");
        }
        if (StringUtils.isBlank(invokeTarget)) {
            throw new ServiceException("定时任务'" + jobName + "'保存失败，调用目标字符串不能为空");
        }
        if (!CronUtils.isValid(cron)) {
            throw new ServiceException("定时任务'" + jobName + "'保存失败，Cron表达式不正确");
        } else if (StringUtils.containsIgnoreCase(invokeTarget, Constants.LOOKUP_RMI)) {
            throw new ServiceException("定时任务'" + jobName + "'保存失败，目标字符串不允许'rmi'调用");
        } else if (StringUtils.containsAnyIgnoreCase(invokeTarget,
                new String[] { Constants.LOOKUP_LDAP, Constants.LOOKUP_LDAPS })) {
            throw new ServiceException("定时任务'" + jobName + "'保存失败，目标字符串不允许'ldap(s)'调用");
        } else if (StringUtils.containsAnyIgnoreCase(invokeTarget,
                new String[] { Constants.HTTP, Constants.HTTPS })) {
            throw new ServiceException("定时任务'" + jobName + "'保存失败，目标字符串不允许'http(s)'调用");
        } else if (StringUtils.containsAnyIgnoreCase(invokeTarget, Constants.JOB_ERROR_STR)) {
            throw new ServiceException("定时任务'" + jobName + "'保存失败，目标字符串存在违规");
        } else if (!whiteList(invokeTarget)) {
            throw new ServiceException("定时任务'" + jobName + "'保存失败，目标字符串不在白名单内");
        }
    }
}
