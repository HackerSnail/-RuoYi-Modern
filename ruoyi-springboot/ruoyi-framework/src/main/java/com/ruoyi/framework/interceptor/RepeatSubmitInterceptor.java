package com.ruoyi.framework.interceptor;

import java.lang.reflect.Method;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.annotation.RepeatSubmit;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.ServletUtils;

/**
 * 防止重复提交拦截器
 *
 * @author HackerSnail <2306002088@qq.com>
 */
@Component
public abstract class RepeatSubmitInterceptor implements HandlerInterceptor {
    /**
     * 前置拦截：仅对标注 @RepeatSubmit 的控制器方法生效，调用子类 isRepeatSubmit 判定，
     * 命中重复则直接回写错误 JSON 并中断请求，否则放行
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (handler instanceof HandlerMethod handlerMethod) {
            Method method = handlerMethod.getMethod();
            // 优先取方法级 @RepeatSubmit；未命中时回退到类级(@Target 已含 TYPE)，便于对整个控制器统一开启防重
            RepeatSubmit annotation = method.getAnnotation(RepeatSubmit.class);
            if (annotation == null) {
                annotation = handlerMethod.getBeanType().getAnnotation(RepeatSubmit.class);
            }
            if (annotation != null) {
                if (this.isRepeatSubmit(request, annotation)) {
                    AjaxResult ajaxResult = AjaxResult.error(annotation.message());
                    // 显式声明 JSON 内容类型与字符集：renderString 内部亦会设置(且其 setCharacterEncoding 在
                    // setContentType 之后、不被无 charset 的二次 setContentType 清除)，此处按评审再显式化作为双保险
                    response.setContentType("application/json;charset=UTF-8");
                    ServletUtils.renderString(response, JSON.toJSONString(ajaxResult));
                    return false;
                }
            }
            return true;
        } else {
            return true;
        }
    }

    /**
     * 验证是否重复提交由子类实现具体的防重复提交的规则
     *
     * @param request    请求信息
     * @param annotation 防重复注解参数
     * @return 结果
     * @throws Exception
     */
    public abstract boolean isRepeatSubmit(HttpServletRequest request, RepeatSubmit annotation);
}
