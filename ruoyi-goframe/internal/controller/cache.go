// Package controller —— /monitor/cache 缓存监控 HTTP 处理器，逐条对齐 CacheController。
// 全部 @PreAuthorize('monitor:cache:list')；数据源为 Redis 管理命令，不走数据库。
package controller

import (
	"fmt"
	"strings"

	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/api"
	"ruoyi-goframe/internal/consts"
	"ruoyi-goframe/internal/middleware"
	"ruoyi-goframe/internal/model"
	"ruoyi-goframe/internal/service"
)

// clearAllConfirmToken 清空全部缓存的二次确认口令，须与请求 confirm 精确相等（复刻 CacheController.CLEAR_ALL_CONFIRM_TOKEN）。
const clearAllConfirmToken = "DELETE_ALL_CACHE"

// cacheNames 预置缓存名 + 中文备注，顺序/前缀/文案严格对齐 buildCaches（cacheName 保留尾冒号原样）。
// 包级只读，跨请求共享；与 Java 一样不在请求内变更。
var cacheNames = []model.SysCache{
	{CacheName: consts.RedisLoginToken, Remark: "用户信息"},
	{CacheName: consts.RedisSysConfig, Remark: "配置信息"},
	{CacheName: consts.RedisSysDict, Remark: "数据字典"},
	{CacheName: consts.RedisCaptchaCode, Remark: "验证码"},
	{CacheName: consts.RedisRepeatSubmit, Remark: "防重提交"},
	{CacheName: consts.RedisRateLimit, Remark: "限流处理"},
	{CacheName: consts.RedisPwdErrCnt, Remark: "密码错误次数"},
}

// CacheInfo GET /monitor/cache → data:{info, dbSize, commandStats}。
func CacheInfo(r *ghttp.Request) {
	ctx := r.Context()
	data := api.M{
		"info":         service.RedisInfoMap(ctx),
		"dbSize":       service.DBSize(ctx),
		"commandStats": service.CommandStatsPie(ctx),
	}
	api.Send(r, api.OkData(data))
}

// CacheNames GET /monitor/cache/getNames → data:[SysCache×7]（cacheName 含尾冒号，cacheKey/value 空）。
func CacheNames(r *ghttp.Request) {
	api.Send(r, api.OkData(cacheNames))
}

// CacheKeys GET /monitor/cache/getKeys/{cacheName} → data:[升序键]。pattern = cacheName + "*"（cacheName 带尾冒号）。
func CacheKeys(r *ghttp.Request) {
	cacheName := r.GetRouter("cacheName").String()
	api.Send(r, api.OkData(service.ScanKeysByPattern(r.Context(), cacheName+"*")))
}

// CacheValue GET /monitor/cache/getValue/{cacheName}/{cacheKey} → data:SysCache（三参构造剥前缀）。
func CacheValue(r *ghttp.Request) {
	ctx := r.Context()
	cacheName := r.GetRouter("cacheName").String()
	cacheKey := r.GetRouter("cacheKey").String()
	raw := service.GetRawValue(ctx, cacheKey)
	// 复刻 SysCache(cacheName, cacheKey, cacheValue)：分组名去冒号；cacheKey 去掉带冒号的 cacheName 前缀。
	row := model.SysCache{
		CacheName:  strings.ReplaceAll(cacheName, ":", ""),
		CacheKey:   strings.ReplaceAll(cacheKey, cacheName, ""),
		CacheValue: raw,
	}
	api.Send(r, api.OkData(row))
}

// ClearCacheName DELETE /monitor/cache/clearCacheName/{cacheName} → 清该前缀全部键，回"已清理 N 个键"。
func ClearCacheName(r *ghttp.Request) {
	cacheName := r.GetRouter("cacheName").String()
	removed := service.DeleteKeysByPattern(r.Context(), cacheName+"*")
	api.Send(r, api.OkMsg(fmt.Sprintf("已清理 %d 个键", removed)))
}

// ClearCacheKey DELETE /monitor/cache/clearCacheKey/{cacheKey} → 单键 UNLINK（Java 忽略结果，恒成功）。
func ClearCacheKey(r *ghttp.Request) {
	service.UnlinkKey(r.Context(), r.GetRouter("cacheKey").String())
	api.Send(r, api.Ok())
}

// ClearCacheAll DELETE /monitor/cache/clearCacheAll?confirm= → 三重护栏后才清全部（高危、不可逆）。
// ① confirm 必须精确等于口令，否则拒绝；② 仅超管可执行；③ 全过才 SCAN+批 UNLINK "*" 并审计告警。
func ClearCacheAll(r *ghttp.Request) {
	ctx := r.Context()
	if r.Get("confirm").String() != clearAllConfirmToken {
		api.Send(r, api.Err("二次确认口令错误，已拒绝清空全部缓存"))
		return
	}
	lu := middleware.CurrentLoginUser(r)
	if lu == nil || !service.IsAdmin(lu.UserId) {
		api.Send(r, api.Err("清空全部缓存仅限超级管理员操作"))
		return
	}
	removed := service.DeleteKeysByPattern(ctx, "*")
	g.Log().Warningf(ctx, "【高危】用户[%s]清空了全部缓存，共删除 %d 个键", lu.Username, removed)
	api.Send(r, api.OkMsg(fmt.Sprintf("已清理 %d 个键", removed)))
}
