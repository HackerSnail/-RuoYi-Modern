package com.ruoyi.framework.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;
import org.springframework.web.servlet.i18n.SessionLocaleResolver;
import com.ruoyi.common.constant.Constants;

/**
 * 资源文件配置加载
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
@Configuration
public class I18nConfig implements WebMvcConfigurer {
    /**
     * 语言切换拦截器的延迟提供者：本类的 {@link #localeChangeInterceptor()} 即其 Bean 定义。
     * 用 ObjectProvider 而非直接注入 LocaleChangeInterceptor，可规避“配置类自造 Bean 又构造器自注入”的循环依赖；
     * addInterceptors 经 getObject() 取到的仍是容器单例
     */
    private final ObjectProvider<LocaleChangeInterceptor> localeChangeInterceptorProvider;

    public I18nConfig(ObjectProvider<LocaleChangeInterceptor> localeChangeInterceptorProvider) {
        this.localeChangeInterceptorProvider = localeChangeInterceptorProvider;
    }

    /**
     * 本地化解析器：基于 Session 存储用户语言，默认取 Constants.DEFAULT_LOCALE
     */
    @Bean
    public LocaleResolver localeResolver() {
        SessionLocaleResolver slr = new SessionLocaleResolver();
        // 默认语言
        slr.setDefaultLocale(Constants.DEFAULT_LOCALE);
        return slr;
    }

    /**
     * 语言切换拦截器：根据请求参数 lang 的值切换当前 Locale
     */
    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor lci = new LocaleChangeInterceptor();
        // 参数名
        lci.setParamName("lang");
        return lci;
    }

    /**
     * 注册语言切换拦截器到 MVC 拦截链
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptorProvider.getObject());
    }
}
