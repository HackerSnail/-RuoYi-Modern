package com.ruoyi.common.core.redis;

import java.nio.charset.StandardCharsets;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 位图（SETBIT/GETBIT）的分布式布隆过滤器：多节点共享同一份位图，用于在回源前
 * 快速判定"某 key 绝对不存在"，从而拦截缓存穿透。存在假阳性（说"在"可能在），但无假阴性
 * （说"不在"一定不在），因此适合作为"拦截不存在"的前置闸门；删除单个元素不支持（整体 reset 后可重建）。
 *
 * <p>哈希采用 Kirsch-Mitzenmacher 二次哈希技巧：由一个 64 位种子哈希派生两个基值 h1/h2，
 * 第 i 个哈希位置 = floorMod(h1 + i*h2, bitSize)，用 O(1) 个基哈希近似 k 个独立哈希函数。</p>
 *
 * @author ruoyi
 */
@Component
public class RedisBloomFilter {
    private final RedisTemplate<Object, Object> redisTemplate;

    public RedisBloomFilter(RedisTemplate<Object, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 依据预期元素数量与期望误判率计算最优位数组大小 m 与哈希函数个数 k。
     * m = -n*ln(p)/(ln2)^2，k = m/n*ln2。
     */
    public static long optimalBitSize(long expectedInsertions, double fpp) {
        double n = expectedInsertions;
        double p = fpp;
        return (long) (-n * Math.log(p) / (Math.log(2) * Math.log(2)));
    }

    public static int optimalHashCount(long expectedInsertions, long bitSize) {
        return Math.max(1, (int) Math.round((double) bitSize / expectedInsertions * Math.log(2)));
    }

    /**
     * 放入元素：把 k 个哈希位置对应 bit 置 1。
     */
    public void put(String bloomKey, long bitSize, int hashCount, String value) {
        long[] base = hash64(value);
        long h1 = base[0];
        long h2 = base[1] | 1L; // 保证 h2 为奇数，遍历更均匀
        for (int i = 0; i < hashCount; i++) {
            long pos = Math.floorMod(h1 + (long) i * h2, bitSize);
            redisTemplate.opsForValue().setBit(bloomKey, pos, true);
        }
    }

    /**
     * 可能包含：k 个位置全部为 1 才返回 true（任何一位为 0 即"一定不存在"）。
     */
    public boolean mightContain(String bloomKey, long bitSize, int hashCount, String value) {
        long[] base = hash64(value);
        long h1 = base[0];
        long h2 = base[1] | 1L;
        for (int i = 0; i < hashCount; i++) {
            long pos = Math.floorMod(h1 + (long) i * h2, bitSize);
            Boolean bit = redisTemplate.opsForValue().getBit(bloomKey, pos);
            if (bit == null || !bit) {
                return false;
            }
        }
        return true;
    }

    /**
     * 重置位图（删除全部元素），供重建场景使用。
     */
    public void clear(String bloomKey) {
        redisTemplate.delete(bloomKey);
    }

    /**
     * 计算两个 64 位子哈希（FNV-1a 变体），供二次哈希组合使用。
     */
    private long[] hash64(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        long h1 = 0xcbf29ce484222325L; // FNV offset basis
        long h2 = 0x9e3779b97f4a7c15L; // 黄金比例种子，作为第二基值
        for (byte b : bytes) {
            h1 = (h1 ^ b) * 0x100000001b3L; // FNV prime
            h2 = Long.rotateLeft(h2 ^ b, 31) * 0x9E3779B1L;
        }
        h1 ^= h1 >>> 33;
        h1 *= 0xff51afd7ed558ccdL;
        h1 ^= h1 >>> 33;
        return new long[] { h1, h2 };
    }
}
