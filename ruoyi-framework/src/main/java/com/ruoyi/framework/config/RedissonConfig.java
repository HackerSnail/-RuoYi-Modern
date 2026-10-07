package com.ruoyi.framework.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.ruoyi.common.utils.StringUtils;

/**
 * Redisson 手动装配：直接复用应用已有的 spring.data.redis.* 连接参数构建单机 RedissonClient，
 * 不引入 redisson-spring-boot-starter，规避其对 Spring Boot 4 自动装配的兼容不确定性。
 *
 * @author ruoyi
 */
@Configuration
public class RedissonConfig {
    @Value("${spring.data.redis.host:127.0.0.1}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.database:0}")
    private int database;

    @Value("${spring.data.redis.password:}")
    private String password;

    /**
     * RedissonClient：destroyMethod=shutdown 保证应用关闭时释放 Netty 线程与连接。
     * 密码为空时不设置（对应无鉴权的本地/测试 Redis）。
     *
     * 注：Redisson 4.x 已将 BaseConfig#setPassword/setUsername 标记为 @Deprecated，
     * 官方提示 "Use password setting in Config instead"，因此密码统一设在 Config 顶层。
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        if (StringUtils.isNotEmpty(password)) {
            config.setPassword(password);
        }
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database);
        return Redisson.create(config);
    }
}
