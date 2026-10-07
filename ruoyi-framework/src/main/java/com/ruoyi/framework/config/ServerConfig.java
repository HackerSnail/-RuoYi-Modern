package com.ruoyi.framework.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import com.ruoyi.common.utils.ServletUtils;

/**
 * 服务相关配置
 * 
 * @author ruoyi
 */
@Component
public class ServerConfig
{
    /**
     * 获取完整的请求路径，包括：域名，端口，上下文访问路径
     * 
     * @return 服务地址
     */
    public String getUrl()
    {
        HttpServletRequest request = ServletUtils.getRequest();
        return getDomain(request);
    }

    /**
     * 从当前请求推导出服务访问根地址：截掉 URL 中的请求 URI 部分，再拼上上下文路径
     */
    public static String getDomain(HttpServletRequest request)
    {
        StringBuffer url = request.getRequestURL();
        String contextPath = request.getServletContext().getContextPath();
        return url.delete(url.length() - request.getRequestURI().length(), url.length()).append(contextPath).toString();
    }
}
