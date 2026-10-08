package com.ruoyi.common.utils.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ruoyi.common.utils.StringUtils;

/**
 * 通用 HTTP GET 工具（基于 Apache HttpClient5）
 * <p>
 * 历史上本类还带有 sendPost / sendSSLPost 等方法，但均在本仓内零调用，且存在请求体平台编码与读端
 * 不一致、{@code conn.disconnect()} 不在 finally、把 GET 参数拼进 URL 却命名为 POST 的语义错乱、
 * 丢弃空白行破坏响应等多处缺陷，属"抄来未消化"的死代码，已整体移除。仅保留被 {@code AddressUtils}
 * 实际使用的 sendGet。
 * <p>
 * 之所以从 {@code HttpURLConnection} 迁到 HttpClient5：①
 * 前者无真正的连接池、{@code disconnect()}
 * 在多数实现下会直接关闭底层 socket，高并发下 fd/端口压力大；② HttpClient5 提供可插拔
 * {@link DnsResolver}，能把"域名解析→安全校验→实际连接"绑定到同一批 IP，从而闭合 DNS 重绑定
 * （rebinding）竞态——这是在 {@code HttpURLConnection}/{@code java.net.http} 上无法干净做到的。
 *
 * @author ruoyi
 */
public final class HttpUtils {
    private static final Logger log = LoggerFactory.getLogger(HttpUtils.class);

    /** HTTP 请求头：Accept */
    private static final String HEADER_ACCEPT = "Accept";

    /** HTTP 请求头：User-Agent */
    private static final String HEADER_USER_AGENT = "User-Agent";

    /** Accept 通配取值 */
    private static final String ACCEPT_ANY = "*/*";

    /** 默认 User-Agent */
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)";

    /** 允许的请求协议白名单：仅 http/https。杜绝 file/dict/gopher/ftp 等被拼接利用造成 SSRF */
    private static final Set<String> ALLOWED_PROTOCOLS = Set.of("http", "https");

    /** 建立连接超时 */
    private static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(10);

    /** 等待响应超时 */
    private static final Timeout RESPONSE_TIMEOUT = Timeout.ofSeconds(15);

    /** 单次 GET 响应体字节上限（8 MiB）：在读取阶段分块累计并即时判限，超限即抛，防超大 body 入内存 OOM */
    private static final long MAX_RESPONSE_BYTES = 8L * 1024 * 1024;

    /** 日志中 url/param/响应体的最大打印长度，超出截断，防止撑爆日志 */
    private static final int MAX_LOG_VALUE_LEN = 200;

    /**
     * 进程级共享客户端：连接池随客户端复用，避免每次请求重建 socket/线程。HttpClient5 的客户端设计为
     * 线程安全、可长期共享，故静态持有；其生命周期与应用一致，无需显式关闭。
     * <p>
     * 权衡：静态持有意味着热重载（devtools restart / 容器反复 reload）会各留一份连接池，直至旧
     * ClassLoader 被 GC；对一次性加载的普通 Boot 应用可接受。若某场景需高频重载，应改为纳入 Spring
     * 生命周期的 bean 并在销毁时 {@code close()}。
     */
    private static final CloseableHttpClient HTTP_CLIENT = buildClient();

    private HttpUtils() {
    }

    private static CloseableHttpClient buildClient() {
        // 关键：连接池使用 SafeDnsResolver，解析出的 IP 先经内网/保留地址校验，随后 socket 直接连到
        // 这批已校验的地址——校验与连接之间不再发生第二次解析，故对 DNS 重绑定是免疫的（TOCTOU 闭合）。
        // 连接超时在 5.2 起从 RequestConfig.setConnectTimeout（已标 @Deprecated）迁至 ConnectionConfig，
        // 并通过连接池构建器的 setDefaultConnectionConfig 注册；响应超时仍留在 RequestConfig（未废弃）。
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT)
                .setSocketTimeout(RESPONSE_TIMEOUT)
                .build();
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new SafeDnsResolver())
                .setDefaultConnectionConfig(connectionConfig)
                .setMaxConnTotal(100)
                .setMaxConnPerRoute(20)
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(RESPONSE_TIMEOUT)
                // 禁用自动重定向：3xx 不被静默跟随到可能不受信任（内网/元数据）的目标
                .setRedirectsEnabled(false)
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()
                .build();
    }

    /**
     * 向指定 URL 发送 GET 请求并返回响应体字符串。
     * <p>
     * 安全约束：① 仅允许 http/https 协议；② 域名解析出的地址必须是公网地址（回环/站点本地/链路本地/
     * 任意本地/组播/CGNAT(100.64/10)/各保留段/IPv6 ULA，以及 IPv4-mapped·IPv4-compatible·6to4 内嵌的
     * 私网 IPv4 一律拒绝），校验与实际连接共用同一批解析结果，闭合 DNS 重绑定竞态；③ 禁用自动重定向；
     * ④ 连接与响应均设超时；⑤ 响应体设 8 MiB 上限，超限即抛。
     * <p>
     * 查询参数由 {@code params} 逐项按 UTF-8 百分号编码后拼接，调用方无需（也不应）自行编码，避免
     * {@code #} 被截为 fragment、以及 {@code &}/{@code =} 语义污染。注意：逐值编码会把 IPv6 地址里的
     * {@code :} 编成 {@code %3A}，若目标服务要求裸 {@code :} 需自行适配。
     * <p>
     * 失败语义：协议非法、字符集非法、解析到被拒地址、连接/读取失败或超时、响应体超限、以及收到非 2xx
     * 响应，一律<b>抛出</b> {@link IOException}（非 2xx 会带上状态码与截断后的错误响应体）。
     *
     * @param url     请求 URL（协议必须是 http/https）
     * @param params  查询参数键值对；为 null 或空则不带 query。键与值均按 UTF-8 百分号编码
     * @param charset 响应体解码字符集名（如 {@code "GBK"}/{@code "UTF-8"}）；为空白时按 UTF-8
     * @return 响应体字符串（按原始字节解码，精确保留换行；远程空响应返回空串）
     * @throws IOException URL 非法、协议不受支持、响应字符集非法、解析到被拒绝的地址，或连接/读取失败/超时/响应超限/非 2xx
     */
    public static String sendGet(String url, Map<String, String> params, String charset) throws IOException {
        String urlNameString = url + buildQuery(url, params);
        URI uri;
        try {
            uri = new URI(urlNameString);
        } catch (URISyntaxException e) {
            log.error("sendGet 非法 URL, url={}", abbrev(urlNameString), e);
            throw new IOException("sendGet 非法 URL: " + abbrev(urlNameString), e);
        }
        // SSRF 防线之一：协议白名单，先于任何连接动作拦截 file/dict/gopher/ftp 等
        String protocol = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_PROTOCOLS.contains(protocol)) {
            throw new IOException("sendGet 协议不受支持，仅允许 http/https，实际为: " + protocol);
        }
        // 原实现的第三参数名为 contentType，实际用途一直是"响应字符集"；此处正名为 charset 并早校验
        Charset cs;
        try {
            cs = StringUtils.isBlank(charset) ? StandardCharsets.UTF_8 : Charset.forName(charset.trim());
        } catch (Exception e) {
            log.error("sendGet 非法响应字符集: {}", charset, e);
            throw new IOException("sendGet 非法响应字符集: " + charset, e);
        }

        log.info("sendGet - {}", abbrev(urlNameString));
        HttpGet httpGet = new HttpGet(uri);
        httpGet.setHeader(HEADER_ACCEPT, ACCEPT_ANY);
        httpGet.setHeader(HEADER_USER_AGENT, DEFAULT_USER_AGENT);
        final Charset charsetForDecode = cs;
        // 响应体以原始字节有界读取后按字符集解码：不做行级拆分，故 \r\n、空行、无前导/尾随换行均被精确保留。
        // 非 2xx（含被禁用的 3xx）不返回体而是抛带状态码的 IOException。
        return HTTP_CLIENT.execute(httpGet, response -> {
            byte[] raw = readLimited(response.getEntity(), MAX_RESPONSE_BYTES);
            String body = interpretResponse(response.getCode(), raw, charsetForDecode, url);
            log.info("recv - {}", abbrev(body));
            return body;
        });
    }

    /**
     * 由 params 构造百分号编码后的查询串（含前导 {@code ?} 或 {@code &}）；params 为 null/空返回空串。
     * url 已带 query 时用 {@code &} 续接，否则用 {@code ?}。键值均按 UTF-8 编码，null 值视作空串。
     * （包可见以便单测直接断言编码语义。）
     */
    static String buildQuery(String url, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (q.length() > 0) {
                q.append('&');
            }
            q.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)).append('=')
             .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(), StandardCharsets.UTF_8));
        }
        return (url != null && url.indexOf('?') >= 0 ? "&" : "?") + q;
    }

    /**
     * 有界读取响应体：先看 Content-Length 做快速失败，再以 8KB 分块累计，一旦超过 {@code max} 立即抛，
     * 防止对端谎报或分块无长度时超大 body 全量入内存导致 OOM。entity 为 null 返回空数组。
     */
    private static byte[] readLimited(HttpEntity entity, long max) throws IOException {
        if (entity == null) {
            return new byte[0];
        }
        long declared = entity.getContentLength();
        if (declared > max) {
            throw new IOException("sendGet 响应体声明长度超过上限 " + max + " 字节, 声明=" + declared);
        }
        try (InputStream in = entity.getContent(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > max) {
                    throw new IOException("sendGet 响应体超过上限 " + max + " 字节");
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /**
     * 把 HTTP 响应映射为字符串：2xx 返回按 {@code charset} 解码的响应体；非 2xx 抛出携带状态码与（截断后）
     * 错误响应体的 {@link IOException}。抽出为独立方法便于单测与复用。
     *
     * @param statusCode HTTP 状态码
     * @param rawBody    原始响应体字节（可为空数组）
     * @param charset    解码字符集
     * @param url        仅用于异常信息的目标 URL
     * @return 2xx 时解码后的响应体（空 body 返回空串）
     * @throws IOException 非 2xx 响应
     */
    public static String interpretResponse(int statusCode, byte[] rawBody, Charset charset, String url)
            throws IOException {
        String body = new String(rawBody == null ? new byte[0] : rawBody, charset);
        if (statusCode >= 200 && statusCode < 300) {
            return body;
        }
        throw new IOException("sendGet 收到非 2xx 响应, status=" + statusCode + ", url=" + abbrev(url)
                + (body.isEmpty() ? "" : ", body=" + abbrev(body)));
    }

    /**
     * 判定一个已解析的 IP 是否属于必须拒绝的非公网地址。逐字节显式判定 v4，并对 IPv6 先还原内嵌 IPv4：
     * ① IPv4 拒绝 0/8、10/8、100.64/10(CGNAT)、127/8、169.254/16(含 169.254.169.254 元数据)、
     * 172.16/12、192.0.2/24、192.168/16、198.18/15、198.51.100/24、203.0.113/24、224/4 组播、
     * 240/4 保留(含 255.255.255.255)；② IPv6 对 {@code ::ffff:a.b.c.d}(mapped) 与 {@code ::a.b.c.d}
     * (compatible) 抽出内嵌 IPv4 再按 ① 判（否则 {@code ::ffff:169.254.169.254} 会伪装成公网 IPv6
     * 绕过所有 v4 规则），对 {@code 2002::/16}(6to4) 抽出第 2~5 字节内嵌 IPv4 判；纯 IPv6 再判
     * ULA(fc00::/7，JDK isSiteLocal 只覆盖已废弃的 fec0::/10) 及 JDK 谓词(回环/任意本地/链路本地/
     * 站点本地/组播)。
     *
     * @param addr 已解析地址，非空
     * @return 命中任一被拒类别返回 true
     */
    public static boolean isBlockedAddress(InetAddress addr) {
        if (addr == null) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            return isBlockedIpv4(b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF, b[3] & 0xFF);
        }
        if (b.length == 16) {
            // IPv4-mapped(::ffff:a.b.c.d) / IPv4-compatible(::a.b.c.d)：内嵌 IPv4 藏在末 4 字节
            if (isIpv4Embedded(b)) {
                return isBlockedIpv4(b[12] & 0xFF, b[13] & 0xFF, b[14] & 0xFF, b[15] & 0xFF);
            }
            // 6to4 2002::/16：内嵌 IPv4 藏在第 2~5 字节
            if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02) {
                return isBlockedIpv4(b[2] & 0xFF, b[3] & 0xFF, b[4] & 0xFF, b[5] & 0xFF);
            }
            // 纯 IPv6：ULA(fc00::/7) + JDK 谓词
            int first = b[0] & 0xFF;
            return first == 0xFC || first == 0xFD
                    || addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress()
                    || addr.isSiteLocalAddress() || addr.isMulticastAddress();
        }
        // 未知长度：保守拒绝
        return true;
    }

    /** 判断 16 字节 IPv6 是否携带内嵌 IPv4：前 10 字节全 0，且第 10~11 字节为 FF FF(mapped) 或 00 00(compatible) */
    private static boolean isIpv4Embedded(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return (b[10] == (byte) 0xFF && b[11] == (byte) 0xFF) || (b[10] == 0 && b[11] == 0);
    }

    /** 逐段显式判定 IPv4 是否属于必须拒绝的非公网/保留地址（不依赖 JDK 谓词，供内嵌 IPv4 还原后复用） */
    private static boolean isBlockedIpv4(int o0, int o1, int o2, int o3) {
        if (o0 == 0) return true;                         // 0.0.0.0/8 this-network
        if (o0 == 10) return true;                        // 10/8
        if (o0 == 100 && (o1 & 0xC0) == 64) return true;  // 100.64/10 CGNAT
        if (o0 == 127) return true;                       // 127/8 loopback
        if (o0 == 169 && o1 == 254) return true;          // 169.254/16 link-local(含云元数据)
        if (o0 == 172 && (o1 & 0xF0) == 16) return true;  // 172.16/12
        if (o0 == 192 && o1 == 0 && o2 == 2) return true; // 192.0.2/24 TEST-NET-1
        if (o0 == 192 && o1 == 168) return true;          // 192.168/16
        if (o0 == 198 && o1 == 51 && o2 == 100) return true; // 198.51.100/24 TEST-NET-2
        if (o0 == 203 && o1 == 0 && o2 == 113) return true;  // 203.0.113/24 TEST-NET-3
        if (o0 == 198 && (o1 & 0xFE) == 18) return true;  // 198.18.0.0/15 benchmark
        if (o0 >= 224) return true;                        // 224/4 组播 + 240/4 保留 + 255.255.255.255
        return false;
    }

    /**
     * 安全 DnsResolver：解析主机名后，逐一校验其地址；只要有一个解析结果落在内网/保留/链路本地/元数据/
     * CGNAT/ULA 段，即抛 {@link UnknownHostException}（{@code IOException}
     * 子类）拒绝连接。返回的地址数组
     * 会被连接池直接用于建立 socket，校验与实际连接之间不发生第二次解析，故 DNS 重绑定无法绕过本校验。
     */
    static final class SafeDnsResolver implements DnsResolver {
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            if (host == null || host.isEmpty()) {
                throw new UnknownHostException("sendGet 目标主机为空");
            }
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (isBlockedAddress(address)) {
                    throw new UnknownHostException("sendGet 拒绝连接内网/保留地址: " + host + " -> " + address.getHostAddress());
                }
            }
            return addresses;
        }

        @Override
        public String resolveCanonicalHostname(String host) throws UnknownHostException {
            // 保持原始主机名，供路由/虚拟主机命名使用；不影响 TLS SNI 与证书主机名校验（仍按原主机名）
            return host;
        }
    }

    /**
     * 截断超长文本用于日志与异常信息，避免 url/param/响应体过长撑爆日志。
     */
    private static String abbrev(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_LOG_VALUE_LEN ? s : s.substring(0, MAX_LOG_VALUE_LEN) + "...(truncated)";
    }
}
