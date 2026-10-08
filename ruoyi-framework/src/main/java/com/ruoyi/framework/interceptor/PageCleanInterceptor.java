package com.ruoyi.framework.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import com.ruoyi.common.utils.PageUtils;

/**
 * 请求结束时清理 PageHelper 的线程变量
 * <p>
 * 背景：PageHelper 把分页参数放在 ThreadLocal（LOCAL_PAGE）里，只有真正执行了被拦截的
 * MyBatis 查询后才会自动清理。只要 {@code startPage()} 之后没有紧跟一次查询——例如
 * 参数校验提前 return、中途抛异常、走分支跳过了 mapper 调用——这个线程变量就会残留在
 * Tomcat 的工作线程上。线程归还线程池后被下一个请求复用，于是出现两类诡异现象：
 * <ul>
 *   <li>没调分页的接口只查出 10 条（继承了上一条的 pageSize）；</li>
 *   <li>分页结果莫名其妙被截断/错位（继承了上一条的 pageNum）。</li>
 * </ul>
 * 问题偶发、与并发量正相关、本地难复现，靠业务代码里手动调
 * {@code BaseController#clearPage()} 兜底很容易漏（全仓此前一个调用点都没有），
 * 因此统一放在拦截器的 afterCompletion 里无条件清理。
 * <p>
 * 覆盖面说明：{@code startPage()} 只在 Controller 里调用，能创建残留的请求必然经过
 * DispatcherServlet，而 afterCompletion 对"preHandle 已通过的拦截器"无论 handler 正常
 * 返回还是抛异常都会执行，故 Controller 请求 100% 覆盖。鉴权失败、404、静态资源
 * 不会经过 Controller，也就不会产生残留，无需覆盖。
 * <p>
 * 注册顺序：在 {@code ResourcesConfig#addInterceptors} 里显式 {@code order(HIGHEST_PRECEDENCE)}
 * 并放在首位注册，保证本拦截器先于其它拦截器执行 preHandle，这样即使后续拦截器
 * （如 RepeatSubmitInterceptor）在 preHandle 中直接中断请求，
 * afterCompletion 仍会回调到本拦截器。
 *
 * @author ruoyi
 */
@Component
public class PageCleanInterceptor implements HandlerInterceptor {
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        PageUtils.clearPage();
    }
}
