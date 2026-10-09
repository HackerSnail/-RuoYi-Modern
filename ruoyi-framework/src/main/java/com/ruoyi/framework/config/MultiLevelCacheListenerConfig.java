package com.ruoyi.framework.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import com.ruoyi.framework.multicache.CacheInvalidateListener;
import com.ruoyi.framework.multicache.MultiLevelCacheService;

/**
 * 二级缓存失效广播的订阅端配置：监听 {@link MultiLevelCacheService#INVALIDATE_CHANNEL} 频道，
 * 收到 key 后清除本节点 Caffeine L1，从而实现多节点本地缓存的最终一致失效。
 *
 * @author ruoyi
 */
@Configuration
public class MultiLevelCacheListenerConfig {
    /**
     * 消息体由发布方经 StringRedisTemplate（StringRedisSerializer）写出，即裸 UTF-8 字符串，
     * 交由 {@link CacheInvalidateListener} 解出逻辑 key 并回调服务失效本地 L1（含异常兼底与日志）。
     */
    @Bean
    public MessageListener cacheInvalidateListener(MultiLevelCacheService multiLevelCacheService) {
        return new CacheInvalidateListener(multiLevelCacheService);
    }

    /**
     * 监听容器专用线程池：容器用它承载订阅任务（长驻阻塞读），与 AsyncManager 的通用池隔离避免互相挤占；
     * 缓存失效是轻量本地操作，2~4 线程足够。作为 @Bean 由 Spring 生命周期 initialize/优雅停服，无需手动 initialize()
     */
    @Bean(name = "cacheInvalidateExecutor")
    public ThreadPoolTaskExecutor cacheInvalidateExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("cache-invalidate-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    @Bean
    public RedisMessageListenerContainer cacheInvalidateContainer(
            RedisConnectionFactory connectionFactory,
            MessageListener cacheInvalidateListener,
            @Qualifier("cacheInvalidateExecutor") ThreadPoolTaskExecutor cacheInvalidateExecutor) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(cacheInvalidateExecutor);
        container.addMessageListener(cacheInvalidateListener,
                new ChannelTopic(MultiLevelCacheService.INVALIDATE_CHANNEL));
        return container;
    }
}
