package com.ruoyi.common.utils;

import com.github.pagehelper.PageHelper;
import com.ruoyi.common.core.page.PageDomain;
import com.ruoyi.common.core.page.TableSupport;
import com.ruoyi.common.utils.sql.SqlUtil;

/**
 * 分页工具类
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
public final class PageUtils {
    private PageUtils() {
    }

    /**
     * 设置请求分页数据
     */
    public static void startPage() {
        // 进入时先清一次，防御同一请求线程内上一次未被消费的 startPage 残留；
        // 跨请求的 ThreadLocal 泄漏已由 PageCleanInterceptor 在 afterCompletion 无条件兜底。
        PageHelper.clearPage();
        PageDomain pageDomain = TableSupport.buildPageRequest();
        Integer pageNum = pageDomain.getPageNum();
        Integer pageSize = pageDomain.getPageSize();
        String orderBy = SqlUtil.escapeOrderBySql(pageDomain.getOrderBy());
        Boolean reasonable = pageDomain.getReasonable();
        PageHelper.startPage(pageNum, pageSize, orderBy).setReasonable(reasonable);
    }

    /**
     * 清理分页的线程变量
     */
    public static void clearPage() {
        PageHelper.clearPage();
    }
}
