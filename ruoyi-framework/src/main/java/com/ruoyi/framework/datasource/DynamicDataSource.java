package com.ruoyi.framework.datasource;

import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * 动态数据源
 * 
 * @author ruoyi
 */
public class DynamicDataSource extends AbstractRoutingDataSource {
    public DynamicDataSource(DataSource defaultTargetDataSource, Map<Object, Object> targetDataSources) {
        super.setDefaultTargetDataSource(defaultTargetDataSource);
        super.setTargetDataSources(targetDataSources);
        super.afterPropertiesSet();
    }

    /**
     * 路由核心：Spring 每次取连接前回调此方法，从 ThreadLocal 读取当前线程的数据源 key
     * （MASTER/SLAVE），据此在目标数据源集合中选择实际连接；key 为空时回退到默认数据源
     */
    @Override
    protected Object determineCurrentLookupKey() {
        return DynamicDataSourceContextHolder.getDataSourceType();
    }
}
