package com.ruoyi.framework.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.framework.interceptor.PageCleanInterceptor;
import com.ruoyi.framework.interceptor.RepeatSubmitInterceptor;

/**
 * 通用配置
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
@Configuration
public class ResourcesConfig implements WebMvcConfigurer {
    private final RepeatSubmitInterceptor repeatSubmitInterceptor;
    private final PageCleanInterceptor pageCleanInterceptor;
    private final String[] allowedOriginPatterns;

    public ResourcesConfig(RepeatSubmitInterceptor repeatSubmitInterceptor,
            PageCleanInterceptor pageCleanInterceptor,
            @Value("${ruoyi.cors.allowed-origin-patterns:*}") String[] allowedOriginPatterns) {
        this.repeatSubmitInterceptor = repeatSubmitInterceptor;
        this.pageCleanInterceptor = pageCleanInterceptor;
        this.allowedOriginPatterns = allowedOriginPatterns;
    }

    /**
     * 映射静态资源：将本地上传目录暴露为 HTTP 可访问路径。
     * Swagger UI 不在此手动映射——本仓用 springdoc-openapi，其 starter 会自动注册
     * /swagger-ui/**；早先指向 springfox webjar 的映射在 classpath 已不存在，属失效死配置，已删除。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        /** 本地文件上传路径 */
        registry.addResourceHandler(Constants.RESOURCE_PREFIX + "/**")
                .addResourceLocations("file:" + RuoYiConfig.getProfile() + "/");
    }

    /**
     * 自定义拦截规则
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 分页 ThreadLocal 的清理放最外层（HIGHEST_PRECEDENCE）：
        // 保证即便后续拦截器中断请求，afterCompletion 也一定会回调过来
        registry.addInterceptor(pageCleanInterceptor).addPathPatterns("/**").order(Ordered.HIGHEST_PRECEDENCE);
        registry.addInterceptor(repeatSubmitInterceptor).addPathPatterns("/**");
    }

    /**
     * 跨域配置：允许源通过 ruoyi.cors.allowed-origin-patterns 配置（默认 *，用 allowedOriginPattern
     * 而非 allowedOrigins 以便与 allowCredentials 共存）；请求头与方法放开为 *，预检结果缓存 1800 秒减少
     * OPTIONS 往返。生产环境建议把允许源收敛为具体域名列表。
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // 设置访问源地址（可配置，默认 *）
        for (String pattern : allowedOriginPatterns) {
            config.addAllowedOriginPattern(pattern);
        }
        // 设置访问源请求头
        config.addAllowedHeader("*");
        // 设置访问源请求方法
        config.addAllowedMethod("*");
        // 有效期 1800秒
        config.setMaxAge(1800L);
        // 添加映射路径，拦截一切请求
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        // 返回新的CorsFilter
        return new CorsFilter(source);
    }
}
