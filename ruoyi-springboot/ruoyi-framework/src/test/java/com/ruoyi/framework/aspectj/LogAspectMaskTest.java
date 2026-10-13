package com.ruoyi.framework.aspectj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.aspectj.lang.JoinPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.enums.OperatorType;
import com.ruoyi.framework.manager.AsyncManager;
import com.ruoyi.system.domain.SysOperLog;

/**
 * LogAspect 值级脱敏（C 档）单测，无 Spring：
 * 1) mask 纯函数（maskMiddle/maskEmail/maskSensitiveValue 归类）；
 * 2) getControllerMethodDescription 的响应体路径同时施加"整字段剔除(凭据)+值级脱敏(PII)"。
 *
 * <p>
 * isSaveRequestData=false 跳过 setRequestValue（该路径触 ServletUtils/Request 上下文），
 * 只走响应体分支，故无需 mockStatic。响应对象用 LinkedHashMap 保序，便于稳定断言。
 * </p>
 */
public class LogAspectMaskTest {

    @Test
    @DisplayName("maskMiddle：保留两端、中间等长星号；过短整体脱敏")
    void maskMiddleKeepsEnds() {
        assertEquals("138****5678", LogAspect.maskMiddle("13812345678", 3, 4));
        // 长度 ≤ head+tail 时不外泄，整体打星
        assertEquals("****", LogAspect.maskMiddle("1234", 3, 4));
        // 银行卡 head=0：只留末四位（18 位卡号 → 14 星 + 后 4 位）
        assertEquals("**************1234", LogAspect.maskMiddle("622202123456781234", 0, 4));
    }

    @Test
    @DisplayName("maskEmail：留首字符与域名")
    void maskEmailKeepsDomain() {
        assertEquals("a***@b.com", LogAspect.maskEmail("alice@b.com"));
    }

    @Test
    @DisplayName("maskSensitiveValue：按属性名归类；非命中/非字符串原样返回")
    void routesByProperty() {
        assertEquals("138****5678", LogAspect.maskSensitiveValue("phonenumber", "13812345678"));
        // 身份证 18 位，head=4 tail=4 → 前4 + 10星 + 后4
        assertEquals("1101**********8834", LogAspect.maskSensitiveValue("idCard", "110101199003078834"));
        assertEquals("a***@b.com", LogAspect.maskSensitiveValue("EMAIL", "alice@b.com"));
        // 未命中名单/非字符串不动
        assertEquals("admin", LogAspect.maskSensitiveValue("userName", "admin"));
        assertEquals(42, LogAspect.maskSensitiveValue("phonenumber", 42));
    }

    @Test
    @DisplayName("响应体脱敏：password/token 整字段剔除，手机号/邮箱部分脱敏，普通字段保留")
    void responseBodyMaskedAndExcluded() {
        LogAspect aspect = new LogAspect(mock(AsyncManager.class));

        Log log = mock(Log.class);
        when(log.businessType()).thenReturn(BusinessType.OTHER);
        when(log.operatorType()).thenReturn(OperatorType.MANAGE);
        when(log.title()).thenReturn("t");
        when(log.isSaveRequestData()).thenReturn(false);
        when(log.isSaveResponseData()).thenReturn(true);
        when(log.excludeParamNames()).thenReturn(new String[0]);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("userName", "admin");
        resp.put("password", "s3cr3t");
        resp.put("token", "eyJhbGciOi");
        resp.put("phonenumber", "13812345678");
        resp.put("email", "alice@b.com");

        SysOperLog operLog = new SysOperLog();
        aspect.getControllerMethodDescription(mock(JoinPoint.class), log, operLog, resp);
        String json = operLog.getJsonResult();

        assertTrue(json.contains("\"userName\":\"admin\""), () -> "普通字段应保留: " + json);
        assertTrue(json.contains("138****5678"), () -> "手机号应部分脱敏: " + json);
        assertTrue(json.contains("a***@b.com"), () -> "邮箱应部分脱敏: " + json);
        assertFalse(json.contains("s3cr3t"), () -> "password 值应被整字段剔除: " + json);
        assertFalse(json.contains("eyJhbGciOi"), () -> "token 值应被整字段剔除: " + json);
        assertFalse(json.contains("13812345678"), () -> "不应出现未脱敏手机号: " + json);
    }
}
