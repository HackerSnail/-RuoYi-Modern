// Package service —— 代码生成配置读取，对齐 com.ruoyi.generator.config.GenConfig（@ConfigurationProperties("gen")）。
//
// Java 侧由 Spring 绑定 gen.* 到实例；Go 无该机制，改在读处经 g.Cfg() 显式取（与 token/file/config 同款约定）。
// 默认值对齐 Java application.yml 的 gen.*（author/packageName/autoRemovePre/tablePrefix/allowOverwrite）。
package service

import (
	"context"

	"github.com/gogf/gf/v2/frame/g"
)

// GenConfig 承载代码生成运行时配置，字段与 Java GenConfig 一一对应。
type GenConfig struct {
	Author         string // 作者
	PackageName    string // 生成包路径
	AutoRemovePre  bool   // 自动去除表前缀
	TablePrefix    string // 表前缀（逗号分隔）
	AllowOverwrite bool   // 是否允许 genCode 覆盖写本地
}

// getGenConfig 读取 gen.* 配置块。未配置的项回退到与 Java 一致的默认值。
func getGenConfig(ctx context.Context) GenConfig {
	c := g.Cfg()
	return GenConfig{
		Author:         c.MustGet(ctx, "gen.author", "").String(),
		PackageName:    c.MustGet(ctx, "gen.packageName", "com.ruoyi.system").String(),
		AutoRemovePre:  c.MustGet(ctx, "gen.autoRemovePre", false).Bool(),
		TablePrefix:    c.MustGet(ctx, "gen.tablePrefix", "").String(),
		AllowOverwrite: c.MustGet(ctx, "gen.allowOverwrite", false).Bool(),
	}
}
