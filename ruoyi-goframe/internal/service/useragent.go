// Package service —— UserAgent 解析，逐条移植 com.ruoyi.common.utils.http.UserAgentUtils。
// 供登录/注册审计（recordLogininfor）从 UA 串识别 browser/os 两列；纯函数、无副作用，可脱离 HTTP 上下文单测。
// 判定顺序、正则、版本显示文案均照 Java 复刻（Firefox 最先，Chromium 壳统一先于泛化 Chrome，Safari 排除 Chrome…）。
package service

import (
	"regexp"
	"strings"
)

var (
	uaChromeRe     = regexp.MustCompile(`Chrome/(\d+)(?:\.\d+)*`)
	uaFirefoxRe    = regexp.MustCompile(`Firefox/(\d+)(?:\.\d+)*`)
	uaEdgeRe       = regexp.MustCompile(`Edg(?:e)?/(\d+)(?:\.\d+)*`)
	uaSafariRe     = regexp.MustCompile(`Version/(\d+)(?:\.\d+)*`)
	uaOperaRe      = regexp.MustCompile(`Opera/(\d+)(?:\.\d+)*`)
	uaOperaNewRe   = regexp.MustCompile(`OPR/(\d+)(?:\.\d+)*`)
	uaIERe         = regexp.MustCompile(`(?:MSIE |Trident/.*rv:)(\d+)(?:\.\d+)*`)
	uaSamsungRe    = regexp.MustCompile(`SamsungBrowser/(\d+)(?:\.\d+)*`)
	uaUCRe         = regexp.MustCompile(`UCBrowser/(\d+)(?:\.\d+)*`)
	uaQQRe         = regexp.MustCompile(`QQBrowser/(\d+)(?:\.\d+)*`)
	uaWeChatRe     = regexp.MustCompile(`MicroMessenger/(\d+)(?:\.\d+)*`)
	uaBaiduRe      = regexp.MustCompile(`baidubrowser/(\d+)(?:\.\d+)*`)
	uaWindowsRe    = regexp.MustCompile(`Windows NT (\d+\.\d+)`)
	uaMacOSRe      = regexp.MustCompile(`Mac OS X (\d+[_\d]*)`)
	uaAndroidRe    = regexp.MustCompile(`Android (\d+)(?:\.\d+)*`)
	uaIOSRe        = regexp.MustCompile(`OS[\s_](\d+)(?:_\d+)*`)
	uaLinuxRe      = regexp.MustCompile(`Linux`)
	uaChromeOSRe   = regexp.MustCompile(`CrOS`)
	digitsOnlyRe   = regexp.MustCompile(`^\d+$`)
)

// firstGroupOrEmpty 取正则首个捕获组；未命中返回 ""。对齐 Java matcher.find() 后取 group(1)。
func firstGroupOrEmpty(re *regexp.Regexp, s string) (string, bool) {
	m := re.FindStringSubmatch(s)
	if m == nil {
		return "", false
	}
	return m[1], true
}

// isChromiumShell 是否为带专属 token 的 Chromium 壳浏览器（Edge/Opera/国产壳等）。
// 这些 UA 普遍也含 "Chrome/"，供 Chrome 分支在专属 pattern 万一漏判时将其排除。
func isChromiumShell(ua string) bool {
	for _, t := range []string{"Edg/", "Edge/", "OPR/", "Opera/", "SamsungBrowser/", "UCBrowser/", "QQBrowser/", "baidubrowser/", "MicroMessenger/"} {
		if strings.Contains(ua, t) {
			return true
		}
	}
	return false
}

// UserAgentBrowser 对应 UserAgentUtils.getBrowser：UA 为空返回 ""，否则按 Java 判定顺序识别浏览器名+主版本号。
func UserAgentBrowser(ua string) string {
	if ua == "" {
		return ""
	}
	// Firefox 唯一标识，置最前。
	if v, ok := firstGroupOrEmpty(uaFirefoxRe, ua); ok {
		return "Firefox" + v
	}
	// 各 Chromium 壳须先于泛化 Chrome 判定。
	if v, ok := firstGroupOrEmpty(uaEdgeRe, ua); ok {
		return "Edge" + v
	}
	if v, ok := firstGroupOrEmpty(uaWeChatRe, ua); ok {
		return "WeChat" + v
	}
	if v, ok := firstGroupOrEmpty(uaUCRe, ua); ok {
		return "UC Browser" + v
	}
	if v, ok := firstGroupOrEmpty(uaQQRe, ua); ok {
		return "QQ Browser" + v
	}
	if v, ok := firstGroupOrEmpty(uaBaiduRe, ua); ok {
		return "Baidu Browser" + v
	}
	if v, ok := firstGroupOrEmpty(uaSamsungRe, ua); ok {
		return "Samsung Browser" + v
	}
	// Opera：新版 OPR/ 旧版 Opera/，均先于 Chrome/Safari。
	if v, ok := firstGroupOrEmpty(uaOperaNewRe, ua); ok {
		return "Opera" + v
	}
	if v, ok := firstGroupOrEmpty(uaOperaRe, ua); ok {
		return "Opera" + v
	}
	// Chrome：命中 Chrome/ 且 UA 含 Chrome 或 CriOS，且非壳。
	if v, ok := firstGroupOrEmpty(uaChromeRe, ua); ok {
		if (strings.Contains(ua, "Chrome") || strings.Contains(ua, "CriOS")) && !isChromiumShell(ua) {
			return "Chrome" + v
		}
	}
	// Safari：Version/ 且不含 Chrome。
	if v, ok := firstGroupOrEmpty(uaSafariRe, ua); ok {
		if !strings.Contains(ua, "Chrome") {
			return "Safari" + v
		}
	}
	// IE。
	if v, ok := firstGroupOrEmpty(uaIERe, ua); ok {
		return "Internet Explorer" + v
	}
	return ""
}

// UserAgentOS 对应 UserAgentUtils.getOperatingSystem：UA 为空返回 ""，否则识别操作系统名。
func UserAgentOS(ua string) string {
	if ua == "" {
		return ""
	}
	if v, ok := firstGroupOrEmpty(uaWindowsRe, ua); ok {
		return "Windows" + windowsVersionDisplay(v)
	}
	if v, ok := firstGroupOrEmpty(uaMacOSRe, ua); ok {
		return "macOS" + extractMajorVersion(v)
	}
	if v, ok := firstGroupOrEmpty(uaAndroidRe, ua); ok {
		return "Android" + extractMajorVersion(v)
	}
	if v, ok := firstGroupOrEmpty(uaIOSRe, ua); ok {
		if strings.Contains(ua, "iPhone") || strings.Contains(ua, "iPad") {
			return "iOS" + extractMajorVersion(v)
		}
	}
	if uaLinuxRe.MatchString(ua) && !strings.Contains(ua, "Android") {
		return "Linux"
	}
	if uaChromeOSRe.MatchString(ua) {
		return "Chrome OS"
	}
	return ""
}

// indexFirstOf 返回 a、b 两字节中在 s 内出现的最早下标；都未出现返回 -1。对齐 Java 取 '_'/'.' 首现位置。
func indexFirstOf(s string, a, b byte) int {
	i := strings.IndexByte(s, a)
	j := strings.IndexByte(s, b)
	if j >= 0 && (i < 0 || j < i) {
		return j
	}
	return i
}

// extractMajorVersion 按 "." 或 "_" 取首段；首段纯数字则返回它，否则返回原串。对齐 Java。
func extractMajorVersion(full string) string {
	if full == "" {
		return ""
	}
	idx := indexFirstOf(full, '_', '.')
	if idx < 0 {
		return full
	}
	first := full[:idx]
	if digitsOnlyRe.MatchString(first) {
		return first
	}
	return full
}

// windowsVersionDisplay Windows NT 版本号→显示名。对齐 Java getWindowsVersionDisplay。
func windowsVersionDisplay(v string) string {
	switch v {
	case "10.0":
		return "10"
	case "6.3":
		return "8.1"
	case "6.2":
		return "8"
	case "6.1":
		return "7"
	case "6.0":
		return "Vista"
	case "5.1":
		return "XP"
	case "5.0":
		return "2000"
	default:
		return extractMajorVersion(v)
	}
}
