package com.ruoyi.framework.config.properties;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
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
public class PermitAllUrlProperties implements InitializingBean {
    private static final Pattern PATTERN = Pattern.compile("\\{(.*?)\\}");

    /** 通配符：把路径变量 {xxx} 替换为 * 供 requestMatchers 放行 */
    private static final String ASTERISK = "*";

    private final ApplicationContext applicationContext;

    /** 收集到的免鉴权 URL；用 LinkedHashSet 去重并保持发现顺序，引用 final 不可替换 */
    private final Set<String> urls = new LinkedHashSet<>();

    public PermitAllUrlProperties(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Bean 初始化完成后执行：遍历所有控制器映射，收集标有 @Anonymous（方法或类上）的接口 URL，
     * 并把路径变量 {xxx} 替换为 * 通配，供 SecurityConfig 将其放行免鉴权
     */
    @Override
    public void afterPropertiesSet() {
        RequestMappingHandlerMapping mapping = applicationContext.getBean("requestMappingHandlerMapping",
                RequestMappingHandlerMapping.class);
        Map<RequestMappingInfo, HandlerMethod> map = mapping.getHandlerMethods();

        map.forEach((info, handlerMethod) -> {
            // 默认 PathPatternParser 策略下 getPathPatternsCondition() 通常非 null；若显式切换为
            // ant_path_matcher 则可能为 null，此时属不受支持的配置，直接快速失败给明确提示。
            // （其姊妹方法 getPatternsCondition() 在 Spring Framework 7 已 @Deprecated(forRemoval=true)，故不回退旧 API。）
            var condition = info.getPathPatternsCondition();
            if (condition == null) {
                throw new IllegalStateException(
                        "PermitAllUrlProperties 依赖 PathPatternParser 匹配策略，"
                                + "spring.mvc.pathmatch.matching-strategy=ant_path_matcher 不受支持，无法收集 @Anonymous URL");
            }
            Set<String> patternValues = condition.getPatternValues();

            // 方法或类上任意一处标注 @Anonymous 即放行其全部 URL；两处同时标注时同一 URL 会被加两次，
            // 已由 Set 天然去重，属预期行为。
            Anonymous method = AnnotationUtils.findAnnotation(handlerMethod.getMethod(), Anonymous.class);
            if (method != null) {
                addAnonymousUrls(patternValues);
            }

            Anonymous controller = AnnotationUtils.findAnnotation(handlerMethod.getBeanType(), Anonymous.class);
            if (controller != null) {
                addAnonymousUrls(patternValues);
            }
        });
    }

    /** 把 patternValues 中的路径变量 {xxx} 替换为 * 通配后收集到免鉴权 URL 集合 */
    private void addAnonymousUrls(Set<String> patternValues) {
        patternValues.forEach(url -> urls.add(PATTERN.matcher(url).replaceAll(ASTERISK)));
    }

    /**
     * 返回免鉴权 URL 的不可变快照：内部用 Set 去重，对外只读，避免持有者可清空/篡改安全白名单
     */
    public List<String> getUrls() {
        return List.copyOf(urls);
    }
}
