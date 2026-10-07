package com.ruoyi.framework.aspectj;

import java.util.Objects;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import com.ruoyi.common.annotation.DataSource;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.datasource.DynamicDataSourceContextHolder;

/**
 * 多数据源处理
 * 
 * @author ruoyi
 */
@Aspect
@Order(1)
@Component
public class DataSourceAspect {
    protected Logger logger = LoggerFactory.getLogger(getClass());

    @Pointcut("@annotation(com.ruoyi.common.annotation.DataSource)"
            + "|| @within(com.ruoyi.common.annotation.DataSource)")
    public void dsPointCut() {

    }

    /**
     * 环绕增强：方法执行前根据 @DataSource 注解切换数据源，执行后无论成败都清理线程上下文，防止脏数据影响后续请求
     */
    @Around("dsPointCut()")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        DataSource dataSource = getDataSource(point);

        if (StringUtils.isNotNull(dataSource)) {
            DynamicDataSourceContextHolder.setDataSourceType(dataSource.value().name());
        }

        try {
            return point.proceed();
        } finally {
            // 销毁数据源 在执行方法之后
            DynamicDataSourceContextHolder.clearDataSourceType();
        }
    }

    /**
     * 获取需要切换的数据源：优先取方法上的 @DataSource，未命中时回退到类上的注解
     */
    public DataSource getDataSource(ProceedingJoinPoint point) {
        MethodSignature signature = (MethodSignature) point.getSignature();
        DataSource dataSource = AnnotationUtils.findAnnotation(signature.getMethod(), DataSource.class);
        if (Objects.nonNull(dataSource)) {
            return dataSource;
        }

        return AnnotationUtils.findAnnotation(signature.getDeclaringType(), DataSource.class);
    }
}
