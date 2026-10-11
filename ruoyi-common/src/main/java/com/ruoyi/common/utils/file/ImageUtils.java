package com.ruoyi.common.utils.file;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.utils.StringUtils;

/**
 * 图片处理工具类
 *
 * @author HackerSnail <2306002088@qq.com>
 */
public final class ImageUtils {
    private static final Logger log = LoggerFactory.getLogger(ImageUtils.class);

    /**
     * 图片读取大小上限：远程/本地均限流，防止超大响应把整文件读进内存造成 OOM/DoS
     */
    private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024L;

    private ImageUtils() {
    }

    /**
     * 读取图片文件为字节数组
     *
     * <p>
     * 直接委托 readFile（其内部已按分支限量读取并把异常吞掉转 null），避免历史"字节→流→再读回字节"
     * 的双重复制。失败返回 null 是刻意语义：调用方（ExcelUtil 图片导出）依赖 null 跳过坏图、不中断整体导出。
     * </p>
     *
     * @param imagePath 图片路径（支持 http(s)/local 两种形式）
     * @return 图片字节数组，失败返回 null
     */
    public static byte[] getImage(String imagePath) {
        return readFile(imagePath);
    }

    /**
     * 获取图片的 InputStream（内部委托 readFile 读取后包装为 ByteArrayInputStream，不做多余拷贝）
     *
     * @param imagePath 图片路径
     * @return 输入流，失败返回 null
     */
    public static InputStream getFile(String imagePath) {
        byte[] result = readFile(imagePath);
        return result == null ? null : new ByteArrayInputStream(result);
    }

    /**
     * 读取文件为字节数据
     *
     * <p>
     * 远程与本地两条分支各自独立 TWR 读取、统一受 MAX_IMAGE_BYTES 限流。远程分支做 SSRF 收紧：
     * 仅允许 http/https、目标主机不得是回环/私网/链路本地(含 169.254.169.254 元数据)等内网地址、
     * 禁用重定向、要求 Content-Type 为 image/*；本地分支把拼接出的最终路径锚定在 profile 根目录内。
     * 任一校验不过或读取失败均记录日志并返回 null。
     * </p>
     *
     * @param url 地址（以 http:// 或 https:// 开头则为网络地址，否则按本地 profile 资源路径处理）
     * @return 字节数据，失败返回 null
     */
    public static byte[] readFile(String url) {
        if (StringUtils.isEmpty(url)) {
            return null;
        }
        try {
            if (StringUtils.startsWithAny(url, "http://", "https://")) {
                return readRemoteImage(url);
            }
            return readLocalImage(url);
        } catch (Exception e) {
            log.error("读取图片失败 {}", url, e);
            return null;
        }
    }

    /**
     * 读取网络图片：协议/SSRF/重定向/Content-Type/大小 五重收紧
     */
    private static byte[] readRemoteImage(String url) throws IOException {
        URL urlObj = new URL(url);
        String protocol = urlObj.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            log.warn("拒绝非 http(s) 协议的图片地址: {}", url);
            return null;
        }
        // SSRF 防护：解析目标主机，拦截回环/私网/链路本地/任意本地等内网地址（含云元数据 169.254.0.0/16）
        if (isBlockedHost(urlObj.getHost())) {
            log.warn("拒绝指向内网/受限地址的图片 URL: {}", url);
            return null;
        }
        HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
        try {
            conn.setConnectTimeout(30 * 1000);
            conn.setReadTimeout(60 * 1000);
            conn.setDoInput(true);
            // 不自动跟随重定向：否则前置的主机校验可被 3xx 跳到内网绕过
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                log.warn("拒绝会重定向的图片 URL(code={}): {}", code, url);
                return null;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                log.warn("图片 URL 响应异常(code={}): {}", code, url);
                return null;
            }
            String contentType = conn.getContentType();
            if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
                log.warn("响应非图片 Content-Type({}): {}", contentType, url);
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                return readBounded(in, MAX_IMAGE_BYTES, url);
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 读取本地图片：拼出最终路径后锚定在 profile 根目录内，越界直接拒绝
     */
    private static byte[] readLocalImage(String url) throws IOException {
        String localPath = RuoYiConfig.getProfile();
        String downloadPath = localPath + StringUtils.substringAfter(url, Constants.RESOURCE_PREFIX);
        // 基目录锚定：资源地址经 substringAfter 拼接后仍必须落在 profile 内，杜绝 ../ 等跳出
        if (!FileUtils.isPathUnderBase(localPath, downloadPath)) {
            log.warn("拒绝跳出 profile 根目录的本地图片路径: {}", url);
            return null;
        }
        try (InputStream in = new FileInputStream(downloadPath)) {
            return readBounded(in, MAX_IMAGE_BYTES, url);
        }
    }

    /**
     * 限量读取：累计超过 maxBytes 立即中止并返回 null，避免无界读入撑爆内存
     */
    private static byte[] readBounded(InputStream in, long maxBytes, String source) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                log.warn("图片读取超出大小上限 {} 字节: {}", maxBytes, source);
                return null;
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /**
     * 判断主机是否指向内网/受限地址。解析失败或命中回环/私网/链路本地/任意本地/CGNAT/IPv6 ULA 均视为受限。
     * <p>
     * 注意：此处先解析再连接存在 DNS 重绑定(TOCTOU)理论窗口，属纵深防御而非绝对隔离；如需强隔离须在连接层
     * 固定已解析 IP。对当前"导出内网图片"场景已足以阻断直连元数据/内网网段的常见 SSRF。
     */
    private static boolean isBlockedHost(String host) {
        if (StringUtils.isEmpty(host)) {
            return true;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            log.warn("图片 URL 主机解析失败: {}", host, e);
            return true;
        }
        for (InetAddress addr : addresses) {
            if (isBlockedAddress(addr)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlockedAddress(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
                || addr.isAnyLocalAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int o1 = b[0] & 0xFF;
            int o2 = b[1] & 0xFF;
            // 100.64.0.0/10 CGNAT（部分云内网/元数据段）
            return o1 == 100 && o2 >= 64 && o2 <= 127;
        }
        if (b.length == 16) {
            // IPv6 唯一本地地址 fc00::/7
            return (b[0] & 0xFE) == 0xFC;
        }
        return false;
    }
}
