package com.ruoyi.common.utils.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.utils.StringUtils;
import org.springframework.http.MediaType;

/**
 * 通用http发送方法
 * 
 * @author ruoyi
 */
public class HttpUtils
{
    private static final Logger log = LoggerFactory.getLogger(HttpUtils.class);

    /** HTTP 请求头：Accept */
    private static final String HEADER_ACCEPT = "accept";

    /** Accept 通配取值 */
    private static final String ACCEPT_ANY = "*/*";

    /** HTTP 请求头：Connection */
    private static final String HEADER_CONNECTION = "connection";

    /** Connection 取值：长连接 */
    private static final String CONNECTION_KEEP_ALIVE = "Keep-Alive";

    /** HTTP 请求头：User-Agent */
    private static final String HEADER_USER_AGENT = "user-agent";

    /** 默认 User-Agent */
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)";

    /** HTTP 请求头：Accept-Charset */
    private static final String HEADER_ACCEPT_CHARSET = "Accept-Charset";

    /** Accept-Charset 取值 */
    private static final String CHARSET_UTF8 = "utf-8";

    /** HTTP 请求头：Content-Type */
    private static final String HEADER_CONTENT_TYPE = "Content-Type";

    /**
     * 向指定 URL 发送GET方法的请求
     *
     * @param url 发送请求的 URL
     * @return 所代表远程资源的响应结果
     */
    public static String sendGet(String url)
    {
        return sendGet(url, StringUtils.EMPTY);
    }

    /**
     * 向指定 URL 发送GET方法的请求
     *
     * @param url 发送请求的 URL
     * @param param 请求参数，请求参数应该是 name1=value1&name2=value2 的形式。
     * @return 所代表远程资源的响应结果
     */
    public static String sendGet(String url, String param)
    {
        return sendGet(url, param, Constants.UTF8);
    }

    /**
     * 向指定 URL 发送GET方法的请求
     *
     * @param url 发送请求的 URL
     * @param param 请求参数，请求参数应该是 name1=value1&name2=value2 的形式。
     * @param contentType 编码类型
     * @return 所代表远程资源的响应结果
     */
    public static String sendGet(String url, String param, String contentType)
    {
        StringBuilder result = new StringBuilder();
        try
        {
            String urlNameString = StringUtils.isNotBlank(param) ? url + "?" + param : url;
            log.info("sendGet - {}", urlNameString);
            URL realUrl = new URL(urlNameString);
            URLConnection connection = realUrl.openConnection();
            connection.setRequestProperty(HEADER_ACCEPT, ACCEPT_ANY);
            connection.setRequestProperty(HEADER_CONNECTION, CONNECTION_KEEP_ALIVE);
            connection.setRequestProperty(HEADER_USER_AGENT, DEFAULT_USER_AGENT);
            connection.connect();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), contentType)))
            {
                String line;
                while ((line = in.readLine()) != null)
                {
                    result.append(line);
                }
            }
            log.info("recv - {}", result);
        }
        catch (ConnectException e)
        {
            log.error("调用HttpUtils.sendGet ConnectException, url=" + url + ",param=" + param, e);
        }
        catch (SocketTimeoutException e)
        {
            log.error("调用HttpUtils.sendGet SocketTimeoutException, url=" + url + ",param=" + param, e);
        }
        catch (IOException e)
        {
            log.error("调用HttpUtils.sendGet IOException, url=" + url + ",param=" + param, e);
        }
        catch (Exception e)
        {
            log.error("调用HttpsUtil.sendGet Exception, url=" + url + ",param=" + param, e);
        }
        return result.toString();
    }

    /**
     * 向指定 URL 发送POST方法的请求
     *
     * @param url 发送请求的 URL
     * @param param 请求参数，请求参数应该是 name1=value1&name2=value2 的形式。
     * @return 所代表远程资源的响应结果
     */
    public static String sendPost(String url, String param)
    {
        return sendPost(url, param, MediaType.APPLICATION_FORM_URLENCODED_VALUE);
    }

    /**
     * 向指定 URL 发送POST方法的请求
     * 
     * @param url 发送请求的 URL
     * @param param 请求参数
     * @param contentType 内容类型
     * @return 所代表远程资源的响应结果
     */
    public static String sendPost(String url, String param, String contentType)
    {
        StringBuilder result = new StringBuilder();
        try
        {
            log.info("sendPost - {}", url);
            URL realUrl = new URL(url);
            URLConnection conn = realUrl.openConnection();
            conn.setRequestProperty(HEADER_ACCEPT, ACCEPT_ANY);
            conn.setRequestProperty(HEADER_CONNECTION, CONNECTION_KEEP_ALIVE);
            conn.setRequestProperty(HEADER_USER_AGENT, DEFAULT_USER_AGENT);
            conn.setRequestProperty(HEADER_ACCEPT_CHARSET, CHARSET_UTF8);
            conn.setRequestProperty(HEADER_CONTENT_TYPE, contentType);
            conn.setDoOutput(true);
            conn.setDoInput(true);
            try (PrintWriter out = new PrintWriter(conn.getOutputStream()))
            {
                out.print(param);
                out.flush();
            }
            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = in.readLine()) != null)
                {
                    result.append(line);
                }
            }
            log.info("recv - {}", result);
        }
        catch (ConnectException e)
        {
            log.error("调用HttpUtils.sendPost ConnectException, url=" + url + ",param=" + param, e);
        }
        catch (SocketTimeoutException e)
        {
            log.error("调用HttpUtils.sendPost SocketTimeoutException, url=" + url + ",param=" + param, e);
        }
        catch (IOException e)
        {
            log.error("调用HttpUtils.sendPost IOException, url=" + url + ",param=" + param, e);
        }
        catch (Exception e)
        {
            log.error("调用HttpsUtil.sendPost Exception, url=" + url + ",param=" + param, e);
        }
        return result.toString();
    }

    public static String sendSSLPost(String url, String param)
    {
        return sendSSLPost(url, param, MediaType.APPLICATION_FORM_URLENCODED_VALUE);
    }

    public static String sendSSLPost(String url, String param, String contentType)
    {
        StringBuilder result = new StringBuilder();
        String urlNameString = url + "?" + param;
        try
        {
            log.info("sendSSLPost - {}", urlNameString);
            URL console = new URL(urlNameString);
            // 使用 JVM 默认信任库与主机名校验，避免信任所有证书带来的中间人攻击风险
            HttpsURLConnection conn = (HttpsURLConnection) console.openConnection();
            conn.setRequestProperty(HEADER_ACCEPT, ACCEPT_ANY);
            conn.setRequestProperty(HEADER_CONNECTION, CONNECTION_KEEP_ALIVE);
            conn.setRequestProperty(HEADER_USER_AGENT, DEFAULT_USER_AGENT);
            conn.setRequestProperty(HEADER_ACCEPT_CHARSET, CHARSET_UTF8);
            conn.setRequestProperty(HEADER_CONTENT_TYPE, contentType);
            conn.setDoOutput(true);
            conn.setDoInput(true);

            conn.connect();
            try (InputStream is = conn.getInputStream();
                    BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)))
            {
                String ret;
                while ((ret = br.readLine()) != null)
                {
                    if (!ret.trim().isEmpty())
                    {
                        result.append(ret);
                    }
                }
            }
            log.info("recv - {}", result);
            conn.disconnect();
        }
        catch (ConnectException e)
        {
            log.error("调用HttpUtils.sendSSLPost ConnectException, url=" + url + ",param=" + param, e);
        }
        catch (SocketTimeoutException e)
        {
            log.error("调用HttpUtils.sendSSLPost SocketTimeoutException, url=" + url + ",param=" + param, e);
        }
        catch (IOException e)
        {
            log.error("调用HttpUtils.sendSSLPost IOException, url=" + url + ",param=" + param, e);
        }
        catch (Exception e)
        {
            log.error("调用HttpsUtil.sendSSLPost Exception, url=" + url + ",param=" + param, e);
        }
        return result.toString();
    }
}