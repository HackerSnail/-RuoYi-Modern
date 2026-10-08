package com.ruoyi.common.utils.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.ServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通用 http 工具封装
 *
 * @author ruoyi
 */
public final class HttpHelper {
    /**
     * 单次读取请求体的字符上限（约 1MB），与 {@code RepeatableFilter} 对 JSON body 的包装上限保持一致。
     * 超出即抛出、继不静默截断——截断会把 JSON 读成残缺串，比失败更危险；chunked（无 Content-Length）
     * 请求体在过滤器层无法预先拦截，此处作为最后一道兜底防 OOM。
     */
    private static final int MAX_BODY_CHARS = 1024 * 1024;

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpHelper.class);

    private HttpHelper() {
    }

    /**
     * 读取请求体为字符串。
     * <p>
     * <b>使用契约（重要）</b>：本方法会<b>一次性消费</b> {@code request.getInputStream()}——
     * {@code getInputStream()} 与 {@code getParameter()}/{@code @RequestBody} 互斥且流不可重读。
     * 因此只应在两种场景调用：
     * <ol>
     *   <li>在 {@link com.ruoyi.common.filter.RepeatedlyRequestWrapper} 构造时对<b>原始</b>请求调用一次，
     *       把流缓存成可重复读的 {@code byte[]}（这是消费流的正当用途）；</li>
     *   <li>对<b>已被包装成可重复读</b>的请求（其 {@code getInputStream()} 每次返回新的内存流）重复调用。</li>
     * </ol>
     * 切勿对未包装的原始请求在业务链中途调用，否则后续取参会拿不到值、{@code @RequestBody} 反序列化为空。
     * <p>
     * 编码：JSON 场景由调用方（{@code RepeatedlyRequestWrapper}）先行 {@code setCharacterEncoding(UTF-8)}
     * 且 {@code application/json} 依 RFC 8259 应为 UTF-8，故固定按 UTF-8 解码；不采信容器默认编码，
     * 以免未声明字符集时退回 ISO-8859-1 把 UTF-8 正文读成乱码。
     *
     * @param request 请求对象
     * @return 请求体字符串；body 本就为空时返回空串
     * @throws IOException 读取失败，或请求体超过 {@link #MAX_BODY_CHARS} 上限——以此与“空 body”区分，
     *                     调用方须感知失败而非拿到一个来源不明的空串
     */
    public static String getBodyString(ServletRequest request) throws IOException {
        StringBuilder sb = new StringBuilder();
        // reader 与其包装的 inputStream 一并纳入 try-with-resources，关闭 reader 即关闭底层流，无需手动 finally
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) {
                    // 还原行间换行（readLine 已剔离 \r\n/\n，这里统一补 \n，不追加行尾换行）
                    sb.append('\n');
                }
                sb.append(line);
                first = false;
                if (sb.length() > MAX_BODY_CHARS) {
                    throw new IOException("请求体超过允许读取的上限（" + MAX_BODY_CHARS + " 字符），已中止读取");
                }
            }
        } catch (IOException e) {
            LOGGER.warn("读取请求体失败", e);
            throw e;
        }
        return sb.toString();
    }
}
