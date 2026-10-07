package com.ruoyi.quartz.util;

import org.quartz.JobExecutionContext;
import com.ruoyi.quartz.domain.SysJob;

/**
 * 定时任务处理（允许并发执行）
 * <p>
 * 未标注 @DisallowConcurrentExecution，同一任务的多次触发可并行执行，
 * 与 QuartzDisallowConcurrentExecution 的区别仅在于是否允许重入。
 * 
 * @author ruoyi
 *
 */
public class QuartzJobExecution extends AbstractQuartzJob {
    @Override
    protected void doExecute(JobExecutionContext context, SysJob sysJob) throws Exception {
        JobInvokeUtil.invokeMethod(sysJob);
    }
}
