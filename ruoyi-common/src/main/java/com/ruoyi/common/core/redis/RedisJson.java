package com.ruoyi.common.core.redis;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;

/**
 * Redis JSON 值类型转换工具。
 * <p>
 * 序列化层（{@code FastJson2JsonRedisSerializer}）已不再写入 {@code @type}，以彻底消除
 * fastjson autoType 反序列化攻击面；代价是反序列化只能还原为通用结构
 * （{@code JSONObject}/{@code JSONArray}/基本类型）。当业务需要具体 POJO（如 LoginUser）时，
 * 由调用方在读取时显式提供目标类型，本工具据此完成一次类型绑定转换。
 *
 * @author HackerSnail <2306002088@qq.com>
 */
public final class RedisJson {
    private RedisJson() {
    }

    /**
     * 将通用结构按目标 {@code Class} 转换为具体对象。
     *
     * @param raw   反序列化得到的通用结构（JSONObject/JSONArray/基本类型），可为 null
     * @param clazz 目标类型
     * @return 目标类型实例；raw 为 null 时返回 null；raw 已是目标类型时直接返回
     */
    public static <T> T convert(Object raw, Class<T> clazz) {
        if (raw == null) {
            return null;
        }
        if (clazz.isInstance(raw)) {
            return clazz.cast(raw);
        }
        return JSON.parseObject(JSON.toJSONString(raw), clazz);
    }

    /**
     * 将通用结构按 {@code TypeReference}（可表达泛型，如 {@code new TypeReference<List<SysDictData>>(){}}）转换。
     *
     * @param raw  反序列化得到的通用结构，可为 null
     * @param type 目标泛型类型
     * @return 目标类型实例；raw 为 null 时返回 null
     */
    public static <T> T convert(Object raw, TypeReference<T> type) {
        if (raw == null) {
            return null;
        }
        return JSON.parseObject(JSON.toJSONString(raw), type);
    }
}
