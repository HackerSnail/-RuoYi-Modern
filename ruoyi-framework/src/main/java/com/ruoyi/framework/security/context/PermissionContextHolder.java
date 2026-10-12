package com.ruoyi.framework.security.context;

import java.util.Objects;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 权限信息
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
public class PermissionContextHolder {
    /**
     * 命名空间前缀避免与共享 request attribute 空间里其它属性撞名（SCOPE_REQUEST 直委托到
     * request.setAttribute，并非隔离内部 map）
     */
    private static final String PERMISSION_CONTEXT_ATTRIBUTES = "ruoyi.permission.context";

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
        Object value = RequestContextHolder.currentRequestAttributes()
                .getAttribute(PERMISSION_CONTEXT_ATTRIBUTES, RequestAttributes.SCOPE_REQUEST);
        return Objects.toString(value, null);
    }
}
