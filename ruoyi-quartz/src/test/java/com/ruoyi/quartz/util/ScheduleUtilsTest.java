package com.ruoyi.quartz.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
