package com.ruoyi.system.mapper;

import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.SysOperLog;

/**
 * 操作日志 数据层
 * 
 * @author ruoyi
 */
public interface SysOperLogMapper {
    /**
     * 新增操作日志
     * 
     * @param operLog 操作日志对象
     */
    public void insertOperlog(SysOperLog operLog);

    /**
     * 查询系统操作日志集合
     * 
     * @param operLog 操作日志对象
     * @return 操作日志集合
     */
    public List<SysOperLog> selectOperLogList(SysOperLog operLog);

    /**
     * 批量删除系统操作日志
     * 
     * @param operIds 需要删除的操作日志ID
     * @return 结果
     */
    public int deleteOperLogByIds(Long[] operIds);

    /**
     * 查询操作日志详细
     * 
     * @param operId 操作ID
     * @return 操作日志对象
     */
    public SysOperLog selectOperLogById(Long operId);

    /**
     * 统计操作日志总行数（清空前调用，用于审计）
     * 
     * @return 当前行数
     */
    public int countOperLog();

    /**
     * 按时间分批删除过期操作日志
     * <p>
     * 走 idx_sys_oper_log_ot 索引，每批最多删 limit 条：避免一条 DELETE 干掉几十万行导致
     * 长事务、undo 暴涨、主从延迟。返回本批实际删除条数，调用方循环到返回 0 为止。
     * 
     * @param expireTime 过期时间点，早于该时间的记录会被删除
     * @param limit      单批上限
     * @return 本批删除条数
     */
    public int deleteOperLogBefore(@Param("expireTime") Date expireTime, @Param("limit") int limit);

    /**
     * 清空操作日志
     */
    public void cleanOperLog();
}
