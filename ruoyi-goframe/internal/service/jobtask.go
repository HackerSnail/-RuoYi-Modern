// Package service —— jobtask：定时任务编译期注册表 + invokeTarget 解析（替代 Spring bean 反射）。
//
// 为什么用注册表而非反射：本 fork 白名单包 com.ruoyi.quartz.task 实际只有两个清理 bean，Java 靠
// JobInvokeUtil 反射调 beanName.method/全类名.method。Go 无 Spring 容器，且任意类实例化本身即 RCE 面，
// 故这里用「编译期固定注册表」承载：key = beanName.methodName，未命中即等价 Java「目标字符串不在白名单内」，
// 天然杜绝反射调任意类。解析口径逐字复刻 JobInvokeUtil 的 getBeanName/getMethodName/getMethodParams。
package service

import (
	"context"
	"fmt"
	"strconv"
	"strings"

	"github.com/gogf/gf/v2/frame/g"
)

// taskHandler 编译期任务处理器：与 Java 反射目标方法等价。args 为解析出的原始字面量（已去首尾空白与包裹引号），
// 类型判定交给具体 handler（对齐 Java 由反射按形参类型选择重载）。
type taskHandler func(ctx context.Context, args []string) error

// taskRegistry 任务白名单落地：本 fork 仅 operLogCleanTask.clean / logininforCleanTask.clean 两个真实任务。
var taskRegistry = map[string]taskHandler{
	"operLogCleanTask.clean":    operLogCleanHandler,
	"logininforCleanTask.clean": logininforCleanHandler,
}

// 无参入口的默认保留天数：oper 缺省取配置 ruoyi.operLogRetentionDays（兜底 90，对齐 RuoYiConfig）；
// logininfor 恒 180（对齐 LogininforCleanTask.DEFAULT_RETENTION_DAYS，非配置项）。
const (
	defaultOperLogRetentionDays    = 90
	defaultLogininforRetentionDays = 180
)

// lookupTask 解析 invokeTarget 并查注册表。复刻 JobInvokeUtil：
//   - beanName  = substringBefore(target,"(") 再 substringBeforeLast(".",)；
//   - methodName= substringBefore(target,"(") 再 substringAfterLast(".",)；
//   - args      = substringBetweenLast("(",")") 去包裹引号后的字面量列表（引号内逗号不拆）。
// key=beanName+"."+methodName；命中返回 handler+args+true，否则 (nil,nil,false)。
func lookupTask(invokeTarget string) (taskHandler, []string, bool) {
	head := subBefore(invokeTarget, "(")
	beanName := subBeforeLast(head, ".")
	methodName := subAfterLast(head, ".")
	args := parseInvokeArgs(invokeTarget)
	h, ok := taskRegistry[beanName+"."+methodName]
	return h, args, ok
}

// taskRegistered 仅判断 beanName.methodName 是否在注册表内（供 validateJob 白名单校验，不解析参数）。
func taskRegistered(invokeTarget string) bool {
	_, _, ok := lookupTask(invokeTarget)
	return ok
}

// parseInvokeArgs 提取括号内参数并逐项清洗；无括号/空括号 → nil（对齐 Java getMethodParams 返回 null）。
func parseInvokeArgs(invokeTarget string) []string {
	inner := subBetweenLast(invokeTarget, "(", ")")
	if inner == "" {
		return nil
	}
	parts := splitArgsRespectingQuotes(inner)
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		out = append(out, unquoteParam(strings.TrimSpace(p)))
	}
	return out
}

// splitArgsRespectingQuotes 按逗号拆分但引号（单/双）内的逗号不拆，复刻 Java 的正向前瞻 split。
func splitArgsRespectingQuotes(s string) []string {
	var out []string
	var b strings.Builder
	var quote rune
	for _, c := range s {
		switch {
		case quote != 0:
			b.WriteRune(c)
			if c == quote {
				quote = 0
			}
		case c == '\'' || c == '"':
			quote = c
			b.WriteRune(c)
		case c == ',':
			out = append(out, b.String())
			b.Reset()
		default:
			b.WriteRune(c)
		}
	}
	out = append(out, b.String())
	return out
}

// unquoteParam 去除包裹引号（对齐 Java startsWithAny("'", "\"") 后 substring(1, len-1)）。
func unquoteParam(s string) string {
	if len(s) >= 2 && (s[0] == '\'' || s[0] == '"') {
		return s[1 : len(s)-1]
	}
	return s
}

// ---------------- StringUtils 等价小工具（严格对齐 Apache 分隔符不存在时的返回值） ----------------

func subBefore(s, sep string) string {
	if s == "" || sep == "" {
		return s
	}
	if i := strings.Index(s, sep); i >= 0 {
		return s[:i]
	}
	return s
}

func subBeforeLast(s, sep string) string {
	if s == "" || sep == "" {
		return s
	}
	if i := strings.LastIndex(s, sep); i >= 0 {
		return s[:i]
	}
	return s
}

func subAfterLast(s, sep string) string {
	if s == "" {
		return s
	}
	if sep == "" {
		return ""
	}
	if i := strings.LastIndex(s, sep); i >= 0 {
		return s[i+len(sep):]
	}
	return ""
}

func subBetweenLast(s, open, close string) string {
	i := strings.LastIndex(s, open)
	if i < 0 {
		return ""
	}
	rest := s[i+len(open):]
	j := strings.Index(rest, close)
	if j < 0 {
		return ""
	}
	return rest[:j]
}

// ---------------- 具体 handler 实现（逐条对齐 OperLogCleanTask / LogininforCleanTask） ----------------

// operLogCleanHandler：args 空 → 取配置 ruoyi.operLogRetentionDays；否则 parseInt（不合法即拒）；days<=0 即拒；调 CleanOperLogBefore。
func operLogCleanHandler(ctx context.Context, args []string) error {
	var days int
	if len(args) == 0 {
		days = g.Cfg().MustGet(ctx, "ruoyi.operLogRetentionDays", defaultOperLogRetentionDays).Int()
	} else {
		n, err := strconv.Atoi(args[0])
		if err != nil {
			return fmt.Errorf("操作日志保留天数参数不合法：%s", args[0])
		}
		days = n
	}
	if days <= 0 {
		return fmt.Errorf("操作日志保留天数必须大于 0，当前值：%d", days)
	}
	_, err := CleanOperLogBefore(ctx, days)
	return err
}

// logininforCleanHandler：args 空 → 默认 180；否则 parseInt；days<=0 即拒；调 CleanLogininforBefore。
func logininforCleanHandler(ctx context.Context, args []string) error {
	var days int
	if len(args) == 0 {
		days = defaultLogininforRetentionDays
	} else {
		n, err := strconv.Atoi(args[0])
		if err != nil {
			return fmt.Errorf("登录日志保留天数参数不合法：%s", args[0])
		}
		days = n
	}
	if days <= 0 {
		return fmt.Errorf("登录日志保留天数必须大于 0，当前值：%d", days)
	}
	_, err := CleanLogininforBefore(ctx, days)
	return err
}
