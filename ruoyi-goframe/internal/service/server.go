// Package service —— 服务器监控（/monitor/server）的采集侧，逐条对齐 com.ruoyi.framework.web.domain.Server
// 的 copyTo()（底层 oshi）+ ServerMonitorService 的后台预采集范式。
//
// 口径（不得错位）：
//   - 不在请求线程采集：后台 goroutine 每 5s 采一份"全新"快照、经 atomic.Value 原子替换，请求只读最近快照
//     （对齐 Java 的 volatile 引用整体替换：读方永远拿到"已完整构造、之后不再改写"的对象，不会读到改一半）。
//   - CPU 使用率无法瞬时得出：取一次 tick 快照，睡 1s 再取一次，用差值算各态占比（该 sleep 只在后台线程，绝不阻塞 Web）。
//   - Java 在 getter 内做单位换算/百分比（字节→GB/MB、tick→占比、BigDecimal HALF_UP 保留位数）；Go 无 getter，
//     故此处把换算做实、结构体字段直接存展示值再序列化。四舍五入统一走 roundHalfUp（对齐 Arith 的 HALF_UP）。
//   - Go 进程无 JVM：jvm 区块用本进程运行时近似填充（runtime.MemStats/Version、进程启动时刻、os.Args），字段全保留。
package service

import (
	"context"
	"fmt"
	"math"
	"net"
	"os"
	"runtime"
	"strings"
	"sync/atomic"
	"time"

	"github.com/shirou/gopsutil/v4/cpu"
	"github.com/shirou/gopsutil/v4/disk"
	"github.com/shirou/gopsutil/v4/host"
	"github.com/shirou/gopsutil/v4/mem"
	"github.com/shirou/gopsutil/v4/process"

	"ruoyi-goframe/internal/model"
)

const (
	// 后台刷新间隔（秒）：请求不触发采集，最多读到 5 秒前的快照（对齐 ServerMonitorService.REFRESH_INTERVAL_SECONDS）。
	monitorRefreshIntervalSeconds = 5
	// CPU tick 双采样间隔：对齐 Server.OSHI_WAIT_SECOND=1000ms。
	cpuSampleWait = time.Second
)

// latestServer 最近一次采集完成的快照；初始为空壳，避免刷新完成前返回 nil（对齐 Java private volatile Server latest = new Server()）。
var latestServer atomic.Value

var (
	monitorOnce atomic.Bool  // 保证刷新器只启动一次
	monitorStop chan struct{} // 关闭信号：让后台 goroutine 退出、停止定时器
)

func init() {
	latestServer.Store(&model.Server{})
}

// StartServerMonitor 启动后台预采集：立即采一次，其后每 5s 采一次；采集异常只吞掉、沿用上一份快照（对齐 Java catch Throwable 保调度不中断）。
// 幂等：重复调用只启动一个 goroutine。
func StartServerMonitor(ctx context.Context) {
	if !monitorOnce.CompareAndSwap(false, true) {
		return
	}
	monitorStop = make(chan struct{})
	go func() {
		refresh(ctx) // initialDelay=0：启动即采一次
		ticker := time.NewTicker(monitorRefreshIntervalSeconds * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-monitorStop:
				return
			case <-ctx.Done():
				return
			case <-ticker.C:
				refresh(ctx)
			}
		}
	}()
}

// StopServerMonitor 停止后台采集（供优雅停调用）。
func StopServerMonitor() {
	if monitorOnce.Load() && monitorStop != nil {
		select {
		case <-monitorStop:
			// 已关闭
		default:
			close(monitorStop)
		}
	}
}

// GetServerSnapshot 返回最近一次采集完成的快照（不阻塞、不触发采集）。对齐 ServerMonitorService.getServer()。
func GetServerSnapshot() *model.Server {
	if v := latestServer.Load(); v != nil {
		return v.(*model.Server)
	}
	return &model.Server{}
}

// refresh 采集一份新快照并原子替换；任何异常都兜住、保留上一份、不外抛。
func refresh(ctx context.Context) {
	defer func() {
		// 对齐 Java「任务抛未捕获异常则后续调度永久取消」的防护：吞掉 panic，保刷新循环存活。
		_ = recover()
	}()
	snapshot := collect(ctx)
	if snapshot == nil {
		return
	}
	snapshot.Timestamp = time.Now().UnixMilli()
	latestServer.Store(snapshot)
}

// collect 组装一份完整快照（CPU/内存/JVM/系统/磁盘）。ctx 取消时提前返回 nil（放弃本次、沿用上一份）。
func collect(ctx context.Context) *model.Server {
	s := &model.Server{}
	s.Cpu = collectCpu(ctx)
	if ctx.Err() != nil {
		return nil
	}
	s.Mem = collectMem(ctx)
	s.Sys = collectSys(ctx)
	s.Jvm = collectJvm(ctx)
	s.SysFiles = collectSysFiles(ctx)
	return s
}

// collectCpu 对齐 Server.setCpuInfo：两次 tick 快照间隔 1s 求差，按 Java Cpu getter 公式换算成百分比。
func collectCpu(ctx context.Context) model.ServerCpu {
	num, _ := cpu.CountsWithContext(ctx, false) // 逻辑处理器数（对齐 getLogicalProcessorCount）
	prev, err1 := cpu.TimesWithContext(ctx, false)
	time.Sleep(cpuSampleWait) // 该 sleep 只在后台采集线程内，绝不落到请求线程
	cur, err2 := cpu.TimesWithContext(ctx, false)
	if err1 != nil || err2 != nil || len(prev) == 0 || len(cur) == 0 {
		return model.ServerCpu{CpuNum: num}
	}
	p := prev[0]
	c := cur[0]
	// 与 Java TickType 对齐的八态差值；gopsutil TimesStat 单位为秒，与 oshi 的 tick 单位不同，
	// 但按同式求「各态/总 * 100」占比即等价（total 字段的绝对量纲随平台而异，前端只用百分比字段）。
	user := c.User - p.User
	nice := c.Nice - p.Nice
	sys := c.System - p.System
	idle := c.Idle - p.Idle
	iowait := c.Iowait - p.Iowait
	irq := c.Irq - p.Irq
	softirq := c.Softirq - p.Softirq
	steal := c.Steal - p.Steal
	total := user + nice + sys + idle + iowait + irq + softirq + steal

	return model.ServerCpu{
		CpuNum: num,
		Total:  roundHalfUp(total*100, 2), // Java getTotal = round(mul(total,100),2)
		Sys:    pct(sys, total),
		Used:   pct(user, total),
		Wait:   pct(iowait, total),
		Free:   pct(idle, total),
	}
}

// collectMem 对齐 Server.setMemInfo：used = total - available，free = available；字节→GB(2位)、使用率百分比。
func collectMem(ctx context.Context) model.ServerMem {
	v, err := mem.VirtualMemoryWithContext(ctx)
	if err != nil || v == nil {
		return model.ServerMem{}
	}
	total := float64(v.Total)
	free := float64(v.Available)
	used := total - free
	return model.ServerMem{
		Total: roundHalfUp(total/(1024*1024*1024), 2),
		Used:  roundHalfUp(used/(1024*1024*1024), 2),
		Free:  roundHalfUp(free/(1024*1024*1024), 2),
		Usage: roundHalfUp(divScale(used, total, 4)*100, 2), // Java mul(div(used,total,4),100)：4位商×100 恒为2位小数，再 round 消除 float 尾差
	}
}

// collectSys 对齐 Server.setSysInfo：主机名/本机 IP/工作目录/OS 名/架构。
func collectSys(ctx context.Context) model.ServerSys {
	osName := runtime.GOOS
	arch := runtime.GOARCH
	// gopsutil 的 host.Info 给出平台名与版本，拼成贴近 Java os.name 的展示串（如 "Windows 11"）。
	if info, e := host.InfoWithContext(ctx); e == nil && info != nil {
		if name := strings.TrimSpace(info.Platform + " " + info.PlatformVersion); name != "" {
			osName = name
		}
	}
	return model.ServerSys{
		ComputerName: hostName(),
		ComputerIp:   hostIP(),
		UserDir:      currentDir(),
		OsName:       osName,
		OsArch:       arch,
	}
}

// collectJvm 用 Go 运行时近似 Java Jvm 区块（字段全保留，数值反映本进程）。字节→MB(2位)。
func collectJvm(ctx context.Context) model.ServerJvm {
	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	total := float64(ms.HeapSys)                       // 相当于 Runtime.totalMemory（已向 OS 提交的堆）
	used := float64(ms.HeapAlloc)                      // 相当于 total-free（在用的堆对象）
	free := total - used                               // 相当于 Runtime.freeMemory
	max := total                                        // Go 无固定 -Xmx；默认取当前提交堆
	if lim := memoryLimitMB(); lim > 0 {
		max = lim // 若设置了 GOMEMLIMIT 则以其为上界
	}

	startSec, _ := processStartSec(ctx)
	startTime := ""
	runTime := ""
	if startSec > 0 {
		startTime = formatInCST(startSec)
		runTime = timeDistance(time.Now().UnixMilli()-startSec*1000)
	}

	name := "Go"
	version := runtime.Version()
	home := os.Getenv("GOROOT")

	return model.ServerJvm{
		Total:     roundHalfUp(total/(1024*1024), 2),
		Max:       roundHalfUp(max/(1024*1024), 2),
		Free:      roundHalfUp(free/(1024*1024), 2),
		Used:      roundHalfUp((total-free)/(1024*1024), 2), // Java getUsed = (total-free)/MB
		Usage:     roundHalfUp(divScale(total-free, total, 4)*100, 2), // Java mul(div(total-free,total,4),100)：同 mem，round 到 2 位对齐
		Name:      name,
		Version:   version,
		Home:      home,
		StartTime: startTime,
		RunTime:   runTime,
		InputArgs: "[" + strings.Join(os.Args, ", ") + "]", // 对齐 Java get_inputArguments().toString() 的 [a, b] 形态
	}
}

// collectSysFiles 对齐 Server.setSysFiles：遍历挂载点，格式化容量字符串、算使用率（total=0 记 0，防除零中断整份采集）。
func collectSysFiles(ctx context.Context) []model.ServerSysFile {
	parts, err := disk.PartitionsWithContext(ctx, false)
	out := make([]model.ServerSysFile, 0, len(parts))
	if err != nil {
		return out
	}
	for _, p := range parts {
		u, e := disk.UsageWithContext(ctx, p.Mountpoint)
		if e != nil || u == nil {
			continue
		}
		total := u.Total
		free := u.Free
		used := total - free
		usage := 0.0
		if total > 0 {
			usage = roundHalfUp(divScale(float64(used), float64(total), 4)*100, 2)
		}
		out = append(out, model.ServerSysFile{
			DirName:     p.Mountpoint,
			SysTypeName: p.Fstype,
			TypeName:    p.Device, // oshi 的 fs.getName()(卷标) 在 Go 无直接对等，退化为设备名
			Total:       convertFileSize(int64(total)),
			Free:        convertFileSize(int64(free)),
			Used:        convertFileSize(int64(used)),
			Usage:       usage,
		})
	}
	return out
}

// ---------------- 换算与采集辅助（对齐 Arith / IpUtils / DateUtils） ----------------

// pct 计算 (part/total*100) 保留 2 位；total<=0 回 0（对齐各态占比、防除零）。
func pct(part, total float64) float64 {
	if total <= 0 {
		return 0
	}
	return roundHalfUp(part/total*100, 2)
}

// divScale 复刻 Arith.div(v1,v2,scale)：HALF_UP 保留 scale 位；除数为 0 回 0（Java 该处调用方已保证非 0，此处再兜一层）。
func divScale(a, b float64, scale int) float64 {
	if b == 0 {
		return 0
	}
	return roundHalfUp(a/b, scale)
}

// roundHalfUp 四舍五入保留 scale 位（HALF_UP，正数等价 Arith.round 的 RoundingMode.HALF_UP）。
func roundHalfUp(v float64, scale int) float64 {
	if math.IsNaN(v) || math.IsInf(v, 0) {
		return v
	}
	p := math.Pow(10, float64(scale))
	return math.Round(v*p) / p
}

// convertFileSize 复刻 Server.convertFileSize：GB/MB/KB 阈值切换，>100 省小数、否则保留 1 位、不足 KB 显示 B。
// 用 float32 除法以贴近 Java 的 (float) 强转语义。
func convertFileSize(size int64) string {
	const kb = 1024
	const mb = kb * 1024
	const gb = mb * 1024
	switch {
	case size >= gb:
		return fmt.Sprintf("%.1f GB", float32(size)/float32(gb))
	case size >= mb:
		f := float32(size) / float32(mb)
		if f > 100 {
			return fmt.Sprintf("%.0f MB", f)
		}
		return fmt.Sprintf("%.1f MB", f)
	case size >= kb:
		f := float32(size) / float32(kb)
		if f > 100 {
			return fmt.Sprintf("%.0f KB", f)
		}
		return fmt.Sprintf("%.1f KB", f)
	default:
		return fmt.Sprintf("%d B", size)
	}
}

// hostName 对齐 IpUtils.getHostName：失败回"未知"。
func hostName() string {
	if n, err := os.Hostname(); err == nil && n != "" {
		return n
	}
	return "未知"
}

// hostIP 对齐 IpUtils.getHostIp：优先站点内(私有)IPv4，其次非回环 IPv4，兜底 127.0.0.1。
func hostIP() string {
	ifs, err := net.Interfaces()
	if err != nil {
		return "127.0.0.1"
	}
	fallback := ""
	for _, ifc := range ifs {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, e := ifc.Addrs()
		if e != nil {
			continue
		}
		for _, a := range addrs {
			var ip net.IP
			switch v := a.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			if ip == nil || ip.IsLoopback() {
				continue
			}
			ip4 := ip.To4()
			if ip4 == nil {
				continue
			}
			if ip.IsPrivate() {
				return ip4.String() // 站点内地址优先（对齐 Java isSiteLocalAddress）
			}
			if fallback == "" {
				fallback = ip4.String()
			}
		}
	}
	if fallback != "" {
		return fallback
	}
	return "127.0.0.1"
}

// currentDir 对齐 user.dir：工作目录，失败回空串。
func currentDir() string {
	if d, err := os.Getwd(); err == nil {
		return d
	}
	return ""
}

// processStartSec 取本进程启动时刻（秒）。失败回 0。
func processStartSec(ctx context.Context) (int64, error) {
	p, err := process.NewProcessWithContext(ctx, int32(os.Getpid()))
	if err != nil {
		return 0, err
	}
	ms, err := p.CreateTime()
	if err != nil {
		return 0, err
	}
	return ms / 1000, nil
}

// memoryLimitMB 读 GOMEMLIMIT（若有）折算 MB；无限制/非法回 0。
func memoryLimitMB() float64 {
	v := os.Getenv("GOMEMLIMIT")
	if v == "" {
		return 0
	}
	if n, err := parseByteSize(v); err == nil && n > 0 {
		return float64(n) / (1024 * 1024)
	}
	return 0
}

// parseByteSize 解析带单位(GiB/MiB/KB/B)的字节串；仅支持常见后缀，够 GOMEMLIMIT 用。
func parseByteSize(s string) (int64, error) {
	s = strings.TrimSpace(strings.ToUpper(s))
	units := []struct {
		suf string
		mul int64
	}{
		{"GIB", 1024 * 1024 * 1024}, {"GB", 1000 * 1000 * 1000}, {"G", 1024 * 1024 * 1024},
		{"MIB", 1024 * 1024}, {"MB", 1000 * 1000}, {"M", 1024 * 1024},
		{"KIB", 1024}, {"KB", 1000}, {"K", 1024}, {"B", 1},
	}
	for _, u := range units {
		if strings.HasSuffix(s, u.suf) {
			numStr := strings.TrimSpace(strings.TrimSuffix(s, u.suf))
			var f float64
			if _, err := fmt.Sscanf(numStr, "%g", &f); err != nil {
				return 0, err
			}
			return int64(f * float64(u.mul)), nil
		}
	}
	var f float64
	if _, err := fmt.Sscanf(s, "%g", &f); err != nil {
		return 0, err
	}
	return int64(f), nil
}

// formatInCST 以 Asia/Shanghai(与 main 中 gtime 全局时区一致，对齐 Java GMT+8) 格式化秒级时间戳为 yyyy-MM-dd HH:mm:ss。
func formatInCST(sec int64) string {
	loc, err := time.LoadLocation("Asia/Shanghai")
	if err != nil || loc == nil {
		loc = time.Local
	}
	return time.Unix(sec, 0).In(loc).Format("2006-01-02 15:04:05")
}

// timeDistance 复刻 DateUtils.timeDistance：毫秒差 → "X天X小时X分钟X秒"。
func timeDistance(diffMs int64) string {
	if diffMs < 0 {
		diffMs = 0
	}
	const nd = 1000 * 24 * 60 * 60
	const nh = 1000 * 60 * 60
	const nm = 1000 * 60
	day := diffMs / nd
	hour := diffMs % nd / nh
	min := diffMs % nd % nh / nm
	sec := diffMs % nd % nh % nm / 1000
	return fmt.Sprintf("%d天%d小时%d分钟%d秒", day, hour, min, sec)
}
