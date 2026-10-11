package com.ruoyi.common.filter;

import java.io.IOException;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import com.ruoyi.common.utils.StringUtils;

/**
 * Repeatable 过滤器
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
public class RepeatableFilter implements Filter {
    /** JSON 请求体允许被包装读入内存的上限，超出直接拒绝，防止大 body 耗尽内存 */
    private static final long MAX_JSON_BODY_SIZE = 1024 * 1024L;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        ServletRequest requestWrapper = null;
        if (request instanceof HttpServletRequest
                && StringUtils.startsWithIgnoreCase(request.getContentType(), MediaType.APPLICATION_JSON_VALUE)) {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            // 包装会把整个请求体读入内存，过大将耗尽内存；Content-Length 已知且超限时直接拒绝——
            // 既防 OOM，又避免“超限就不包装”使 @RepeatSubmit + @RequestBody 无法二次读取而静默失败。
            // chunked（无 Content-Length，返回 -1）不在此拦，交由容器层限制
            if (httpRequest.getContentLengthLong() > MAX_JSON_BODY_SIZE) {
                ((HttpServletResponse) response).sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                        "请求体过大，已超过允许的最大 JSON 体积");
                return;
            }
            requestWrapper = new RepeatedlyRequestWrapper(httpRequest);
        }
        if (null == requestWrapper) {
            chain.doFilter(request, response);
        } else {
            chain.doFilter(requestWrapper, response);
        }
    }

    @Override
    public void destroy() {

    }
}
