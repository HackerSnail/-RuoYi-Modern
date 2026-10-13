package com.ruoyi.framework.multicache;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

/**
 * 二级缓存失效广播的订阅监听器：把裸 UTF-8 消息体解成逻辑 key，回调
 * {@link MultiLevelCacheService#onInvalidateMessage(String)} 清除本节点 L1。
 *
 * <p>
 * 单条消息处理失败仅记录日志并吞掉，不让异常向上冒泡干扰容器对其余消息的派发；
 * 缓存失效本身是幂等的轻量本地操作，偶发丢弃可由 L1 兜底 TTL 最终收敛。
 * </p>
 *
 * @author HackerSnail <2306002088@qq.com>
 */
public class CacheInvalidateListener implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(CacheInvalidateListener.class);

    private final MultiLevelCacheService multiLevelCacheService;

    public CacheInvalidateListener(MultiLevelCacheService multiLevelCacheService) {
        this.multiLevelCacheService = multiLevelCacheService;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String key = null;
        try {
            key = new String(message.getBody(), StandardCharsets.UTF_8);
            multiLevelCacheService.onInvalidateMessage(key);
        } catch (Exception e) {
            // key 提到 try 外：解码成功但处理失败时能打出是哪个 key；若解码本身失败则 key=null。
            // 不在 catch 内再取 message.getBody()，避免二次异常；也不格式化 pattern/channel：订阅(ChannelTopic)场景 pattern 可能为 null。
            log.error("处理缓存失效广播消息异常, key={}", key, e);
        }
    }
}
