package com.ruoyi.framework.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import com.ruoyi.common.utils.ServletUtils;

/**
 * 服务相关配置
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
@Component
public class ServerConfig {
    /**
     * 获取完整的请求路径，包括：域名，端口，上下文访问路径
     * 
     * @return 服务地址
     */
    public String getUrl() {
        HttpServletRequest request = ServletUtils.getRequest();
        return getDomain(request);
    }

    /**
     * 从当前请求推导出服务访问根地址（内部辅助）：截掉 URL 中的请求 URI 部分，再拼上上下文路径。
     * 仅由 getUrl() 调用，改为 private 实例方法以消除静态工具方法暴露与 HttpServletRequest 的隐式全局传播。
     */
    private String getDomain(HttpServletRequest request) {
        StringBuffer url = request.getRequestURL();
        String contextPath = request.getServletContext().getContextPath();
        return url.delete(url.length() - request.getRequestURI().length(), url.length()).append(contextPath).toString();
    }
}
