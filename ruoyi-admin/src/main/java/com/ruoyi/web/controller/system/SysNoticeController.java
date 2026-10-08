package com.ruoyi.web.controller.system;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.SysNotice;
import com.ruoyi.system.service.ISysNoticeReadService;
import com.ruoyi.system.service.ISysNoticeService;

/**
 * 公告 信息操作处理
 * 
 * @author ruoyi
 */
@RestController
@RequestMapping("/system/notice")
public class SysNoticeController extends BaseController {
    /** 首页顶栏公告默认展示条数 */
    private static final int TOP_NOTICE_DEFAULT = 5;

    /** 首页顶栏公告可请求的最大条数，防止前端传入过大值拖垮查询 */
    private static final int TOP_NOTICE_MAX = 50;

    private final ISysNoticeService noticeService;

    private final ISysNoticeReadService noticeReadService;

    public SysNoticeController(ISysNoticeService noticeService, ISysNoticeReadService noticeReadService) {
        this.noticeService = noticeService;
        this.noticeReadService = noticeReadService;
    }

    /**
     * 获取通知公告列表
     */
    @PreAuthorize("@ss.hasPermi('system:notice:list')")
    @GetMapping("/list")
    public TableDataInfo list(SysNotice notice) {
        startPage();
        List<SysNotice> list = noticeService.selectNoticeList(notice);
        return getDataTable(list);
    }

    /**
     * 根据通知公告编号获取详细信息
     */
    @GetMapping(value = "/{noticeId}")
    public AjaxResult getInfo(@PathVariable Long noticeId) {
        return success(noticeService.selectNoticeById(noticeId));
    }

    /**
     * 新增通知公告
     */
    @PreAuthorize("@ss.hasPermi('system:notice:add')")
    @Log(title = "通知公告", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@Validated @RequestBody SysNotice notice) {
        notice.setCreateBy(getUsername());
        return toAjax(noticeService.insertNotice(notice));
    }

    /**
     * 修改通知公告
     */
    @PreAuthorize("@ss.hasPermi('system:notice:edit')")
    @Log(title = "通知公告", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@Validated @RequestBody SysNotice notice) {
        notice.setUpdateBy(getUsername());
        return toAjax(noticeService.updateNotice(notice));
    }

    /**
     * 重新推送公告：清空该公告的全部已读记录，使所有用户在顶栏重新看到未读标记
     * <p>
     * 作为独立的显式操作，不放在 edit 内自动触发——仅改标题/错别字不应抹掉全员已读进度；
     * 只有公告内容确有重要更新、需要全员重读时，由管理员主动调用本接口。
     */
    @PreAuthorize("@ss.hasPermi('system:notice:edit')")
    @Log(title = "公告重新推送", businessType = BusinessType.UPDATE)
    @PutMapping("/repush/{noticeId}")
    public AjaxResult repush(@PathVariable Long noticeId) {
        // 复用按公告ID清理已读记录：删除后列表查询的 not exists/LEFT JOIN 判定即回到未读
        noticeReadService.deleteByNoticeIds(new Long[] { noticeId });
        return success();
    }

    /**
     * 首页顶部公告列表（带当前用户已读标记；默认最多 5 条，可由 size 参数调整，上限 50）
     */
    @GetMapping("/listTop")
    @ResponseBody
    public AjaxResult listTop(Integer size) {
        // 顶栏默认展示 TOP_NOTICE_DEFAULT 条；允许前端传 size 自定义，但夹到 [1, TOP_NOTICE_MAX] 防止超大值
        int limit = (size == null || size <= 0) ? TOP_NOTICE_DEFAULT : Math.min(size, TOP_NOTICE_MAX);
        Long userId = getUserId();
        List<SysNotice> list = noticeReadService.selectNoticeListWithReadStatus(userId, limit);
        // 统计当前用户未读条数，供顶栏小红点展示
        long unreadCount = list.stream().filter(n -> !n.getIsRead()).count();
        AjaxResult result = AjaxResult.success(list);
        result.put("unreadCount", unreadCount);
        return result;
    }

    /**
     * 标记公告已读
     */
    @PostMapping("/markRead")
    @ResponseBody
    public AjaxResult markRead(Long noticeId) {
        Long userId = getUserId();
        noticeReadService.markRead(noticeId, userId);
        return success();
    }

    /**
     * 批量标记已读
     */
    @PostMapping("/markReadAll")
    @ResponseBody
    public AjaxResult markReadAll(String ids) {
        Long userId = getUserId();
        Long[] noticeIds = Convert.toLongArray(ids);
        noticeReadService.markReadBatch(userId, noticeIds);
        return success();
    }

    /**
     * 已读用户列表数据
     */
    @PreAuthorize("@ss.hasPermi('system:notice:list')")
    @GetMapping("/readUsers/list")
    @ResponseBody
    public TableDataInfo readUsersList(Long noticeId, String searchValue) {
        startPage();
        List<?> list = noticeReadService.selectReadUsersByNoticeId(noticeId, searchValue);
        return getDataTable(list);
    }

    /**
     * 未读用户列表（催读：查某公告尚未阅读的用户，供管理侧发起提醒）
     * <p>
     * 与 readUsersList 对称：已读=命中已读表；未读=启用且未删除的用户中不存在该公告已读记录者。
     * 注：本接口仅负责查出未读用户；实际催读推送（站内信/企业微信等）依赖推送通道，另行接入。
     */
    @PreAuthorize("@ss.hasPermi('system:notice:list')")
    @GetMapping("/unreadUsers/list")
    @ResponseBody
    public TableDataInfo unreadUsersList(Long noticeId, String searchValue) {
        startPage();
        List<?> list = noticeReadService.selectUnreadUsersByNoticeId(noticeId, searchValue);
        return getDataTable(list);
    }

    /**
     * 删除通知公告
     */
    @PreAuthorize("@ss.hasPermi('system:notice:remove')")
    @Log(title = "通知公告", businessType = BusinessType.DELETE)
    @DeleteMapping("/{noticeIds}")
    public AjaxResult remove(@PathVariable Long[] noticeIds) {
        // 先清掉关联的已读记录，再删公告本体，避免遗留孤儿已读数据
        noticeReadService.deleteByNoticeIds(noticeIds);
        return toAjax(noticeService.deleteNoticeByIds(noticeIds));
    }
}
