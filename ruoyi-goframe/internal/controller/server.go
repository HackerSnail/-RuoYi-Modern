// Package controller —— /monitor/server 服务器监控 HTTP 处理器，对齐 ServerController.getInfo。
// 单接口 GET，权限 monitor:server:list；直接返回后台预采集的最近快照（不阻塞、不在请求线程内 sleep）。
package controller

import (
	"github.com/gogf/gf/v2/net/ghttp"

	"ruoyi-goframe/internal/response"
	"ruoyi-goframe/internal/service"
)

// ServerGetInfo GET /monitor/server → data:{cpu,mem,jvm,sys,sysFiles,timestamp}（最近快照）。
func ServerGetInfo(r *ghttp.Request) {
	response.Send(r, response.OkData(service.GetServerSnapshot()))
}
