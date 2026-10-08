package com.ruoyi.common.utils.http;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.ruoyi.common.utils.StringUtils;

/**
 * UserAgent解析工具类
 * 
 * @author ruoyi
 */
public class UserAgentUtils {

    // 浏览器正则表达式模式
    private static final Pattern CHROME_PATTERN = Pattern.compile("Chrome/(\\d+)(?:\\.\\d+)*");
    private static final Pattern FIREFOX_PATTERN = Pattern.compile("Firefox/(\\d+)(?:\\.\\d+)*");
    private static final Pattern EDGE_PATTERN = Pattern.compile("Edg(?:e)?/(\\d+)(?:\\.\\d+)*");
    private static final Pattern SAFARI_PATTERN = Pattern.compile("Version/(\\d+)(?:\\.\\d+)*");
    private static final Pattern OPERA_PATTERN = Pattern.compile("Opera/(\\d+)(?:\\.\\d+)*");
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
        // Chrome系列浏览器
        Matcher chromeMatcher = CHROME_PATTERN.matcher(browser);
        if (chromeMatcher.find() && (browser.contains("Chrome") || browser.contains("CriOS"))) {
            return "Chrome" + chromeMatcher.group(1);
        }
        // Firefox
        Matcher firefoxMatcher = FIREFOX_PATTERN.matcher(browser);
        if (firefoxMatcher.find()) {
            return "Firefox" + firefoxMatcher.group(1);
        }
        // Safari浏览器（需排除Chrome）
        Matcher safariMatcher = SAFARI_PATTERN.matcher(browser);
        if (safariMatcher.find() && !browser.contains("Chrome")) {
            return "Safari" + safariMatcher.group(1);
        }
        // Opera浏览器
        Matcher operaMatcher = OPERA_PATTERN.matcher(browser);
        if (operaMatcher.find()) {
            return "Opera" + operaMatcher.group(1);
        }
        // IE浏览器
        Matcher ieMatcher = IE_PATTERN.matcher(browser);
        if (ieMatcher.find()) {
            return "Internet Explorer" + ieMatcher.group(1);
        }
        return StringUtils.EMPTY;
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
            String version = macMatcher.group(1).replace("_", ".");
            return "macOS" + extractMajorVersion(version);
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
     * 提取优化的主版本号
     */
    private static String extractMajorVersion(String fullVersion) {
        if (StringUtils.isEmpty(fullVersion)) {
            return StringUtils.EMPTY;
        }
        try {
            // 清理版本号中的非数字字符
            String cleanVersion = fullVersion.replaceAll("[^0-9.]", "");
            String[] parts = cleanVersion.split("\\.");
            if (parts.length > 0) {
                String firstPart = parts[0];
                if (firstPart.matches("\\d+")) {
                    int version = Integer.parseInt(firstPart);

                    // 仅针对 macOS 10.x 时代以 "_" 分隔的旧格式（清理后可能得到三位数），把 142
                    // 归一成 14；现代 macOS(11+/12+/14+)、Android、iOS、Windows 均为一到两位数，
                    // 不会走到这条分支。
                    if (version >= 100) {
                        return String.valueOf(version / 10);
                    }
                    return firstPart;
                }
            }
        } catch (NumberFormatException e) {
            // 版本号解析失败，返回原始值
        }
        return fullVersion;
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
