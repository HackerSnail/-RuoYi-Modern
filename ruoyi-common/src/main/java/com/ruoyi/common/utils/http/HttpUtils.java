package com.ruoyi.common.utils.http;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
import org.apache.hc.core5.http.io.entity.EntityUtils;
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

    /** 日志中 url/param/响应体的最大打印长度，超出截断，防止撑爆日志 */
    private static final int MAX_LOG_VALUE_LEN = 200;

    /**
     * 进程级共享客户端：连接池随客户端复用，避免每次请求重建 socket/线程。HttpClient5 的客户端设计为
     * 线程安全、可长期共享，故静态持有；其生命周期与应用一致，无需显式关闭。
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
     * 安全约束：① 仅允许 http/https 协议——{@code file:/dict:/gopher:} 等一律先于连接动作拒绝；
     * ② 域名解析出的地址必须是公网地址，回环/站点本地/链路本地/任意本地/组播/CGNAT(100.64/10)/
     * IPv6 ULA(fc00::/7) 等一律拒绝（云元数据 169.254.169.254 属链路本地，被同条规则覆盖）；该校验
     * 与实际连接共用同一批解析结果，闭合 DNS 重绑定竞态；③ 禁用自动重定向，避免 3xx 跳到内网绕过②；
     * ④ 连接与响应均设超时。
     * <p>
     * 失败语义：协议非法、字符集非法、解析到被拒地址、连接/读取失败或超时、以及收到非 2xx 响应，
     * 一律<b>抛出</b> {@link IOException}（非 2xx 会带上状态码与截断后的错误响应体）——不再吞异常返回
     * 空串，以便调用方区分"远程确实返回空 body"（正常返回空串）与"请求失败"（抛异常）。
     *
     * @param url     请求 URL（协议必须是 http/https）
     * @param param   查询参数，形如 {@code name1=value1&name2=value2}；为空则不拼接到 URL
     * @param charset 响应体解码字符集名（如 {@code "GBK"}/{@code "UTF-8"}）；为空白时按 UTF-8
     * @return 响应体字符串（按原始字节解码，精确保留换行；远程空响应返回空串）
     * @throws IOException URL 非法、协议不受支持、响应字符集非法、解析到被拒绝的地址，或连接/读取失败/超时/非 2xx
     */
    public static String sendGet(String url, String param, String charset) throws IOException {
        String urlNameString = StringUtils.isNotBlank(param) ? url + "?" + param : url;
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
        // 响应体以原始字节读取后按字符集解码：不做行级拆分，故 \r\n、空行、无前导/尾随换行均被精确保留。
        // 非 2xx（含被禁用的 3xx）不返回体而是抛带状态码的 IOException。
        return HTTP_CLIENT.execute(httpGet, response -> {
            HttpEntity entity = response.getEntity();
            byte[] raw = entity == null ? new byte[0] : EntityUtils.toByteArray(entity);
            String body = interpretResponse(response.getCode(), raw, charsetForDecode, url);
            log.info("recv - {}", abbrev(body));
            return body;
        });
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
     * 判定一个已解析的 IP 是否属于必须拒绝的非公网地址：回环、任意本地(0.0.0.0)、链路本地(含 169.254/16
     * 云元数据)、站点本地(10/8、172.16/12、192.168/16)、组播，以及 JDK 内置判定未覆盖的
     * CGNAT(100.64.0.0/10) 与 IPv6 Unique-Local(fc00::/7)。
     *
     * @param addr 已解析地址，非空
     * @return 命中任一被拒类别返回 true
     */
    public static boolean isBlockedAddress(InetAddress addr) {
        if (addr == null) {
            return true;
        }
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int o0 = b[0] & 0xFF;
            int o1 = b[1] & 0xFF;
            // 100.64.0.0/10 运营商级 NAT（CGNAT），既非站点本地也非链路本地，需单独拦截
            return o0 == 100 && (o1 & 0xC0) == 64;
        }
        if (b.length == 16) {
            int first = b[0] & 0xFF;
            // fc00::/7 IPv6 Unique Local Address
            return first == 0xFC || first == 0xFD;
        }
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
