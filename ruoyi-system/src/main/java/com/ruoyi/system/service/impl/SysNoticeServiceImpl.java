package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.SysNotice;
import com.ruoyi.system.mapper.SysNoticeMapper;
import com.ruoyi.system.service.ISysNoticeReadService;
import com.ruoyi.system.service.ISysNoticeService;

/**
 * 公告 服务层实现
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
@Service
public class SysNoticeServiceImpl implements ISysNoticeService {
    private final SysNoticeMapper noticeMapper;

    private final ISysNoticeReadService noticeReadService;

    public SysNoticeServiceImpl(SysNoticeMapper noticeMapper, ISysNoticeReadService noticeReadService) {
        this.noticeMapper = noticeMapper;
        this.noticeReadService = noticeReadService;
    }

    /**
     * 查询公告信息
     * 
     * @param noticeId 公告ID
     * @return 公告信息
     */
    @Override
    public SysNotice selectNoticeById(Long noticeId) {
        return noticeMapper.selectNoticeById(noticeId);
    }

    /**
     * 查询公告列表
     * 
     * @param notice 公告信息
     * @return 公告集合
     */
    @Override
    public List<SysNotice> selectNoticeList(SysNotice notice) {
        return noticeMapper.selectNoticeList(notice);
    }

    /**
     * 新增公告
     * 
     * @param notice 公告信息
     * @return 结果
     */
    @Override
    public int insertNotice(SysNotice notice) {
        return noticeMapper.insertNotice(notice);
    }

    /**
     * 修改公告
     * 
     * @param notice 公告信息
     * @return 结果
     */
    @Override
    public int updateNotice(SysNotice notice) {
        // 物理删表 + 无所有权模型：仅回查存在性，把“改了不存在的公告”从静默 rows=0 收敛为明确业务异常
        SysNotice exist = noticeMapper.selectNoticeById(notice.getNoticeId());
        if (StringUtils.isNull(exist)) {
            throw new ServiceException("公告不存在或已被删除，noticeId=" + notice.getNoticeId());
        }
        return noticeMapper.updateNotice(notice);
    }

    /**
     * 批量删除公告信息：级联清理已读记录与删本体置于同一事务，避免“清了已读、删本体失败”导致公告残留却全员回到未读
     * 
     * @param noticeIds 需要删除的公告ID
     * @return 结果
     */
    @Override
    @Transactional
    public int deleteNoticeByIds(Long[] noticeIds) {
        // 空/null 入参在入口即拒，避免 foreach 生成 IN () 语法错误
        if (StringUtils.isNull(noticeIds) || noticeIds.length == 0) {
            throw new ServiceException("删除的公告 ID 不能为空");
        }
        // 先级联清理 sys_notice_read 已读记录，再删公告本体，同事务原子提交（呼应第10轮配套表）
        noticeReadService.deleteByNoticeIds(noticeIds);
        return noticeMapper.deleteNoticeByIds(noticeIds);
    }
}
