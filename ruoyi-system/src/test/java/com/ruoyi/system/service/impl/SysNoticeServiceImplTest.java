package com.ruoyi.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.SysNotice;
import com.ruoyi.system.mapper.SysNoticeMapper;
import com.ruoyi.system.service.ISysNoticeReadService;

/**
 * {@link SysNoticeServiceImpl} 单元测试：覆盖本轮三条主线——
 * updateNotice 存在性回查（建1）、deleteNoticeByIds 空守卫（建2）、级联清理与删本体同事务的编排顺序（必3）。
 * 纯 mock mapper/已读服务，无需 Spring/Redis；@Transactional 的原子性由集成启动与框架保证，此处验编排逻辑。
 *
 * @author ruoyi
 */
class SysNoticeServiceImplTest {

    private SysNoticeMapper noticeMapper;

    private ISysNoticeReadService noticeReadService;

    private SysNoticeServiceImpl service;

    @BeforeEach
    void setUp() {
        noticeMapper = mock(SysNoticeMapper.class);
        noticeReadService = mock(ISysNoticeReadService.class);
        service = new SysNoticeServiceImpl(noticeMapper, noticeReadService);
    }

    @Test
    @DisplayName("deleteNoticeByIds：null 入参被拒，不触任何删除")
    void deleteRejectsNullAndSkipsEverything() {
        assertThrows(ServiceException.class, () -> service.deleteNoticeByIds(null));
        verify(noticeMapper, never()).deleteNoticeByIds(any());
        verify(noticeReadService, never()).deleteByNoticeIds(any());
    }

    @Test
    @DisplayName("deleteNoticeByIds：空数组被拒，避免 foreach 生成 IN ()")
    void deleteRejectsEmptyArray() {
        assertThrows(ServiceException.class, () -> service.deleteNoticeByIds(new Long[0]));
        verify(noticeMapper, never()).deleteNoticeByIds(any());
        verify(noticeReadService, never()).deleteByNoticeIds(any());
    }

    @Test
    @DisplayName("deleteNoticeByIds：先级联清理已读，再删公告本体（同方法内顺序）")
    void deleteCascadesReadBeforeBody() {
        Long[] ids = new Long[] { 1L, 2L };
        when(noticeMapper.deleteNoticeByIds(ids)).thenReturn(2);

        int rows = service.deleteNoticeByIds(ids);

        assertEquals(2, rows);
        InOrder ordered = inOrder(noticeReadService, noticeMapper);
        ordered.verify(noticeReadService).deleteByNoticeIds(ids);
        ordered.verify(noticeMapper).deleteNoticeByIds(ids);
    }

    @Test
    @DisplayName("updateNotice：目标不存在（物理删表回查 null）抛业务异常，不落 UPDATE")
    void updateThrowsWhenNoticeMissing() {
        SysNotice notice = new SysNotice();
        notice.setNoticeId(99L);
        when(noticeMapper.selectNoticeById(99L)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.updateNotice(notice));
        verify(noticeMapper, never()).updateNotice(any());
    }

    @Test
    @DisplayName("updateNotice：目标存在则委托 mapper 更新")
    void updateProceedsWhenNoticeExists() {
        SysNotice notice = new SysNotice();
        notice.setNoticeId(5L);
        when(noticeMapper.selectNoticeById(5L)).thenReturn(new SysNotice());
        when(noticeMapper.updateNotice(notice)).thenReturn(1);

        assertEquals(1, service.updateNotice(notice));
        verify(noticeMapper).updateNotice(notice);
    }
}
