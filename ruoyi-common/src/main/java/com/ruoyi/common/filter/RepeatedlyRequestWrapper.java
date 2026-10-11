package com.ruoyi.common.filter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import com.ruoyi.common.utils.http.HttpHelper;
import com.ruoyi.common.constant.Constants;

/**
 * 构建可重复读取inputStream的request
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
public class RepeatedlyRequestWrapper extends HttpServletRequestWrapper {
    private final byte[] body;

    public RepeatedlyRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        request.setCharacterEncoding(Constants.UTF8);
        body = HttpHelper.getBodyString(request).getBytes(Constants.UTF8);
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(getInputStream()));
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        final ByteArrayInputStream bais = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() throws IOException {
                return bais.read();
            }

            @Override
            public int available() throws IOException {
                return bais.available();
            }

            @Override
            public boolean isFinished() {
                // 内存流读尽（无剩余可读字节）即为完成，符合 Servlet 规范对 isFinished 的约定
                return bais.available() == 0;
            }

            @Override
            public boolean isReady() {
                // 同步阻塞式读取下内存流永远可读、不会阻塞，故恒返回 true
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {

            }
        };
    }
}
