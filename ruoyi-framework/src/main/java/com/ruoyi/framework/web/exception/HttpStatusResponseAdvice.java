package com.ruoyi.framework.web.exception;

import java.util.Set;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.AjaxResult;

/**
 * 全站真实 HTTP 状态码收口：在写响应体前，按 {@link AjaxResult} 的 code 把传输层 HTTP 状态设为语义正确的值。
 *
 * <p>
 * 之所以用 {@link ResponseBodyAdvice} 而非逐个改 {@code GlobalExceptionHandler}：大量业务失败是控制器
 * <b>内联直接 {@code return error()/toAjax()}</b>（不经异常 advice），只有本 advice 能同时覆盖控制器内联返回
 * 与 {@code @ExceptionHandler} 返回值（二者都走消息转换器的返回处理）。响应体内容保持原样（code 仍在 body 里），
 * 只调整传输层状态码，二者解耦。
 * </p>
 *
 * <p>
 * 映射策略：白名单内的已知 HTTP 语义码原样生效；白名单之外（含非 HTTP 业务码 {@code WARN=601}、
 * {@code ServiceException} 的任意码、越界值）统一回退 HTTP 400，不把业务拒绝伪装成 500 服务端故障。
 * 注意残留：RuoYi 通用 {@code AjaxResult.error(msg)} 默认 code=500，与真·系统故障同码、无法在本层区分，
 * 故这类业务失败仍会映射为 HTTP 500（要彻底分离须回改控制器业务码，另议）。
 * </p>
 *
 * @author ruoyi
 */
@RestControllerAdvice
public class HttpStatusResponseAdvice implements ResponseBodyAdvice<Object> {
    /** 原样透传为 HTTP 状态的已知语义码 */
    private static final Set<Integer> PASS_THROUGH_CODES = Set.of(
            HttpStatus.SUCCESS, HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN,
            HttpStatus.NOT_FOUND, HttpStatus.BAD_METHOD, HttpStatus.CONFLICT, HttpStatus.UNSUPPORTED_TYPE,
            HttpStatus.ERROR, HttpStatus.NOT_IMPLEMENTED);

    /** 白名单之外统一回退的客户端错误码 */
    private static final int FALLBACK_CLIENT_ERROR = HttpStatus.BAD_REQUEST;

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // 覆盖所有转换器；是否实际改写状态在 beforeBodyWrite 内按 body 类型判定（仅 AjaxResult 生效）
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body instanceof AjaxResult ajaxResult) {
            Object code = ajaxResult.get(AjaxResult.CODE_TAG);
            if (code instanceof Number codeNumber) {
                response.setStatusCode(resolveHttpStatus(codeNumber.intValue()));
            }
        }
        return body;
    }

    /**
     * 业务码 → HTTP 状态：白名单原样，其余（含 601/任意业务码/越界）回退 400。
     */
    private HttpStatusCode resolveHttpStatus(int code) {
        if (PASS_THROUGH_CODES.contains(code)) {
            return HttpStatusCode.valueOf(code);
        }
        return HttpStatusCode.valueOf(FALLBACK_CLIENT_ERROR);
    }
}
