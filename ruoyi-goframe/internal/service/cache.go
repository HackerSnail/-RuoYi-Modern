// Package service —— 缓存监控的 Redis 管理侧，逐条对齐 CacheController 依赖的 RedisCache.keys/
// deleteKeysByPattern/deleteObject + connection.serverCommands().info()/dbSize()。
//
// 口径（不得错位）：
//   - SCAN 非阻塞遍历（count=500 游标），替代旧 KEYS；列键结果升序（复刻 TreeSet）。
//   - deleteKeysByPattern 流式批 UNLINK：每攒够 500 键发一次 UNLINK（异步回收、不阻塞主线程、内存恒定、无截断）。
//   - INFO 全文按首个 ':' 解析成 map，跳过空行与 '#' 注释头；数值指标一律以字符串值回传（对齐 Properties）。
//   - commandstats 解析成 [{name,value}] 饼图数据，name 去 cmdstat_ 前缀，value 取 calls= 与 ,usec 之间。
package service

import (
	"context"
	"sort"
	"strings"

	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/util/gconv"
)

const (
	scanBatchSize   = 500 // SCAN COUNT，同 RedisCache.SCAN_BATCH_SIZE
	unlinkBatchSize = 500 // 每凑够该数量键发一批 UNLINK，同 RedisCache.UNLINK_BATCH_SIZE
)

// RedisInfoMap 执行 INFO 并解析为 map（对应 connection.serverCommands().info() 的 Properties）。
func RedisInfoMap(ctx context.Context) map[string]string {
	reply, err := g.Redis().Do(ctx, "INFO")
	if err != nil {
		return map[string]string{}
	}
	return parseInfo(reply.String())
}

// DBSize 执行 DBSIZE 返回键总数。对应 serverCommands().dbSize()。
func DBSize(ctx context.Context) int64 {
	reply, err := g.Redis().Do(ctx, "DBSIZE")
	if err != nil {
		return 0
	}
	return reply.Int64()
}

// CommandStatsPie 执行 INFO commandstats 并解析为饼图数据 [{name,value}]。对应 toPieList(info("commandstats"))。
func CommandStatsPie(ctx context.Context) []map[string]string {
	reply, err := g.Redis().Do(ctx, "INFO", "commandstats")
	if err != nil {
		return []map[string]string{}
	}
	pie := make([]map[string]string, 0)
	for key, property := range parseInfo(reply.String()) {
		name := key
		if strings.HasPrefix(key, "cmdstat_") {
			name = key[len("cmdstat_"):]
		}
		pie = append(pie, map[string]string{"name": name, "value": substringBetween(property, "calls=", ",usec")})
	}
	return pie
}

// parseInfo 把 Redis INFO 文本（\r\n 分隔，key:value）解析成 map；跳过空行与 '#' 注释头。
func parseInfo(raw string) map[string]string {
	m := make(map[string]string)
	for _, line := range strings.Split(raw, "\n") {
		line = strings.TrimRight(line, "\r")
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		idx := strings.Index(line, ":")
		if idx < 0 {
			continue
		}
		m[line[:idx]] = line[idx+1:]
	}
	return m
}

// substringBetween 复刻 StringUtils.substringBetween(str, after, before)：取首次 after 之后、其后首个 before 之前的片段；找不到返回空串。
func substringBetween(str, after, before string) string {
	i := strings.Index(str, after)
	if i < 0 {
		return ""
	}
	i += len(after)
	j := strings.Index(str[i:], before)
	if j < 0 {
		return ""
	}
	return str[i : i+j]
}

// ScanKeysByPattern 用 SCAN 游标按 glob 收集全部匹配键（非阻塞），返回升序切片。对应 RedisCache.keys(pattern)（Java 用 TreeSet 排序）。
func ScanKeysByPattern(ctx context.Context, pattern string) []string {
	keys := scanKeysRaw(ctx, pattern)
	if keys == nil {
		keys = []string{}
	}
	sort.Strings(keys)
	return keys
}

// scanKeysRaw SCAN 游标循环收集匹配键（不排序，供删除路径内部复用）。
func scanKeysRaw(ctx context.Context, pattern string) []string {
	var keys []string
	var cursor int64
	for {
		reply, err := g.Redis().Do(ctx, "SCAN", cursor, "MATCH", pattern, "COUNT", scanBatchSize)
		if err != nil {
			break
		}
		arr := reply.Array()
		if len(arr) < 2 {
			break
		}
		cursor = gconv.Int64(arr[0])
		keys = append(keys, gconv.SliceStr(arr[1])...)
		if cursor == 0 {
			break
		}
	}
	return keys
}

// DeleteKeysByPattern 扫描 + 分批 UNLINK，返回实际删除键数。对应 RedisCache.deleteKeysByPattern（流式：每攒够 500 即删）。
func DeleteKeysByPattern(ctx context.Context, pattern string) int64 {
	var (
		total int64
		batch []interface{}
	)
	flush := func() {
		if len(batch) == 0 {
			return
		}
		if reply, err := g.Redis().Do(ctx, "UNLINK", batch...); err == nil {
			total += reply.Int64()
		}
		batch = batch[:0]
	}
	for _, key := range scanKeysRaw(ctx, pattern) {
		batch = append(batch, key)
		if len(batch) >= unlinkBatchSize {
			flush()
		}
	}
	flush()
	return total
}

// UnlinkKey 单键 UNLINK（大 key 不阻塞主线程）。对应 RedisCache.deleteObject(String)→unlink。忽略返回值（控制器 Java 亦忽略）。
func UnlinkKey(ctx context.Context, key string) {
	_, _ = g.Redis().Do(ctx, "UNLINK", key)
}

// GetRawValue 读取键的原始字符串值；不存在返回空串。对应 redisTemplate.opsForValue().get(cacheKey)。
func GetRawValue(ctx context.Context, key string) string {
	v, err := g.Redis().Get(ctx, key)
	if err != nil || v == nil || v.IsNil() {
		return ""
	}
	return v.String()
}
