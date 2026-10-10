package com.ruoyi.quartz.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.JobDataMap;
import org.quartz.JobKey;
import org.quartz.Scheduler;

import com.ruoyi.common.constant.ScheduleConstants;
import com.ruoyi.quartz.domain.SysJob;
import com.ruoyi.quartz.mapper.SysJobMapper;
import com.ruoyi.quartz.util.ScheduleUtils;

/**
 * SysJobServiceImpl.run 安全契约测试。
 *
 * <p>
 * 锁定 review "run 绕过 invokeTarget 校验构成 RCE" 的核实结论：run 仅按 jobId 从库 selectJobById
 * 重载完整记录并以该库记录构造 JobDataMap 触发，请求体的 invokeTarget 一律不被采用。测试以攻击者
 * 构造的恶意 invokeTarget 作为请求体，断言实际进入调度器的仍是库中白名单目标。
 * </p>
 */
public class SysJobServiceImplTest {

    @Test
    @DisplayName("run：以库记录为准触发，忽略请求体 invokeTarget")
    void runReloadsFromDbIgnoresRequestBody() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        SysJobMapper mapper = mock(SysJobMapper.class);
        SysJobServiceImpl service = new SysJobServiceImpl(scheduler, mapper);

        SysJob db = new SysJob();
        db.setJobId(1L);
        db.setJobGroup("DEFAULT");
        db.setInvokeTarget("com.ruoyi.quartz.task.RyTask.ryParams('ry')");
        when(mapper.selectJobById(1L)).thenReturn(db);

        JobKey key = ScheduleUtils.getJobKey(1L, "DEFAULT");
        when(scheduler.checkExists(key)).thenReturn(true);

        // 攻击者持 changeStatus 权限，请求体塞入恶意 invokeTarget，应被完全忽略
        SysJob req = new SysJob();
        req.setJobId(1L);
        req.setJobGroup("DEFAULT");
        req.setInvokeTarget("java.lang.Runtime.exec('calc')");

        boolean result = service.run(req);
        assertTrue(result);

        ArgumentCaptor<JobDataMap> captor = ArgumentCaptor.forClass(JobDataMap.class);
        verify(scheduler).triggerJob(eq(key), captor.capture());
        SysJob used = (SysJob) captor.getValue().get(ScheduleConstants.TASK_PROPERTIES);
        assertEquals("com.ruoyi.quartz.task.RyTask.ryParams('ry')", used.getInvokeTarget());
    }
}
