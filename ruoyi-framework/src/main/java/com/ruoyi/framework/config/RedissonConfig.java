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
    private final String host;

    private final int port;

    private final int database;

    private final String password;

    public RedissonConfig(
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.database:0}") int database,
            @Value("${spring.data.redis.password:}") String password) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.password = password;
    }

    /**
     * RedissonClient：destroyMethod=shutdown 保证应用关闭时释放 Netty 线程与连接。
     * 密码为空时不设置（对应无鉴权的本地/测试 Redis）。
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        if (StringUtils.hasText(password)) {
            config.setPassword(password);
        }
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database);
        return Redisson.create(config);
    }
}
