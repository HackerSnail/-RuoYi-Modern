package com.ruoyi.quartz.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.JobDataMap;
import org.quartz.JobKey;
import org.quartz.Scheduler;

import com.ruoyi.common.constant.ScheduleConstants;
import com.ruoyi.common.exception.ServiceException;
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
 *
 * <p>
 * 另覆盖本轮从 Controller 下沉的 deleteJobByIds 批量删除收口：空选与超 500 上限均抛
 * ServiceException 且不触碰 mapper，限长内正常逐个删除。
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
        db.setInvokeTarget("com.ruoyi.quartz.task.OperLogCleanTask.clean('90')");
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
        assertEquals("com.ruoyi.quartz.task.OperLogCleanTask.clean('90')", used.getInvokeTarget());
    }

    @Test
    @DisplayName("deleteJobByIds：空选抛 ServiceException，不触碰 mapper")
    void deleteJobByIdsEmptyRejected() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        SysJobMapper mapper = mock(SysJobMapper.class);
        SysJobServiceImpl service = new SysJobServiceImpl(scheduler, mapper);

        assertThrows(ServiceException.class, () -> service.deleteJobByIds(new Long[0]));
        verify(mapper, never()).selectJobById(any());
    }

    @Test
    @DisplayName("deleteJobByIds：超 500 上限抛 ServiceException，不触碰 mapper")
    void deleteJobByIdsOverLimitRejected() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        SysJobMapper mapper = mock(SysJobMapper.class);
        SysJobServiceImpl service = new SysJobServiceImpl(scheduler, mapper);

        Long[] ids = new Long[501];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = (long) i;
        }
        assertThrows(ServiceException.class, () -> service.deleteJobByIds(ids));
        verify(mapper, never()).selectJobById(any());
    }

    @Test
    @DisplayName("deleteJobByIds：限长内正常逐个删除")
    void deleteJobByIdsWithinLimit() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        SysJobMapper mapper = mock(SysJobMapper.class);
        SysJobServiceImpl service = new SysJobServiceImpl(scheduler, mapper);

        SysJob job = new SysJob();
        job.setJobId(1L);
        job.setJobGroup("DEFAULT");
        when(mapper.selectJobById(1L)).thenReturn(job);
        when(mapper.deleteJobById(1L)).thenReturn(1);

        service.deleteJobByIds(new Long[] { 1L });
        verify(mapper).deleteJobById(1L);
        verify(scheduler).deleteJob(ScheduleUtils.getJobKey(1L, "DEFAULT"));
    }

    @Test
    @DisplayName("deleteJobByIds：返回实际删除行数")
    void deleteJobByIdsReturnsAffectedRows() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        SysJobMapper mapper = mock(SysJobMapper.class);
        SysJobServiceImpl service = new SysJobServiceImpl(scheduler, mapper);

        SysJob a = new SysJob();
        a.setJobId(1L);
        a.setJobGroup("DEFAULT");
        SysJob b = new SysJob();
        b.setJobId(2L);
        b.setJobGroup("DEFAULT");
        when(mapper.selectJobById(1L)).thenReturn(a);
        when(mapper.selectJobById(2L)).thenReturn(b);
        when(mapper.deleteJobById(1L)).thenReturn(1);
        when(mapper.deleteJobById(2L)).thenReturn(1);

        assertEquals(2, service.deleteJobByIds(new Long[] { 1L, 2L }));
    }

    @Test
    @DisplayName("deleteJobByIds：库中不存在的 ID 安全跳过，不 NPE、不整批失败")
    void deleteJobByIdsSkipsMissing() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        SysJobMapper mapper = mock(SysJobMapper.class);
        SysJobServiceImpl service = new SysJobServiceImpl(scheduler, mapper);

        SysJob existing = new SysJob();
        existing.setJobId(2L);
        existing.setJobGroup("DEFAULT");
        // 1L 已不在库中（陈旧 ID / 并发删除），selectJobById 返 null
        when(mapper.selectJobById(1L)).thenReturn(null);
        when(mapper.selectJobById(2L)).thenReturn(existing);
        when(mapper.deleteJobById(2L)).thenReturn(1);

        int rows = service.deleteJobByIds(new Long[] { 1L, 2L });
        // 陈旧 ID 被跳过、不计入影响行数
        assertEquals(1, rows);
        verify(mapper, never()).deleteJobById(1L);
        verify(mapper).deleteJobById(2L);
        verify(scheduler).deleteJob(ScheduleUtils.getJobKey(2L, "DEFAULT"));
    }
}
