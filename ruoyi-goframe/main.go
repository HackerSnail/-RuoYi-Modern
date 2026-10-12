// ruoyi-goframe —— RuoYi 的 GoFrame v2 后端（同库同契约，前端 RuoYi-Vue3 不改可对接）。
// P0：地基 + 认证全链路（/captchaImage /login /logout /getInfo /getRouters）。
package main

import (
	"context"
	"os"

	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/os/gtime"

	// 注册 MySQL 驱动（gf v2 驱动拆到 contrib，未导入则 g.DB() 报 "db type mysql 未注册"）。
	_ "github.com/gogf/gf/contrib/drivers/mysql/v2"
	// 注册 Redis 适配器（gf v2 nosql 拆到 contrib，未导入则 g.Redis() 报 "redis adapter is not set"）。
	_ "github.com/gogf/gf/contrib/nosql/redis/v2"

	"ruoyi-goframe/internal/router"
	"ruoyi-goframe/internal/service"
)

func main() {
	// 全局时间固定东八区，对齐 Java jackson time-zone=GMT+8 + date-format=yyyy-MM-dd HH:mm:ss。
	_ = gtime.SetTimeZone("Asia/Shanghai")

	// 服务器监控后台预采集（对齐 ServerMonitorService）：启动即采一次、其后每 5s 刷新，请求只读最近快照。
	// s.Run() 阻塞至优雅停返回后再停采集；ctx 的 cancel 由 defer 兜底。
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	service.StartServerMonitor(ctx)

	s := g.Server()
	// SERVER_ADDRESS 环境变量可临时覆盖监听地址（未设置则用 config.yaml 的 server.address），便于本地与 Java 并存调试。
	if addr := os.Getenv("SERVER_ADDRESS"); addr != "" {
		s.SetAddr(addr)
	}
	router.Register(s)
	s.Run()
	service.StopServerMonitor()
}
