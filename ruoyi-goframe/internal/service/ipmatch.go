// Package service —— 登录 IP 黑名单匹配，逐字移植 Java IpUtils.isMatchedIp 及其三条谓词
// （isIP / isIpWildCard / isIPSegment + ipIsInWildCardNoCheck / ipIsInNetNoCheck）。
// 契约要点（务必与 Java 一致）：
//   - 过滤串按分号 ";" 分隔（非逗号），空过滤或空 IP 直接不匹配。
//   - 每条规则依次尝试三种匹配：精确 IP 相等、"*" 通配段（逐段比对到首个 "*"）、"a.b.c.d-e.f.g.h" 数值网段区间。
//   - 正则逐条按 Java REGX_IP / REGX_IP_WILDCARD / REGX_IP_SEG 全串匹配（Go 用 ^...$ 锚定等价 matches()）。
// 相对 Java 的唯一加固：网段/通配比对时对 IP 段数不足、非数字段做前置守卫（Java 会在畸形 IP 上抛
// ArrayIndexOutOfBounds/NumberFormatException），此处按“不匹配”安全回落，行为对合法 IPv4 与 Java 等价。
package service

import (
	"regexp"
	"strconv"
	"strings"
)

// 与 Java 同构的正则片段（REGX_0_255 → REGX_IP → REGX_IP_WILDCARD / REGX_IP_SEG）。
const (
	regx0255 = `(25[0-5]|2[0-4]\d|1\d{2}|[1-9]\d|\d)`
)

var (
	reIP         = regexp.MustCompile(`^(` + `((` + regx0255 + `\.){3}` + regx0255 + `)` + `)$`)
	reIPWildCard = regexp.MustCompile(`^(` +
		`((\*\.){3}\*)|(` + regx0255 + `(\.\*){3})|(` + regx0255 + `\.` + regx0255 + `)(\.\*){2}|((` + regx0255 + `\.){3}\*)` +
		`)$`)
	reIPSeg = regexp.MustCompile(`^(` + `((` + regx0255 + `\.){3}` + regx0255 + `)\-(` + `((` + regx0255 + `\.){3}` + regx0255 + `)` + `)` + `)$`)
)

// isIPAddr 对应 IpUtils.isIP：非空且全串匹配 IPv4。
func isIPAddr(ip string) bool {
	return ip != "" && reIP.MatchString(ip)
}

// isIPWildCard 对应 IpUtils.isIpWildCard：非空且匹配通配地址形式。
func isIPWildCard(ip string) bool {
	return ip != "" && reIPWildCard.MatchString(ip)
}

// isIPSegment 对应 IpUtils.isIPSegment：非空且匹配 "ip-ip" 网段形式。
func isIPSegment(seg string) bool {
	return seg != "" && reIPSeg.MatchString(seg)
}

// ipInWildCard 对应 ipIsInWildCardNoCheck：逐段比对，遇 "*" 段即视为该位及之后全匹配。
func ipInWildCard(wildcard, ip string) bool {
	s1 := strings.Split(wildcard, ".")
	s2 := strings.Split(ip, ".")
	for i := 0; i < len(s1) && s1[i] != "*"; i++ {
		if i >= len(s2) || s1[i] != s2[i] {
			return false
		}
	}
	return true
}

// ipInNetSegment 对应 ipIsInNetNoCheck：把区间两端与目标各拼成 32 位整数比较，区间可反序书写。
func ipInNetSegment(area, ip string) bool {
	idx := strings.Index(area, "-")
	if idx < 0 {
		return false
	}
	ips, ok1 := ipToLong(strings.Split(area[:idx], "."))
	ipe, ok2 := ipToLong(strings.Split(area[idx+1:], "."))
	ipt, ok3 := ipToLong(strings.Split(ip, "."))
	if !ok1 || !ok2 || !ok3 {
		return false
	}
	if ips > ipe { // 区间可能写成「大-小」，先交换保证下界/上界
		ips, ipe = ipe, ips
	}
	return ips <= ipt && ipt <= ipe
}

// ipToLong 把四段 IPv4 拼成一个整数（对应 Java 的 4 次 <<8|parseInt）；任一段非数字或段数不足则失败。
func ipToLong(segs []string) (int64, bool) {
	if len(segs) != 4 {
		return 0, false
	}
	var v int64
	for _, s := range segs {
		n, err := strconv.Atoi(s)
		if err != nil {
			return 0, false
		}
		v = v<<8 | int64(n)
	}
	return v, true
}

// IsMatchedIp 校验 IP 是否命中黑名单过滤串（对应 Constants 里 sys.login.blackIPList 的消费）。
// filter 空或 ip 空一律不匹配（同 Java isEmpty 前置）。
func IsMatchedIp(filter, ip string) bool {
	if filter == "" || ip == "" {
		return false
	}
	for _, item := range strings.Split(filter, ";") {
		if isIPAddr(item) && item == ip {
			return true
		} else if isIPWildCard(item) && ipInWildCard(item, ip) {
			return true
		} else if isIPSegment(item) && ipInNetSegment(item, ip) {
			return true
		}
	}
	return false
}
