package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.SysLogininfor;

/**
 * 系统访问日志情况信息 数据层
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
public interface SysLogininforMapper {
    /**
     * 新增系统登录日志
     * 
     * @param logininfor 访问日志对象
     */
    public void insertLogininfor(SysLogininfor logininfor);

    /**
     * 查询系统登录日志集合
     * 
     * @param logininfor 访问日志对象
     * @return 登录记录集合
     */
    public List<SysLogininfor> selectLogininforList(SysLogininfor logininfor);

    /**
     * 批量删除系统登录日志
     * 
     * @param infoIds 需要删除的登录日志ID
     * @return 结果
     */
    public int deleteLogininforByIds(Long[] infoIds);

    /**
     * 估算登录日志当前行数（来自 information_schema.TABLES 的统计值，非精确计数，供清空前审计参考）
     * 
     * @return 约行数；无统计时可能为 null
     */
    public Integer estimateLogininforRows();

    /**
     * 删除早于保留天数的登录日志（按天归档，login_time 早于 sysdate()-days）
     * 
     * @param retentionDays 保留天数
     * @return 删除行数
     */
    public int deleteLogininforBefore(@Param("retentionDays") int retentionDays);

    /**
     * 清空系统登录日志
     * 
     * @return 结果
     */
    public int cleanLogininfor();
}
