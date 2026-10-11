package com.ruoyi.framework.security.handle;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.StringUtils;

/**
 * 认证失败处理类 返回未授权
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
@Component
public class AuthenticationEntryPointImpl implements AuthenticationEntryPoint, Serializable {
    private static final long serialVersionUID = -8970718410437077606L;

    /**
     * 未登录/无凭证访问受保护资源时触发：以 HTTP 401 传输状态 + JSON 错误体返回（而非重定向到登录页）。
     * 本端点刻意发出真实 401（区别于经 renderString 恒返 HTTP200 的其它错误）；body 内仍带 code=401
     * 以兼容按响应体判鉴权的客户端。
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        int code = HttpStatus.UNAUTHORIZED;
        String msg = StringUtils.format("请求访问：{}，认证失败，无法访问系统资源", request.getRequestURI());
        String body = JSON.toJSONString(AjaxResult.error(code, msg));
        // 不经 renderString（其内部强制 setStatus(200) 会把 401 覆写），直接写传输层 401 + application/json;charset=UTF-8
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().print(body);
    }
}
