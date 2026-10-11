package com.ruoyi.framework.config;

import java.util.HashMap;
import java.util.Map;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.filter.RefererFilter;
import com.ruoyi.common.filter.RepeatableFilter;
import com.ruoyi.common.filter.XssFilter;
import com.ruoyi.common.utils.StringUtils;

/**
 * Filter配置
 *
 * @author HackerSnail <2306002088@qq.com>
 */
@Configuration
public class FilterConfig {
    @Value("${xss.excludes:}")
    private String excludes;

    @Value("${xss.urlPatterns:}")
    private String urlPatterns;

    @Value("${referer.allowed-domains:}")
    private String allowedDomains;

    /**
     * 注册 XSS 过滤（仅 xss.enabled=true 时生效），最高优先级，对配置的 url 范围过滤脚本参数，excludes 为豁免项
     */
    @Bean
    @ConditionalOnProperty(value = "xss.enabled", havingValue = "true")
    public FilterRegistrationBean<XssFilter> xssFilterRegistration() {
        FilterRegistrationBean<XssFilter> registration = new FilterRegistrationBean<>();
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setFilter(new XssFilter());
        registration.addUrlPatterns(StringUtils.split(urlPatterns, ","));
        registration.setName("xssFilter");
        registration.setOrder(FilterRegistrationBean.HIGHEST_PRECEDENCE);
        Map<String, String> initParameters = new HashMap<>();
        initParameters.put("excludes", excludes);
        registration.setInitParameters(initParameters);
        return registration;
    }

    /**
     * 注册 Referer 防盗链过滤（仅 referer.enabled=true 时生效），仅拦截本地资源路径，校验 Referer 是否在允许域名内
     */
    @Bean
    @ConditionalOnProperty(value = "referer.enabled", havingValue = "true")
    public FilterRegistrationBean<RefererFilter> refererFilterRegistration() {
        FilterRegistrationBean<RefererFilter> registration = new FilterRegistrationBean<>();
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setFilter(new RefererFilter());
        registration.addUrlPatterns(Constants.RESOURCE_PREFIX + "/*");
        registration.setName("refererFilter");
        registration.setOrder(FilterRegistrationBean.HIGHEST_PRECEDENCE);
        Map<String, String> initParameters = new HashMap<>();
        initParameters.put("allowedDomains", allowedDomains);
        registration.setInitParameters(initParameters);
        return registration;
    }

    /**
     * 注册可重复读过滤器：包装 request 使 body 可多次读取（供日志/防重提交等拦截器消费）。
     * 置于高优先级（仅次于 XSS）先于其它过滤器缓存 body，避免原始流被提前消费；
     * 其后 Spring Security 等还会再包一层，故下游 SameUrlDataInterceptor 经 WebUtils 逐层拆包识别本包装器，
     * 不再依赖它处于最外层
     */
    @Bean
    public FilterRegistrationBean<RepeatableFilter> repeatableFilterRegistration() {
        FilterRegistrationBean<RepeatableFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RepeatableFilter());
        registration.addUrlPatterns("/*");
        registration.setName("repeatableFilter");
        registration.setOrder(FilterRegistrationBean.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

}
