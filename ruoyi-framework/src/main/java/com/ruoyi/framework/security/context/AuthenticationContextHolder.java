package com.ruoyi.framework.security.context;

import org.springframework.security.core.Authentication;

/**
 * 身份验证信息
 * 
 * @author ruoyi
 */
public class AuthenticationContextHolder
{
    /**
     * 以 ThreadLocal 持有当前认证对象，供认证过程中（如密码校验失败计数）跨方法传递，
     * 使用后必须 clearContext 清理以防线程复用泄漏
     */
    private static final ThreadLocal<Authentication> contextHolder = new ThreadLocal<>();

    public static Authentication getContext()
    {
        return contextHolder.get();
    }

    public static void setContext(Authentication context)
    {
        contextHolder.set(context);
    }

    public static void clearContext()
    {
        contextHolder.remove();
    }
}
