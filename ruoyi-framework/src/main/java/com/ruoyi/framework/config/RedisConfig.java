package com.ruoyi.framework.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * redis配置
 * 
 * @author ruoyi
 */
@Configuration
@EnableCaching
public class RedisConfig {
    /**
     * 配置 RedisTemplate：key/Hash-key 用 String 序列化便于直观浏览与排查，
     * value/Hash-value 用 FastJson2 序列化（不写 @type，具体类型由调用方读取时显式指定）。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        FastJson2JsonRedisSerializer<Object> serializer = new FastJson2JsonRedisSerializer<>(Object.class);

        // 使用StringRedisSerializer来序列化和反序列化redis的key值
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);

        // Hash的key也采用StringRedisSerializer的序列化方式
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * 限流 Lua 脚本 Bean（供 RateLimiterAspect 执行）
     */
    @Bean
    public DefaultRedisScript<Long> limitScript() {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(limitScriptText());
        redisScript.setResultType(Long.class);
        return redisScript;
    }

    /**
     * 限流脚本：基于 ZSET 的滑动窗口。以微秒时间戳为 score，先 ZREMRANGEBYSCORE 剔除窗口外的旧记录，
     * 再取 ZCARD 作为窗口内当前请求数：若已达阈值 count 则返回 count+1（触发上层拒绝，且不写入本次）；
     * 否则 ZADD 本次并 PEXPIRE 收尾，返回新的窗口计数。时间取 redis.call('TIME') 由服务端生成，
     * 避免多节点客户端时钟漂移；整段脚本在 Redis 单线执行以保证原子性。相较固定窗口可消除临界突刺。
     */
    private String limitScriptText() {
        return "local key = KEYS[1]\n" +
                "local count = tonumber(ARGV[1])\n" +
                "local time = tonumber(ARGV[2])\n" +
                "local t = redis.call('TIME')\n" +
                "local now = tonumber(t[1]) * 1000000 + tonumber(t[2])\n" +
                "local window = time * 1000000\n" +
                "redis.call('ZREMRANGEBYSCORE', key, 0, now - window)\n" +
                "local current = redis.call('ZCARD', key)\n" +
                "if current >= count then\n" +
                "    return current + 1\n" +
                "end\n" +
                "redis.call('ZADD', key, now, now .. '-' .. math.random(1, 1000000))\n" +
                "redis.call('PEXPIRE', key, time * 1000)\n" +
                "return current + 1;";
    }

    /**
     * 重复提交判定 Lua 脚本 Bean（供 SameUrlDataInterceptor 原子执行）：单脚本内完成
     * “取回上次参数串→与本次相同则判重复，否则覆盖并刷新窗口”，消除 Java 侧 get-compare-set 的 TOCTOU 竞态。
     * 时间窗由 key 的 PX TTL(=interval 毫秒)承载，key 存活即仍在窗口内，故无需再单独存时间戳。
     * ARGV[1]=规范化参数串（valueSerializer=FastJson2 序列化，读写编码对称故可等值比较），
     * ARGV[2]=interval 毫秒（以数字传入，序列化后不加引号，可直接用作 PX）。
     */
    @Bean
    public DefaultRedisScript<Long> repeatSubmitScript() {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(repeatSubmitScriptText());
        redisScript.setResultType(Long.class);
        return redisScript;
    }

    private String repeatSubmitScriptText() {
        return "local cur = redis.call('GET', KEYS[1])\n" +
                "if cur and cur == ARGV[1] then\n" +
                "    return 1\n" +
                "end\n" +
                "redis.call('SET', KEYS[1], ARGV[1], 'PX', tonumber(ARGV[2]))\n" +
                "return 0;";
    }
}
