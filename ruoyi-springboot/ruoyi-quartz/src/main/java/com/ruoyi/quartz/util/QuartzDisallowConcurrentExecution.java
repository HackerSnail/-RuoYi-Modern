package com.ruoyi.quartz.util;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import com.ruoyi.quartz.domain.SysJob;

/**
 * 定时任务处理（禁止并发执行）
 * <p>
 * 类上的 @DisallowConcurrentExecution 使同一 JobDetail 的多个 trigger 不会并行触发，
 * 上一个实例未执行完时下一个会等待，适用于不允许重入的任务。
 * <p>
 * 粒度提醒：该互斥仅对同一 JobDetail（同一 JobKey）生效——不同任务各自是独立 JobDetail，
 * 彼此之间仍可并发执行，本注解并非“全系统同一时刻只跑一个任务”的全局互斥；若确需全局串行或
 * 跨节点（集群）互斥，须另加分布式锁，不能依赖此注解。
 * 
 * @author HackerSnail <2306002088@qq.com>
 *
 */
@DisallowConcurrentExecution
public class QuartzDisallowConcurrentExecution extends AbstractQuartzJob {
    @Override
    protected void doExecute(JobExecutionContext context, SysJob sysJob) throws Exception {
        JobInvokeUtil.invokeMethod(sysJob);
    }
}
