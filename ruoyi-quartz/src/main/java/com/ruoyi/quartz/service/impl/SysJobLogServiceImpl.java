package com.ruoyi.quartz.service.impl;

import java.util.List;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.quartz.domain.SysJobLog;
import com.ruoyi.quartz.mapper.SysJobLogMapper;
import com.ruoyi.quartz.service.ISysJobLogService;

/**
 * 定时任务调度日志信息 服务层
 * 
 * @author ruoyi
 */
@Service
public class SysJobLogServiceImpl implements ISysJobLogService {
    /** 单次批量删除调度日志的最大条数上限：收口于 Service，覆盖所有调用方（含非 HTTP 入口） */
    private static final int MAX_DELETE_BATCH = 500;

    private final SysJobLogMapper jobLogMapper;

    public SysJobLogServiceImpl(SysJobLogMapper jobLogMapper) {
        this.jobLogMapper = jobLogMapper;
    }

    /**
     * 获取quartz调度器日志的计划任务
     * 
     * @param jobLog 调度日志信息
     * @return 调度任务日志集合
     */
    @Override
    public List<SysJobLog> selectJobLogList(SysJobLog jobLog) {
        return jobLogMapper.selectJobLogList(jobLog);
    }

    /**
     * 通过调度任务日志ID查询调度信息
     * 
     * @param jobLogId 调度任务日志ID
     * @return 调度任务日志对象信息
     */
    @Override
    public SysJobLog selectJobLogById(Long jobLogId) {
        return jobLogMapper.selectJobLogById(jobLogId);
    }

    /**
     * 新增任务日志
     * 
     * @param jobLog 调度日志信息
     */
    @Override
    public void addJobLog(SysJobLog jobLog) {
        jobLogMapper.insertJobLog(jobLog);
    }

    /**
     * 批量删除调度日志信息
     * 
     * @param logIds 需要删除的数据ID
     * @return 结果
     */
    @Override
    public int deleteJobLogByIds(Long[] logIds) {
        // 批量删除收口于 Service：空选与超上限均拒（抛 ServiceException 由全局异常处理转友好 error），与任务删除同一口径
        if (logIds == null || logIds.length == 0) {
            throw new ServiceException("请选择要删除的调度日志");
        }
        if (logIds.length > MAX_DELETE_BATCH) {
            throw new ServiceException("单次最多删除 " + MAX_DELETE_BATCH + " 条调度日志，当前 " + logIds.length + " 条");
        }
        return jobLogMapper.deleteJobLogByIds(logIds);
    }

    /**
     * 删除任务日志
     * 
     * @param jobLogId 调度日志ID
     */
    @Override
    public int deleteJobLogById(Long jobLogId) {
        return jobLogMapper.deleteJobLogById(jobLogId);
    }

    /**
     * 清空任务日志
     */
    @Override
    public void cleanJobLog() {
        jobLogMapper.cleanJobLog();
    }
}
