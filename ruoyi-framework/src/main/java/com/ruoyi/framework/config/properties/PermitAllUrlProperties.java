package com.ruoyi.framework.config.properties;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import com.ruoyi.common.annotation.Anonymous;

/**
 * 设置Anonymous注解允许匿名访问的url
 * 
 * @author ruoyi
 */
@Component
public class PermitAllUrlProperties implements InitializingBean, ApplicationContextAware {
    private static final Pattern PATTERN = Pattern.compile("\\{(.*?)\\}");

    /** 通配符：把路径变量 {xxx} 替换为 * 供 requestMatchers 放行 */
    private static final String ASTERISK = "*";

    private ApplicationContext applicationContext;

    /** 收集到的免鉴权 URL；用 LinkedHashSet 去重并保持发现顺序，引用 final 不可替换 */
    private final Set<String> urls = new LinkedHashSet<>();

    /**
     * Bean 初始化完成后执行：遍历所有控制器映射，收集标有 @Anonymous（方法或类上）的接口 URL，
     * 并把路径变量 {xxx} 替换为 * 通配，供 SecurityConfig 将其放行免鉴权
     */
    @Override
    public void afterPropertiesSet() {
        RequestMappingHandlerMapping mapping = applicationContext.getBean("requestMappingHandlerMapping",
                RequestMappingHandlerMapping.class);
        Map<RequestMappingInfo, HandlerMethod> map = mapping.getHandlerMethods();

        map.keySet().forEach(info -> {
            HandlerMethod handlerMethod = map.get(info);

            // 默认 Spring Boot 使用 PathPatternParser 匹配策略，getPathPatternsCondition() 恒非 null；
            // 其姊妹方法 getPatternsCondition() 在 Spring Framework 7 已 @Deprecated(forRemoval=true)，
            // 故此处不回退到旧 API——若显式改用 ant_path_matcher 策略则属不受支持的配置，直接快速失败给明确提示。
            var condition = info.getPathPatternsCondition();
            if (condition == null) {
                throw new IllegalStateException(
                        "PermitAllUrlProperties 依赖 PathPatternParser 匹配策略，"
                                + "spring.mvc.pathmatch.matching-strategy=ant_path_matcher 不受支持，无法收集 @Anonymous URL");
            }
            Set<String> patternValues = condition.getPatternValues();

            // 获取方法上边的注解 替代path variable 为 *
            Anonymous method = AnnotationUtils.findAnnotation(handlerMethod.getMethod(), Anonymous.class);
            Optional.ofNullable(method)
                    .ifPresent(anonymous -> patternValues
                            .forEach(url -> urls.add(PATTERN.matcher(url).replaceAll(ASTERISK))));

            // 获取类上边的注解, 替代path variable 为 *
            Anonymous controller = AnnotationUtils.findAnnotation(handlerMethod.getBeanType(), Anonymous.class);
            Optional.ofNullable(controller)
                    .ifPresent(anonymous -> patternValues
                            .forEach(url -> urls.add(PATTERN.matcher(url).replaceAll(ASTERISK))));
        });
    }

    @Override
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        this.applicationContext = context;
    }

    /**
     * 返回免鉴权 URL 的不可变快照：内部用 Set 去重，对外只读，避免持有者可清空/篡改安全白名单
     */
    public List<String> getUrls() {
        return List.copyOf(urls);
    }
}
