package com.ruoyi.system.service.impl;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.SysOperLog;
import com.ruoyi.system.mapper.SysOperLogMapper;
import com.ruoyi.system.service.ISysOperLogService;

/**
 * 操作日志 服务层处理
 * 
 * @author ruoyi
 */
@Service
public class SysOperLogServiceImpl implements ISysOperLogService {
    /**
     * 定期清理的单批删除条数：单批太大就是长事务，太小则往返次数多
     */
    private static final int CLEAN_BATCH_SIZE = 5000;

    private final SysOperLogMapper operLogMapper;

    public SysOperLogServiceImpl(SysOperLogMapper operLogMapper) {
        this.operLogMapper = operLogMapper;
    }

    /**
     * 新增操作日志
     * 
     * @param operLog 操作日志对象
     */
    @Override
    public void insertOperlog(SysOperLog operLog) {
        operLogMapper.insertOperlog(operLog);
    }

    /**
     * 查询系统操作日志集合
     * 
     * @param operLog 操作日志对象
     * @return 操作日志集合
     */
    @Override
    public List<SysOperLog> selectOperLogList(SysOperLog operLog) {
        return operLogMapper.selectOperLogList(operLog);
    }

    /**
     * 批量删除系统操作日志
     * 
     * @param operIds 需要删除的操作日志ID
     * @return 结果
     */
    @Override
    public int deleteOperLogByIds(Long[] operIds) {
        return operLogMapper.deleteOperLogByIds(operIds);
    }

    /**
     * 查询操作日志详细
     * 
     * @param operId 操作ID
     * @return 操作日志对象
     */
    @Override
    public SysOperLog selectOperLogById(Long operId) {
        return operLogMapper.selectOperLogById(operId);
    }

    /**
     * 清空操作日志
     * <p>
     * truncate 不返回影响行数，所以先 count 再清，把行数返回给调用方写进操作日志，
     * 事后才能回答"谁在什么时候清空了多少条"。
     */
    @Override
    public int cleanOperLog() {
        int count = operLogMapper.countOperLog();
        operLogMapper.cleanOperLog();
        return count;
    }

    /**
     * 按保留天数清理过期操作日志
     * <p>
     * 分批循环删除直到某一批返回 0：每批 5000 条、每批一个独立事务，
     * 不会出现"一条 SQL 删几十万行"带来的长事务、undo 暴涨与主从延迟。
     */
    @Override
    public int cleanOperLogBefore(int retentionDays) {
        if (retentionDays <= 0) {
            throw new ServiceException("操作日志保留天数必须大于 0，当前值：" + retentionDays);
        }
        Date expireTime = Date.from(LocalDateTime.now().minusDays(retentionDays).atZone(ZoneId.systemDefault())
                .toInstant());
        int total = 0;
        int batchDeleted;
        do {
            batchDeleted = operLogMapper.deleteOperLogBefore(expireTime, CLEAN_BATCH_SIZE);
            total += batchDeleted;
        } while (batchDeleted > 0);
        return total;
    }
}
