package com.ruoyi.common.utils.http;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.ruoyi.common.utils.StringUtils;

/**
 * UserAgent解析工具类
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
public class UserAgentUtils {

    // 浏览器正则表达式模式
    private static final Pattern CHROME_PATTERN = Pattern.compile("Chrome/(\\d+)(?:\\.\\d+)*");
    private static final Pattern FIREFOX_PATTERN = Pattern.compile("Firefox/(\\d+)(?:\\.\\d+)*");
    private static final Pattern EDGE_PATTERN = Pattern.compile("Edg(?:e)?/(\\d+)(?:\\.\\d+)*");
    private static final Pattern SAFARI_PATTERN = Pattern.compile("Version/(\\d+)(?:\\.\\d+)*");
    private static final Pattern OPERA_PATTERN = Pattern.compile("Opera/(\\d+)(?:\\.\\d+)*");
    private static final Pattern OPERA_NEW_PATTERN = Pattern.compile("OPR/(\\d+)(?:\\.\\d+)*");
    private static final Pattern IE_PATTERN = Pattern.compile("(?:MSIE |Trident/.*rv:)(\\d+)(?:\\.\\d+)*");
    private static final Pattern SAMSUNG_PATTERN = Pattern.compile("SamsungBrowser/(\\d+)(?:\\.\\d+)*");
    private static final Pattern UC_PATTERN = Pattern.compile("UCBrowser/(\\d+)(?:\\.\\d+)*");
    private static final Pattern QQ_PATTERN = Pattern.compile("QQBrowser/(\\d+)(?:\\.\\d+)*");
    private static final Pattern WECHAT_PATTERN = Pattern.compile("MicroMessenger/(\\d+)(?:\\.\\d+)*");
    private static final Pattern BAIDU_PATTERN = Pattern.compile("baidubrowser/(\\d+)(?:\\.\\d+)*");

    // 操作系统正则表达式模式
    private static final Pattern WINDOWS_PATTERN = Pattern.compile("Windows NT (\\d+\\.\\d+)");
    private static final Pattern MACOS_PATTERN = Pattern.compile("Mac OS X (\\d+[_\\d]*)");
    private static final Pattern ANDROID_PATTERN = Pattern.compile("Android (\\d+)(?:\\.\\d+)*");
    private static final Pattern IOS_PATTERN = Pattern.compile("OS[\\s_](\\d+)(?:_\\d+)*");
    private static final Pattern LINUX_PATTERN = Pattern.compile("Linux");
    private static final Pattern CHROMEOS_PATTERN = Pattern.compile("CrOS");

    /**
     * 获取客户端浏览器（纯正则识别）
     */
    public static String getBrowser(String userAgent) {
        if (StringUtils.isBlank(userAgent)) {
            return StringUtils.EMPTY;
        }
        return formatBrowser(userAgent);
    }

    /**
     * 获取客户端操作系统（纯正则识别）
     */
    public static String getOperatingSystem(String userAgent) {
        if (StringUtils.isBlank(userAgent)) {
            return StringUtils.EMPTY;
        }
        return formatOperatingSystem(userAgent);
    }

    /**
     * 全面浏览器检测
     */
    private static String formatBrowser(String browser) {
        // Firefox：“Firefox/” 标识唯一、几乎不被其它浏览器复用，置于最前。
        Matcher firefoxMatcher = FIREFOX_PATTERN.matcher(browser);
        if (firefoxMatcher.find()) {
            return "Firefox" + firefoxMatcher.group(1);
        }
        // 以下均为“带各自特有 token 的 Chromium 壳浏览器”，须统一先于泛化的 Chrome 判定：这类 UA
        // 同时含专属标识（Edg/、MicroMessenger/、UCBrowser/、QQBrowser/、baidubrowser/、SamsungBrowser/）
        // 与 "Chrome/"，若先判 Chrome 会把它们一律误识别为 Chrome；专属 token 比 "Chrome/" 更具区分度。
        // Edge 浏览器
        Matcher edgeMatcher = EDGE_PATTERN.matcher(browser);
        if (edgeMatcher.find()) {
            return "Edge" + edgeMatcher.group(1);
        }
        // 微信内置浏览器
        Matcher wechatMatcher = WECHAT_PATTERN.matcher(browser);
        if (wechatMatcher.find()) {
            return "WeChat" + wechatMatcher.group(1);
        }
        // UC浏览器
        Matcher ucMatcher = UC_PATTERN.matcher(browser);
        if (ucMatcher.find()) {
            return "UC Browser" + ucMatcher.group(1);
        }
        // QQ浏览器
        Matcher qqMatcher = QQ_PATTERN.matcher(browser);
        if (qqMatcher.find()) {
            return "QQ Browser" + qqMatcher.group(1);
        }
        // 百度浏览器
        Matcher baiduMatcher = BAIDU_PATTERN.matcher(browser);
        if (baiduMatcher.find()) {
            return "Baidu Browser" + baiduMatcher.group(1);
        }
        // Samsung浏览器
        Matcher samsungMatcher = SAMSUNG_PATTERN.matcher(browser);
        if (samsungMatcher.find()) {
            return "Samsung Browser" + samsungMatcher.group(1);
        }
        // Opera 浏览器：现代 Chromium 版用 "OPR/" token 且含 "Chrome/"，旧 Presto 版用 "Opera/" token，
        // 二者都须先于 Chrome/Safari 判定，否则会被 Chrome（含 OPR/）或 Safari（Version/ 且无 Chrome）误吞。
        Matcher operaNewMatcher = OPERA_NEW_PATTERN.matcher(browser);
        if (operaNewMatcher.find()) {
            return "Opera" + operaNewMatcher.group(1);
        }
        Matcher operaMatcher = OPERA_PATTERN.matcher(browser);
        if (operaMatcher.find()) {
            return "Opera" + operaMatcher.group(1);
        }
        // Chrome 浏览器：须排除各 Chromium 壳（它们已在上文各自命中并返回，这里再兜一层负向守卫，
        // 防止某壳的专属 pattern 万一漏判时被误标为 Chrome）。Safari 判定仍保留 !contains("Chrome")。
        Matcher chromeMatcher = CHROME_PATTERN.matcher(browser);
        if (chromeMatcher.find() && (browser.contains("Chrome") || browser.contains("CriOS"))
                && !isChromiumShell(browser)) {
            return "Chrome" + chromeMatcher.group(1);
        }
        // Safari浏览器（需排除Chrome）
        Matcher safariMatcher = SAFARI_PATTERN.matcher(browser);
        if (safariMatcher.find() && !browser.contains("Chrome")) {
            return "Safari" + safariMatcher.group(1);
        }
        // IE浏览器
        Matcher ieMatcher = IE_PATTERN.matcher(browser);
        if (ieMatcher.find()) {
            return "Internet Explorer" + ieMatcher.group(1);
        }
        return StringUtils.EMPTY;
    }

    /**
     * 是否为带专属 token 的 Chromium 壳浏览器（Edge/Opera/国产壳等）。这些 UA 普遍也含 "Chrome/"，
     * 供 Chrome 分支在专属 pattern 万一漏判时将其排除。"Edg/" 与 "Edge/" 分别覆盖新旧 Edge 写法。
     */
    private static boolean isChromiumShell(String browser) {
        return browser.contains("Edg/") || browser.contains("Edge/") || browser.contains("OPR/")
                || browser.contains("Opera/") || browser.contains("SamsungBrowser/")
                || browser.contains("UCBrowser/") || browser.contains("QQBrowser/")
                || browser.contains("baidubrowser/") || browser.contains("MicroMessenger/");
    }

    /**
     * 检测操作系统
     */
    private static String formatOperatingSystem(String operatingSystem) {
        // Windows系统
        Matcher windowsMatcher = WINDOWS_PATTERN.matcher(operatingSystem);
        if (windowsMatcher.find()) {
            return "Windows" + getWindowsVersionDisplay(windowsMatcher.group(1));
        }
        // macOS系统
        Matcher macMatcher = MACOS_PATTERN.matcher(operatingSystem);
        if (macMatcher.find()) {
            // "_" 分隔交由 extractMajorVersion 内部按 [._] 取首段处理，无需在此预替换
            return "macOS" + extractMajorVersion(macMatcher.group(1));
        }
        // Android系统
        Matcher androidMatcher = ANDROID_PATTERN.matcher(operatingSystem);
        if (androidMatcher.find()) {
            return "Android" + extractMajorVersion(androidMatcher.group(1));
        }
        // iOS系统
        Matcher iosMatcher = IOS_PATTERN.matcher(operatingSystem);
        if (iosMatcher.find() && (operatingSystem.contains("iPhone") || operatingSystem.contains("iPad"))) {
            return "iOS" + extractMajorVersion(iosMatcher.group(1));
        }
        // Linux系统
        if (LINUX_PATTERN.matcher(operatingSystem).find() && !operatingSystem.contains("Android")) {
            return "Linux";
        }
        // Chrome OS
        if (CHROMEOS_PATTERN.matcher(operatingSystem).find()) {
            return "Chrome OS";
        }
        return StringUtils.EMPTY;
    }

    /**
     * 提取主版本号：直接按 "." 或 "_" 取首段（macOS 旧格式用 "_" 分隔如 "10_15_7"，其余用 "." 或纯数字）。
     * 去掉原先“清理非数字后按点分割、三位数再除以 10”的脆弱启发式——它会把 "10_15_7" 误算成 "1015"。
     */
    private static String extractMajorVersion(String fullVersion) {
        if (StringUtils.isEmpty(fullVersion)) {
            return StringUtils.EMPTY;
        }
        // 只需首段：取首个 "." 或 "_" 之前的子串，避免 split() 每次分配整个数组。
        int idx = fullVersion.indexOf('_');
        int dot = fullVersion.indexOf('.');
        if (dot >= 0 && (idx < 0 || dot < idx)) {
            idx = dot;
        }
        String first = idx >= 0 ? fullVersion.substring(0, idx) : fullVersion;
        return first.matches("\\d+") ? first : fullVersion;
    }

    /**
     * Windows版本号显示优化
     */
    private static String getWindowsVersionDisplay(String version) {
        switch (version) {
            case "10.0":
                return "10";
            case "6.3":
                return "8.1";
            case "6.2":
                return "8";
            case "6.1":
                return "7";
            case "6.0":
                return "Vista";
            case "5.1":
                return "XP";
            case "5.0":
                return "2000";
            default:
                return extractMajorVersion(version);
        }
    }
}
