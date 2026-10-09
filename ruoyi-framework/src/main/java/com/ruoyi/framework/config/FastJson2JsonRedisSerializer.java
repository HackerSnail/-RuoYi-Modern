package com.ruoyi.framework.config;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import com.alibaba.fastjson2.JSON;

/**
 * Redis使用FastJson序列化
 * 
 * @author ruoyi
 */
public class FastJson2JsonRedisSerializer<T> implements RedisSerializer<T> {
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    private final Class<T> clazz;

    public FastJson2JsonRedisSerializer(Class<T> clazz) {
        super();
        if (clazz == null) {
            throw new IllegalArgumentException("clazz must not be null");
        }
        this.clazz = clazz;
    }

    @Override
    public byte[] serialize(T t) throws SerializationException {
        if (t == null) {
            return new byte[0];
        }
        // 不再写入 @type：消除 fastjson autoType 反序列化攻击面；具体类型由调用方在读取时显式提供
        return JSON.toJSONString(t).getBytes(DEFAULT_CHARSET);
    }

    @Override
    public T deserialize(byte[] bytes) throws SerializationException {
        if (bytes == null || bytes.length <= 0) {
            return null;
        }
        String str = new String(bytes, DEFAULT_CHARSET);

        // clazz 为 Object（本仓 RedisTemplate<Object,Object> 的默认）时解析为通用结构
        // （JSONObject/JSONArray/基本类型），由 RedisCache 的带类型读按调用方目标类再转换；
        // 指定了具体 clazz 时直接按该类型绑定。全程不启用 autoType、不识别 @type。
        if (clazz == null || Object.class.equals(clazz)) {
            @SuppressWarnings("unchecked")
            T value = (T) JSON.parse(str);
            return value;
        }
        return JSON.parseObject(str, clazz);
    }
}
