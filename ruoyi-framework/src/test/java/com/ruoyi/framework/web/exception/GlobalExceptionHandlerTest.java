package com.ruoyi.framework.web.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.user.CaptchaExpireException;
import com.ruoyi.common.utils.MessageUtils;

/**
 * {@link GlobalExceptionHandler#handleBaseException} 行为测试。
 *
 * <p>
 * 锁定本次修复的核心面：继承 {@code BaseException} 的鉴权/文件类异常（如验证码失效）不再落入兜底
 * {@code handleRuntimeException} 被伪装成 HTTP 500 "系统繁忙"，而是走专属 handler，返回普通
 * {@code AjaxResult}——默认 code=500（业务码，交 HttpStatusResponseAdvice 映射为真实 HTTP 400）且
 * msg 为 {@code BaseException.getMessage()} 解析出的面向用户文案。{@code getMessage()} 依赖
 * {@code MessageUtils}（须 Spring 容器取 MessageSource），故静态打桩隔离。
 * </p>
 */
public class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private HttpServletRequest loginRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/login");
        return request;
    }

    @Test
    @DisplayName("BaseException 走专属 handler：返回 code=500 且 msg 为解析后的用户文案")
    void baseExceptionHandledWithResolvedMessage() {
        try (MockedStatic<MessageUtils> messageUtils = mockStatic(MessageUtils.class, inv -> "验证码已失效")) {
            AjaxResult result = handler.handleBaseException(new CaptchaExpireException(), loginRequest());

            assertEquals(HttpStatus.ERROR, result.get(AjaxResult.CODE_TAG),
                    "应返回业务默认 code=500（非 ResponseEntity），由 advice 映射为 HTTP 400");
            assertEquals("验证码已失效", result.get(AjaxResult.MSG_TAG),
                    "应把 i18n 解析出的明确文案回给前端，而非笼统的‘系统繁忙’");
        }
    }

    @Test
    @DisplayName("getMessage 解析为空时兜底固定文案，绝不返回空 msg")
    void blankMessageFallsBackToGenericText() {
        // MessageUtils 返回 null 且 CaptchaExpireException 无 defaultMessage → getMessage() 为 null
        try (MockedStatic<MessageUtils> messageUtils = mockStatic(MessageUtils.class, inv -> null)) {
            AjaxResult result = handler.handleBaseException(new CaptchaExpireException(), loginRequest());

            assertEquals(HttpStatus.ERROR, result.get(AjaxResult.CODE_TAG));
            assertEquals("操作失败", result.get(AjaxResult.MSG_TAG));
        }
    }
}
