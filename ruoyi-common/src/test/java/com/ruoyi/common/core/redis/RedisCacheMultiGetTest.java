package com.ruoyi.common.core.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.alibaba.fastjson2.JSON;

/**
 * {@link RedisCache#multiGetObject(java.util.Collection, Class)} 的类型还原测试。
 *
 * <p>
 * 起因：value 序列化器不再写入 {@code @type}（消除 fastjson autoType 攻击面），批量读回来的元素是
 * {@code JSONObject}。旧实现靠泛型 {@code (T) value} 无类型强转，编译期零提示，直到在线用户列表把元素
 * 当 {@code LoginUser} 用时才在运行期抛 ClassCastException 整表 500。本测试锁定"必须按目标类型还原"
 * 这一收口，任何退回无类型写法的改动都会在此失败。
 * </p>
 */
public class RedisCacheMultiGetTest {

    /** 被测批量读的目标类型（模拟 LoginUser 这类缓存 POJO） */
    public static class Session {
        private String userName;
        private Long loginTime;

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public Long getLoginTime() {
            return loginTime;
        }

        public void setLoginTime(Long loginTime) {
            this.loginTime = loginTime;
        }
    }

    private RedisTemplate<String, Object> redisTemplate;

    private ValueOperations<String, Object> valueOperations;

    private RedisCache redisCache;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        redisCache = new RedisCache(redisTemplate);
    }

    /** 复刻真实存储形态：先序列化为 JSON 再解析，得到的就是无类型信息的 JSONObject */
    private static Object genericOf(String userName, long loginTime) {
        Session session = new Session();
        session.setUserName(userName);
        session.setLoginTime(loginTime);
        return JSON.parse(JSON.toJSONString(session));
    }

    @Test
    @DisplayName("通用 JSON 结构按目标类还原，消费者不再踩 ClassCastException")
    void convertsGenericJsonToTargetType() {
        when(valueOperations.multiGet(anyList())).thenReturn(new ArrayList<>(List.of(genericOf("admin", 3L))));

        List<Session> got = redisCache.multiGetObject(List.of("k1"), Session.class);

        assertEquals(1, got.size());
        assertInstanceOf(Session.class, got.get(0));
        assertEquals("admin", got.get(0).getUserName());
        assertEquals(3L, got.get(0).getLoginTime());
    }

    @Test
    @DisplayName("剔除已过期/不存在的 null，且顺序与入参键一致")
    void skipsMissingKeysAndKeepsOrder() {
        // MGET 对不存在的键回 null，这里用 Arrays.asList（List.of 不容 null）复刻该形状
        when(valueOperations.multiGet(anyList())).thenReturn(
                new ArrayList<>(Arrays.asList(genericOf("second", 2L), null, genericOf("first", 1L))));

        List<Session> got = redisCache.multiGetObject(List.of("kB", "kMissing", "kA"), Session.class);

        assertEquals(List.of("second", "first"), got.stream().map(session -> session.getUserName()).toList());
    }

    @Test
    @DisplayName("目标类型缺失直接拒绝：不给无类型读留后门")
    void rejectsNullTargetType() {
        assertThrows(IllegalArgumentException.class, () -> redisCache.multiGetObject(List.of("k1"), null));
        verify(valueOperations, never()).multiGet(anyList());
    }

    @Test
    @DisplayName("空键集合不发 MGET 往返")
    void emptyKeysIssuesNoRoundTrip() {
        assertTrue(redisCache.multiGetObject(Collections.emptyList(), Session.class).isEmpty());
        verify(valueOperations, never()).multiGet(anyList());
    }

    @Test
    @DisplayName("超 500 个键按 500 一批分批 MGET，往返数不随键数线性增长")
    void batchesLargeKeySets() {
        // 每批返回值长度须与入参键数一致，模拟 MGET 语义
        when(valueOperations.multiGet(anyList())).thenAnswer(invocation -> {
            List<String> keys = invocation.getArgument(0);
            return new ArrayList<Object>(Collections.nCopies(keys.size(), null));
        });

        List<String> keys = new ArrayList<>(1001);
        for (int i = 0; i < 1001; i++) {
            keys.add("login_tokens:uuid-" + i);
        }
        redisCache.multiGetObject(keys, Session.class);

        verify(valueOperations, times(3)).multiGet(anyList());
    }

    @Test
    @DisplayName("已是目标类型的值原样返回，不做多余序列化")
    void passesThroughAlreadyTypedValue() {
        Session session = new Session();
        session.setUserName("typed");
        when(valueOperations.multiGet(anyList())).thenReturn(new ArrayList<>(List.of(session)));

        assertEquals("typed", redisCache.multiGetObject(List.of("k1"), Session.class).get(0).getUserName());
    }
}
