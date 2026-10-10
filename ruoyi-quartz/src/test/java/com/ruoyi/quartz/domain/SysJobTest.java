package com.ruoyi.quartz.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SysJob 领域对象测试：锁定 toString 去 cron 解析副作用 + getNextValidTime 对脏 cron 的兜底。
 *
 * <p>
 * 覆盖本轮修复点：① toString 不再调用 getNextValidTime（无效 cron 时打印不崩、不含 nextValidTime 行）；
 * ② getNextValidTime 对空/非法 cron 一律返回 null 而非抛 IllegalArgumentException（保护列表序列化不因脏数据 500）。
 * </p>
 */
public class SysJobTest {

    @Test
    @DisplayName("getNextValidTime：空 cron 返回 null")
    void nextValidTimeEmptyCron() {
        SysJob job = new SysJob();
        job.setCronExpression("");
        assertNull(job.getNextValidTime());
        job.setCronExpression(null);
        assertNull(job.getNextValidTime());
    }

    @Test
    @DisplayName("getNextValidTime：非法 cron 兜底返回 null，不抛异常")
    void nextValidTimeInvalidCronReturnsNull() {
        SysJob job = new SysJob();
        // 非空但语法非法：旧实现走 CronUtils.getNextExecution 抛 IllegalArgumentException
        job.setCronExpression("this is not a valid cron");
        assertNull(job.getNextValidTime());
    }

    @Test
    @DisplayName("getNextValidTime：合法 cron 正常推算")
    void nextValidTimeValidCron() {
        SysJob job = new SysJob();
        job.setCronExpression("0 0 0 * * ?");
        assertNotNull(job.getNextValidTime());
    }

    @Test
    @DisplayName("toString：非法 cron 不抛（不再引用 getNextValidTime）")
    void toStringDoesNotThrowOnInvalidCron() {
        SysJob job = new SysJob();
        job.setJobId(1L);
        job.setCronExpression("this is not a valid cron");
        assertDoesNotThrow(job::toString);
    }

    @Test
    @DisplayName("toString：不再包含 nextValidTime 行")
    void toStringExcludesNextValidTime() {
        SysJob job = new SysJob();
        job.setCronExpression("0 0 0 * * ?");
        assertFalse(job.toString().contains("nextValidTime"));
    }
}
