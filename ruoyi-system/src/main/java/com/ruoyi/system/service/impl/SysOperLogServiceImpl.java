package com.ruoyi.system.service.impl;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(SysOperLogServiceImpl.class);

    /**
     * 定期清理的单批删除条数：单批太大就是长事务，太小则往返次数多
     */
    private static final int CLEAN_BATCH_SIZE = 5000;

    /**
     * 分批删除的批间休眠毫秒：避免循环持续吃满主库 IO 影响在线业务
     */
    private static final long CLEAN_BATCH_SLEEP_MILLIS = 100L;

    /**
     * 单轮删除总量上限：历史数据异常膨胀时不让单轮无休止跑下去，超限中断交由下一轮定时任务继续
     */
    private static final int CLEAN_MAX_TOTAL = 1_000_000;

    /**
     * 批量删除日志的单次上限：作为审计证据表的政策护栏，限制一次可擦除的量（拦误操作/批量滥量）
     */
    private static final int DELETE_MAX_BATCH = 500;

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
        try {
            operLogMapper.insertOperlog(operLog);
        } catch (Exception e) {
            // 唯一调用方 AsyncFactory.recordOper 经 AsyncManager.schedule 投递，schedule 返回的 Future 被丢弃，
            // 若此处不接住，落库异常(如 DataAccessException)会被静默吞进 Future——审计证据丢失且无人知。
            // 降级：把关键定位字段连同异常打进应用日志(不受 truncate 影响、天然持久)，至少让“丢了什么”可追溯。
            log.error("【审计降级】操作日志落库失败，已转应用日志留痕：title={}, businessType={}, operName={}, operIp={}, requestMethod={}, operTime={}",
                    operLog.getTitle(), operLog.getBusinessType(), operLog.getOperName(), operLog.getOperIp(),
                    operLog.getRequestMethod(), operLog.getOperTime(), e);
        }
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
        // 空/null 入参入口即拒，避免 foreach 生成 IN () 语法错误
        if (operIds == null || operIds.length == 0) {
            throw new ServiceException("删除的操作日志 ID 不能为空");
        }
        // 审计证据表的批量上限护栏：限制单次可删除的量，超出拒绝
        if (operIds.length > DELETE_MAX_BATCH) {
            throw new ServiceException("单次删除操作日志不得超过 " + DELETE_MAX_BATCH + " 条，当前 " + operIds.length + " 条");
        }
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
            // 总量上限：历史异常膨胀时不让单轮无休止跑下去持续吃 IO，剩余交下一轮
            if (total >= CLEAN_MAX_TOTAL) {
                log.warn("操作日志定期清理达单轮上限 {} 条，提前结束，剩余交由下一轮定时任务继续", CLEAN_MAX_TOTAL);
                break;
            }
            if (batchDeleted > 0) {
                try {
                    // 批间让出 IO，避免连续删除持续吃满主库影响在线业务
                    Thread.sleep(CLEAN_BATCH_SLEEP_MILLIS);
                } catch (InterruptedException ie) {
                    // 恢复中断标志并结束本轮（下次调度继续），不吞中断
                    Thread.currentThread().interrupt();
                    log.warn("操作日志定期清理被中断，已删除 {} 条后提前结束", total);
                    break;
                }
            }
        } while (batchDeleted > 0);
        return total;
    }
}
