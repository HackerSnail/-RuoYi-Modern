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
import java.util.regex.Pattern;
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

    /**
     * host 形式白名单：仅允许 RFC 1123 主机名（总长 ≤253，每标签 1~63 字节且首尾为字母/数字，中间可含
     * {@code -}）。十进制/十六进制/前导零八进制的 numeric host（如 {@code 2130706433}、{@code 0x7f.0.0.1}、
     * {@code 010.0.0.1}）一律前置拒绝，不再依赖 {@link InetAddress#getAllByName} 跨版本行为。
     */
    private static final Pattern HOSTNAME_PATTERN = Pattern.compile(
            "^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$");

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
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                // 禁用自动重定向：3xx 不被静默跟随到可能不受信任（内网/元数据）的目标；
                // 客户端级一次性失效，无需再在 RequestConfig 里 setRedirectsEnabled(false)。
                .disableRedirectHandling()
                .build();
    }

    /**
     * 无 host 白名单的便捷重载，等价于 {@code sendGet(url, params, charset, null)}。
     * 目标可枚举场景（如固定的 IP 库域名）应用带 {@code allowedHosts} 的重载进一步收敛攻击面。
     */
    public static String sendGet(String url, Map<String, String> params, String charset) throws IOException {
        return sendGet(url, params, charset, null);
    }

    /**
     * 向指定 URL 发送 GET 请求并返回响应体字符串。
     * <p>
     * 安全约束：① 仅允许 http/https 协议；② 域名解析出的地址必须是公网地址（回环/站点本地/链路本地/
     * 任意本地/组播/CGNAT(100.64/10)/各保留段/IPv6 ULA，以及 IPv4-mapped·IPv4-compatible·6to4·Teredo 内嵌的
     * 私网 IPv4 一律拒绝），校验与实际连接共用同一批解析结果，闭合 DNS 重绑定竞态；③ 禁用自动重定向；
     * ④ 连接与响应均设超时；⑤ 响应体设 8 MiB 上限，超限即抛；⑥ 端口白名单，仅允许默认/80/443，
     * 防止被引向非常规端口暴露内部服务；⑦ 可选域白名单，{@code allowedHosts} 非空时严格匹配
     * {@code uri.getHost()}（大小写不敏感）——黑名单适用于目标不可枚举的通用代理，白名单适用于
     * 目标可枚举的固定域名场景（如 {@code AddressUtils}），两者叠加最稳。
     * <p>
     * 查询参数由 {@code params} 逐项按 UTF-8 百分号编码后拼接，调用方无需（也不应）自行编码，避免
     * {@code #} 被截为 fragment、以及 {@code &}/{@code =} 语义污染。注意：逐值编码会把 IPv6 地址里的
     * {@code :} 编成 {@code %3A}，若目标服务要求裸 {@code :} 需自行适配。
     * <p>
     * 失败语义：协议非法、端口非法、host 不在白名单、字符集非法、解析到被拒地址、连接/读取失败或超时、
     * 响应体超限、以及收到非 2xx 响应，一律<b>抛出</b> {@link IOException}。
     *
     * @param url          请求 URL（协议必须是 http/https）
     * @param params       查询参数键值对；为 null 或空则不带 query。键与值均按 UTF-8 百分号编码
     * @param charset      响应体解码字符集名（如 {@code "GBK"}/{@code "UTF-8"}）；为空白时按 UTF-8
     * @param allowedHosts 可选域白名单；非空时 {@code uri.getHost()} 必须大小写不敏感命中其中一项；
     *                     比对前两侧均剥 IPv6 方括号（因 {@code URI#getHost()} 对 IPv6 字面量可能带
     *                     也可能不带 {@code []}，跨 JDK 不一致），因而 {@code "[::1]"} 与 {@code "::1"}
     *                     可互替书写；null/空退化为仅依赖地址黑名单拦 SSRF
     * @return 响应体字符串（按原始字节解码，精确保留换行；远程空响应返回空串）
     * @throws IOException URL 非法、协议/端口/host 不受支持、响应字符集非法、解析到被拒绝的地址，或连接/读取失败/超时/响应超限/非 2xx
     */
    public static String sendGet(String url, Map<String, String> params, String charset, Set<String> allowedHosts)
            throws IOException {
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
        // SSRF 防线之二：端口白名单，-1(默认)/80/443 以外一律拒绝
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) {
            throw new IOException("sendGet 端口不受支持，仅允许默认/80/443，实际为: " + port);
        }
        // SSRF 防线之三（可选纵深）：目标可枚举时的域白名单，严于任何地址黑名单；
        // 两侧均剥 IPv6 方括号 + 小写归一，让 "[::1]" 与 "::1" 可互替书写。
        if (allowedHosts != null && !allowedHosts.isEmpty()) {
            String hostOfUri = normalizeHostForWhitelist(uri.getHost());
            boolean allowed = false;
            for (String a : allowedHosts) {
                if (a != null && normalizeHostForWhitelist(a).equals(hostOfUri)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw new IOException("sendGet host 不在允许清单，实际为: " + abbrev(hostOfUri));
            }
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
     * url 已带 query 时用 {@code &} 续接，否则用 {@code ?}。键/值中任一侧为 null 均视作空串。
     * <p>
     * 编码约定：采用 {@link URLEncoder#encode(String, java.nio.charset.Charset)} 的 HTML 表单编码——
     * 空格→{@code +}、{@code :}→{@code %3A} 等。与 RFC 3986 的 {@code %20}/{@code :} 略不同，但
     * 对目标服务普遍采用 form-urlencoded 语义的 query 串无影响；若对端严格遵循 RFC 3986（如局部
     * 需预签名的 API），需自行预先拼接并传入 null params。
     * <p>
     * 参数顺序：取决于 {@link Map} 本身的迭代顺序；如需稳定顺序（如签名预映），请传 {@link LinkedHashMap}
     * 或 {@link java.util.SortedMap}，{@code HashMap}/{@code Map.of} 不保证顺序。
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
            String k = entry.getKey();
            String v = entry.getValue();
            q.append(URLEncoder.encode(k == null ? "" : k, StandardCharsets.UTF_8)).append('=')
             .append(URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8));
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
        try (InputStream in = entity.getContent();
             ByteArrayOutputStream bos = new ByteArrayOutputStream(
                     (int) Math.min(Math.max(declared, 0), max))) {
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
     * 站点本地/组播)；对 {@code 2001:0000::/32}(Teredo) 抽 bytes 4~7 服务端 IPv4 与 bytes 12~15
     * 按位取反的客户端 IPv4 内嵌一并判；{@code 2001:db8::/32} 文档段直接拒。
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
            // Teredo 2001:0000::/32：服务端 IPv4 藏 bytes 4~7，客户端 IPv4 藏 bytes 12~15 且按位取反
            if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01 && b[2] == 0 && b[3] == 0) {
                if (isBlockedIpv4(b[4] & 0xFF, b[5] & 0xFF, b[6] & 0xFF, b[7] & 0xFF)) {
                    return true;
                }
                if (isBlockedIpv4((~b[12] & 0xFF), (~b[13] & 0xFF), (~b[14] & 0xFF), (~b[15] & 0xFF))) {
                    return true;
                }
            }
            // 2001:db8::/32 IPv6 文档段（RFC 3849）：不属于任何可路由地址，一律拒
            if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01 && (b[2] & 0xFF) == 0x0D && (b[3] & 0xFF) == 0xB8) {
                return true;
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
        if (o0 == 192 && o1 == 0 && o2 == 0) return true;   // 192.0.0/24 IETF Protocol Assignments
        if (o0 == 192 && o1 == 88 && o2 == 99) return true; // 192.88.99/24 6to4 relay anycast(已废弃)
        if (o0 == 192 && o1 == 168) return true;          // 192.168/16
        if (o0 == 198 && o1 == 51 && o2 == 100) return true; // 198.51.100/24 TEST-NET-2
        if (o0 == 203 && o1 == 0 && o2 == 113) return true;  // 203.0.113/24 TEST-NET-3
        if (o0 == 198 && (o1 & 0xFE) == 18) return true;  // 198.18.0.0/15 benchmark
        if (o0 >= 224) return true;                        // 224/4 组播 + 240/4 保留 + 255.255.255.255
        return false;
    }

    /**
     * 安全 DnsResolver：先对 host 形式做白名单校验（拒绝 numeric host 的跨版本歧义），再解析并
     * 逐一校验地址；只要有一个解析结果落在内网/保留/链路本地/元数据/CGNAT/ULA/Teredo 内嵌私网/IPv6 文档段
     * 等白名单外，即抛 {@link UnknownHostException}（{@code IOException}
     * 子类）拒绝连接。返回的地址数组
     * 会被连接池直接用于建立 socket，校验与实际连接之间不发生第二次解析，故 DNS 重绑定无法绕过本校验。
     */
    static final class SafeDnsResolver implements DnsResolver {
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            if (host == null || host.isEmpty()) {
                throw new UnknownHostException("sendGet 目标主机为空");
            }
            // host 形式白名单：拒绝 numeric host(十进制/十六进制/前导零八进制)的跨版本歧义；
            // 方括号 IPv6 先取中内容，再交给 getAllByName（它不接受字面上的方括号）。
            assertAllowedHostFormat(host);
            String target = stripBracketsIfIpv6Literal(host);
            InetAddress[] addresses = InetAddress.getAllByName(target);
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
     * host 形式白名单：仅允许三形态之一 —— ① RFC 1123 主机名（且不命中"单标签全数字"或"任一数字标签带前导零"
     * 的歧义 numeric host）；② 严格点分四段 IPv4（每段 0~255、无多前导零）；③ 方括号包裹的 IPv6 字面量。
     * 否则抛 {@link UnknownHostException}，不再依赖 {@link InetAddress#getAllByName} 对 numeric host
     * 形式的跨版本行为。主要封住 {@code 2130706433}(十进制→127.0.0.1)、{@code 0x7f.0.0.1}(十六进制)、
     * {@code 010.0.0.1}(前导零可能被解为八进制的 8.0.0.1，与浏览器/OS 不一致) 等形式。
     * <p>包可见以便单测直接断言。</p>
     */
    static void assertAllowedHostFormat(String host) throws UnknownHostException {
        if (host == null || host.isEmpty()) {
            throw new UnknownHostException("sendGet host 为空");
        }
        if (host.length() > 253) {
            throw new UnknownHostException("sendGet host 总长超 253: " + abbrev(host));
        }
        // 形态三：方括号 IPv6。内容不在此处再预格式校验，交由后续 getAllByName 解析失败自然抛。
        if (host.charAt(0) == '[') {
            if (host.charAt(host.length() - 1) != ']' || host.length() < 3) {
                throw new UnknownHostException("sendGet IPv6 字面量方括号不完整: " + abbrev(host));
            }
            return;
        }
        // 形态二：严格点分四段 IPv4，无前置零
        if (isStrictDottedQuadIpv4(host)) {
            return;
        }
        // 形态一：RFC 1123 主机名 + 排除 numeric 歧义
        if (HOSTNAME_PATTERN.matcher(host).matches() && !isAmbiguousNumericHost(host)) {
            return;
        }
        throw new UnknownHostException("sendGet host 形式不受支持，仅允许主机名/严格点分四段 IPv4/方括号 IPv6: " + abbrev(host));
    }

    /**
     * 域白名单比对归一化：null/空→""；剥 IPv6 方括号包裹 {@code [...]}；剩 lowercase。使用
     * {@link java.util.Locale#ROOT}，不受主机区域设置影响。</p>
     * <p>包可见以便单测直接断言。</p>
     */
    static String normalizeHostForWhitelist(String host) {
        if (host == null || host.isEmpty()) {
            return "";
        }
        String h = host;
        if (h.length() >= 2 && h.charAt(0) == '[' && h.charAt(h.length() - 1) == ']') {
            h = h.substring(1, h.length() - 1);
        }
        return h.toLowerCase(Locale.ROOT);
    }

    /** 剥方括号 IPv6 字面量的包裹符，非包裹形式原样返回（包含 [ 开头的方括号不完整情形，交给 assertAllowedHostFormat 先拒） */
    private static String stripBracketsIfIpv6Literal(String host) {
        if (host.length() >= 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    /** 严格点分四段 IPv4：4 段、每段仅含数字、无多余前导零且数值≤ 255 */
    static boolean isStrictDottedQuadIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) {
                return false;
            }
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            if (p.length() > 1 && p.charAt(0) == '0') {
                return false;
            }
            if (Integer.parseInt(p) > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判定“numeric host 形式歧义”：① 任一标签以 {@code 0x}/{@code 0X} 开头（部分 Java 版本会将十六进制
     * 预置为 IP）→ true；② 任一全数字标签带前导零（如 {@code 010}，可能被解为八进制的 8，与浏览器/OS 不一致）
     * → true；③ 所有标签均为纯数字（如 {@code 2130706433} 十进制→127.0.0.1，{@code 1.2.3} 缩写形式）
     * → true。命中任一 →白名单拒。严格点分四段 IPv4 已在 assertAllowedHostFormat 中优先放行，不会走到这里。
     */
    static boolean isAmbiguousNumericHost(String host) {
        String[] labels = host.split("\\.", -1);
        boolean allLabelsNumeric = true;
        for (String label : labels) {
            if (label.isEmpty()) {
                return true;
            }
            if (label.length() >= 2 && label.charAt(0) == '0'
                    && (label.charAt(1) == 'x' || label.charAt(1) == 'X')) {
                return true;
            }
            boolean allDigits = true;
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (c < '0' || c > '9') {
                    allDigits = false;
                    break;
                }
            }
            if (allDigits) {
                if (label.length() > 1 && label.charAt(0) == '0') {
                    return true;
                }
            } else {
                allLabelsNumeric = false;
            }
        }
        return allLabelsNumeric;
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
