package com.ruoyi.framework.web.exception;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.exception.DemoModeException;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.exception.base.BaseException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.html.EscapeUtil;

/**
 * 全局异常处理器
 * 
 * @author HackerSnail <2306002088@qq.com>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 权限校验异常
     */
    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult handleAccessDeniedException(AccessDeniedException e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',权限校验失败'{}'", requestURI, e.getMessage());
        return AjaxResult.error(HttpStatus.FORBIDDEN, "没有权限，请联系管理员授权");
    }

    /**
     * 请求方式不支持
     * <p>
     * 用 {@code BAD_METHOD(405)} 作业务码，经 HttpStatusResponseAdvice 透传为真实 HTTP 405（语义比默认 500→400 更精确）。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public AjaxResult handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException e,
            HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',不支持'{}'请求", requestURI, e.getMethod());
        return AjaxResult.error(HttpStatus.BAD_METHOD, e.getMessage());
    }

    /**
     * 业务异常
     * <p>
     * 属预期内失败，降为 {@code warn}（仍带栈，便于定位漏网到全局的抛出点），不再以 {@code error} 污染日志。
     */
    @ExceptionHandler(ServiceException.class)
    public AjaxResult handleServiceException(ServiceException e, HttpServletRequest request) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage(), e);
        Integer code = e.getCode();
        return StringUtils.isNotNull(code) ? AjaxResult.error(code, e.getMessage()) : AjaxResult.error(e.getMessage());
    }

    /**
     * 用户/文件等基础异常（{@link BaseException} 及其子类：登录鉴权失败、验证码失效、上传超限等）
     * <p>
     * {@link BaseException#getMessage()} 已把 i18n code 解析为面向用户的明确文案，属预期内业务失败，
     * 应与 {@link ServiceException} 同档处理：warn 记录、返回普通 {@code AjaxResult}（默认 code=500），
     * 经 {@code HttpStatusResponseAdvice} 映射为真实 HTTP 400。否则这类异常会落到兜底
     * {@link #handleRuntimeException} 被伪装成 HTTP 500 “系统繁忙”，前端既拿不到文案、状态码语义也错。
     * {@code BaseException} 比 {@code RuntimeException} 更 specific，Spring 按最具体匹配稳选本方法，
     * 不影响既有 {@code ServiceException} 等分支。
     */
    @ExceptionHandler(BaseException.class)
    public AjaxResult handleBaseException(BaseException e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        String msg = e.getMessage();
        if (StringUtils.isEmpty(msg)) {
            // 兜底：code 未在 messages.properties 命中且无 defaultMessage 时给通用文案，避免返回空 msg
            msg = "操作失败";
        }
        log.warn("请求地址'{}',业务异常: {}", requestURI, msg, e);
        return AjaxResult.error(msg);
    }

    /**
     * 请求路径中缺少必需的路径变量
     */
    @ExceptionHandler(MissingPathVariableException.class)
    public AjaxResult handleMissingPathVariableException(MissingPathVariableException e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        log.error("请求路径中缺少必需的路径变量'{}',发生系统异常.", requestURI, e);
        return AjaxResult.error(String.format("请求路径中缺少必需的路径变量[%s]", e.getVariableName()));
    }

    /**
     * 请求参数类型不匹配
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public AjaxResult handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e,
            HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        String value = Convert.toStr(e.getValue());
        if (StringUtils.isNotEmpty(value)) {
            value = EscapeUtil.clean(value);
        }
        log.error("请求参数类型不匹配'{}',发生系统异常.", requestURI, e);
        return AjaxResult.error(String.format("请求参数类型不匹配，参数[%s]要求类型为：'%s'，但输入值为：'%s'", e.getName(),
                e.getRequiredType().getName(), value));
    }

    /**
     * 上传文件超过 multipart
     * 大小限制（spring.servlet.multipart.max-file-size/max-request-size）
     * <p>
     * 该异常在进入 Controller 前由 multipart 解析抛出，若不单独拦截会落到兜底 handleException
     * 返回笼统的“系统繁忙”，用户无从得知是文件过大；此处给出明确的业务文案。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public AjaxResult handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e,
            HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',上传文件过大 '{}'", requestURI, e.getMessage());
        return AjaxResult.error("上传文件过大，已超过允许的单文件大小限制，请压缩后重试");
    }

    /**
     * 拦截未知的运行时异常
     * <p>
     * 真·系统故障：以 {@code ResponseEntity} 显式返回 HTTP 500（而非普通 {@code AjaxResult}），
     * 以免被 {@code HttpStatusResponseAdvice} 把业务默认的 code=500 回退成 400——系统故障应保留 500。
     *
     * @see HttpStatusResponseAdvice
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<AjaxResult> handleRuntimeException(RuntimeException e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',发生未知异常.", requestURI, e);
        return ResponseEntity.status(HttpStatus.ERROR).body(AjaxResult.error("系统繁忙，请稍后再试"));
    }

    /**
     * 系统异常
     * <p>
     * 同 {@link #handleRuntimeException}：真·系统故障显式返回 HTTP 500，与业务失败的 code=500→HTTP 400 区分开。
     *
     * @see HttpStatusResponseAdvice
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<AjaxResult> handleException(Exception e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',发生系统异常.", requestURI, e);
        return ResponseEntity.status(HttpStatus.ERROR).body(AjaxResult.error("系统繁忙，请稍后再试"));
    }

    /**
     * 自定义验证异常
     */
    @ExceptionHandler(BindException.class)
    public AjaxResult handleBindException(BindException e) {
        log.error(e.getMessage(), e);
        return buildValidationError(e.getAllErrors());
    }

    /**
     * 自定义验证异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public AjaxResult handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        log.error(e.getMessage(), e);
        return buildValidationError(e.getBindingResult().getAllErrors());
    }

    /**
     * 组装参数校验失败响应：{@code msg} 为各条违规消息的分号拼接（旧前端可直接展示），
     * {@code data} 为字段级明细列表（每项含 {@code field}/{@code message}，供前端做字段级定位）。
     * 空违规时给固定文案，避免 {@code get(0)} 越界；非 {@code FieldError} 的全局错误 {@code field} 记空串。
     */
    private AjaxResult buildValidationError(List<ObjectError> errors) {
        if (errors == null || errors.isEmpty()) {
            return AjaxResult.error("参数校验失败");
        }
        List<Map<String, String>> details = new ArrayList<>(errors.size());
        StringJoiner joiner = new StringJoiner("; ");
        for (ObjectError error : errors) {
            String field = (error instanceof FieldError fieldError) ? fieldError.getField() : "";
            String message = error.getDefaultMessage();
            Map<String, String> item = new LinkedHashMap<>();
            item.put("field", field);
            item.put("message", message);
            details.add(item);
            joiner.add(message);
        }
        return AjaxResult.error(joiner.toString(), details);
    }

    /**
     * 方法级参数校验异常（如 @Validated 直接约束方法参数、或手动调用 BeanValidators 抛出）
     * <p>
     * ConstraintViolationException 继承 RuntimeException，若不单独拦截会落到兜底
     * handleRuntimeException 返回笼统的“系统繁忙，请稍后再试”，前端拿不到字段级错误；
     * 此处与 BindException/MethodArgumentNotValidException 对称，返回全部违规的字段级明细（propertyPath + message），而非仅首条。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public AjaxResult handleConstraintViolationException(ConstraintViolationException e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        Set<ConstraintViolation<?>> violations = e.getConstraintViolations();
        if (violations == null || violations.isEmpty()) {
            String fallback = StringUtils.isNotEmpty(e.getMessage()) ? e.getMessage() : "参数校验失败";
            log.error("请求地址'{}',参数校验失败'{}'", requestURI, fallback);
            return AjaxResult.error(fallback);
        }
        List<Map<String, String>> details = new ArrayList<>(violations.size());
        StringJoiner joiner = new StringJoiner("; ");
        for (ConstraintViolation<?> violation : violations) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("field", violation.getPropertyPath().toString());
            item.put("message", violation.getMessage());
            details.add(item);
            joiner.add(violation.getMessage());
        }
        String message = joiner.toString();
        log.error("请求地址'{}',参数校验失败'{}'", requestURI, message);
        return AjaxResult.error(message, details);
    }

    /**
     * 演示模式异常
     */
    @ExceptionHandler(DemoModeException.class)
    public AjaxResult handleDemoModeException(DemoModeException e) {
        return AjaxResult.error("演示模式，不允许操作");
    }
}
