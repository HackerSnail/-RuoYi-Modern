package com.ruoyi.integration;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mapper 层集成测试基类。
 *
 * <p>策略：连共享测试库 {@code 192.168.25.50 / ry-vue}，用 Spring 的 {@code @Transactional}
 * 让每个测试方法运行在事务中并在结束后自动回滚——用例内自建 fixture 行、断言、随事务一并撤销，
 * 对库中既有数据零残留。</p>
 *
 * <p>用全量 {@code @SpringBootTest}（{@code WebEnvironment.MOCK}）而非 {@code @MybatisTest} 切片：若依的 MyBatis
 * 由 framework 的 {@code MyBatisConfig} + {@code ApplicationConfig#@MapperScan} 以非标准键手工装配，切片测试
 * 加载不到这些配置会导致 mapper 未注册；而 MOCK 提供 MVC 基础设施 bean（{@code requestMappingHandlerMapping}），
 * 满足 {@code PermitAllUrlProperties}/{@code SecurityConfig} 的依赖——NONE 会因缺该 bean 而启动失败。
 * 代价是需完整上下文（含 Redis），故这些用例统一打 {@code @Tag("integration")}，
 * 默认 {@code mvn test} 排除、仅 {@code mvn test -P integration} 运行。</p>
 */
@Tag("integration")
@ActiveProfiles("druid")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
public abstract class BaseMapperIntegrationTest
{
    @Autowired
    protected DataSource dataSource;

    protected JdbcTemplate jdbc;

    @BeforeEach
    void setUpJdbcTemplate()
    {
        // 直接基于注入的 DataSource 构造，避免依赖 JdbcTemplate 自动配置是否生效
        this.jdbc = new JdbcTemplate(dataSource);
    }
}
