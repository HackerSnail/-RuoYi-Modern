package com.ruoyi.framework.interceptor.impl;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;
import com.ruoyi.common.annotation.RepeatSubmit;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.filter.RepeatedlyRequestWrapper;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.http.HttpHelper;
import com.ruoyi.framework.interceptor.RepeatSubmitInterceptor;

/**
 * 判断请求url和数据是否和上一次相同，
 * 如果和上次相同，则是重复提交表单。 有效时间为10秒内。
 * 
 * @author ruoyi
 */
@Component
public class SameUrlDataInterceptor extends RepeatSubmitInterceptor {
    private static final Logger LOGGER = LoggerFactory.getLogger(SameUrlDataInterceptor.class);

    // 令牌自定义标识
    @Value("${token.header}")
    private String header;

    private final RedisTemplate<String, Object> redisTemplate;

    private final RedisScript<Long> repeatSubmitScript;

    public SameUrlDataInterceptor(RedisTemplate<String, Object> redisTemplate,
            @Qualifier("repeatSubmitScript") RedisScript<Long> repeatSubmitScript) {
        this.redisTemplate = redisTemplate;
        this.repeatSubmitScript = repeatSubmitScript;
    }

    /**
     * 基于 Redis 的重复提交判定：以 key(前缀 + url + ":" + 令牌) 缓存本次规范化参数串，
     * 用 Lua 脚本原子完成“取回上次→相同且仍在窗口内判重复，否则覆盖并刷新窗口”，消除 Java 侧
     * get-compare-set 的 TOCTOU 竞态；时间窗由 key 的 TTL(=annotation.interval 毫秒)承载，故无需再存时间戳。
     */
    @Override
    public boolean isRepeatSubmit(HttpServletRequest request, RepeatSubmit annotation) {
        String nowParams = "";
        // 逐层拆包定位 RepeatedlyRequestWrapper：RepeatableFilter 已提前到高优先级缓存 body，
        // 其后 Spring Security 等还会再包一层，故不能用 instanceof 直接判定最外层类型
        RepeatedlyRequestWrapper repeatedlyRequest = WebUtils.getNativeRequest(request, RepeatedlyRequestWrapper.class);
        if (repeatedlyRequest != null) {
            try {
                nowParams = HttpHelper.getBodyString(repeatedlyRequest);
            } catch (IOException e) {
                // 读取可重复请求体失败：不阻断请求，降级为下方按 parameterMap 比对（内存流读取极少失败）
                LOGGER.warn("读取重复提交校验的请求体失败，降级为参数比对", e);
            }
        }

        // body 为空时降级按 parameterMap 比对；规范化(按 key 排序拼接)以获得顺序稳定、与 Map 实现无关的可比较串，
        // 避免直接 JSON 序列化 getParameterMap 的编码不确定导致防重漏判
        if (StringUtils.isEmpty(nowParams)) {
            nowParams = normalizeParams(request.getParameterMap());
        }

        // 唯一标识 = key 前缀 + url + 固定冒号分隔符 + 令牌；加冒号消除 url 与 submitKey 直接拼接的歧义
        String url = request.getRequestURI();
        String submitKey = StringUtils.trimToEmpty(request.getHeader(header));
        String cacheRepeatKey = CacheConstants.REPEAT_SUBMIT_KEY + url + ":" + submitKey;

        // 原子“读-比-写”：返回 1=判为重复(参数相同且 key 仍在窗口内)，0=非重复且已记录本次并刷新 TTL。
        // annotation.interval() 单位为毫秒，作为脚本内 SET ... PX 的 TTL
        Long repeatFlag = redisTemplate.execute(repeatSubmitScript,
                Collections.singletonList(cacheRepeatKey), nowParams, annotation.interval());
        return repeatFlag != null && repeatFlag == 1L;
    }

    /**
     * 规范化请求参数：按参数名升序，每个参数名拼接其全部取值(String[] 以逗号连接)，得到稳定串。
     * 注：值内含分隔符(,&,=)的极端歧义未做转义——较原 JSON 方案已消除顺序不确定性，防重目的足够。
     */
    private String normalizeParams(Map<String, String[]> parameterMap) {
        if (parameterMap == null || parameterMap.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        // TreeMap 按 key 自然序排序，消除 HashMap 迭代顺序不确定
        for (Map.Entry<String, String[]> entry : new TreeMap<>(parameterMap).entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            String[] values = entry.getValue();
            sb.append(entry.getKey()).append('=').append(values == null ? "" : String.join(",", values));
        }
        return sb.toString();
    }
}
