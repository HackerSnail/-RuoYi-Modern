package com.ruoyi.quartz.service;

import java.util.List;
import org.quartz.SchedulerException;
import com.ruoyi.common.exception.job.TaskException;
import com.ruoyi.quartz.domain.SysJob;

/**
 * 定时任务调度信息 服务层
 * <p>
 * 写类方法（增删改、暂停/恢复）遵循“先更新 DB，成功后同步 Quartz 调度器”的双写契约，并置于
 * {@code @Transactional} 中：DB 未写成功则 Scheduler 不动；Scheduler 同步失败则 DB 回滚，
 * 最终与 sys_job 表保持一致。{@code run()} 为只读+触发（仅从库重载并触发已注册任务、不写 DB），不参与该双写契约。
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
public interface ISysJobService {
    /**
     * 获取quartz调度器的计划任务
     * 
     * @param job 调度信息
     * @return 调度任务集合
     */
    public List<SysJob> selectJobList(SysJob job);

    /**
     * 通过调度任务ID查询调度信息
     * 
     * @param jobId 调度任务ID
     * @return 调度任务对象信息
     */
    public SysJob selectJobById(Long jobId);

    /**
     * 暂停任务
     * 
     * @param job 调度信息
     * @return 结果
     */
    public int pauseJob(SysJob job) throws SchedulerException;

    /**
     * 恢复任务
     * 
     * @param job 调度信息
     * @return 结果
     */
    public int resumeJob(SysJob job) throws SchedulerException;

    /**
     * 删除任务后，所对应的trigger也将被删除
     * 
     * @param job 调度信息
     * @return 结果
     */
    public int deleteJob(SysJob job) throws SchedulerException;

    /**
     * 批量删除调度信息
     * 
     * @param jobIds 需要删除的任务ID（单次不超过 500 条）
     * @return 实际删除的行数（库中不存在的 ID 被跳过、不计入）
     * @throws com.ruoyi.common.exception.ServiceException 空选或单次超过 500 条上限
     */
    public int deleteJobByIds(Long[] jobIds) throws SchedulerException;

    /**
     * 任务调度状态修改
     * 
     * @param job 调度信息
     * @return 结果
     */
    public int changeStatus(SysJob job) throws SchedulerException;

    /**
     * 立即运行任务（一次性触发，不改变原 cron 调度计划）
     * 
     * @param job 调度信息
     * @return 任务存在且成功触发返回 true，不存在或已过期返回 false
     */
    public boolean run(SysJob job) throws SchedulerException;

    /**
     * 新增任务
     * 
     * @param job 调度信息
     * @return 结果
     */
    public int insertJob(SysJob job) throws SchedulerException, TaskException;

    /**
     * 更新任务
     * 
     * @param job 调度信息
     * @return 结果
     */
    public int updateJob(SysJob job) throws SchedulerException, TaskException;

    /**
     * 校验cron表达式是否有效
     * 
     * @param cronExpression 表达式
     * @return 结果
     */
    public boolean checkCronExpressionIsValid(String cronExpression);
}
