package com.ruoyi.common.core.redis;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import com.alibaba.fastjson2.TypeReference;
import org.springframework.data.redis.core.BoundSetOperations;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

/**
 * spring redis 工具类
 *
 * @author HackerSnail <2306002088@qq.com>
 **/
@SuppressWarnings(value = { "unchecked", "rawtypes" })
@Component
public class RedisCache {
    public final RedisTemplate<String, Object> redisTemplate;

    public RedisCache(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 缓存基本的对象，Integer、String、实体类等
     *
     * @param key   缓存的键值
     * @param value 缓存的值
     */
    public <T> void setCacheObject(final String key, final T value) {
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 缓存基本的对象，Integer、String、实体类等
     *
     * @param key      缓存的键值
     * @param value    缓存的值
     * @param timeout  时间
     * @param timeUnit 时间颗粒度
     */
    public <T> void setCacheObject(final String key, final T value, final Integer timeout, final TimeUnit timeUnit) {
        redisTemplate.opsForValue().set(key, value, Duration.of(timeout, timeUnit.toChronoUnit()));
    }

    /**
     * 设置有效时间
     *
     * @param key     Redis键
     * @param timeout 超时时间
     * @return true=设置成功；false=设置失败
     */
    public boolean expire(final String key, final long timeout) {
        return expire(key, timeout, TimeUnit.SECONDS);
    }

    /**
     * 设置有效时间
     *
     * @param key     Redis键
     * @param timeout 超时时间
     * @param unit    时间单位
     * @return true=设置成功；false=设置失败
     */
    public boolean expire(final String key, final long timeout, final TimeUnit unit) {
        return redisTemplate.expire(key, Duration.of(timeout, unit.toChronoUnit()));
    }

    /**
     * 获取有效时间
     *
     * @param key Redis键
     * @return 有效时间
     */
    public long getExpire(final String key) {
        return redisTemplate.getExpire(key);
    }

    /**
     * 判断 key是否存在
     *
     * @param key 键
     * @return true 存在 false不存在
     */
    public Boolean hasKey(String key) {
        return redisTemplate.hasKey(key);
    }

    /**
     * 获得缓存的基本对象（无类型）。
     * <p>
     * 序列化层已不写 {@code @type}，故本方法对 POJO 只会返回通用结构（JSONObject/JSONArray），
     * 仅适用于 String/Integer 等基本类型，或调用方自行按 Map/JSONArray 处理的场景；
     * 需要还原成具体 POJO 时请改用 {@link #getCacheObject(String, Class)} 或 TypeReference 重载。
     *
     * @param key 缓存键值
     * @return 缓存键值对应的数据
     */
    public <T> T getCacheObject(final String key) {
        ValueOperations<String, Object> operation = redisTemplate.opsForValue();
        return (T) operation.get(key);
    }

    /**
     * 获得缓存的具体对象（按目标 Class 还原）。
     *
     * @param key   缓存键值
     * @param clazz 目标类型
     * @return 目标类型实例（不存在返回 null）
     */
    public <T> T getCacheObject(final String key, final Class<T> clazz) {
        return RedisJson.convert(redisTemplate.opsForValue().get(key), clazz);
    }

    /**
     * 获得缓存的具体对象（按 TypeReference 还原，适用于含泛型的 List/Map 等）。
     *
     * @param key  缓存键值
     * @param type 目标泛型类型，如 {@code new TypeReference<List<SysDictData>>(){}}
     * @return 目标类型实例（不存在返回 null）
     */
    public <T> T getCacheObject(final String key, final TypeReference<T> type) {
        return RedisJson.convert(redisTemplate.opsForValue().get(key), type);
    }

    /**
     * 删除单个对象（UNLINK 而非 DEL）。
     * <p>
     * UNLINK (Redis 4.0+) 将实际内存回收从命令执行线程剥离到后台 IO 线程，
     * 对大 key（集合 / 大 value / 位图）尤其重要：DEL 一个百万成员的 hash/set/zset
     * 会直接拖住主线程几百毫秒，UNLINK 只从 keyspace 摘除引用、主线程无感。
     * 对不存在或较小的 key，UNLINK 与 DEL 行为、耗时完全等价。
     *
     * @param key 缓存键
     * @return true=确实删除了一个 key；false=key 不存在
     */
    public boolean deleteObject(final String key) {
        // SDR 4.x: RedisTemplate.unlink(K) 返回 Boolean（true=key 实际被删），
        // RedisTemplate.unlink(Collection<K>) 返回 Long（实际删除个数），两者签名不同
        return Boolean.TRUE.equals(redisTemplate.unlink(key));
    }

    /**
     * 删除集合对象（分批 UNLINK）。
     * <p>
     * 旧写法一次 DEL 传 N 个键：命令自身 O(N) 且同步回收内存，大集合场景会长时间
     * 阻塞 Redis 主线程。本方法委托 {@link #unlinkObjects(Collection)} 按 500 个一批发送 UNLINK。
     *
     * @param collection 多个对象
     * @return 至少删除一个则 true
     */
    public boolean deleteObject(final Collection<String> collection) {
        return unlinkObjects(collection) > 0;
    }

    /**
     * 缓存List数据
     *
     * @param key      缓存的键值
     * @param dataList 待缓存的List数据
     * @return 缓存的对象
     */
    public <T> long setCacheList(final String key, final List<T> dataList) {
        Long count = redisTemplate.opsForList().rightPushAll(key, (Collection) dataList);
        return count == null ? 0 : count;
    }

    /**
     * 获得缓存的list对象
     *
     * @param key 缓存的键值
     * @return 缓存键值对应的数据
     */
    public <T> List<T> getCacheList(final String key) {
        return (List<T>) redisTemplate.opsForList().range(key, 0, -1);
    }

    /**
     * 缓存Set
     *
     * @param key     缓存键值
     * @param dataSet 缓存的数据
     * @return 缓存数据的对象
     */
    public <T> BoundSetOperations<String, T> setCacheSet(final String key, final Set<T> dataSet) {
        BoundSetOperations<String, T> setOperation = (BoundSetOperations<String, T>) (BoundSetOperations) redisTemplate
                .boundSetOps(key);
        for (T t : dataSet) {
            setOperation.add(t);
        }
        return setOperation;
    }

    /**
     * 获得缓存的set
     *
     * @param key
     * @return
     */
    public <T> Set<T> getCacheSet(final String key) {
        return (Set<T>) redisTemplate.opsForSet().members(key);
    }

    /**
     * 缓存Map
     *
     * @param key
     * @param dataMap
     */
    public <T> void setCacheMap(final String key, final Map<String, T> dataMap) {
        if (dataMap != null) {
            redisTemplate.opsForHash().putAll(key, dataMap);
        }
    }

    /**
     * 获得缓存的Map
     *
     * @param key
     * @return
     */
    public <T> Map<String, T> getCacheMap(final String key) {
        return (Map<String, T>) (Map) redisTemplate.opsForHash().entries(key);
    }

    /**
     * 往Hash中存入数据
     *
     * @param key   Redis键
     * @param hKey  Hash键
     * @param value 值
     */
    public <T> void setCacheMapValue(final String key, final String hKey, final T value) {
        redisTemplate.opsForHash().put(key, hKey, value);
    }

    /**
     * 获取Hash中的数据
     *
     * @param key  Redis键
     * @param hKey Hash键
     * @return Hash中的对象
     */
    public <T> T getCacheMapValue(final String key, final String hKey) {
        HashOperations<String, Object, Object> opsForHash = redisTemplate.opsForHash();
        return (T) opsForHash.get(key, hKey);
    }

    /**
     * 获取多个Hash中的数据
     *
     * @param key   Redis键
     * @param hKeys Hash键集合
     * @return Hash对象集合
     */
    public <T> List<T> getMultiCacheMapValue(final String key, final Collection<Object> hKeys) {
        return (List<T>) redisTemplate.opsForHash().multiGet(key, hKeys);
    }

    /**
     * 删除Hash中的某条数据
     *
     * @param key  Redis键
     * @param hKey Hash键
     * @return 是否成功
     */
    public boolean deleteCacheMapValue(final String key, final String hKey) {
        return redisTemplate.opsForHash().delete(key, hKey) > 0;
    }

    /**
     * 每轮 SCAN 服务端建议扫描的桶数（非硬上限）；500 是 Redis 官方推荐的起点，
     * 单轮扫描量小时不阶 Redis 主线程，多轮扫描累计时兼顾吞吐。
     */
    private static final int SCAN_BATCH_SIZE = 500;

    /**
     * {@link #keys(String)} 单次调用累计上限：防止误用宽 pattern（如 {@code "*"}）
     * 把海量键全部拉到 JVM 堆，命中上限会直接截断游标迭代。
     */
    private static final int SCAN_HARD_CAP = 100_000;

    /**
     * UNLINK 分批大小：一次传入过多键同样会占用主线程与网络单包，500 个一批很安全。
     */
    private static final int UNLINK_BATCH_SIZE = 500;

    /**
     * MGET 分批大小：单条 MGET 一次取太多键同样是大命令，500 个一批兼顾往返次数与单包大小。
     */
    private static final int MGET_BATCH_SIZE = 500;

    /**
     * 按 pattern 列出所有匹配的键。
     * <p>
     * 实现已从旧的 {@code KEYS pattern}（O(N) 且会阻塞 Redis 主线程，海量 key 场景下
     * 可直接引发哨兵切换）重写为 SCAN 游标分批拉取：服务端一次只扫 {@value #SCAN_BATCH_SIZE}
     * 个桶，主线程不长时间阻塞；客户端累计上限 {@value #SCAN_HARD_CAP} 作为兵层隔离。
     *
     * @param pattern glob 风格匹配串，例如 {@code "login_tokens:*"}
     * @return 命中的键集合（已去重）
     */
    public Set<String> keys(final String pattern) {
        return redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> keys = new HashSet<>();
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(SCAN_BATCH_SIZE).build();
            Cursor<byte[]> cursor = connection.keyCommands().scan(options);
            try {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                    if (keys.size() >= SCAN_HARD_CAP) {
                        break;
                    }
                }
            } finally {
                closeQuietly(cursor);
            }
            return keys;
        });
    }

    /**
     * 按 pattern 扫描并分批 UNLINK（SCAN 保证查询非阻塞 + UNLINK 把内存回收交给 Redis 后台线程）。
     * <p>
     * 流式处理：SCAN 每攒满 {@value #UNLINK_BATCH_SIZE} 个键就立刻在这一条连接上 UNLINK，
     * 不再像旧实现那样先把全部键攒进 JVM 堆再删——内存恒定，也没有
     * {@value #SCAN_HARD_CAP} 的截断问题（键再多也能清干净）。
     *
     * @param pattern glob 匹配串
     * @return 实际删除的键数量
     */
    public long deleteKeysByPattern(final String pattern) {
        Long removed = redisTemplate.execute((RedisCallback<Long>) connection -> {
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(SCAN_BATCH_SIZE).build();
            long total = 0L;
            List<byte[]> batch = new ArrayList<>(UNLINK_BATCH_SIZE);
            // 复用回调里的这条连接直接 UNLINK，避免再去连接池借连接（大库扫描时借不到会卡死）
            Cursor<byte[]> cursor = connection.keyCommands().scan(options);
            try {
                while (cursor.hasNext()) {
                    batch.add(cursor.next());
                    if (batch.size() >= UNLINK_BATCH_SIZE) {
                        total += nullToZero(connection.keyCommands().unlink(batch.toArray(new byte[0][])));
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) {
                    total += nullToZero(connection.keyCommands().unlink(batch.toArray(new byte[0][])));
                }
            } finally {
                closeQuietly(cursor);
            }
            return total;
        });
        return removed == null ? 0L : removed;
    }

    /**
     * 批量获取缓存对象（MGET 分批，按目标类型还原）
     * <p>
     * 替代"先 SCAN 出 N 个 key、再逐个 GET"的 N+1 往返：在线用户几千时，一次列表查询
     * 就是几千次 RTT，接口慢且连接池吃紧。MGET 一次取 {@value #MGET_BATCH_SIZE} 个，
     * 往返数降到 N/500。
     * <p>
     * 返回顺序与入参的迭代顺序一致；MGET 对已过期或不存在的键返回 null，这里直接剔除，
     * 调用方拿到的都是有效值。
     * <p>
     * <b>为什么强制传目标类型</b>：序列化层已不再写入 {@code @type}（为消除 fastjson autoType
     * 反序列化攻击面），value 序列化器只能还原成 {@code JSONObject} 等通用结构。若沿用早期那种靠泛型
     * {@code (T) value} 无类型强转的写法，编译期给不出任何提示，直到消费者把元素当 POJO 用时才在
     * 运行期抛 ClassCastException（在线用户列表就因此整表 500）。类型绑定统一经
     * {@link RedisJson#convert(Object, Class)} 完成，与 {@code getCacheObject(key, Class)} 同一口径。
     *
     * @param keys  缓存键集合
     * @param clazz 目标类型，不可为 null
     * @return 对应的缓存值（已去除 null），顺序与入参一致
     */
    public <T> List<T> multiGetObject(final Collection<String> keys, final Class<T> clazz) {
        if (clazz == null) {
            throw new IllegalArgumentException("clazz must not be null");
        }
        List<T> values = new ArrayList<>();
        if (keys == null || keys.isEmpty()) {
            return values;
        }
        List<String> batch = new ArrayList<>(MGET_BATCH_SIZE);
        for (String key : keys) {
            batch.add(key);
            if (batch.size() >= MGET_BATCH_SIZE) {
                collectTypedNonNull(values, redisTemplate.opsForValue().multiGet(batch), clazz);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            collectTypedNonNull(values, redisTemplate.opsForValue().multiGet(batch), clazz);
        }
        return values;
    }

    /**
     * 把一批通用结构按目标类型还原后收集，同时剔除不存在/已过期的 null。
     * 转换失败（缓存里的 JSON 与目标类结构不匹配）属于数据异常，由 fastjson 抛出，不在此吞掉。
     */
    private static <T> void collectTypedNonNull(List<T> values, List<Object> batchValues, Class<T> clazz) {
        if (batchValues == null) {
            return;
        }
        for (Object value : batchValues) {
            T converted = RedisJson.convert(value, clazz);
            if (converted != null) {
                values.add(converted);
            }
        }
    }

    /**
     * 分批 UNLINK 集合键（每批 {@value #UNLINK_BATCH_SIZE} 个）。
     * <p>
     * DEL 同步行释内存，大 value / 大集合时容易拖主线程；UNLINK 只从 keyspace 摘除，
     * 实际回收交给 Redis 后台 IO 线程。本方法不区分 key 存在与否，返回实际删除数。
     */
    public long unlinkObjects(final Collection<String> collection) {
        if (collection == null || collection.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        List<String> batch = new ArrayList<>(UNLINK_BATCH_SIZE);
        for (String k : collection) {
            batch.add(k);
            if (batch.size() >= UNLINK_BATCH_SIZE) {
                total += nullToZero(redisTemplate.unlink(batch));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            total += nullToZero(redisTemplate.unlink(batch));
        }
        return total;
    }

    private static long nullToZero(Long n) {
        return n == null ? 0L : n;
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignore) {
            // Cursor 关闭失败无功能影响，吞掉以免污染 Redis 调用栈
        }
    }
}
