package com.ruoyi.framework.aspectj;

import java.util.Collection;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.ArrayUtils;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.web.multipart.MultipartFile;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.enums.BusinessStatus;
import com.ruoyi.common.enums.HttpMethod;
import com.ruoyi.common.filter.PropertyPreExcludeFilter;
import com.ruoyi.common.utils.ExceptionUtil;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.ServletUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.framework.manager.AsyncManager;
import com.ruoyi.framework.manager.factory.AsyncFactory;
import com.ruoyi.system.domain.SysOperLog;

/**
 * 操作日志记录处理
 * 
 * @author ruoyi
 */
@Aspect
@Component
public class LogAspect {
    private static final Logger log = LoggerFactory.getLogger(LogAspect.class);

    /** 排除敏感属性字段 */
    public static final String[] EXCLUDE_PROPERTIES = {
            "password", "oldPassword", "newPassword", "confirmPassword",
            "token", "captcha", "smsCode", "verifyCode", "accessToken", "refreshToken" };

    /** 参数最大长度限制 */
    private static final int PARAM_MAX_LENGTH = 2000;

    /**
     * 环绕通知：在单一生命周期内采集耗时、返回值与异常。
     * 业务异常/错误原样向上抛出（本切面不吞异常），仅在 finally 中记录操作日志，
     * 从而避免异常路径下丢失耗时；耗时用局部变量而非 ThreadLocal，杜绝嵌套调用相互覆盖。
     */
    @Around("@annotation(controllerLog)")
    public Object around(ProceedingJoinPoint joinPoint, Log controllerLog) throws Throwable {
        long start = System.currentTimeMillis();
        Throwable exception = null;
        Object result = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable e) {
            exception = e;
            throw e;
        } finally {
            long costTime = System.currentTimeMillis() - start;
            handleLog(joinPoint, controllerLog, exception, result, costTime);
        }
    }

    /**
     * 记录操作日志：全程 try-catch 兜底，日志记录自身失败只落本地 error、绝不影响业务响应。
     */
    protected void handleLog(final JoinPoint joinPoint, Log controllerLog, final Throwable e, Object jsonResult,
            long costTime) {
        try {
            // 获取当前的用户
            LoginUser loginUser = SecurityUtils.getLoginUser();

            // *========数据库日志=========*//
            SysOperLog operLog = new SysOperLog();
            operLog.setStatus(BusinessStatus.SUCCESS.ordinal());
            // 请求的地址
            String ip = IpUtils.getIpAddr();
            operLog.setOperIp(ip);
            operLog.setOperUrl(StringUtils.substring(ServletUtils.getRequest().getRequestURI(), 0, 255));
            if (loginUser != null) {
                operLog.setOperName(loginUser.getUsername());
                SysUser currentUser = loginUser.getUser();
                if (StringUtils.isNotNull(currentUser) && StringUtils.isNotNull(currentUser.getDept())) {
                    operLog.setDeptName(currentUser.getDept().getDeptName());
                }
            }

            if (e != null) {
                operLog.setStatus(BusinessStatus.FAIL.ordinal());
                operLog.setErrorMsg(StringUtils
                        .substring(Convert.toStr(e.getMessage(), ExceptionUtil.getExceptionMessage(e)), 0, 2000));
            }
            // 设置方法名称
            String className = joinPoint.getTarget().getClass().getName();
            String methodName = joinPoint.getSignature().getName();
            operLog.setMethod(className + "." + methodName + "()");
            // 设置请求方式
            operLog.setRequestMethod(ServletUtils.getRequest().getMethod());
            // 处理设置注解上的参数
            getControllerMethodDescription(joinPoint, controllerLog, operLog, jsonResult);
            // 设置消耗时间
            operLog.setCostTime(costTime);
            // 异步写入数据库（不阻塞主请求），日志记录失败不影响业务响应
            AsyncManager.me().execute(AsyncFactory.recordOper(operLog));
        } catch (Exception exp) {
            // 记录本地异常日志
            log.error("记录操作日志异常", exp);
        }
    }

    /**
     * 获取注解中对方法的描述信息 用于Controller层注解
     * 
     * @param log     日志
     * @param operLog 操作日志
     */
    public void getControllerMethodDescription(JoinPoint joinPoint, Log log, SysOperLog operLog, Object jsonResult) {
        // 设置action动作
        operLog.setBusinessType(log.businessType().ordinal());
        // 设置标题
        operLog.setTitle(log.title());
        // 设置操作人类别
        operLog.setOperatorType(log.operatorType().ordinal());
        // 是否需要保存request，参数和值
        if (log.isSaveRequestData()) {
            // 获取参数的信息，传入到数据库中。
            setRequestValue(joinPoint, operLog, log.excludeParamNames());
        }
        // 是否需要保存response，参数和值
        if (log.isSaveResponseData() && StringUtils.isNotNull(jsonResult)) {
            try {
                operLog.setJsonResult(StringUtils.substring(JSON.toJSONString(jsonResult), 0, 2000));
            } catch (Exception ex) {
                // 返回值序列化失败(循环引用/大对象等)时降级记占位串，保住 title/status/costTime 等其余字段
                LogAspect.log.error("操作日志返回值序列化失败", ex);
                operLog.setJsonResult(StringUtils.substring("{\"jsonResult\":\"序列化失败\"}", 0, 2000));
            }
        }
    }

    /**
     * 获取请求的参数，放到log中
     * 
     * @param operLog 操作日志
     */
    private void setRequestValue(JoinPoint joinPoint, SysOperLog operLog, String[] excludeParamNames) {
        String requestMethod = operLog.getRequestMethod();
        Map<?, ?> paramsMap = ServletUtils.getParamMap(ServletUtils.getRequest());
        // GET 等参数在 query 里的请求用 paramMap；PUT/POST/DELETE 且无 query 参数时序列化方法入参
        if (StringUtils.isEmpty(paramsMap) && StringUtils.equalsAny(requestMethod, HttpMethod.PUT.name(),
                HttpMethod.POST.name(), HttpMethod.DELETE.name())) {
            String params = argsArrayToString(joinPoint.getArgs(), excludeParamNames);
            operLog.setOperParam(params);
        } else {
            operLog.setOperParam(StringUtils.substring(
                    JSON.toJSONString(paramsMap, excludePropertyPreFilter(excludeParamNames)), 0, PARAM_MAX_LENGTH));
        }
    }

    /**
     * 参数拼装
     */
    private String argsArrayToString(Object[] paramsArray, String[] excludeParamNames) {
        StringBuilder params = new StringBuilder();
        if (paramsArray != null && paramsArray.length > 0) {
            for (Object o : paramsArray) {
                if (StringUtils.isNotNull(o) && !isFilterObject(o)) {
                    try {
                        String jsonObj = JSON.toJSONString(o, excludePropertyPreFilter(excludeParamNames));
                        params.append(jsonObj).append(" ");
                        if (params.length() >= PARAM_MAX_LENGTH) {
                            return StringUtils.substring(params.toString(), 0, PARAM_MAX_LENGTH);
                        }
                    } catch (Exception e) {
                        log.error("请求参数拼装异常 msg:{}, 参数:{}", e.getMessage(), paramsArray, e);
                    }
                }
            }
        }
        return params.toString();
    }

    /**
     * 忽略敏感属性
     */
    public PropertyPreExcludeFilter excludePropertyPreFilter(String[] excludeParamNames) {
        return new PropertyPreExcludeFilter().addExcludes(ArrayUtils.addAll(EXCLUDE_PROPERTIES, excludeParamNames));
    }

    /**
     * 判断是否需要过滤的对象（文件、request/response、校验结果等不可序列化或不该入日志的对象）
     * 
     * @param o 对象信息。
     * @return 如果是需要过滤的对象，则返回true；否则返回false。
     */
    public boolean isFilterObject(final Object o) {
        Class<?> clazz = o.getClass();
        if (clazz.isArray()) {
            return clazz.getComponentType().isAssignableFrom(MultipartFile.class);
        } else if (Collection.class.isAssignableFrom(clazz)) {
            // 遍历全部元素：任一为 MultipartFile 才过滤，不能只看首元素就 return
            for (Object value : (Collection<?>) o) {
                if (value instanceof MultipartFile) {
                    return true;
                }
            }
            return false;
        } else if (Map.class.isAssignableFrom(clazz)) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) o).entrySet()) {
                if (entry.getValue() instanceof MultipartFile) {
                    return true;
                }
            }
            return false;
        }
        return o instanceof MultipartFile || o instanceof HttpServletRequest || o instanceof HttpServletResponse
                || o instanceof BindingResult;
    }
}
