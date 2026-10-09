package com.ruoyi.framework.aspectj;

import java.lang.reflect.Method;
import java.util.Objects;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
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
    private static final Logger log = LoggerFactory.getLogger(DataSourceAspect.class);

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
            DynamicDataSourceContextHolder.pushDataSourceType(dataSource.value().name());
        } else {
            // 护栏：切点命中却没解析到 @DataSource（如代理场景下注解写了却没生效），显式记录而非静默放行
            log.debug("未解析到 @DataSource 注解，按当前/默认数据源执行：{}", point.getSignature());
        }

        try {
            return point.proceed();
        } finally {
            // 仅在本次确实入栈时出栈，与 push 对称；嵌套时内层出栈自动恢复外层数据源
            if (StringUtils.isNotNull(dataSource)) {
                DynamicDataSourceContextHolder.pollDataSourceType();
            }
        }
    }

    /**
     * 获取需要切换的数据源：优先取方法上的 @DataSource，未命中时回退到类上的注解。
     * 代理场景下 signature.getMethod() 可能是接口方法（无注解），故先取目标类的最具体方法。
     */
    public DataSource getDataSource(ProceedingJoinPoint point) {
        MethodSignature signature = (MethodSignature) point.getSignature();
        Class<?> targetClass = point.getTarget().getClass();
        Method method = AopUtils.getMostSpecificMethod(signature.getMethod(), targetClass);
        DataSource dataSource = AnnotationUtils.findAnnotation(method, DataSource.class);
        if (Objects.nonNull(dataSource)) {
            return dataSource;
        }

        return AnnotationUtils.findAnnotation(targetClass, DataSource.class);
    }
}
