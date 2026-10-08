package com.ruoyi.framework.config;

import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
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
     * 消息体由发布方经 StringRedisTemplate（SimpleStringRedisSerializer）写出，即裸 UTF-8 字符串，
     * 此处按同样方式解出逻辑 key 并回调服务失效本地 L1。
     */
    @Bean
    public MessageListener cacheInvalidateListener(MultiLevelCacheService multiLevelCacheService) {
        return (message, pattern) -> {
            String key = new String(message.getBody(), StandardCharsets.UTF_8);
            multiLevelCacheService.onInvalidateMessage(key);
        };
    }

    @Bean
    public RedisMessageListenerContainer cacheInvalidateContainer(
            org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory,
            MessageListener cacheInvalidateListener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(cacheInvalidateListener,
                new ChannelTopic(MultiLevelCacheService.INVALIDATE_CHANNEL));
        return container;
    }
}
