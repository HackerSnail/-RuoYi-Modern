package com.ruoyi.common.utils.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ruoyi.common.utils.StringUtils;

/**
 * 通用 HTTP GET 工具（基于 {@link HttpURLConnection}）
 * <p>
 * 历史上本类还带有 sendPost / sendSSLPost 等方法，但均在本仓内零调用，且存在请求体平台编码与读端
 * 不一致、{@code conn.disconnect()} 不在 finally、把 GET 参数拼进 URL 却命名为 POST 的语义错乱、
 * 丢弃空白行破坏响应等多处缺陷，属"抄来未消化"的死代码，已整体移除。仅保留被 {@code AddressUtils}
 * 实际使用的 sendGet，并对其做了协议白名单 / 超时 / 禁用重定向等安全加固。
 *
 * @author ruoyi
 */
public final class HttpUtils {
    private static final Logger log = LoggerFactory.getLogger(HttpUtils.class);

    /** HTTP 请求头：Accept */
    private static final String HEADER_ACCEPT = "accept";

    /** HTTP 请求头：Connection */
    private static final String HEADER_CONNECTION = "connection";

    /** HTTP 请求头：User-Agent */
    private static final String HEADER_USER_AGENT = "user-agent";

    /** Accept 通配取值 */
    private static final String ACCEPT_ANY = "*/*";

    /** Connection 取值：长连接（HttpURLConnection 本默认 Keep-Alive，此处仅为显式声明，不影响行为） */
    private static final String CONNECTION_KEEP_ALIVE = "Keep-Alive";

    /** 默认 User-Agent */
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)";

    /** 允许的请求协议白名单：仅 http/https。杜绝 file/dict/gopher/ftp 等被拼接利用造成 SSRF */
    private static final Set<String> ALLOWED_PROTOCOLS = Set.of("http", "https");

    /** 建立连接超时（毫秒）：防止下游主机不可达时线程长时间挂起 */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /** 读取响应超时（毫秒）：防止下游返回缓慢/悬挂时线程无限阻塞打满容器线程池 */
    private static final int READ_TIMEOUT_MS = 15_000;

    /** 日志中 url/param 的最大打印长度，超出截断，防止超长入参撑爆日志 */
    private static final int MAX_LOG_VALUE_LEN = 200;

    private HttpUtils() {
    }

    /**
     * 向指定 URL 发送 GET 请求并返回响应体字符串。
     * <p>
     * 安全约束：① 仅允许 http/https 协议——{@code new URL("file:/...").openConnection()} 会返回可读
     * 本地文件的 {@code FileURLConnection}，是 SSRF 的入口，故对 file/dict/gopher/ftp 等一律拒绝；
     * ② 禁用自动重定向，避免 302 跳转到内网/云元数据地址绕过协议白名单；③ 连接与读取均设超时。
     * <p>
     * 失败语义：协议非法、字符集非法、连接/读取失败或超时一律<b>抛出</b> {@link IOException}，不再
     * 吞异常返回空串——以便调用方区分"远程确实返回空 body"（正常返回空串）与"请求失败"（抛异常）。
     *
     * @param url     请求 URL（协议必须是 http/https）
     * @param param   查询参数，形如 {@code name1=value1&name2=value2}；为空则不拼接到 URL
     * @param charset 响应体解码字符集名（如 {@code "GBK"}/{@code "UTF-8"}）；为空白时按 UTF-8
     * @return 响应体字符串（保留原始行间换行；远程空响应返回空串）
     * @throws IOException URL 非法、协议不受支持、响应字符集非法，或连接/读取失败/超时
     */
    public static String sendGet(String url, String param, String charset) throws IOException {
        String urlNameString = StringUtils.isNotBlank(param) ? url + "?" + param : url;
        URL realUrl;
        try {
            realUrl = new URL(urlNameString);
        } catch (MalformedURLException e) {
            log.error("sendGet 非法 URL, url={}", abbrev(urlNameString), e);
            throw new IOException("sendGet 非法 URL: " + abbrev(urlNameString), e);
        }
        // SSRF 防线之一：协议白名单，先于任何连接动作拦截 file/dict/gopher/ftp 等
        String protocol = realUrl.getProtocol() == null ? "" : realUrl.getProtocol().toLowerCase(Locale.ROOT);
        if (!ALLOWED_PROTOCOLS.contains(protocol)) {
            throw new IOException("sendGet 协议不受支持，仅允许 http/https，实际为: " + protocol);
        }
        // 原实现的第三参数名为 contentType，实际用途一直是"响应字符集"；此处正名为 charset 并早校验，
        // 避免误传 MIME（如 "application/json"）时才在 InputStreamReader 处抛 IllegalCharsetNameException
        Charset cs;
        try {
            cs = StringUtils.isBlank(charset) ? StandardCharsets.UTF_8 : Charset.forName(charset.trim());
        } catch (Exception e) {
            log.error("sendGet 非法响应字符集: {}", charset, e);
            throw new IOException("sendGet 非法响应字符集: " + charset, e);
        }

        StringBuilder result = new StringBuilder();
        log.info("sendGet - {}", abbrev(urlNameString));
        // 协议已校验为 http/https，其 URLConnection 必为 HttpURLConnection，可安全强转以使用超时/重定向开关
        HttpURLConnection connection = (HttpURLConnection) realUrl.openConnection();
        try {
            connection.setRequestProperty(HEADER_ACCEPT, ACCEPT_ANY);
            connection.setRequestProperty(HEADER_CONNECTION, CONNECTION_KEEP_ALIVE);
            connection.setRequestProperty(HEADER_USER_AGENT, DEFAULT_USER_AGENT);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            // SSRF 防线之二：禁用自动重定向，302 不再被静默跟随到可能不受信任的目标
            connection.setInstanceFollowRedirects(false);
            connection.connect();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), cs))) {
                String line;
                boolean first = true;
                while ((line = in.readLine()) != null) {
                    if (!first) {
                        // 还原行间换行（readLine 会剥离 \r\n/\n，统一补 \n，不追加行尾），避免响应被压成一行
                        result.append('\n');
                    }
                    result.append(line);
                    first = false;
                }
            }
            log.info("recv - {}", abbrev(result.toString()));
            return result.toString();
        } catch (IOException e) {
            log.error("调用 HttpUtils.sendGet 失败, url={}, param={}", abbrev(url), abbrev(param), e);
            throw e;
        } finally {
            // disconnect 置于 finally：即使 connect/getInputStream 抛异常也确保释放底层连接
            connection.disconnect();
        }
    }

    /**
     * 截断超长文本用于日志打印，避免 url/param 过长撑爆日志。仅影响打印内容，不影响实际发出的请求。
     */
    private static String abbrev(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_LOG_VALUE_LEN ? s : s.substring(0, MAX_LOG_VALUE_LEN) + "...(truncated)";
    }
}
