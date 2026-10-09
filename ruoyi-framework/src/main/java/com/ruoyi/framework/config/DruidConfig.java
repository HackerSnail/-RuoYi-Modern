package com.ruoyi.framework.config;

import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.spring.boot4.autoconfigure.DruidDataSourceBuilder;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.framework.config.properties.DruidProperties;
import com.ruoyi.framework.datasource.DynamicDataSource;

/**
 * druid 配置多数据源
 * 
 * @author ruoyi
 */
@Configuration
public class DruidConfig {

    /**
     * 主数据源（始终启用），绑定 spring.datasource.druid.master 配置
     */
    @Bean
    @ConfigurationProperties("spring.datasource.druid.master")
    public DataSource masterDataSource(DruidProperties druidProperties) {
        DruidDataSource dataSource = DruidDataSourceBuilder.create().build();
        return druidProperties.dataSource(dataSource);
    }

    /**
     * 从数据源，仅当 spring.datasource.druid.slave.enabled=true 时才创建
     */
    @Bean
    @ConfigurationProperties("spring.datasource.druid.slave")
    @ConditionalOnProperty(prefix = "spring.datasource.druid.slave", name = "enabled", havingValue = "true")
    public DataSource slaveDataSource(DruidProperties druidProperties) {
        DruidDataSource dataSource = DruidDataSourceBuilder.create().build();
        return druidProperties.dataSource(dataSource);
    }

    /**
     * 动态数据源：@Primary 作为全局默认数据源，以 master 为默认目标，聚合主/从供 DynamicDataSource 按上下文切换。
     * 从库用 @Qualifier + ObjectProvider 按 bean 名注入：slave.enabled=false 时该 bean 不存在，getIfAvailable() 返回 null，
     * 等价于原先 SpringUtils.getBean + catch NoSuchBeanDefinitionException 的“从库可选”语义；
     * 若从库已启用却创建失败，则容器启动即失败（fail-fast），不再静默降级为无从库。
     */
    @Bean(name = "dynamicDataSource")
    @Primary
    public DynamicDataSource dataSource(@Qualifier("masterDataSource") DataSource masterDataSource,
            @Qualifier("slaveDataSource") ObjectProvider<DataSource> slaveProvider) {
        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put(DataSourceType.MASTER.name(), masterDataSource);
        DataSource slaveDataSource = slaveProvider.getIfAvailable();
        if (slaveDataSource != null) {
            targetDataSources.put(DataSourceType.SLAVE.name(), slaveDataSource);
        }
        return new DynamicDataSource(masterDataSource, targetDataSources);
    }
}
