package com.ruoyi.quartz.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.quartz.domain.SysJob;

/**
 * ScheduleUtils 并发策略选择测试。
 *
 * <p>
 * 覆盖本次"消灭魔数"改动点：并发标志从裸字符串 "0" 改为
 * {@code ScheduleConstants.CONCURRENT_ALLOW}。验证 concurrent="0" 选择允许并发的执行类，
 * 其余值选择禁止并发的执行类，确保常量替换后行为不变。
 * </p>
 */
public class ScheduleUtilsTest {
    private static Class<?> invokeGetQuartzJobClass(String concurrent) throws Exception {
        SysJob job = new SysJob();
        job.setConcurrent(concurrent);
        Method method = ScheduleUtils.class.getDeclaredMethod("getQuartzJobClass", SysJob.class);
        method.setAccessible(true);
        return (Class<?>) method.invoke(null, job);
    }

    @Test
    @DisplayName("并发=允许(0) 选用 QuartzJobExecution")
    void allowConcurrent() throws Exception {
        assertEquals(QuartzJobExecution.class, invokeGetQuartzJobClass("0"));
    }

    @Test
    @DisplayName("并发=禁止(1) 选用 QuartzDisallowConcurrentExecution")
    void denyConcurrent() throws Exception {
        assertEquals(QuartzDisallowConcurrentExecution.class, invokeGetQuartzJobClass("1"));
    }

    private static SysJob job(String cron, String invokeTarget) {
        SysJob job = new SysJob();
        job.setJobName("t");
        job.setCronExpression(cron);
        job.setInvokeTarget(invokeTarget);
        return job;
    }

    @Test
    @DisplayName("下沉校验：空 invokeTarget 被拒且不再击穿 whiteList 触发 NPE")
    void blankInvokeTargetRejectedNotNpe() {
        // 旧代码：空 invokeTarget 穿过 containsIgnoreCase(false) 直达 whiteList(null) → NPE 500
        // 现下沉收口：首步判空抛 ServiceException（非 NPE）
        ServiceException ex = assertThrows(ServiceException.class,
                () -> ScheduleUtils.validateJob(job("0 0 0 * * ?", "")));
        assertTrue(ex.getMessage().contains("调用目标字符串不能为空"));
    }

    @Test
    @DisplayName("下沉校验：空/空白 cron 被拒")
    void blankCronRejected() {
        assertThrows(ServiceException.class, () -> ScheduleUtils.validateJob(job(null, "com.ruoyi.quartz.task.OperLogCleanTask.clean('90')")));
        assertThrows(ServiceException.class, () -> ScheduleUtils.validateJob(job("  ", "com.ruoyi.quartz.task.OperLogCleanTask.clean('90')")));
    }

    @Test
    @DisplayName("下沉校验：rmi/ldap/http 远程加载被拒")
    void remoteLookupRejected() {
        assertThrows(ServiceException.class, () -> ScheduleUtils.validateJob(job("0 0 0 * * ?", "rmi://evil/x")));
        assertThrows(ServiceException.class, () -> ScheduleUtils.validateJob(job("0 0 0 * * ?", "ldap://evil/x")));
        assertThrows(ServiceException.class, () -> ScheduleUtils.validateJob(job("0 0 0 * * ?", "http://evil/x")));
    }

    @Test
    @DisplayName("下沉校验：非白名单全类名被拒")
    void nonWhitelistRejected() {
        assertThrows(ServiceException.class,
                () -> ScheduleUtils.validateJob(job("0 0 0 * * ?", "java.lang.Runtime.exec('calc')")));
    }

    @Test
    @DisplayName("下沉校验：白名单全类名通过（走 count>1 前缀分支，不依赖 Spring 容器）")
    void whitelistPasses() {
        ScheduleUtils.validateJob(job("0 0 0 * * ?", "com.ruoyi.quartz.task.OperLogCleanTask.clean('90')"));
    }
}
