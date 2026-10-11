package com.ruoyi.framework.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * 动态数据源
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
public class DynamicDataSource extends AbstractRoutingDataSource {
    /**
     * 路由核心：Spring 每次取连接前回调此方法，从 ThreadLocal 读取当前线程的数据源 key
     * （MASTER/SLAVE），据此在目标数据源集合中选择实际连接；key 为空时回退到默认数据源。
     * 本类保持为纯路由壳：默认/目标数据源在 DruidConfig 的 @Bean 工厂方法显式装配，原始映射到
     * resolvedDataSources 的解析由 Spring 对 InitializingBean 的 afterPropertiesSet() 回调完成，
     * 故不再自建带参构造、也不在构造期手动触发初始化。
     */
    @Override
    protected Object determineCurrentLookupKey() {
        return DynamicDataSourceContextHolder.getDataSourceType();
    }
}
