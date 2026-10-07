package com.ruoyi.framework.security.context;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import com.ruoyi.common.core.text.Convert;

/**
 * 权限信息
 * 
 * @author ruoyi
 */
public class PermissionContextHolder {
    private static final String PERMISSION_CONTEXT_ATTRIBUTES = "PERMISSION_CONTEXT";

    /**
     * 将当前正在校验的权限字符存入 request 作用域，供鉴权失败时回显“缺少哪个权限”
     */
    public static void setContext(String permission) {
        RequestContextHolder.currentRequestAttributes().setAttribute(PERMISSION_CONTEXT_ATTRIBUTES, permission,
                RequestAttributes.SCOPE_REQUEST);
    }

    /**
     * 从 request 作用域读回当前权限字符
     */
    public static String getContext() {
        return Convert.toStr(RequestContextHolder.currentRequestAttributes().getAttribute(PERMISSION_CONTEXT_ATTRIBUTES,
                RequestAttributes.SCOPE_REQUEST));
    }
}
