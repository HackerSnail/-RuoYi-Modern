package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.SysLogininfor;
import com.ruoyi.system.mapper.SysLogininforMapper;
import com.ruoyi.system.service.ISysLogininforService;

/**
 * 系统访问日志情况信息 服务层处理
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
@Service
public class SysLogininforServiceImpl implements ISysLogininforService {

    private final SysLogininforMapper logininforMapper;

    public SysLogininforServiceImpl(SysLogininforMapper logininforMapper) {
        this.logininforMapper = logininforMapper;
    }

    /**
     * 新增系统登录日志
     * 
     * @param logininfor 访问日志对象
     */
    @Override
    public void insertLogininfor(SysLogininfor logininfor) {
        logininforMapper.insertLogininfor(logininfor);
    }

    /**
     * 查询系统登录日志集合
     * 
     * @param logininfor 访问日志对象
     * @return 登录记录集合
     */
    @Override
    public List<SysLogininfor> selectLogininforList(SysLogininfor logininfor) {
        return logininforMapper.selectLogininforList(logininfor);
    }

    /**
     * 批量删除系统登录日志
     * 
     * @param infoIds 需要删除的登录日志ID
     * @return 结果
     */
    @Override
    public int deleteLogininforByIds(Long[] infoIds) {
        if (infoIds == null || infoIds.length == 0) {
            throw new ServiceException("删除的登录日志 ID 不能为空");
        }
        int rows = logininforMapper.deleteLogininforByIds(infoIds);
        if (rows == 0) {
            throw new ServiceException("删除失败，所选登录日志可能已被清空");
        }
        return rows;
    }

    /**
     * 清空系统登录日志
     * <p>
     * truncate 是 DDL（隐式提交、不返回影响行数），且与 count 之间无法用事务保证原子；
     * 并发写入会让精确计数不可信。审计的关键是"谁在何时执行了清空"，条数仅为参考，
     * 故改用 information_schema 的行数估算并明确标注为近似值，不再冒充精确计数。
     *
     * @return 清空前的约行数（估算值，可能存在 ±偏差）
     */
    @Override
    public int cleanLogininfor() {
        Integer approx = logininforMapper.estimateLogininforRows();
        logininforMapper.cleanLogininfor();
        return approx == null ? 0 : approx;
    }

    /**
     * 归档清理：删除早于保留天数的登录日志（由 Quartz 定期任务调用）。
     *
     * @param retentionDays 保留天数，必须大于 0
     * @return 删除行数
     */
    @Override
    public int cleanLogininforBefore(int retentionDays) {
        if (retentionDays <= 0) {
            throw new ServiceException("登录日志保留天数必须大于 0，当前值：" + retentionDays);
        }
        return logininforMapper.deleteLogininforBefore(retentionDays);
    }
}
