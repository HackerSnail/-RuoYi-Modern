package com.ruoyi.common.core.controller;

import java.beans.PropertyEditorSupport;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.page.PageDomain;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.core.page.TableSupport;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.PageUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.sql.SqlUtil;

/**
 * web层通用数据处理
 * 
 * @author ruoyi
 */
public class BaseController {
    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * 将前台传递过来的日期格式的字符串，自动转化为Date类型
     */
    @InitBinder
    public void initBinder(WebDataBinder binder) {
        // Date 类型转换
        binder.registerCustomEditor(Date.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                setValue(DateUtils.parseDate(text));
            }
        });
    }

    /**
     * 设置请求分页数据
     */
    protected void startPage() {
        PageUtils.startPage();
    }

    /**
     * 设置请求排序数据
     */
    protected void startOrderBy() {
        PageDomain pageDomain = TableSupport.buildPageRequest();
        if (StringUtils.isNotEmpty(pageDomain.getOrderBy())) {
            String orderBy = SqlUtil.escapeOrderBySql(pageDomain.getOrderBy());
            PageHelper.orderBy(orderBy);
        }
    }

    /**
     * 清理分页的线程变量
     */
    protected void clearPage() {
        PageUtils.clearPage();
    }

    /**
     * 导出场景的查询包装：给查询加 LIMIT（上限 + 1 条），先判数据量再决定要不要导出
     * <p>
     * 导出接口不分页（不调 {@code startPage()}），直接查会把全部匹配行一次性拉进 JVM 堆。
     * 这里借助 PageHelper 把 SQL 限制在 {@code ruoyi.exportMaxRows} + 1 条
     * （count=false，不额外查总数）：
     * <ul>
     *   <li>查回来不超过上限 → 正常导出；</li>
     *   <li>正好多出 1 条 → 说明超限，抛业务异常让前端提示"缩小查询条件"，
     *       而不是先把几百万行装进堆内存、到 ExcelUtil 里才发现超限。</li>
     * </ul>
     * 相比"分页导出"，这样改动最小、结果集仍然完整（上限内是全量数据）；
     * 若将来要支持超大数据量导出，再改成按页查、按页写 SXSSF 的流式导出。
     *
     * @param query 真实的列表查询，例如 {@code () -> operLogService.selectOperLogList(operLog)}
     * @return 查询结果，条数不超过配置的上限
     */
    protected <T> List<T> exportLimit(Supplier<List<T>> query) {
        int maxRows = RuoYiConfig.getExportMaxRows();
        PageHelper.startPage(1, maxRows + 1, false);
        try {
            List<T> list = query.get();
            if (list != null && list.size() > maxRows) {
                throw new ServiceException("导出数据量超过单次导出上限 " + maxRows + " 条，请缩小查询条件后重试");
            }
            return list == null ? new ArrayList<T>() : list;
        } finally {
            PageUtils.clearPage();
        }
    }

    /**
     * 响应请求分页数据
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    protected TableDataInfo getDataTable(List<?> list) {
        TableDataInfo rspData = new TableDataInfo();
        rspData.setCode(HttpStatus.SUCCESS);
        rspData.setMsg("查询成功");
        rspData.setRows(list);
        rspData.setTotal(new PageInfo(list).getTotal());
        return rspData;
    }

    /**
     * 返回成功
     */
    public AjaxResult success() {
        return AjaxResult.success();
    }

    /**
     * 返回失败消息
     */
    public AjaxResult error() {
        return AjaxResult.error();
    }

    /**
     * 返回成功消息
     */
    public AjaxResult success(String message) {
        return AjaxResult.success(message);
    }
    
    /**
     * 返回成功消息
     */
    public AjaxResult success(Object data) {
        return AjaxResult.success(data);
    }

    /**
     * 返回失败消息
     */
    public AjaxResult error(String message) {
        return AjaxResult.error(message);
    }

    /**
     * 返回警告消息
     */
    public AjaxResult warn(String message) {
        return AjaxResult.warn(message);
    }

    /**
     * 响应返回结果
     * 
     * @param rows 影响行数
     * @return 操作结果
     */
    protected AjaxResult toAjax(int rows) {
        return rows > 0 ? AjaxResult.success() : AjaxResult.error();
    }

    /**
     * 响应返回结果
     * 
     * @param result 结果
     * @return 操作结果
     */
    protected AjaxResult toAjax(boolean result) {
        return result ? success() : error();
    }

    /**
     * 页面跳转
     */
    public String redirect(String url) {
        return StringUtils.format("redirect:{}", url);
    }

    /**
     * 获取用户缓存信息
     */
    public LoginUser getLoginUser() {
        return SecurityUtils.getLoginUser();
    }

    /**
     * 获取登录用户id
     */
    public Long getUserId() {
        return getLoginUser().getUserId();
    }

    /**
     * 获取登录部门id
     */
    public Long getDeptId() {
        return getLoginUser().getDeptId();
    }

    /**
     * 获取登录用户名
     */
    public String getUsername() {
        return getLoginUser().getUsername();
    }
}
